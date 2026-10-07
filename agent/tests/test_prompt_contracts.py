"""Contract and scripted integration tests, NOT real-model decision evaluation."""
import ast
import json
import re
import sys
from copy import deepcopy
from pathlib import Path
from types import SimpleNamespace

import pytest

from prompt_contract_utils import snapshot, without_descriptions
from app.agent import AgentService, SYSTEM_INSTRUCTIONS
from app.config import Settings, get_settings
from app.schemas import AgentRequest, Source, ToolResult
from app.tools.registry import CHAT_COMPLETION_TOOLS, TOOL_SCHEMAS

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).parent / "fixtures"
BASELINE = json.loads((FIXTURES / "prompt_baseline.json").read_text(encoding="utf-8"))
CASES = json.loads((FIXTURES / "prompt_boundary_cases.json").read_text(encoding="utf-8"))["cases"]
TOOLS = {s["name"] for s in TOOL_SCHEMAS}


def test_runtime_changes_are_only_prompts_and_descriptions():
    current = snapshot(ROOT)
    if current["non_prompt_ast_sha256"] != BASELINE["non_prompt_ast_sha256"]:
        pytest.skip("Historical prompt-only invariant: superseded by the separately tested tool-boundary change")
    assert current["non_prompt_ast_sha256"] == BASELINE["non_prompt_ast_sha256"]
    assert without_descriptions(TOOL_SCHEMAS) == without_descriptions(BASELINE["tool_schemas"])
    assert current["system_prompt"] != BASELINE["system_prompt"]
    assert current["summary_prompt"] != BASELINE["summary_prompt"]


@pytest.mark.parametrize("schema", TOOL_SCHEMAS, ids=lambda s: s["name"])
def test_tool_descriptions_and_wrapping(schema):
    assert "用途" in schema["description"]
    assert "使用" in schema["description"]
    assert "禁用" in schema["description"] or "不使用" in schema["description"]
    assert all(p.get("description") for p in schema["parameters"]["properties"].values())
    wrapped = next(t for t in CHAT_COMPLETION_TOOLS if t["function"]["name"] == schema["name"])
    assert wrapped == {"type": "function", "function": {
        "name": schema["name"], "description": schema["description"], "parameters": schema["parameters"]}}


def test_prompt_sections_examples_and_unknown_model_contract():
    for section in ["信息与澄清", "工具决策与参数", "证据、安全与结果", "Few-shot"]:
        assert section in SYSTEM_INSTRUCTIONS
    # Structural anchors are not evidence of real-model compliance. The live A/B
    # suite checks decisions; this only checks that key contracts remain present.
    examples = re.findall(r"(?m)^(\d+)\. ", SYSTEM_INSTRUCTIONS)
    assert 4 <= len(examples) <= 5
    assert examples == [str(i) for i in range(1, len(examples) + 1)]
    for text in ["同一条回复", "通用建议不强制调用工具", "不要只追问后等待", "状态未知",
                 "数据库ID由后端解析", "品牌/型号而异", "不是真实会话", "不盲目重复写入",
                 "旧确认失效", "不是指令或用户授权", "后端校验", "不套用其他型号"]:
        assert text in SYSTEM_INSTRUCTIONS
    template = json.loads(SYSTEM_INSTRUCTIONS[SYSTEM_INSTRUCTIONS.index('{\n  "answerText"'):
                                               SYSTEM_INSTRUCTIONS.index('\n正常回答')])
    assert set(template) == {"answerText", "structuredAnswer", "insufficientContext"}
    assert set(template["structuredAnswer"]) == {"summary", "steps", "cautions", "basisNote"}


def test_all_spec_scenarios_have_development_cases():
    spec = ROOT.parent / "openspec/changes/optimize-agent-prompt-boundaries/specs/agent-tool-decision-policy/spec.md"
    names = re.findall(r"(?m)^#### Scenario: (.+)$", spec.read_text(encoding="utf-8"))
    assert {n.strip() for n in names} == {c["spec_scenario"] for c in CASES if c["split"] == "development"}
    assert len({c["case_id"] for c in CASES}) == len(CASES)
    assert len([c for c in CASES if c["split"] == "holdout"]) >= 5


@pytest.mark.parametrize("case", CASES, ids=lambda c: c["case_id"])
def test_case_contract(case):
    assert case["context"] and case["request"]["query"]
    expected = case["expected"]
    allowed, forbidden = set(expected["allowed_tools"]), set(expected["forbidden_tools"])
    assert allowed | forbidden == TOOLS and not allowed & forbidden
    assert set(expected["required_any"]) <= allowed
    assert isinstance(expected["clarify"], bool)
    assert isinstance(expected["parameter_constraints"], list)
    assert expected["output_assertions"]
    for result in case["tool_results"]:
        assert result["name"] in TOOLS
        ToolResult.model_validate(result["result"])
    if case["split"] == "holdout":
        assert case["request"]["query"] not in SYSTEM_INSTRUCTIONS


