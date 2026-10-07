"""Isolated contract tests: no dotenv, network, business API or pytest plugins."""
import os
from pathlib import Path
import sys


def guard(event, args):
    if event == "open" and args and isinstance(args[0], (str, bytes, os.PathLike)):
        name = Path(os.fsdecode(args[0])).name.lower()
        if name == ".env" or name.startswith(".env."):
            raise PermissionError("Contract tests forbid dotenv access")
    if event in {"socket.connect", "socket.bind", "socket.getaddrinfo", "subprocess.Popen", "os.system"}:
        raise PermissionError("Contract tests forbid network and subprocesses")


sys.addaudithook(guard)
sys.dont_write_bytecode = True
kept = {key: value for key, value in os.environ.items()
        if key.upper() in {"SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATH"}}
os.environ.clear()
os.environ.update(kept)
os.environ["PYTEST_DISABLE_PLUGIN_AUTOLOAD"] = "1"
root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root))
sys.path.insert(0, str(root / "tests"))

from session_memory_guard import guard_embedding_runtime

guard_embedding_runtime()

from app import config


def forbidden_settings(*args, **kwargs):
    raise AssertionError("Default settings are forbidden in contract tests")


original_init = config.Settings.__init__


def explicit_settings(self, **kwargs):
    if kwargs.get("_env_file", object()) is not None:
        raise AssertionError("Contract tests require _env_file=None")
    return original_init(self, **kwargs)


config.Settings.__init__ = explicit_settings
config.get_settings = forbidden_settings

import pytest

if __name__ == "__main__":
    raise SystemExit(pytest.main([
        "-q", "--noconftest", "-p", "no:cacheprovider",
        str(root / "tests/test_agent_contract_boundaries.py"),
        str(root / "tests/test_agent_loop.py"),
        str(root / "tests/test_write_outcomes.py"),
    ]))
