"""Offline contract tests: no dotenv, model network, database or business writes."""

import json
from types import SimpleNamespace

from app.agent import AgentService
from app.config import Settings
from app.schemas import AgentRequest, ToolContext, ToolResult
from app.tools.argument_policy import prepare_arguments
from app.tools.business_tools import BusinessTools
from app.tools.manual_tools import ManualTools
from app.tools.registry import ToolRegistry


def test_model_database_id_is_not_dispatched_to_retrieval():
    captured = []
    registry = ToolRegistry.__new__(ToolRegistry)
    registry.handlers = {"manual_search": lambda args, context: captured.append((args, context)) or ToolResult()}
    context = ToolContext(manual_id="mongo-platform-1", manual_ref_id="mine-2")
    result = registry.execute("manual_search", '{"query":"童锁","manual_id":"TEST-W100"}', context)
    assert result.ok
    assert captured[0][0] == {"query": "童锁"}
    assert captured[0][1].manual_id == "mongo-platform-1"

    class Retriever:
        def search(self, query, manual_id):
            captured.append((query, manual_id))
            return []

        def sources(self, results):
            return []

    ManualTools(retriever=Retriever()).manual_search({"query": "童锁", "manual_id": "TEST-W100"}, context)
    assert captured[-1] == ("童锁", "mongo-platform-1")


def test_collection_uses_current_target_only_when_user_requested_it():
    current = ToolContext(manual_id="mongo-platform-1", manual_name="TEST-W100说明书",
                          user_query="请把正在看的这本收藏起来")
    parsed, error = prepare_arguments("add_manual_to_account", {"manual_id": "TEST-W100"}, current)
    assert error == "" and parsed == {"manual_query": ""}
    other = current.model_copy(update={"user_query": "把B那本收藏起来"})
    _, error = prepare_arguments("add_manual_to_account", {}, other)
    assert error == "manual_target_requires_confirmation"
    ambiguous = current.model_copy(update={"user_query": "把B那本TEST-W100说明书收藏起来"})
    _, error = prepare_arguments("add_manual_to_account", {}, ambiguous)
    assert error == "manual_target_requires_confirmation"
    question = current.model_copy(update={"user_query": "怎样加入我的说明书？"})
    _, error = prepare_arguments("add_manual_to_account", {}, question)
    assert error == "explicit_add_request_required"


def test_private_upload_is_not_a_platform_collection_target():
    context = ToolContext(manual_ref_id="my-upload-1", user_query="请把正在看的这本收藏起来")
    _, error = prepare_arguments("add_manual_to_account", {}, context)
    assert error == "platform_manual_required"


def test_repair_removes_guessed_brand_and_model_supplied_as_id():
    context = ToolContext(user_query="信息都对，确认提交这次维修申请", manual_id="mongo-platform-1",
                          manual_model="TEST-W100", last_assistant_message="待提交维修申请：设备TEST-W100；故障不排水。是否确认提交？",
                          user_history=["TEST-W100洗衣机不排水，没有故障码。"])
    parsed, error = prepare_arguments("create_repair_order", {
        "brand": "TEST", "model": "TEST-W100", "manual_id": "TEST-W100",
        "issue_desc": "不排水", "confirmed": True,
    }, context)
    assert error == ""
    assert parsed["brand"] == ""
    assert parsed["model"] == "TEST-W100"
    assert "manual_id" not in parsed

    class Java:
        def create_repair_order(self, payload):
            return {"id": "order-1", **payload}

    saved = BusinessTools(java_client=Java()).create_repair_order(
        {**parsed, "manual_id": "TEST-W100", "device_id": "fake-product"},
        context.model_copy(update={"user_id": "user-1", "product_id": "verified-product"}),
    )
    assert saved.data["manualId"] == "mongo-platform-1"
    assert saved.data["deviceId"] == "verified-product"
    assert saved.data["brandName"] == ""
    _, error = prepare_arguments("create_repair_order", {"issue_desc": "不排水", "confirmed": True},
                                 context.model_copy(update={"user_query": "先不要提交，电话要改"}))
    assert error == "explicit_confirmation_required"


def _reply(content, calls=None):
    return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=content, tool_calls=calls or []))])


def _service(responses, repair_enabled=True, registry=None):
    agent = AgentService.__new__(AgentService)
    agent.settings = Settings(_env_file=None, deepseek_api_key="offline-test",
                              agent_format_repair_enabled=repair_enabled)
    calls = []
    iterator = iter(responses)

    def create(**kwargs):
        calls.append(kwargs)
        return next(iterator)

    agent.client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create)))
    agent.registry = registry or SimpleNamespace(execute=lambda *_: ToolResult())
    return agent, calls


