"""Offline loop regression tests: never load .env or contact external services."""

import json
from copy import deepcopy
from types import SimpleNamespace

import pytest

from app.agent import AgentService
from app.config import Settings
from app.schemas import AgentRequest, Source, ToolResult


def reply(content=None, calls=None, reasoning=None):
    message = SimpleNamespace(content=content, tool_calls=calls, reasoning_content=reasoning)
    return SimpleNamespace(choices=[SimpleNamespace(message=message)])


def final(text):
    return reply(json.dumps({"answerText": text, "structuredAnswer": {
        "summary": text, "steps": [], "cautions": [], "basisNote": ""},
        "insufficientContext": False}))


def tool_reply(index):
    call = SimpleNamespace(
        id=f"call-{index}",
        function=SimpleNamespace(name="manual_search", arguments=json.dumps({"query": f"query-{index}"})),
    )
    return reply(calls=[call], reasoning=f"reasoning-{index}")


class FakeRegistry:
    def __init__(self):
        self.calls = []

    def execute(self, name, arguments, context):
        self.calls.append((name, json.loads(arguments)))
        index = len(self.calls)
        return ToolResult(
            data={"evidence": f"evidence-{index}"},
            sources=[Source(kind="manual", fragmentId=f"fragment-{index}", page=index)],
        )


class FakeCompletions:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.requests = []

    def create(self, **kwargs):
        self.requests.append(deepcopy(kwargs))
        return next(self.responses)


def make_agent(responses, rounds=3):
    # Bypass the SDK constructor and registry initialization entirely: no HTTP,
    # MongoDB, Qdrant, embedding downloads or implicit Settings() calls.
    service = AgentService.__new__(AgentService)
    service.settings = Settings(_env_file=None, deepseek_api_key="offline-test", agent_max_tool_rounds=rounds)
    service.registry = FakeRegistry()
    completions = FakeCompletions(responses)
    service.client = SimpleNamespace(chat=SimpleNamespace(completions=completions))
    return service, completions


def test_four_tool_rounds_then_fifth_call_summarizes_all_results():
    service, client = make_agent([
        *(tool_reply(i) for i in range(1, 5)),
        final("summary from all four results"),
    ])
    result = service.run(AgentRequest(query="How do I use this appliance?"))
    assert result.status == "ok"
    assert result.answerText == "summary from all four results"
    assert len(client.requests) == 5
    assert [request["tool_choice"] for request in client.requests] == ["auto"] * 4 + ["none"]
    assert len(service.registry.calls) == 4
    messages = client.requests[-1]["messages"]
    assert [m["tool_call_id"] for m in messages if m["role"] == "tool"] == [f"call-{i}" for i in range(1, 5)]
    assert "evidence-4" in messages[-2]["content"]
    assert len(result.sources) == 4
    assert len(result.toolCalls) == 4
    assert any(m.get("reasoning_content") == "reasoning-4" for m in messages)


@pytest.mark.parametrize("tool_rounds", [0, 1, 2, 3])
def test_early_final_answer_does_not_add_summary_call(tool_rounds):
    service, client = make_agent([*(tool_reply(i) for i in range(tool_rounds)), final("done")])
    result = service.run(AgentRequest(query="question"))
    assert result.answerText == "done"
    assert len(client.requests) == tool_rounds + 1
    assert all(r["tool_choice"] == "auto" for r in client.requests)


@pytest.mark.parametrize("final_response", [
    SimpleNamespace(choices=[]), reply(""), reply("   "), tool_reply(5),
])
def test_invalid_summary_is_reported_without_executing_fifth_tool(final_response):
    service, client = make_agent([*(tool_reply(i) for i in range(1, 5)), final_response])
    result = service.run(AgentRequest(query="question"))
    assert len(client.requests) == 5
    assert len(service.registry.calls) == 4
    assert result.status == "error"
    assert result.error == "summary_unavailable"
    assert result.insufficientContext is True


def test_custom_budget_still_reserves_one_summary_call():
    service, client = make_agent([tool_reply(1), tool_reply(2), final("done")], rounds=1)
    result = service.run(AgentRequest(query="question"))
    assert result.status == "ok"
    assert len(client.requests) == 3
    assert client.requests[-1]["tool_choice"] == "none"
