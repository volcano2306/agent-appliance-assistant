"""Gateway compatibility regressions with explicit settings and fake transports."""
from time import perf_counter
from types import SimpleNamespace
from contextlib import nullcontext
import gzip
import json

import pytest

from app.config import Settings
from app.memory import response_usage
from app.model_options import model_provider, model_request_options
from app.schemas import AgentRequest
from test_agent_loop import final, make_agent, tool_reply
from test_session_memory import response, service, summary_request, valid_summary
from prompt_ab_eval_support import build_live_client


def test_newapi_tool_round_trip_has_no_deepseek_parameters():
    agent, client = make_agent([tool_reply(1), final("完成")])
    result = agent.run(AgentRequest(query="查询当前说明书"))
    assert result.status == "ok" and result.target == "newapi"
    assert result.model == "qwen3.8-flash-next"
    assert len(agent.registry.calls) == 1
    assert all("extra_body" not in call for call in client.requests)
    assert client.requests[1]["messages"][-1]["role"] == "tool"


def test_gateway_extension_reaches_tools_finalization_repair_and_summary():
    body = {"chat_template_kwargs": {"enable_thinking": False}}
    agent, client = make_agent([tool_reply(1), tool_reply(2), final("完成"), final("修复")], rounds=1)
    agent.settings.model_extra_body = body
    assert agent.run(AgentRequest(query="查询")).status == "ok"
    assert client.requests[-1]["tool_choice"] == "none"
    assert agent._repair_final_format("invalid") is not None
    assert all(call["extra_body"] == body for call in client.requests)
    summarizer, summary_client = service([response(valid_summary())], model_extra_body=body)
    assert summarizer.summarize(summary_request()).ok
    assert summary_client.calls[0]["extra_body"] == body
    assert "tools" not in summary_client.calls[0]


@pytest.mark.parametrize("thinking", [False, True])
def test_official_deepseek_keeps_thinking_protocol(thinking):
    settings = Settings(_env_file=None, deepseek_base_url="https://api.deepseek.com/v1",
                        deepseek_model="deepseek-v4-flash")
    assert model_provider(settings) == "deepseek"
    assert model_request_options(settings, thinking=thinking) == {
        "extra_body": {"thinking": {"type": "enabled" if thinking else "disabled"}}}


def test_model_name_does_not_enable_deepseek_parameters_on_a_gateway():
    settings = Settings(_env_file=None, deepseek_model="deepseek-v4-flash",
                        deepseek_thinking_enabled=True)
    assert model_request_options(settings, thinking=True) == {}
    settings.deepseek_base_url = "https://api.deepseek.com.other.invalid/v1"
    assert model_provider(settings) == "openai-compatible"
    assert model_request_options(settings) == {}


@pytest.mark.parametrize("cached,legacy,expected", [(12, None, 12), (12, 0, 0), (None, None, None)])
def test_gateway_cache_usage_keeps_missing_values_unknown(cached, legacy, expected):
    result = SimpleNamespace(usage=SimpleNamespace(prompt_tokens=123, completion_tokens=45,
        prompt_cache_hit_tokens=legacy, prompt_tokens_details=SimpleNamespace(cached_tokens=cached)))
    usage = response_usage(result, "answer", perf_counter(), "ok")
    assert usage.cachedInputTokens == expected
    assert usage.inputTokens == 123 and usage.outputTokens == 45


def test_guarded_transport_does_not_decompress_provider_response_twice(monkeypatch):
    import httpx

    payload = {"id": "synthetic-response", "object": "chat.completion", "created": 0,
               "model": "qwen3.8-flash-next", "choices": [{"index": 0,
               "message": {"role": "assistant", "content": "2"}, "finish_reason": "stop"}]}

    class FakeTransport:
        def __init__(self, **kwargs):
            pass

        def handle_request(self, request):
            return httpx.Response(200, headers={"content-encoding": "gzip"},
                stream=httpx.ByteStream(gzip.compress(json.dumps(payload).encode())), request=request)

        def close(self):
            pass

    monkeypatch.setattr(httpx, "HTTPTransport", FakeTransport)
    guard = SimpleNamespace(configure=lambda url: None, network=nullcontext)
    with build_live_client("synthetic-key", "https://windhub.cc/v1", 1, guard) as client:
        result = client.chat.completions.create(model="qwen3.8-flash-next",
            messages=[{"role": "user", "content": "test"}], max_tokens=1)
    assert result.choices[0].message.content == "2"
