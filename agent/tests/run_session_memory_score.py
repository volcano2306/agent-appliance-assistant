"""Export/aggregate semantic review without model calls or dotenv."""
import argparse
from pathlib import Path
import sys
parser = argparse.ArgumentParser()
parser.add_argument("--run", type=Path, required=True)
parser.add_argument("--review", type=Path)
parser.add_argument("--split", choices=["dev", "heldout", "all"], default="heldout")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root)); sys.path.insert(0, str(root / "tests"))
from session_memory_guard import install, guard_settings
install(); guard_settings()
import json
import hashlib
from app.memory_eval_data import validate_dataset
from app.memory_eval_metrics import atomic_json
from app.memory_eval_review import review_pack, score_review
state = json.loads((args.run / "state.json").read_text(encoding="utf-8"))
manifest = state.get("configuration", {}).get("manifest", {})
data = validate_dataset(manifest)
if state.get("configuration", {}).get("manifest", {}).get("version") != manifest["version"]:
    raise SystemExit("Dataset version changed or not recorded; do not score with different gold")
if state["configuration"].get("codeHashes", {}).get("app/memory_eval_data.py") != hashlib.sha256((root / "app/memory_eval_data.py").read_bytes()).hexdigest():
    raise SystemExit("Dataset generator changed; do not score with different gold")
if args.review is None:
    destination = args.run / "review-template.json"
    if destination.exists(): raise SystemExit("Review template exists; refusing to overwrite annotations")
    atomic_json(destination, review_pack(state["rows"], data))
    print("Review template exported; semantic quality remains unverified")
else:
    journal = json.loads((args.run / "calls.json").read_text(encoding="utf-8")); state["calls"] = journal["calls"]
    result = score_review(state, data, manifest, json.loads(args.review.read_text(encoding="utf-8")), split=args.split)
    atomic_json(args.run / "quality.json", result)
    print(json.dumps(result["decision"], ensure_ascii=False))
