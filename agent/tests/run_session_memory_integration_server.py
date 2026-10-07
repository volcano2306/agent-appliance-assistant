"""Loopback-only FastAPI wire test server with synthetic model/tools.

No dotenv, production database, model connection, ASR or business endpoint.
Stop with Ctrl+C after Java opt-in tests finish. Not a deployment entry point.
"""
import argparse
from pathlib import Path
import socket
import sys

parser = argparse.ArgumentParser()
parser.add_argument("--port", type=int, default=0)
args = parser.parse_args()
# Reserve exactly one loopback listener before the deny-network audit hook.
listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.bind(("127.0.0.1", args.port)); listener.listen(128); listener.setblocking(False)
port = listener.getsockname()[1]
root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root)); sys.path.insert(0, str(root / "tests"))
from session_memory_guard import install, guard_settings
install(); guard_settings()

import json
from types import SimpleNamespace
import uvicorn
from app import main
from app.agent import AgentService
from app.config import Settings
from app.memory import SummaryService, compact
from app.schemas import ToolResult

stats = dict(modelCalls=0, summaryCalls=0, toolCalls=0, lastIdentity=None)


class Model:
    def __init__(self): self.chat = SimpleNamespace(completions=self)
    def create(self, **kwargs):
        stats["modelCalls"] += 1
        if "历史摘要组件" in kwargs["messages"][0]["content"]:
            stats["summaryCalls"] += 1
            data = json.loads(kwargs["messages"][1]["content"])
            message = data["turns"][-1]["user"]
            value = dict(device=[dict(text=message["content"][:12], source=message["messageId"])],
                currentIssue=[], attempts=[], constraints=[], openQuestions=[], evidenceRefs=[], background=[])
        else:
            value = dict(answerText="跨语言合成回答，不执行写操作。", insufficientContext=True,
                sourceFragmentIds=[], sources=[], structuredAnswer=dict(summary="合成回答", steps=[], cautions=[], basisNote=""))
        return SimpleNamespace(choices=[SimpleNamespace(finish_reason="stop", message=SimpleNamespace(
            content=compact(value), tool_calls=[]))], usage=SimpleNamespace(
                prompt_tokens=100, completion_tokens=20, prompt_cache_hit_tokens=0))


class Tools:
    def execute(self, *args):
        stats["toolCalls"] += 1
        return ToolResult(ok=False, error="synthetic_not_dispatched")


settings = Settings(_env_file=None, memory_enabled=True, memory_model_context_window=40000,
    agent_internal_token="session-memory-synthetic-wire-token", deepseek_api_key="synthetic-not-a-secret",
    deepseek_base_url="https://synthetic.invalid/v1", deepseek_model="synthetic-memory-model")
model = Model()
agent = AgentService.__new__(AgentService); agent.settings = settings; agent.client = model; agent.registry = Tools()
def check(request):
    stats["lastIdentity"] = [request.userId, request.conversationId, request.requestId, request.memory.executionVersion]
agent.execution_checker = check
main.get_settings = lambda: settings
main.AgentService = lambda: agent
main.app.dependency_overrides[main.get_summary_service] = lambda: SummaryService(settings, model)
main.app.add_api_route("/__test__/stats", lambda: dict(stats), methods=["GET"])

print(f"SYNTHETIC_WIRE_PORT={port}", flush=True)
try:
    uvicorn.Server(uvicorn.Config(main.app, host="127.0.0.1", port=port, log_config=None,
        access_log=False, lifespan="off")).run(sockets=[listener])
finally:
    listener.close()
