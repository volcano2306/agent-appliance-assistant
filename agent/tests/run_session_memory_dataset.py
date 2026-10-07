"""Freeze/export synthetic fixture annotations for human inspection; no LLM."""
import argparse
from dataclasses import asdict
import hashlib
import json
from pathlib import Path
import sys
parser = argparse.ArgumentParser()
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--manifest", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root)); sys.path.insert(0, str(root / "tests"))
from session_memory_guard import install, guard_settings
install(); guard_settings()
from app.memory_eval_data import validate_dataset
from app.memory_eval_metrics import atomic_json
manifest_path = args.manifest or root / "tests/fixtures/session_memory/manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
data = validate_dataset(manifest)
args.output.mkdir(parents=True, exist_ok=True)
freeze = dict(version=manifest["version"], hashes={name: hashlib.sha256((root / name).read_bytes()).hexdigest()
    for name in ("app/memory_eval_data.py", "app/memory.py", "app/agent.py", "tests/fixtures/session_memory/manifest.json")},
    manifest=manifest, manifestHash=hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
    trajectories=[], realModelVerified=False)
markdown = ["# 会话记忆合成数据标注", "", "这是预先标注的实验输入与ground truth，不是LLM测试结果。留出集不用于Prompt调参。", ""]
for trajectory in data:
    annotations = [dict(memory_id=fact.id, value=fact.text, source_message_ids=[fact.source],
        introduced_at=fact.valid_from, valid_until=fact.valid_until, supersedes=fact.supersedes,
        priority="required" if fact.priority == "general" else fact.priority, required_at=fact.required_at,
        acceptable_answers=fact.acceptable, forbidden_claims=fact.forbidden) for fact in trajectory.facts]
    freeze["trajectories"].append(dict(id=trajectory.id, split=trajectory.split, density=trajectory.density,
        probes=trajectory.probes, turns=[turn.model_dump() for turn in trajectory.turns], annotations=annotations))
    markdown += [f"## {trajectory.id}", "", f"{trajectory.split}；{len(trajectory.turns)}轮；活跃记忆点{trajectory.density}；检查点{trajectory.probes}。", "",
        "| 记忆点 | 值 | 来源 | 有效区间 | 替代项 |", "|---|---|---|---|---|"]
    markdown += [f"| {fact.id} | {fact.text} | {fact.source} | {fact.valid_from}—{fact.valid_until or '持续'} | {fact.supersedes or '无'} |" for fact in trajectory.facts]
    markdown += [""]
atomic_json(args.output / "frozen-fixtures.json", freeze)
(args.output / "annotations.md").write_text("\n".join(markdown), encoding="utf-8")
print("Frozen 12 synthetic trajectories; no model call, no semantic pass rate")
