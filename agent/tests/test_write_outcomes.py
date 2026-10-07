"""Isolated write-outcome regressions: no dotenv, network or real business writes."""

import json
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

from app.agent import AgentService
from app.clients.java_api import JavaApiClient
from app.config import Settings
from app.schemas import AgentRequest, HistoryMessage, ToolContext, ToolResult
from app.tools.business_tools import BusinessTools


def reply(text="", calls=()):
    return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(
        content=text, tool_calls=list(calls), reasoning_content=None))])


def final(text):
    return reply(json.dumps({"answerText": text, "structuredAnswer": {
        "summary": text, "steps": [], "cautions": [], "basisNote": ""},
        "insufficientContext": False}, ensure_ascii=False))


def tool_call(name, arguments="{}", identifier="call-1"):
    return SimpleNamespace(id=identifier, function=SimpleNamespace(name=name, arguments=arguments))


def agent_with(responses, result=None):
    service = AgentService.__new__(AgentService)
    service.settings = Settings(_env_file=None, deepseek_api_key="offline-test",
                                agent_format_repair_enabled=False)
    requests, tool_requests = [], []
    iterator = iter(responses)

    def create(**kwargs):
        requests.append(kwargs)
        value = next(iterator)
        if isinstance(value, Exception):
            raise value
        return value

    def execute(name, arguments, context):
        tool_requests.append((name, arguments, context))
        return result if result is not None else ToolResult(ok=False, error="login_required",
                                                          write_status="not_dispatched")

    service.client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create)))
    service.registry = SimpleNamespace(execute=execute)
    return service, requests, tool_requests


def repair_request():
    return AgentRequest(
        query="信息确认无误，请提交维修申请", userId="user-1", manualId="manual-1",
        history=[HistoryMessage(role="user", content="洗衣机不排水"),
                 HistoryMessage(role="assistant", content="待提交维修申请：故障不排水。是否确认提交？")],
    )


def repair_success():
    return ToolResult(data={"id": "order-1"}, write_status="succeeded",
                      write_target="user-1|manual-1||不排水", write_identifier="order-1")


def test_p08b_no_tool_cannot_claim_repair_success():
    fixture = json.loads((Path(__file__).parent / "fixtures/write_outcome_p08b.json").read_text(encoding="utf-8"))
    original = fixture["raw_final"]["answerText"]
    service, requests, tool_requests = agent_with([reply(json.dumps(fixture["raw_final"], ensure_ascii=False))])
    response = service.run(repair_request())
    assert response.answerText != original
    assert "本轮没有执行提交维修申请" in response.answerText
    assert "已提交" not in response.model_dump_json()
    assert len(requests) == 1 and tool_requests == fixture["tool_calls"]
    assert response.structuredAnswer.summary == response.answerText


def test_no_tool_claim_hidden_in_structured_answer_is_corrected():
    payload = {"answerText": "请留意后续通知。", "structuredAnswer": {
        "summary": "请留意后续通知。", "steps": [], "cautions": [],
        "basisNote": "本轮已提交维修申请。"}, "insufficientContext": False}
    service, _, tools = agent_with([reply(json.dumps(payload, ensure_ascii=False))])
    response = service.run(repair_request())
    assert tools == []
    assert "本轮没有执行提交维修申请" in response.answerText
    assert "已提交" not in response.model_dump_json()


@pytest.mark.parametrize("query,answer", [
    ("怎么收藏说明书？", "打开说明书后，可在页面找到收藏按钮。"),
    ("报修需要准备什么？", "请准备设备型号、故障现象和联系电话。"),
    ("之前的报修单已经提交了吗？", "之前的记录显示已提交；本轮没有创建新工单。"),
])
def test_read_only_and_historical_answers_are_untouched(query, answer):
    service, _, tools = agent_with([final(answer)])
    response = service.run(AgentRequest(query=query))
    assert response.answerText == answer and tools == []


def test_unobserved_manual_success_is_corrected():
    service, _, _ = agent_with([final("已为你收藏这本说明书。")])
    response = service.run(AgentRequest(query="请收藏当前这本说明书", manualId="manual-1"))
    assert "本轮没有执行收藏说明书" in response.answerText