def test_program_assembles_valid_frontend_response_without_extra_model_call():
    agent, calls = _service([_reply("维修状态未知，请勿重复提交。")], repair_enabled=False)
    response = agent.run(AgentRequest(query="刚才提交了吗？"))
    assert response.answerText == "维修状态未知，请勿重复提交。"
    assert response.structuredAnswer.summary == response.answerText
    assert len(calls) == 1
    assert AgentService._raw_final_valid("维修状态未知，请勿重复提交。") is False
    json.loads(response.model_dump_json())


def test_one_format_only_call_never_dispatches_tools_or_changes_answer():
    repaired = {"answerText": "维修状态未知，请勿重复提交。", "structuredAnswer": {
        "summary": "维修状态未知", "steps": [], "cautions": [], "basisNote": ""},
        "insufficientContext": False}
    agent, calls = _service([_reply("维修状态未知，请勿重复提交。"), _reply(json.dumps(repaired, ensure_ascii=False))])
    response = agent.run(AgentRequest(query="刚才提交了吗？"))
    assert len(calls) == 2
    assert calls[1]["tool_choice"] == "none"
    assert response.answerText == "维修状态未知，请勿重复提交。"
    assert response.structuredAnswer.summary == response.answerText
    assert AgentService._raw_final_valid(json.dumps(repaired, ensure_ascii=False))


def test_tool_error_is_returned_without_invoking_handler():
    registry = ToolRegistry.__new__(ToolRegistry)
    registry.handlers = {"create_repair_order": lambda *_: (_ for _ in ()).throw(AssertionError("write"))}
    context = ToolContext(user_query="报修需要准备什么？")
    result = registry.execute("create_repair_order", '{"issue_desc":"不排水","confirmed":true}', context)
    assert not result.ok and result.error == "explicit_confirmation_required"


def test_repair_does_not_accept_unconfirmed_or_invented_issue():
    context = ToolContext(user_query="信息都对，确认提交", last_assistant_message="待提交维修申请：不排水。是否确认提交？",
                          user_history=["洗衣机不排水"])
    _, error = prepare_arguments("create_repair_order", {"issue_desc": "电机烧毁", "confirmed": True}, context)
    assert error == "issue_desc_unverified"
    _, error = prepare_arguments("create_repair_order", {"issue_desc": "不排水", "confirmed": True},
                                 context.model_copy(update={"user_query": "不确认提交"}))
    assert error == "explicit_confirmation_required"
    _, error = prepare_arguments("create_repair_order", {"issue_desc": "不排水", "confirmed": True},
                                 context.model_copy(update={"unconfirmed_change": True}))
    assert error == "repair_preview_required"


def test_repair_uses_confirmed_issue_instead_of_model_expansion():
    context = ToolContext(
        user_query="信息都对，确认提交这次维修申请",
        manual_model="TEST-W100",
        last_assistant_message=(
            "待提交维修申请：设备TEST-W100洗衣机，故障是不排水；"
            "你没有提供联系电话和预约时间。是否确认提交？"
        ),
        user_history=["TEST-W100洗衣机不排水，没有故障码。"],
    )
    parsed, error = prepare_arguments("create_repair_order", {
        "issue_desc": "TEST-W100洗衣机不排水，无故障码。",
        "model": "TEST-W100", "confirmed": True,
    }, context)
    assert error == ""
    assert parsed["issue_desc"] == "不排水"
    assert parsed["model"] == "TEST-W100"

    _, error = prepare_arguments("create_repair_order", {
        "issue_desc": "TEST-W100洗衣机电机烧毁，无故障码。", "confirmed": True,
    }, context)
    assert error == "issue_desc_unverified"

    ungrounded = context.model_copy(update={
        "last_assistant_message": "待提交维修申请：故障是电机烧毁。是否确认提交？"
    })
    _, error = prepare_arguments("create_repair_order", {
        "issue_desc": "电机烧毁", "confirmed": True,
    }, ungrounded)
    assert error == "issue_desc_unverified"


def test_a_write_is_dispatched_at_most_once_even_if_model_repeats_it():
    write = SimpleNamespace(id="w1", function=SimpleNamespace(name="add_manual_to_account", arguments="{}"))
    repeat = SimpleNamespace(id="w2", function=SimpleNamespace(name="add_manual_to_account", arguments="{}"))
    final = {"answerText": "已处理", "structuredAnswer": {"summary": "已处理", "steps": [],
             "cautions": [], "basisNote": ""}, "insufficientContext": False}
    registry_calls = []
    registry = SimpleNamespace(execute=lambda name, args, context: registry_calls.append(name) or ToolResult())
    agent, _ = _service([_reply("", [write]), _reply("", [repeat]),
                         _reply(json.dumps(final, ensure_ascii=False))], registry=registry)
    response = agent.run(AgentRequest(query="收藏这本说明书"))
    assert registry_calls == ["add_manual_to_account"]
    assert response.toolCalls[1]["error"] == "write_already_attempted"
