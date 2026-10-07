"""Evaluation only: source-extracted production loop, real-model or fake transport.

No app configuration or business-module imports. Dotenv is denied except for an
explicitly authorized one-read model loader in the live CLI. The CLI installs
the audit guard before importing SDKs. Fake responses are only used in unit tests.
"""
import ast
import contextlib
import copy
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import socket
from types import SimpleNamespace
from typing import Any
from urllib.parse import urlsplit
from time import perf_counter

from prompt_contract_utils import snapshot, without_descriptions

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests/fixtures"
WRITE_TOOLS = {"add_manual_to_account", "create_repair_order"}


def compact(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def digest(value):
    return hashlib.sha256(compact(value).encode("utf-8")).hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def wrapped(snap):
    return [{"type": "function", "function": {key: s[key] for key in ("name", "description", "parameters")}}
            for s in snap["tool_schemas"]]


def size_of(snap):
    def count(text):
        han = len(re.findall(r"[\u4e00-\u9fff]", text))
        return {"characters": len(text), "estimated_tokens": round(han * .6 + (len(text) - han) * .3, 1)}
    system, tools = snap["system_prompt"].strip(), compact(wrapped(snap))
    return {"system": count(system), "tools": count(tools), "fixed": count(system + tools),
            "summary_extra": count(snap["summary_prompt"]), "actual_tokens": None,
            "estimate_method": "Han chars*0.6 + remaining chars*0.3; NOT a model tokenizer"}


def forbidden(*args, **kwargs):
    raise PermissionError("Application settings and real business constructors are forbidden")


def load_runtime(root=ROOT):
    """Compile the unchanged AgentService class, never import app.agent/config/registry.

    All method bodies are copied from the parsed source without transformation.
    __init__ is never called; its dependencies are explicit fail-closed stubs.
    """
    spec = importlib.util.spec_from_file_location("prompt_eval_schemas", root / "app/schemas.py")
    schemas = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(schemas)
    options_spec = importlib.util.spec_from_file_location("prompt_eval_model_options", root / "app/model_options.py")
    options = importlib.util.module_from_spec(options_spec)
    options_spec.loader.exec_module(options)
    tree = ast.parse((root / "app/agent.py").read_text(encoding="utf-8-sig"))
    cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "AgentService")
    snap = snapshot(root)
    namespace = {"json": json, "re": re, "Any": Any, "Settings": SimpleNamespace,
                 "model_provider": options.model_provider, "model_request_options": options.model_request_options,
                 "ToolRegistry": SimpleNamespace, "get_settings": forbidden, "OpenAI": forbidden,
                 "SYSTEM_INSTRUCTIONS": snap["system_prompt"], "CHAT_COMPLETION_TOOLS": wrapped(snap)}
    namespace.update({n: getattr(schemas, n) for n in (
        "AgentRequest", "AgentResponse", "Source", "StructuredAnswer", "ToolContext")})
    exec(compile(ast.Module(body=[cls], type_ignores=[]), str(root / "app/agent.py"), "exec"), namespace)
    return SimpleNamespace(AgentService=namespace["AgentService"], schemas=schemas)


class AuditGuard:
    def __init__(self):
        self.active = False
        self.host = None
        self.port = None
        self.addresses = set()
        self._config_read_available = False
        self._config_scope_used = False

    def __call__(self, event, args):
        if event == "open" and args and isinstance(args[0], (str, bytes, os.PathLike)):
            name = Path(os.fsdecode(args[0])).name.lower()
            if name == ".env" or name.startswith(".env."):
                exact = os.path.normcase(os.path.abspath(os.fsdecode(args[0]))) == os.path.normcase(str(ROOT / ".env"))
                read_only = len(args) > 2 and args[1] == "r" and not (args[2] & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC | os.O_APPEND))
                if not (self._config_read_available and exact and read_only):
                    raise PermissionError("Evaluation forbids dotenv access")
                self._config_read_available = False
        if event in {"subprocess.Popen", "os.system", "socket.bind"}:
            raise PermissionError("Evaluation forbids subprocesses and server sockets")
        if event == "socket.getaddrinfo":
            if not self.active or args[0] != self.host or str(args[1]) != str(self.port):
                raise PermissionError("Evaluation forbids this network lookup")
        if event == "socket.connect":
            address = args[1]
            if (not self.active or not isinstance(address, tuple) or len(address) < 2
                    or address[0] not in self.addresses or address[1] != self.port):
                raise PermissionError("Evaluation forbids this network connection")

    @contextlib.contextmanager
    def local_model_config(self):
        if self._config_scope_used or self.active:
            raise PermissionError("Local model configuration consent is single-use before network")
        self._config_scope_used = True
        self._config_read_available = True
        try:
            yield
        finally:
            self._config_read_available = False

    @contextlib.contextmanager
    def network(self):
        old = self.active
        self.active = True
        try:
            yield
        finally:
            self.active = old

    def configure(self, base_url):
        parsed = endpoint_parts(base_url)
        self.host, self.port = parsed.hostname, parsed.port or 443
        with self.network():
            self.addresses = {row[4][0] for row in socket.getaddrinfo(self.host, self.port, type=socket.SOCK_STREAM)}


