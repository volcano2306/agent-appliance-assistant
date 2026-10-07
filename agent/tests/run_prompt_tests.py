"""Isolated offline entry point; install guards BEFORE importing pytest/app.

Run: .venv311/Scripts/python.exe -I -B tests/run_prompt_tests.py
Only two explicitly selected test modules run; no project conftest/plugins.
"""
import os
import sys
from pathlib import Path


def guard(event, args):
    if event == "open" and args and isinstance(args[0], (str, bytes, os.PathLike)):
        name = Path(os.fsdecode(args[0])).name.lower()
        if name == ".env" or name.startswith(".env."):
            raise PermissionError("Offline prompt tests forbid dotenv access")
    if event in {"socket.connect", "socket.bind", "socket.getaddrinfo", "subprocess.Popen", "os.system"}:
        raise PermissionError("Offline prompt tests forbid network and subprocesses")


sys.addaudithook(guard)
sys.dont_write_bytecode = True
# Do not inherit application secrets or settings into test Settings instances.
keep = {k: v for k, v in os.environ.items()
        if k.upper() in {"SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATH"}}
os.environ.clear()
os.environ.update(keep)
os.environ["PYTEST_DISABLE_PLUGIN_AUTOLOAD"] = "1"
root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root))
sys.path.insert(0, str(root / "tests"))

from app import config


def forbidden_settings(*args, **kwargs):
    raise AssertionError("Default configuration loading is forbidden in offline tests")


original_init = config.Settings.__init__


def explicit_settings(self, **kwargs):
    if "_env_file" not in kwargs or kwargs["_env_file"] is not None:
        raise AssertionError("Tests must explicitly pass _env_file=None")
    return original_init(self, **kwargs)


config.Settings.__init__ = explicit_settings
config.get_settings = forbidden_settings
import pytest

if __name__ == "__main__":
    raise SystemExit(pytest.main([
        "-q", "--noconftest", "-p", "no:cacheprovider",
        str(root / "tests/test_prompt_contracts.py"),
        str(root / "tests/test_agent_loop.py"),
    ]))
