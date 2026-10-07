"""Pure-stdlib source inspection: never import the application or load settings."""
import ast
import hashlib
import json
from pathlib import Path


def stable_ast_dump(value):
    """Match the compact AST format across Python 3.11 and 3.14.

    Python 3.14 omits empty list fields by default; 3.11 includes them.
    Keep every nonempty field (including executable nodes), not line metadata.
    """
    if isinstance(value, ast.AST):
        fields = [f"{key}={stable_ast_dump(item)}" for key, item in ast.iter_fields(value)
                  if (item is not None and item != [])
                  or (isinstance(value, ast.Constant) and key == "value")]
        return f"{type(value).__name__}({', '.join(fields)})"
    if isinstance(value, list):
        return "[" + ", ".join(stable_ast_dump(item) for item in value) + "]"
    return repr(value)


def snapshot(ai_root):
    root = Path(ai_root)
    agent_bytes = (root / "app/agent.py").read_bytes()
    registry_bytes = (root / "app/tools/registry.py").read_bytes()
    agent = ast.parse(agent_bytes.decode("utf-8-sig"))
    registry = ast.parse(registry_bytes.decode("utf-8-sig"))
    system = next(n for n in agent.body if isinstance(n, ast.Assign)
                  and any(isinstance(t, ast.Name) and t.id == "SYSTEM_INSTRUCTIONS" for t in n.targets))
    schemas = next(n for n in registry.body if isinstance(n, ast.Assign)
                   and any(isinstance(t, ast.Name) and t.id == "TOOL_SCHEMAS" for t in n.targets))
    summary = [n for n in ast.walk(agent) if isinstance(n, ast.Constant)
               and isinstance(n.value, str) and n.value.startswith("工具执行阶段已经结束。")]
    assert len(summary) == 1
    result = {"system_prompt": ast.literal_eval(system.value), "summary_prompt": summary[0].value,
              "tool_schemas": ast.literal_eval(schemas.value),
              "source_sha256": {"app/agent.py": hashlib.sha256(agent_bytes).hexdigest(),
                                "app/tools/registry.py": hashlib.sha256(registry_bytes).hexdigest()}}
    system.value = ast.Constant(value="PROMPT")
    summary[0].value = "SUMMARY"

    class StripDescriptions(ast.NodeTransformer):
        def visit_Dict(self, node):
            node = self.generic_visit(node)
            pairs = [(k, v) for k, v in zip(node.keys, node.values)
                     if not (isinstance(k, ast.Constant) and k.value == "description")]
            node.keys, node.values = [k for k, _ in pairs], [v for _, v in pairs]
            return node

    registry = StripDescriptions().visit(registry)
    result["non_prompt_ast_sha256"] = {
        "app/agent.py": hashlib.sha256(stable_ast_dump(agent).encode()).hexdigest(),
        "app/tools/registry.py": hashlib.sha256(stable_ast_dump(registry).encode()).hexdigest(),
    }
    return result


def without_descriptions(value):
    if isinstance(value, dict):
        return {k: without_descriptions(v) for k, v in value.items() if k != "description"}
    if isinstance(value, list):
        return [without_descriptions(v) for v in value]
    return value


if __name__ == "__main__":
    import sys
    print(json.dumps(snapshot(sys.argv[1]), ensure_ascii=True, indent=2))