def endpoint_parts(url):
    parsed = urlsplit(url)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment):
        raise ValueError("Model endpoint must be HTTPS without credentials, query or fragment")
    _ = parsed.port
    return parsed


def check_endpoint(url, base_url):
    want, got = endpoint_parts(base_url), endpoint_parts(str(url))
    if ((got.hostname, got.port or 443) != (want.hostname, want.port or 443)
            or got.path != want.path.rstrip("/") + "/chat/completions"):
        raise PermissionError("Only the configured model chat/completions endpoint is allowed")


def build_live_client(key, base_url, timeout, guard):
    import httpx
    from openai import OpenAI
    endpoint_parts(base_url)

    class ModelOnlyTransport(httpx.BaseTransport):
        def __init__(self):
            self.inner = httpx.HTTPTransport(retries=0, trust_env=False)

        def handle_request(self, request):
            check_endpoint(request.url, base_url)
            if request.method != "POST":
                raise PermissionError("Only POST model requests are allowed")
            guard.configure(base_url)
            with guard.network():
                response = self.inner.handle_request(request)
                # Consume inside the guarded transport; no network-capable stream escapes.
                body = response.read()
                # read() has decoded Content-Encoding. Do not make the outer
                # httpx response decompress the same body a second time.
                headers = {key: value for key, value in response.headers.items()
                           if key.lower() not in {"content-encoding", "content-length", "transfer-encoding"}}
                response.close()
            return httpx.Response(response.status_code, headers=headers, content=body)

        def close(self):
            self.inner.close()

    http = httpx.Client(transport=ModelOnlyTransport(), timeout=timeout, follow_redirects=False, trust_env=False)
    return OpenAI(api_key=key, base_url=base_url.rstrip("/"), timeout=timeout, max_retries=0, http_client=http)


def scrub(value, secrets=()):
    """No headers/settings/whole SDK responses are ever logged; defense in depth."""
    if isinstance(value, dict):
        return {k: scrub(v, secrets) for k, v in value.items()
                if k.lower() not in {"reasoning_content", "authorization", "api_key", "secret", "headers"}}
    if isinstance(value, list):
        return [scrub(v, secrets) for v in value]
    if isinstance(value, str):
        for secret in secrets:
            if secret:
                value = value.replace(secret, "[REDACTED]")
        value = re.sub(r"(?i)Bearer\s+[^\s\"']+", "Bearer [REDACTED]", value)
        return re.sub(r"(?<!\d)1[3-9]\d{9}(?!\d)", "[PHONE]", value)
    return value


class StopRun(RuntimeError):
    pass


class Budget:
    def __init__(self, max_calls=130, max_tokens=200000):
        self.max_calls, self.max_tokens = max_calls, max_tokens
        self.calls = self.tokens = self.consecutive_errors = 0
        self.stop_reason = ""

    def before(self):
        if self.stop_reason:
            raise StopRun(self.stop_reason)
        if self.calls >= self.max_calls or self.tokens >= self.max_tokens:
            self.stop_reason = "budget_exhausted"
            raise StopRun(self.stop_reason)
        self.calls += 1

    def usage(self, usage):
        if usage is None:
            self.stop_reason = "usage_missing"
            raise StopRun(self.stop_reason)
        self.tokens += usage["prompt_tokens"] + usage["completion_tokens"]
        self.consecutive_errors = 0

    def error(self, status):
        self.consecutive_errors += 1
        if status in {401, 403}:
            self.stop_reason = "authentication_failed"
        elif status in {400, 404, 422}:
            self.stop_reason = "model_request_incompatible"
        elif self.consecutive_errors >= 3:
            self.stop_reason = "consecutive_errors"


