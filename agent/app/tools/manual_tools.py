from typing import Any

from app.rag.hybrid_retriever import HybridRetriever
from app.schemas import ToolContext, ToolResult


class ManualTools:
    def __init__(self, retriever: HybridRetriever | None = None):
        self.retriever = retriever or HybridRetriever()

    def manual_search(self, arguments: dict[str, Any], context: ToolContext) -> ToolResult:
        query = str(arguments.get("query") or "").strip()
        manual_id = str(context.manual_id or context.manual_ref_id or "").strip()
        if not query:
            return ToolResult(ok=False, error="query_required")
        results = self.retriever.search(query, manual_id)
        sources = self.retriever.sources(results)
        return ToolResult(
            ok=bool(results),
            data={
                "query": query,
                "manualId": manual_id,
                "matches": [
                    {
                        "fragmentId": item.get("fragment_id"),
                        "page": item.get("page"),
                        "text": item.get("text"),
                        "score": item.get("rrf_score") or item.get("score"),
                    }
                    for item in results
                ],
            },
            error="" if results else "no_manual_evidence",
            sources=sources,
        )

    def diagnose_fault(self, arguments: dict[str, Any], context: ToolContext) -> ToolResult:
        brand = str(arguments.get("brand") or "").strip()
        model = str(arguments.get("model") or "").strip()
        fault_code = str(arguments.get("fault_code") or "").strip()
        symptoms = str(arguments.get("symptoms") or "").strip()
        query = " ".join(part for part in [brand, model, fault_code, symptoms, "故障 原因 排查 维修 注意事项"] if part)
        if not fault_code and not symptoms:
            return ToolResult(ok=False, error="fault_code_or_symptoms_required")
        result = self.manual_search({"query": query}, context)
        if isinstance(result.data, dict):
            result.data.update({"brand": brand, "model": model, "faultCode": fault_code, "symptoms": symptoms})
        return result
