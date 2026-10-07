import re
from typing import Any

import httpx

from app.clients.java_api import JavaApiClient
from app.schemas import ToolContext, ToolResult


class BusinessTools:
    _REJECTION_ERRORS = {
        400: "invalid_write_request",
        401: "login_required",
        403: "permission_denied",
        404: "write_target_not_found",
    }

    def __init__(self, java_client: JavaApiClient | None = None):
        self.java_client = java_client or JavaApiClient()

    def add_manual_to_account(self, arguments: dict[str, Any], context: ToolContext) -> ToolResult:
        if not context.user_id:
            return ToolResult(ok=False, error="login_required", write_status="not_dispatched")

        context_manual_id = str(context.manual_id or "").strip()
        if context_manual_id:
            return self._add_manual(context.user_id, context_manual_id)
        if context.manual_ref_id:
            return ToolResult(ok=False, error="platform_manual_required", write_status="not_dispatched")

        manual_query = str(arguments.get("manual_query") or "").strip()

        if not manual_query:
            return ToolResult(ok=False, error="manual_reference_required", write_status="not_dispatched")

        try:
            candidates = self.java_client.find_manuals(manual_query)
        except Exception:
            return ToolResult(ok=False, error="manual_lookup_failed", write_status="not_dispatched")

        selected = self._select_manual(candidates, manual_query)
        if selected is not None:
            manual_id = str(selected.get("id") or "").strip()
            if manual_id:
                return self._add_manual(context.user_id, manual_id)

        summaries = [self._manual_summary(item) for item in candidates[:5]]
        summaries = [item for item in summaries if item.get("id")]
        if not summaries:
            return ToolResult(
                ok=False,
                error="manual_not_found",
                data={"query": manual_query, "candidates": []},
                write_status="not_dispatched",
            )
        return ToolResult(
            ok=False,
            error="manual_selection_required",
            data={"query": manual_query, "candidates": summaries},
            write_status="not_dispatched",
        )

    def _add_manual(self, user_id: str, manual_id: str) -> ToolResult:
        try:
            data = self.java_client.add_manual(user_id, manual_id)
        except httpx.HTTPStatusError as exc:
            error = self._REJECTION_ERRORS.get(exc.response.status_code)
            status = "failed" if error else "unknown"
            return ToolResult(ok=False, error=error or "write_result_unknown",
                              write_status=status, write_target=manual_id)
        except Exception:
            return ToolResult(ok=False, error="write_result_unknown", write_status="unknown",
                              write_target=manual_id)
        if (isinstance(data, dict) and data.get("status") == "already_exists"
                and data.get("_http_status") == 409 and not data.get("error")
                and str(data.get("manualId") or manual_id) == manual_id):
            return ToolResult(data={"status": "already_exists", "manualId": manual_id},
                              write_status="already_exists", write_target=manual_id)
        if (isinstance(data, dict) and not data.get("error")
                and str(data.get("id") or "").strip()
                and str(data.get("manualId") or "").strip() == manual_id):
            return ToolResult(data=data, write_status="succeeded", write_target=manual_id,
                              write_identifier=str(data["id"]))
        return ToolResult(ok=False, error="write_result_unknown", write_status="unknown",
                          write_target=manual_id)

    @classmethod
    def _select_manual(cls, candidates: list[dict[str, Any]], query: str) -> dict[str, Any] | None:
        query_key = cls._search_key(query)
        if not query_key:
            return None

        scored: list[tuple[int, dict[str, Any]]] = []
        for item in candidates:
            values = [item.get("id"), item.get("modelName"), item.get("productName")]
            keys = [cls._search_key(value) for value in values if value]
            score = 0
            if query_key in keys:
                score = 3
            elif any(len(key) >= 3 and key in query_key for key in keys):
                score = 2
            elif any(len(query_key) >= 3 and query_key in key for key in keys):
                score = 1
            scored.append((score, item))

        best_score = max((score for score, _ in scored), default=0)
        best = [item for score, item in scored if score == best_score and score > 0]
        if len(best) == 1:
            return best[0]
        return None

    @staticmethod
    def _search_key(value: Any) -> str:
        return re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", str(value or "").lower())

    @staticmethod
    def _manual_summary(item: dict[str, Any]) -> dict[str, str]:
        return {
            "id": str(item.get("id") or ""),
            "productName": str(item.get("productName") or ""),
            "brandName": str(item.get("brandName") or ""),
            "modelName": str(item.get("modelName") or ""),
        }

    def create_repair_order(self, arguments: dict[str, Any], context: ToolContext) -> ToolResult:
        if not context.user_id:
            return ToolResult(ok=False, error="login_required", write_status="not_dispatched")
        if arguments.get("confirmed") is not True:
            return ToolResult(ok=False, error="explicit_confirmation_required", write_status="not_dispatched")
        issue_desc = str(arguments.get("issue_desc") or "").strip()
        if not issue_desc:
            return ToolResult(ok=False, error="issue_desc_required", write_status="not_dispatched")
        payload = {
            "userId": context.user_id,
            "deviceId": str(context.product_id or ""),
            "manualId": str(context.manual_id or ""),
            "serviceType": "repair",
            "brandName": str(arguments.get("brand") or ""),
            "modelName": str(arguments.get("model") or ""),
            "faultCode": str(arguments.get("fault_code") or ""),
            "issueDesc": issue_desc,
            "contactPhone": str(arguments.get("contact_phone") or ""),
            "preferredTime": str(arguments.get("preferred_time") or ""),
            "conversationId": context.conversation_id,
        }
        target = "|".join((context.user_id, payload["manualId"], payload["deviceId"], issue_desc))
        try:
            data = self.java_client.create_repair_order(payload)
        except httpx.HTTPStatusError as exc:
            error = self._REJECTION_ERRORS.get(exc.response.status_code)
            status = "failed" if error else "unknown"
            return ToolResult(ok=False, error=error or "write_result_unknown",
                              write_status=status, write_target=target)
        except Exception:
            return ToolResult(ok=False, error="write_result_unknown", write_status="unknown",
                              write_target=target)
        if (isinstance(data, dict) and not data.get("error")
                and str(data.get("id") or "").strip()
                and all(str(data.get(key) or "").strip() == str(payload[value] or "").strip()
                        for key, value in (("userId", "userId"), ("manualId", "manualId"),
                                           ("deviceId", "deviceId"), ("issueDesc", "issueDesc")))):
            return ToolResult(data=data, write_status="succeeded", write_target=target,
                              write_identifier=str(data["id"]))
        return ToolResult(ok=False, error="write_result_unknown", write_status="unknown",
                          write_target=target)