def read_usage(response):
    u = getattr(response, "usage", None)
    if u is None:
        return None
    if hasattr(u, "model_dump"):
        u = u.model_dump()
    elif not isinstance(u, dict):
        u = vars(u)
    if any(type(u.get(k)) is not int or u[k] < 0 for k in ("prompt_tokens", "completion_tokens")):
        return None
    return {k: u.get(k) for k in ("prompt_tokens", "completion_tokens", "total_tokens",
                                  "prompt_cache_hit_tokens", "prompt_cache_miss_tokens",
                                  "prompt_tokens_details", "completion_tokens_details")}


class ObservedCompletions:
    def __init__(self, delegate, snap, budget, trace, output_limit=2048, secrets=()):
        self.delegate, self.snap, self.budget, self.trace = delegate, snap, budget, trace
        self.output_limit, self.secrets = output_limit, secrets

    def create(self, **kwargs):
        self.budget.before()
        req = copy.deepcopy(kwargs)
        req["messages"][0]["content"] = self.snap["system_prompt"].strip()
        if req["tool_choice"] == "none":
            req["messages"][-1]["content"] = self.snap["summary_prompt"]
        req["tools"] = wrapped(self.snap)
        req["max_tokens"] = self.output_limit
        record = {"index": len(self.trace["model_calls"]) + 1, "tool_choice": req["tool_choice"],
                  "status": "started", "usage": None, "tool_calls": [], "content": ""}
        self.trace["model_calls"].append(record)
        start = perf_counter()
        try:
            response = self.delegate.create(**req)
            record["usage"] = read_usage(response)
            record["response_model"] = scrub(getattr(response, "model", None), self.secrets)
            if response.choices:
                choice = response.choices[0]
                message = choice.message
                record["finish_reason"] = getattr(choice, "finish_reason", None)
                record["content"] = scrub(message.content or "", self.secrets)
                record["tool_calls"] = [scrub({"id": c.id, "name": c.function.name,
                    "arguments": c.function.arguments or "{}"}, self.secrets) for c in message.tool_calls or []]
            record["status"] = "received"
            self.budget.usage(record["usage"])
            if not response.choices:
                raise RuntimeError("model_missing_choices")
            if record.get("finish_reason") == "length":
                raise RuntimeError("model_output_truncated")
            return response
        except StopRun:
            record["status"] = "budget_or_usage_stop"
            raise
        except Exception as exc:
            status = getattr(exc, "status_code", None)
            self.budget.error(status)
            record.update(status="error", error_type=type(exc).__name__, http_status=status)
            # Never log raw SDK exceptions: they can contain request headers or URLs.
            raise RuntimeError("model_request_failed:" + type(exc).__name__) from None
        finally:
            record["seconds"] = round(perf_counter() - start, 4)


def schema_errors(value, schema, path="arguments"):
    checks = {"object": lambda v: isinstance(v, dict), "array": lambda v: isinstance(v, list),
              "string": lambda v: isinstance(v, str), "boolean": lambda v: type(v) is bool,
              "number": lambda v: type(v) in (int, float) and math.isfinite(v), "null": lambda v: v is None}
    types = schema.get("type", [])
    if isinstance(types, str):
        types = [types]
    if types and not any(checks[t](value) for t in types):
        return [path + ":type"]
    errors = []
    if isinstance(value, dict):
        props = schema.get("properties", {})
        errors += [path + ":missing:" + k for k in schema.get("required", []) if k not in value]
        if schema.get("additionalProperties") is False:
            errors += [path + ":extra:" + k for k in value if k not in props]
        for k, v in value.items():
            if k in props:
                errors += schema_errors(v, props[k], path + "." + k)
    if isinstance(value, list) and "items" in schema:
        for i, v in enumerate(value):
            errors += schema_errors(v, schema["items"], path + f"[{i}]")
    return errors