@pytest.mark.parametrize("result,expected", [
    (repair_success(), "工单号：order-1"),
    (ToolResult(ok=False, error="write_result_unknown", write_status="unknown"), "结果暂无法确认"),
    (ToolResult(ok=False, error="repair_rejected", write_status="failed"), "业务服务已明确拒绝"),
    (ToolResult(ok=False, error="login_required", write_status="not_dispatched"), "本轮未执行"),
])
def test_write_result_overrules_model_success_claim(result, expected):
    service, _, tools = agent_with([
        reply(calls=[tool_call("create_repair_order", '{"issue_desc":"不排水","confirmed":true}')]),
        final("已提交维修申请。"),
    ], result)
    response = service.run(repair_request())
    assert expected in response.answerText
    assert len(tools) == 1
    assert response.structuredAnswer.summary == response.answerText


def test_unknown_result_overrules_definite_failure_claim():
    service, _, _ = agent_with([
        reply(calls=[tool_call("create_repair_order")]), final("本轮未提交维修申请。")],
        ToolResult(ok=False, error="write_result_unknown", write_status="unknown"),
    )
    assert "结果暂无法确认" in service.run(repair_request()).answerText


def test_wrong_manual_target_does_not_authorize_success():
    result = ToolResult(write_status="succeeded", write_target="other-manual", write_identifier="mine-1")
    service, _, _ = agent_with([
        reply(calls=[tool_call("add_manual_to_account")]), final("已为你收藏这本说明书。")], result)
    response = service.run(AgentRequest(query="请收藏当前这本说明书", manualId="manual-1"))
    assert "结果暂无法确认" in response.answerText


def test_success_of_another_write_tool_cannot_prove_repair_submission():
    manual_result = ToolResult(write_status="succeeded", write_target="manual-1",
                               write_identifier="mine-1")
    service, _, tools = agent_with([
        reply(calls=[tool_call("add_manual_to_account")]),
        final("已为你收藏说明书，也已提交维修申请。"),
    ], manual_result)
    response = service.run(AgentRequest(
        query="请收藏当前说明书，并确认提交维修申请", manualId="manual-1", userId="user-1"))
    assert len(tools) == 1
    assert "本轮没有执行提交维修申请" in response.answerText
    assert "已提交维修申请" not in response.answerText


@pytest.mark.parametrize("last_response", [RuntimeError("provider secret=bad"), SimpleNamespace(choices=[]), reply(""), reply("not json")])
def test_model_finalization_failure_preserves_verified_write(last_response):
    service, requests, tools = agent_with([
        reply(calls=[tool_call("create_repair_order")]), last_response], repair_success())
    response = service.run(repair_request())
    assert response.status == "ok"
    assert "工单号：order-1" in response.answerText
    assert len(tools) == 1 and len(requests) == 2
    assert "secret=bad" not in response.model_dump_json()
    json.loads(response.model_dump_json())


def test_model_failure_after_uncertain_write_never_retries():
    service, _, tools = agent_with([
        reply(calls=[tool_call("create_repair_order")]), RuntimeError("provider failed")],
        ToolResult(ok=False, error="write_result_unknown", write_status="unknown"),
    )
    response = service.run(repair_request())
    assert "结果暂无法确认" in response.answerText
    assert "已提交" not in response.answerText
    assert len(tools) == 1


def test_duplicate_write_is_not_dispatched_again():
    service, _, tools = agent_with([
        reply(calls=[tool_call("create_repair_order", identifier="first")]),
        reply(calls=[tool_call("create_repair_order", identifier="second")]),
        final("已提交维修申请。"),
    ], repair_success())
    response = service.run(repair_request())
    assert len(tools) == 1
    assert response.toolCalls[1]["error"] == "write_already_attempted"


def test_raw_write_error_is_not_exposed_to_model_or_user():
    secret = "secret-key-123 phone-13800000000"
    service, requests, _ = agent_with([
        reply(calls=[tool_call("create_repair_order")]), final("已提交维修申请。")],
        ToolResult(ok=False, error=f"failed: {secret}", data={"detail": secret}),
    )
    response = service.run(repair_request())
    assert secret not in response.model_dump_json()
    assert secret not in json.dumps(requests[1]["messages"], ensure_ascii=False)


class FakeJava:
    def __init__(self, response=None, exception=None):
        self.response, self.exception, self.calls = response, exception, 0

    def add_manual(self, user_id, manual_id):
        self.calls += 1
        if self.exception:
            raise self.exception
        return self.response

    def create_repair_order(self, payload):
        self.calls += 1
        if self.exception:
            raise self.exception
        return self.response


