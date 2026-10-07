from typing import Any

import httpx

from app.config import Settings, get_settings


class JavaApiClient:
    def __init__(self, settings: Settings | None = None):
        self.settings = settings or get_settings()

    def add_manual(self, user_id: str, manual_id: str) -> dict[str, Any]:
        return self._request("POST", "/api/my-manuals", json={"userId": user_id, "manualId": manual_id})

    def find_manuals(self, keyword: str) -> list[dict[str, Any]]:
        payload = self._request("GET", "/api/manuals", params={"keyword": keyword})
        items = payload.get("data", [])
        return [item for item in items if isinstance(item, dict)] if isinstance(items, list) else []

    def create_repair_order(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request("POST", "/api/service-orders", json=payload)

    def check_execution(self, request) -> None:
        if not self.settings.agent_internal_token or request.memory.executionVersion is None:
            raise PermissionError("execution_qualification_missing")
        result = self._request("POST", "/api/internal/agent/execution/check", json={
            "userId": request.userId, "conversationId": request.conversationId,
            "requestId": request.requestId, "executionVersion": request.memory.executionVersion,
        })
        if result.get("allowed") is not True:
            raise PermissionError("execution_qualification_lost")

    def publish_terminal(self, request, response) -> None:
        if not self.settings.agent_internal_token:
            raise PermissionError("execution_qualification_missing")
        result = self._request("POST", "/api/internal/agent/execution/terminal", json={
            "execution": {"userId": request.userId, "conversationId": request.conversationId,
                          "requestId": request.requestId, "executionVersion": request.memory.executionVersion},
            "response": response.model_dump(mode="json"),
        })
        if result.get("accepted") is not True:
            raise PermissionError("terminal_not_accepted")

    def _request(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        headers = dict(kwargs.pop("headers", {}))
        if self.settings.agent_internal_token:
            headers["X-Internal-Token"] = self.settings.agent_internal_token
        with httpx.Client(base_url=self.settings.java_base_url, timeout=15) as client:
            response = client.request(method, path, headers=headers, **kwargs)
        if response.status_code == 409 and method == "POST" and path == "/api/my-manuals":
            return {"status": "already_exists", "_http_status": 409}
        response.raise_for_status()
        data = response.json()
        return data if isinstance(data, dict) else {"data": data}