def argument_errors(name, args, cp, schemas):
    schema = next((s["parameters"] for s in schemas if s["name"] == name), None)
    if schema is None:
        return ["unknown_tool"]
    errors = schema_errors(args, schema)
    if not isinstance(args, dict):
        return errors
    rules = cp["expected"]["argument_rules"].get(name, {})
    for key, val in rules.get("equals", {}).items():
        if key not in args or compact(args[key]) != compact(val):
            errors.append(key + ":wrong_value")
    for key, vals in rules.get("optional_values", {}).items():
        if key in args and args[key] not in vals:
            errors.append(key + ":not_from_input")
    for key in set(rules.get("nonempty", []) + (["query"] if name in {"manual_search", "recommend_internet_products"} else [])):
        if not isinstance(args.get(key), str) or not args[key].strip():
            errors.append(key + ":empty")
    for key, val in args.items():
        if key.endswith("_id") and val not in ["", *cp["expected"].get("known_ids", [])]:
            errors.append(key + ":unknown_id")
    if name == "diagnose_fault" and not (args.get("fault_code") or args.get("symptoms")):
        errors.append("fault_code_or_symptoms_required")
    return errors


class SimulatedRegistry:
    def __init__(self, cp, schemas, tool_result_type, secrets=()):
        self.cp, self.schemas, self.result_type = cp, schemas, tool_result_type
        self.calls, self.secrets = [], secrets

    def execute(self, name, arguments, context):
        try:
            args = json.loads(arguments)
            errors = argument_errors(name, args, self.cp, self.schemas)
        except (ValueError, TypeError):
            args, errors = None, ["invalid_arguments_json"]
        data = self.cp["tool_results"].get(name, {"ok": False, "error": "unexpected_simulated_tool"})
        if errors:
            data = {"ok": False, "error": "invalid_simulated_arguments"}
        result = self.result_type.model_validate(data)
        self.calls.append(scrub({"name": name, "arguments": args, "parameter_errors": errors,
                                 "result": result.model_dump(mode="json")}, self.secrets))
        return result


def raw_final_errors(text):
    try:
        data = json.loads(text)
    except (ValueError, TypeError):
        return ["raw_json_invalid"]
    final_schema = {"type": "object", "required": ["answerText", "structuredAnswer", "insufficientContext"],
        "properties": {"answerText": {"type": "string"}, "insufficientContext": {"type": "boolean"},
        "structuredAnswer": {"type": "object", "required": ["summary", "steps", "cautions", "basisNote"],
        "properties": {"summary": {"type": "string"}, "basisNote": {"type": "string"},
                       "steps": {"type": "array", "items": {"type": "string"}},
                       "cautions": {"type": "array", "items": {"type": "string"}}}}}}
    return schema_errors(data, final_schema, "final")


def score_trace(trace, cp, schemas):
    requested = [c for m in trace["model_calls"] for c in m["tool_calls"]]
    names = [c["name"] for c in requested]
    e = cp["expected"]
    decision = all(n in e["allowed_tools"] for n in names)
    decision &= not e["required_any"] or bool(set(names) & set(e["required_any"]))
    decision &= all(names.count(n) <= count for n, count in e["max_calls"].items())
    decision &= not any(m["tool_calls"] for m in trace["model_calls"] if m["tool_choice"] == "none")
    parameter_rows = []
    for call in requested:
        try:
            errors = argument_errors(call["name"], json.loads(call["arguments"]), cp, schemas)
        except (ValueError, TypeError):
            errors = ["invalid_arguments_json"]
        parameter_rows.append({"name": call["name"], "errors": errors})
    raw = trace.get("raw_final", "")
    json_errors = raw_final_errors(raw)
    final_errors = []
    if not json_errors:
        data = json.loads(raw)
        final_errors = [key for key, val in e.get("final_equals", {}).items() if data.get(key) != val]
    critical = {"unauthorized_repair": sum(n == "create_repair_order" and n not in e["write_authorized"] for n in names),
                "unauthorized_or_wrong_target_collection": sum(n == "add_manual_to_account" and n not in e["write_authorized"] for n in names),
                "duplicate_write": sum(max(0, names.count(n) - 1) for n in WRITE_TOOLS)}
    return {"tool_decision": bool(decision), "parameter_checks": parameter_rows,
            "raw_json_valid": not json_errors, "json_errors": json_errors,
            "final_value_errors": final_errors, "critical": critical,
            "automatic_pass": trace["status"] == "completed" and bool(decision) and not json_errors
                and not final_errors and not any(row["errors"] for row in parameter_rows) and not any(critical.values())}


