"""Explicit live-test-only, one-shot model configuration grant (opt-in only)."""
import io
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlparse

AUTHORIZED_ENV = Path(__file__).resolve().parents[1] / ".env"
FIELDS = {"DEEPSEEK_API_KEY": "API_KEY", "DEEPSEEK_BASE_URL": "BASE_URL", "DEEPSEEK_MODEL": "MODEL"}


def redact_model_secret(value, secret):
    """Defense-in-depth for a provider unexpectedly echoing an authorization key."""
    if isinstance(value, str):
        return value.replace(secret, "[REDACTED]") if secret else value
    if isinstance(value, dict):
        return {redact_model_secret(key, secret): redact_model_secret(item, secret) for key, item in value.items()}
    if isinstance(value, list):
        return [redact_model_secret(item, secret) for item in value]
    return value


def extract_model_config(stream):
    """Parse only the three allowlisted lines; never interpolate other secrets."""
    from dotenv.parser import parse_stream
    selected = []
    for line in stream:
        match = re.match(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z_0-9]*)\s*=", line)
        if match and match.group(1).upper() in FIELDS:
            selected.append(line)
    values = {}
    for binding in parse_stream(io.StringIO("".join(selected))):
        if binding.error or not binding.key or binding.value is None or "${" in binding.value:
            raise ValueError("project_model_configuration_invalid")
        key = FIELDS.get(binding.key.upper())
        if key:
            values[key] = binding.value.strip()
    if not all(values.get(key) for key in FIELDS.values()):
        raise ValueError("project_model_configuration_incomplete")
    url = urlparse(values["BASE_URL"])
    if (url.scheme != "https" or not url.hostname or url.username or url.password
            or url.query or url.fragment or not re.fullmatch(r"[A-Za-z0-9_.:/-]{1,120}", values["MODEL"])):
        raise ValueError("project_model_configuration_invalid")
    return values


def model_config_audit(expected, permit):
    def audit(event, args):
        if event == "open" and args and isinstance(args[0], (str, bytes, os.PathLike)):
            path = Path(os.fsdecode(args[0]))
            if path.name.lower() == ".env" or path.name.lower().startswith(".env."):
                if not permit[0] or path.resolve() != expected or args[1] != "r":
                    raise PermissionError("Model configuration grant forbids this dotenv access")
    return audit


def load_authorized_project_model():
    """One read of the exact local agent path; all later dotenv reads remain denied."""
    permit = [True]
    expected = AUTHORIZED_ENV.resolve()
    sys.addaudithook(model_config_audit(expected, permit))
    try:
        if AUTHORIZED_ENV.is_symlink():
            raise ValueError("project_model_configuration_invalid")
        with AUTHORIZED_ENV.open("r", encoding="utf-8-sig") as stream:
            return extract_model_config(stream)
    except Exception:
        raise ValueError("project_model_configuration_unavailable") from None
    finally:
        permit[0] = False


def verified_context_window(credentials):
    """Official direct-provider mapping; unknown gateways still need explicit window.

    https://api-docs.deepseek.com/quick_start/pricing/ checked 2026-10-04:
    Flash / V4 Pro have 1M context; legacy V4 Flash resolves to current Flash.
    """
    if urlparse(credentials["BASE_URL"]).hostname == "api.deepseek.com" and credentials["MODEL"] in {
        "deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp", "deepseek-v4-pro",
    }:
        return 1_000_000
    return None
