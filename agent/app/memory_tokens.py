"""Pinned local text tokenizer; never downloads or loads model/config secrets."""
from functools import lru_cache
from hashlib import sha256
from pathlib import Path

TOKENIZER_SHA256 = "8f9f37ca37fdc4f5fd36d5cf4d3b0e8392edb4e894fd10cc0d70b4957c8633cf"
COUNTER_NAME = "deepseek-v4-text-" + TOKENIZER_SHA256[:12]


@lru_cache(maxsize=1)
def tokenizer():
    from tokenizers import Tokenizer
    path = Path(__file__).resolve().parents[1] / "assets/deepseek-v4/tokenizer.json"
    if sha256(path.read_bytes()).hexdigest() != TOKENIZER_SHA256:
        raise ValueError("memory_tokenizer_checksum_mismatch")
    return Tokenizer.from_file(str(path))


def count_text(text: str) -> int:
    return len(tokenizer().encode(text, add_special_tokens=False).ids)
