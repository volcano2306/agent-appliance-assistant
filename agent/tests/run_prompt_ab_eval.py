"""Explicit real-model evaluation; optional consented model-only local config."""
import argparse
from datetime import datetime, timezone
import json
import logging
import os
from pathlib import Path
import sys
import uuid

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from prompt_ab_eval_support import (AuditGuard, Budget, ROOT, build_live_client, compact, digest,
    execute_checkpoint, preflight, schedule, scrub, size_of, snapshot)

GUARD = AuditGuard()
sys.addaudithook(GUARD)
from prompt_ab_eval_report import finalize, review_template, write_json, write_report


def positive(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("Must be positive")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--live", action="store_true")
    mode.add_argument("--finalize", type=Path)
    parser.add_argument("--use-local-env", action="store_true",
        help="Authorize this local live process to load only three model fields from agent/.env")
    parser.add_argument("--variant", choices=["both", "baseline", "candidate"], default="both")
    parser.add_argument("--repeats", type=positive, default=1)
    parser.add_argument("--max-api-calls", type=positive, default=130)
    parser.add_argument("--max-total-tokens", type=positive, default=200000)
    parser.add_argument("--max-output-tokens", type=positive, default=2048)
    parser.add_argument("--timeout-seconds", type=positive, default=60)
    parser.add_argument("--thinking", choices=["disabled", "enabled"], default="disabled")
    args = parser.parse_args()
    if args.use_local_env and not args.live:
        parser.error("--use-local-env requires --live")
    if args.finalize:
        metrics = finalize(args.finalize)
        print(json.dumps({"acceptance_status": metrics["acceptance_status"], "review_status": metrics["review_status"]}))
        return 0 if metrics["acceptance_status"] == "passed" else 2
    baseline, current, fixture, runtime = preflight()
    sizes = {"baseline": size_of(baseline), "candidate": size_of(current)}
    jobs = schedule(fixture, args.repeats, args.variant)
    if args.preflight:
        print(json.dumps({"status": "offline_preflight_passed", "live_executed": False,
                          "case_count": 10, "checkpoint_count": 13, "planned_trajectories": len(jobs),
                          "sizes": sizes}, ensure_ascii=False, indent=2))
        return 0
    # Suppress SDK/HTTP diagnostic logging, including OPENAI_LOG from the shell.
    logging.disable(logging.CRITICAL)
    os.environ.pop("OPENAI_LOG", None)
    if args.use_local_env:
        from prompt_eval_config import load_local_model_config
        key, base, model = load_local_model_config(GUARD)
    else:
        names = ["PROMPT_EVAL_API_KEY", "PROMPT_EVAL_BASE_URL", "PROMPT_EVAL_MODEL"]
        values = {n: os.environ.get(n, "").strip() for n in names}
        missing = [n for n in names if not values[n]]
        if missing:
            print("No model request made. Missing explicit process variables: " + ", ".join(missing))
            return 2
        key, base, model = [values[n] for n in names]
    from prompt_ab_eval_support import endpoint_parts
    endpoint_parts(base)
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    directory = ROOT / "eval-results/prompt-overhead" / run_id
    directory.mkdir(parents=True, exist_ok=False)
    snapshots = {"baseline": baseline, "candidate": current}
    config_source = "authorized_local_model_config" if args.use_local_env else "explicit_process_variables"
    secrets = (key, base, model)
    manifest = {"run_id": run_id, "configuration_source": config_source,
        "endpoint_fingerprint": digest(base), "model_fingerprint": digest(model), "live_status": "running",
        "implementation_status": "ready", "fixture_hash": digest(fixture), "sizes": sizes,
        "snapshot_hashes": {v: digest(s) for v, s in snapshots.items()}, "parameters": {
            "thinking": args.thinking, "max_tokens": args.max_output_tokens, "timeout": args.timeout_seconds,
            "max_retries": 0, "max_api_calls": args.max_api_calls, "max_total_tokens": args.max_total_tokens},
        "planned": [{"trace_id": f"{c}-{p['checkpoint_id']}-{v}-{r}", "case_id": c,
            "checkpoint_id": p["checkpoint_id"], "variant": v, "repeat": r} for c, p, v, r in jobs],
        "trace_hashes": {}}
    write_json(directory / "manifest.json", manifest)
    write_json(directory / "cases.json", fixture)
    write_json(directory / "snapshots.json", snapshots)
    print(json.dumps({"run_id": run_id, "configuration_source": config_source,
                      "trajectories": len(jobs), "parameters": manifest["parameters"]}, ensure_ascii=False))
    budget = Budget(args.max_api_calls, args.max_total_tokens)
    traces, client = [], None
    try:
        client = build_live_client(key, base, args.timeout_seconds, GUARD)
        for case_id, cp, variant, repeat in jobs:
            if budget.stop_reason:
                break
            if snapshot(ROOT)["source_sha256"] != current["source_sha256"]:
                budget.stop_reason = "source_changed"
                break
            trace = execute_checkpoint(runtime, cp, case_id, variant, repeat, snapshots[variant],
                client.chat.completions, budget, model, args.thinking == "enabled", args.max_output_tokens, secrets)
            traces.append(trace)
            with (directory / "traces.jsonl").open("a", encoding="utf-8") as out:
                out.write(compact(trace) + "\n")
            manifest["trace_hashes"][trace["trace_id"]] = digest(trace)
            write_json(directory / "manifest.json", manifest)
            print(trace["trace_id"] + ": " + trace["status"])
    except KeyboardInterrupt:
        budget.stop_reason = "user_interrupted"
    except Exception as exc:
        budget.stop_reason = "runner_error:" + type(exc).__name__
    finally:
        if client:
            client.close()
        if not (directory / "traces.jsonl").exists():
            (directory / "traces.jsonl").write_text("", encoding="utf-8")
        manifest["live_status"] = "completed" if len(traces) == len(jobs) and all(t["status"] == "completed" for t in traces) else "incomplete"
        manifest["stop_reason"] = budget.stop_reason
        manifest["observed_api_calls"] = budget.calls
        manifest["reported_tokens_so_far"] = budget.tokens
        write_json(directory / "manifest.json", scrub(manifest, secrets))
        review = review_template(traces, fixture)
        write_json(directory / "review.json", review)
        metrics = write_report(directory, manifest, traces, review, fixture)
        print("Results: " + str(directory))
        print("Semantic review required; acceptance=" + metrics["acceptance_status"])
    return 0 if manifest["live_status"] == "completed" else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, PermissionError) as exc:
        # Own validation messages only; do not dump SDK exceptions or environment.
        print("Evaluation validation failed: " + str(exc), file=sys.stderr)
        raise SystemExit(2)