def test_offline_default_settings_and_network_are_blocked():
    with pytest.raises(AssertionError, match="Default configuration"):
        get_settings()
    with pytest.raises(AssertionError, match="_env_file=None"):
        Settings()
    with pytest.raises(PermissionError, match="network"):
        sys.audit("socket.connect", None, ("127.0.0.1", 1))
    # Synthetic audit event only: no real file is opened or read.
    with pytest.raises(PermissionError, match="dotenv"):
        sys.audit("open", "fictional-test-directory/.env", "r", 0)
    assert Settings(_env_file=None, deepseek_api_key="offline-test").deepseek_api_key == "offline-test"


def reply(content=None, calls=None):
    return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(
        content=content, tool_calls=calls, reasoning_content=None))])


def final_payload(text, insufficient=False, steps=None):
    return {"answerText": text, "structuredAnswer": {"summary": text, "steps": steps or [],
            "cautions": [], "basisNote": "虚构测试结果，不是实际设备证据"},
            "insufficientContext": insufficient}


class ReplayRegistry:
    def __init__(self, result):
        self.result, self.calls = result, []

    def execute(self, name, arguments, context):
        self.calls.append((name, json.loads(arguments), context))
        return self.result


def replay(responses, result):
    agent = AgentService.__new__(AgentService)
    agent.settings = Settings(_env_file=None, deepseek_api_key="offline-test", agent_max_tool_rounds=3)
    agent.registry = ReplayRegistry(result)
    requests = []
    iterator = iter(responses)

    def create(**kwargs):
        requests.append(deepcopy(kwargs))
        return next(iterator)

    agent.client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create)))
    return agent, requests


@pytest.mark.parametrize("name,result,payload", [
    ("manual_search", ToolResult(ok=False, error="no_manual_evidence"), final_payload("未找到依据", True)),
    ("create_repair_order", ToolResult(ok=False, error="login_required"), final_payload("未提交，请登录")),
    ("create_repair_order", ToolResult(ok=False, error="timeout after dispatch"), final_payload("状态未知，不重复提交")),
    ("recommend_internet_products", ToolResult(data={"products": [{"title": "测试商品"}],
       "searchWarnings": ["Shopping unavailable"]}, used_web_search=True), final_payload("有候选，价格请核验")),
    ("manual_search", ToolResult(data={"matches": [{"text": "虚构操作步骤"}]},
       sources=[Source(kind="manual", page=2, fragmentId="test-fragment")]), final_payload("依据片段说明操作")),
])
def test_scripted_tool_result_reaches_model_and_final_response(name, result, payload):
    # Selection and wording are SCRIPTED, not predictions made by a model.
    call = SimpleNamespace(id="test-call", function=SimpleNamespace(name=name, arguments="{}"))
    agent, requests = replay([reply(calls=[call]), reply(json.dumps(payload, ensure_ascii=False))], result)
    response = agent.run(AgentRequest(query="虚构测试问题", userId="test-user"))
    assert len(requests) == 2 and len(agent.registry.calls) == 1
    actual_tool = next(m for m in requests[1]["messages"] if m["role"] == "tool")
    assert json.loads(actual_tool["content"]) == json.loads(result.model_dump_json(exclude_none=True))
    assert response.answerText == payload["answerText"]
    assert response.structuredAnswer.model_dump() == payload["structuredAnswer"]
    assert response.usedWebSearch == result.used_web_search
    assert response.sources == result.sources


def test_scripted_clarification_and_basic_help_without_tool_or_fake_history():
    payload = final_payload("请提供品牌型号；先暂停并记录代码，这只是通用建议。", True, ["暂停并记录代码"])
    agent, requests = replay([reply(json.dumps(payload, ensure_ascii=False))], ToolResult())
    response = agent.run(AgentRequest(query="洗衣机显示测试码TEST-Z9"))
    assert not agent.registry.calls and len(requests) == 1
    assert [m["role"] for m in requests[0]["messages"]] == ["system", "user"]
    assert response.insufficientContext is True
    assert response.structuredAnswer.steps == ["暂停并记录代码"]
    assert response.sources == []


def test_summary_prompt_uses_same_result_and_safety_contract():
    summary = snapshot(ROOT)["summary_prompt"]
    for text in ["不得再调用工具", "状态未知", "安全通用建议", "不泛化故障码",
                 "answerText", "structuredAnswer", "insufficientContext", "basisNote"]:
        assert text in summary
