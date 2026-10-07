"""OpenAI-compatible request options; no configuration or secret-file loading."""
from copy import deepcopy
from urllib.parse import urlsplit


def model_provider(settings) -> str:
    host = (urlsplit(getattr(settings, "deepseek_base_url", "")).hostname or "").lower()
    if host == "api.deepseek.com":
        return "deepseek"
    if host == "windhub.cc":
        return "newapi"
    return "openai-compatible"


def model_request_options(settings, *, thinking: bool = False) -> dict:
    # A compatibility gateway is not necessarily a DeepSeek upstream. In
    # particular, disabled DeepSeek thinking is still a proprietary parameter.
    body = {}
    if model_provider(settings) == "deepseek":
        body["thinking"] = {"type": "enabled" if thinking else "disabled"}
    body.update(deepcopy(getattr(settings, "model_extra_body", {})))
    return {"extra_body": body} if body else {}
