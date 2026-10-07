"""One bounded model request; no application startup or business tools.

--use-project-model requires fresh explicit user permission to load only the
three allowlisted model fields. No dotenv permission is granted by default.
"""
import argparse
import json
import os
from pathlib import Path
import sys
from time import perf_counter

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

from prompt_ab_eval_support import AuditGuard, build_live_client, scrub
from session_memory_model_config import load_authorized_project_model


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--use-project-model", action="store_true")
    # Corrected endpoint explicitly supplied by the user; no redirects.
    parser.add_argument("--base-url", choices=["https://windhub.cc/v1"])
    args = parser.parse_args()
    guard = AuditGuard()
    sys.addaudithook(guard)
    credentials = None
    try:
        # Check SDK dependencies before consuming the limited configuration grant.
        import httpx
        from openai import OpenAI

        if args.use_project_model:
            with guard.local_model_config():
                credentials = load_authorized_project_model()
        else:
            credentials = {"API_KEY": os.getenv("NEWAPI_API_KEY", ""),
                           "BASE_URL": "https://windhub.cc/v1",
                           "MODEL": "qwen3.8-flash-next"}
        if args.base_url:
            credentials["BASE_URL"] = args.base_url
        if not credentials["API_KEY"] or credentials["API_KEY"].startswith("replace-"):
            raise ValueError("model_credentials_unavailable")
        if (credentials["BASE_URL"].rstrip("/") != "https://windhub.cc/v1"
                or credentials["MODEL"] != "qwen3.8-flash-next"):
            raise ValueError("model_configuration_does_not_match_requested_gateway")
        started = perf_counter()
        with build_live_client(credentials["API_KEY"], credentials["BASE_URL"], 60, guard) as client:
            response = client.chat.completions.create(
                model=credentials["MODEL"],
                messages=[{"role": "user", "content": "请直接回答：1+1等于几？"}],
                max_tokens=256,
            )
        choice = response.choices[0] if response.choices else None
        answer = choice.message.content if choice else None
        usage = response.usage
        result = {"connected": True, "answer_received": bool(answer),
                  "model": response.model, "question": "请直接回答：1+1等于几？",
                  "answer": answer, "finish_reason": choice.finish_reason if choice else None,
                  "elapsed_seconds": round(perf_counter() - started, 2),
                  "input_tokens": getattr(usage, "prompt_tokens", None),
                  "output_tokens": getattr(usage, "completion_tokens", None)}
    except Exception as exc:
        result = {"connected": False, "error_type": type(exc).__name__,
                  "http_status": getattr(exc, "status_code", None)}
        if isinstance(exc, ModuleNotFoundError):
            result["dependency"] = exc.name
        if isinstance(exc, ValueError):
            result["error"] = str(exc)
        body = getattr(exc, "body", None)
        if isinstance(body, dict):
            error = body.get("error", body)
            if isinstance(error, dict):
                result["error"] = str(error.get("message", "model_request_failed"))[:500]
    result = scrub(result, (credentials["API_KEY"],) if credentials else ())
    print(json.dumps(result, ensure_ascii=True))
    return 0 if result.get("answer_received") else 1


if __name__ == "__main__":
    raise SystemExit(main())
