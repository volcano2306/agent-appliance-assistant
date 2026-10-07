from pathlib import Path

from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile

from app.agent import AgentService
from app.clients.tencent_asr import TencentAsrClient
from app.config import get_settings
from app.model_options import model_provider
from app.memory import SummaryService
from app.schemas import AgentRequest, AgentResponse, SummaryRequest, SummaryResult, MemoryPlanRequest, MemoryPlanResult


app = FastAPI(title="Smart Appliance Agent", version="1.0.0")


def get_summary_service() -> SummaryService:
    return SummaryService(get_settings())


def verify_memory_token(x_internal_token: str = Header(default="")) -> None:
    expected = get_settings().agent_internal_token
    if not expected or x_internal_token != expected:
        raise HTTPException(status_code=401, detail="invalid_internal_token")


@app.post("/v1/agent/summarize", response_model=SummaryResult,
          dependencies=[Depends(verify_memory_token)])
def summarize(request: SummaryRequest, service: SummaryService = Depends(get_summary_service)) -> SummaryResult:
    return service.summarize(request)


@app.post("/v1/agent/memory/plan", response_model=MemoryPlanResult,
          dependencies=[Depends(verify_memory_token)])
def plan_memory(request: MemoryPlanRequest, service: SummaryService = Depends(get_summary_service)) -> MemoryPlanResult:
    return service.plan(request)


def verify_internal_token(x_internal_token: str = Header(default="")) -> None:
    expected = get_settings().agent_internal_token
    if expected and x_internal_token != expected:
        raise HTTPException(status_code=401, detail="invalid_internal_token")


@app.get("/health")
def health() -> dict:
    settings = get_settings()
    return {
        "status": "ok",
        "modelConfigured": settings.has_model_credentials(),
        "serpApiConfigured": settings.has_serpapi_credentials(),
        "tencentAsrConfigured": settings.has_tencent_asr_credentials(),
        "provider": model_provider(settings),
        "model": settings.deepseek_model,
    }


@app.post("/v1/agent/chat", response_model=AgentResponse, dependencies=[Depends(verify_internal_token)])
def chat(request: AgentRequest, x_internal_token: str = Header(default="")) -> AgentResponse:
    if request.memory is not None:
        verify_memory_token(x_internal_token)
        if not (get_settings().memory_enabled or get_settings().memory_execution_enabled):
            return AgentResponse(status="error", error="memory_disabled", requestId=request.requestId,
                                 conversationId=request.conversationId, answerText="会话记忆功能尚未启用。")
    try:
        return AgentService().run(request)
    except Exception as exc:
        return AgentResponse(
            status="error",
            error="agent_upstream_failed",
            model=get_settings().deepseek_model,
            requestId=request.requestId,
            conversationId=request.conversationId,
            answerText="Agent服务暂时无法继续，请先核实请求状态。" if request.memory else f"Agent服务调用失败：{exc}",
        )


@app.post("/v1/transcriptions", dependencies=[Depends(verify_internal_token)])
async def transcribe(audio: UploadFile = File(...)) -> dict:
    content = await audio.read()
    if not content:
        raise HTTPException(status_code=400, detail="audio_required")
    if len(content) > 12 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="audio_too_large")
    suffix = Path(audio.filename or "voice.pcm").suffix.lower().lstrip(".") or "pcm"
    try:
        return TencentAsrClient().transcribe(content, suffix)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"asr_failed: {exc}") from exc
