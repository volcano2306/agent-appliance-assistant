"""Run offline memory tests without dotenv, business services or pytest plugins."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from session_memory_guard import install, guard_settings

install()
root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root))
guard_settings()

import pytest

if __name__ == "__main__":
    raise SystemExit(pytest.main([
        "-q", "--noconftest", "-p", "no:cacheprovider",
        str(root / "tests/test_session_memory.py"),
        str(root / "tests/test_model_gateway.py"),
        str(root / "tests/test_session_memory_threshold.py"),
        str(root / "tests/test_session_memory_eval_metrics.py"),
        str(root / "tests/test_session_memory_eval_runtime.py"),
        str(root / "tests/test_session_memory_cost.py"),
        str(root / "tests/test_session_memory_evidence.py"),
        str(root / "tests/test_session_memory_eval_replay.py"),
        str(root / "tests/test_session_memory_eval_quality.py"),
        str(root / "tests/test_session_memory_eval_review.py"),
        str(root / "tests/test_session_memory_model_config.py"),
    ]))