def execute_checkpoint(runtime, cp, case_id, variant, repeat, snap, delegate, budget,
                       model="offline-test", thinking=False, output_limit=2048, secrets=()):
    trace = {"trace_id": f"{case_id}-{cp['checkpoint_id']}-{variant}-{repeat}", "case_id": case_id,
             "checkpoint_id": cp["checkpoint_id"], "variant": variant, "repeat": repeat,
             "status": "started", "prompt_hash": digest(snap), "checkpoint_hash": digest(cp),
             "model_calls": [], "tool_calls": [], "raw_final": "", "parsed_response": None}
    observer = ObservedCompletions(delegate, snap, budget, trace, output_limit, secrets)
    registry = SimulatedRegistry(cp, snap["tool_schemas"], runtime.schemas.ToolResult, secrets)
    service = runtime.AgentService.__new__(runtime.AgentService)
    service.settings = SimpleNamespace(has_model_credentials=lambda: True, deepseek_model=model,
                                       agent_max_tool_rounds=3, deepseek_thinking_enabled=thinking)
    service.client = SimpleNamespace(chat=SimpleNamespace(completions=observer))
    service.registry = registry
    request = copy.deepcopy(cp["request"])
    request["conversationId"] = trace["trace_id"]
    start = perf_counter()
    try:
        response = service.run(runtime.schemas.AgentRequest.model_validate(request))
        trace["parsed_response"] = scrub(response.model_dump(mode="json"), secrets)
        trace["status"] = "completed" if response.status == "ok" else "application_error"
    except Exception as exc:
        trace["status"] = "error"
        trace["error"] = {"type": type(exc).__name__, "stop_reason": budget.stop_reason or "request_or_application_error"}
    trace["seconds"] = round(perf_counter() - start, 4)
    trace["tool_calls"] = registry.calls
    if trace["model_calls"]:
        last = trace["model_calls"][-1]
        trace["raw_final"] = last["content"] if not last["tool_calls"] else ""
    trace["score"] = score_trace(trace, cp, snap["tool_schemas"])
    return trace


def schedule(fixture, repeats=1, variant="both"):
    jobs = []
    for repeat in range(1, repeats + 1):
        for case in fixture["cases"]:
            for cp in case["checkpoints"]:
                order = ["baseline", "candidate"] if len(jobs) // 2 % 2 == 0 else ["candidate", "baseline"]
                for v in (order if variant == "both" else [variant]):
                    jobs.append((case["case_id"], cp, v, repeat))
    return jobs


def preflight():
    baseline = read_json(FIXTURES / "prompt_overhead_baseline.json")
    current = snapshot(ROOT)
    fixture = read_json(FIXTURES / "prompt_overhead_cases.json")
    if current["non_prompt_ast_sha256"] != baseline["non_prompt_ast_sha256"]:
        raise ValueError("Non-prompt production code changed since this baseline")
    if without_descriptions(current["tool_schemas"]) != without_descriptions(baseline["tool_schemas"]):
        raise ValueError("Tool schema changed")
    runtime = load_runtime()
    names = {s["name"] for s in current["tool_schemas"]}
    ids = []
    for case in fixture["cases"]:
        for cp in case["checkpoints"]:
            ids.append(cp["checkpoint_id"])
            runtime.schemas.AgentRequest.model_validate(cp["request"])
            e = cp["expected"]
            if set(e["allowed_tools"]) & set(e["forbidden_tools"]) or set(e["allowed_tools"]) | set(e["forbidden_tools"]) != names:
                raise ValueError("Invalid tool partition")
            if not set(e["required_any"]) <= set(e["allowed_tools"]) or not cp["semantic_rubric"]:
                raise ValueError("Invalid required tool or missing semantic rubric")
            if cp["request"]["query"] in current["system_prompt"]:
                raise ValueError("Evaluation query leaked into system examples")
            for result in cp["tool_results"].values():
                runtime.schemas.ToolResult.model_validate(result)
    if len(fixture["cases"]) != 10 or len(ids) != 13 or len(set(ids)) != 13:
        raise ValueError("Expected exactly ten cases and thirteen distinct checkpoints")
    return baseline, current, fixture, runtime