@pytest.mark.parametrize("payload,status", [
    ({"id": "mine-1", "manualId": "manual-1"}, "succeeded"),
    ({"status": "already_exists", "_http_status": 409}, "already_exists"),
    ({"status": "already_exists"}, "unknown"),
    ({"status": "already_exists", "_http_status": 409, "manualId": "other"}, "unknown"),
    ({"error": "cannot_save"}, "unknown"),
    ({"id": "mine-1", "manualId": "other"}, "unknown"),
    ({}, "unknown"),
])
def test_manual_business_response_is_checked(payload, status):
    java = FakeJava(response=payload)
    result = BusinessTools(java_client=java).add_manual_to_account(
        {}, ToolContext(user_id="user-1", manual_id="manual-1"))
    assert result.write_status == status and java.calls == 1
    assert result.ok is (status in {"succeeded", "already_exists"})


@pytest.mark.parametrize("payload,status", [
    ({"id": "order-1", "userId": "user-1", "manualId": "manual-1", "deviceId": "", "issueDesc": "不排水"}, "succeeded"),
    ({"status": "already_exists"}, "unknown"),
    ({"error": "cannot_save"}, "unknown"),
    ({"id": "order-1", "userId": "other", "manualId": "manual-1", "deviceId": "", "issueDesc": "不排水"}, "unknown"),
    ({}, "unknown"),
])
def test_repair_business_response_is_checked(payload, status):
    java = FakeJava(response=payload)
    result = BusinessTools(java_client=java).create_repair_order(
        {"confirmed": True, "issue_desc": "不排水"}, ToolContext(user_id="user-1", manual_id="manual-1"))
    assert result.write_status == status and java.calls == 1
    assert result.ok is (status == "succeeded")


def test_login_rejection_does_not_dispatch():
    java = FakeJava(response={})
    result = BusinessTools(java_client=java).create_repair_order(
        {"confirmed": True, "issue_desc": "不排水"}, ToolContext())
    assert result.write_status == "not_dispatched" and java.calls == 0


def test_timeout_is_unknown_and_exception_text_is_hidden():
    java = FakeJava(exception=httpx.ReadTimeout("secret-key phone-13800000000"))
    result = BusinessTools(java_client=java).create_repair_order(
        {"confirmed": True, "issue_desc": "不排水"}, ToolContext(user_id="user-1"))
    assert result.write_status == "unknown" and result.error == "write_result_unknown" and java.calls == 1


@pytest.mark.parametrize("path,status,expected", [
    ("/api/my-manuals", 409, "already_exists"),
    ("/api/service-orders", 409, "raises"),
])
def test_java_client_409_is_endpoint_specific(monkeypatch, path, status, expected):
    original_client = httpx.Client
    transport = httpx.MockTransport(lambda request: httpx.Response(status, text="conflict"))
    monkeypatch.setattr("app.clients.java_api.httpx.Client", lambda **kwargs: original_client(
        transport=transport, **kwargs))
    client = JavaApiClient(Settings(_env_file=None))
    if expected == "raises":
        with pytest.raises(httpx.HTTPStatusError):
            client._request("POST", path, json={"userId": "user-1"})
    else:
        assert client._request("POST", path, json={"userId": "user-1"})["status"] == expected


@pytest.mark.parametrize("operation,http_status,body,expected", [
    ("manual", 201, {"id": "mine-1", "manualId": "manual-1"}, "succeeded"),
    ("manual", 200, {"error": "save_failed"}, "unknown"),
    ("manual", 409, None, "already_exists"),
    ("manual", 401, None, "failed"),
    ("repair", 201, {"id": "order-1", "userId": "user-1", "manualId": "manual-1",
                     "deviceId": "", "issueDesc": "不排水"}, "succeeded"),
    ("repair", 200, {"error": "save_failed"}, "unknown"),
    ("repair", 409, None, "unknown"),
    ("repair", 400, None, "failed"),
    ("repair", 500, None, "unknown"),
])
def test_mock_http_write_outcomes(monkeypatch, operation, http_status, body, expected):
    original_client = httpx.Client

    def respond(request):
        expected_path = "/api/my-manuals" if operation == "manual" else "/api/service-orders"
        assert request.url.path == expected_path
        if body is None:
            return httpx.Response(http_status, text="business error")
        return httpx.Response(http_status, json=body)

    transport = httpx.MockTransport(respond)
    monkeypatch.setattr("app.clients.java_api.httpx.Client", lambda **kwargs: original_client(
        transport=transport, **kwargs))
    java = JavaApiClient(Settings(_env_file=None))
    tools = BusinessTools(java_client=java)
    context = ToolContext(user_id="user-1", manual_id="manual-1")
    result = (tools.add_manual_to_account({}, context) if operation == "manual" else
              tools.create_repair_order({"confirmed": True, "issue_desc": "不排水"}, context))
    assert result.write_status == expected
    assert result.ok is (expected in {"succeeded", "already_exists"})
