"""Explicitly authorized, model-only dotenv loader. Never import app Settings."""
from io import StringIO
import re

from prompt_ab_eval_support import ROOT

NAMES = ("DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL", "DEEPSEEK_MODEL")


def parse_model_lines(lines):
    # Parse only whitelisted single-line assignments; no variable interpolation,
    # shell execution, default Settings, or retention of unrelated credentials.
    from dotenv.parser import parse_stream
    values = {}
    for line in lines:
        match = re.match(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z_0-9]*)\s*=", line)
        if not match or match.group(1).upper() not in NAMES:
            continue
        bindings = list(parse_stream(StringIO(line)))
        if len(bindings) != 1 or bindings[0].error or bindings[0].value is None:
            raise ValueError("Invalid single-line model configuration; contents suppressed")
        value = bindings[0].value.strip()
        if not value or "${" in value or any(c in value for c in "\r\n\x00"):
            raise ValueError("Empty, multiline or interpolated model configuration is unsupported")
        values[match.group(1).upper()] = value
    missing = [n for n in NAMES if n not in values]
    if missing:
        raise ValueError("Missing model configuration fields: " + ", ".join(missing))
    return tuple(values[n] for n in NAMES)


def load_local_model_config(guard):
    try:
        # One read, exact file, revoked before model SDK/network construction.
        with guard.local_model_config():
            with (ROOT / ".env").open("r", encoding="utf-8-sig") as stream:
                return parse_model_lines(stream)
    except ValueError:
        raise
    except Exception:
        raise ValueError("Local model configuration could not be loaded; contents suppressed") from None
