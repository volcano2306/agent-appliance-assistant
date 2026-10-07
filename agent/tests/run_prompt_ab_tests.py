"""Network-free tests for the real-model evaluator, not a real model benchmark."""
import os
from pathlib import Path
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from prompt_ab_eval_support import AuditGuard

sys.addaudithook(AuditGuard())
keep = {k: v for k, v in os.environ.items() if k.upper() in {"SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATH"}}
os.environ.clear()
os.environ.update(keep)
os.environ["PYTEST_DISABLE_PLUGIN_AUTOLOAD"] = "1"
import pytest

if __name__ == "__main__":
    raise SystemExit(pytest.main(["-q", "--noconftest", "-p", "no:cacheprovider",
        str(Path(__file__).with_name("test_prompt_ab_eval.py"))]))
