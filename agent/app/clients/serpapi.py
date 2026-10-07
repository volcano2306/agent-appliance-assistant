from typing import Any

import httpx

from app.config import Settings, get_settings


class SerpApiError(RuntimeError):
    """A sanitized SerpAPI error that never includes the API key or request URL."""

    def __init__(self, engine: str, message: str, status_code: int | None = None):
        self.engine = engine
        self.status_code = status_code
        prefix = f"SerpAPI {engine}"
        if status_code is not None:
            prefix += f" HTTP {status_code}"
        super().__init__(f"{prefix}: {message}")


class SerpApiClient:
    def __init__(self, settings: Settings | None = None):
        self.settings = settings or get_settings()

    def shopping(self, query: str, max_results: int) -> list[dict[str, Any]]:
        payload = self._search("google_shopping", query, max_results)
        candidates = self._shopping_candidates(payload)
        output: list[dict[str, Any]] = []
        seen: set[str] = set()
        for item in candidates:
            link = str(item.get("product_link") or item.get("link") or "").strip()
            title = str(item.get("title") or "").strip()
            unique_key = str(item.get("product_id") or link or title)
            if not unique_key or unique_key in seen:
                continue
            seen.add(unique_key)
            output.append({
                "title": title,
                "price": item.get("price", ""),
                "extracted_price": item.get("extracted_price"),
                "source": item.get("source", ""),
                "link": link,
                "thumbnail": item.get("thumbnail") or item.get("serpapi_thumbnail") or "",
                "rating": item.get("rating"),
                "reviews": item.get("reviews"),
                "delivery": item.get("delivery", ""),
                "snippet": item.get("snippet", ""),
                "search_engine": "google_shopping",
            })
            if len(output) >= max_results:
                break
        return output

    def web(self, query: str, max_results: int) -> list[dict[str, Any]]:
        payload = self._search("google", query, max_results)
        output = []
        for item in payload.get("organic_results", [])[:max_results]:
            output.append({
                "title": item.get("title", ""),
                "link": item.get("link", ""),
                "snippet": item.get("snippet", ""),
                "source": item.get("source") or item.get("displayed_link") or "",
                "date": item.get("date", ""),
                "search_engine": "google",
            })
        return output

    def _search(self, engine: str, query: str, max_results: int) -> dict[str, Any]:
        if not self.settings.has_serpapi_credentials():
            raise SerpApiError(engine, "SERPAPI_API_KEY is not configured")
        params = self._request_params(engine, query, max_results)

        try:
            with httpx.Client(timeout=self.settings.serpapi_timeout_seconds) as client:
                response = client.get(self.settings.serpapi_base_url, params=params)
        except httpx.TimeoutException as exc:
            raise SerpApiError(engine, "request timed out") from exc
        except httpx.RequestError as exc:
            raise SerpApiError(engine, "network connection failed") from exc

        payload = self._json_payload(response, engine)
        provider_error = payload.get("error")
        if response.is_error or provider_error:
            message = str(provider_error or response.reason_phrase or "request failed")
            raise SerpApiError(engine, message, response.status_code)
        return payload

    def _request_params(self, engine: str, query: str, max_results: int) -> dict[str, Any]:
        params: dict[str, Any] = {
            "engine": engine,
            "q": query,
            "api_key": self.settings.serpapi_api_key,
            "google_domain": self.settings.serpapi_google_domain,
            "device": self.settings.serpapi_device,
            "output": "json",
        }
        if engine == "google_shopping":
            params.update({
                "hl": self.settings.serpapi_shopping_language,
                "gl": self.settings.serpapi_shopping_country,
            })
        else:
            params.update({
                "hl": self.settings.serpapi_language,
                "gl": self.settings.serpapi_country,
                "safe": "active",
                "num": max_results,
            })
        return params

    @staticmethod
    def _json_payload(response: httpx.Response, engine: str) -> dict[str, Any]:
        try:
            payload = response.json()
        except ValueError as exc:
            raise SerpApiError(engine, "response was not valid JSON", response.status_code) from exc
        if not isinstance(payload, dict):
            raise SerpApiError(engine, "response JSON was not an object", response.status_code)
        return payload

    @staticmethod
    def _shopping_candidates(payload: dict[str, Any]) -> list[dict[str, Any]]:
        output: list[dict[str, Any]] = []
        for key in ("shopping_results", "inline_shopping_results"):
            values = payload.get(key)
            if isinstance(values, list):
                output.extend(item for item in values if isinstance(item, dict))

        categorized = payload.get("categorized_shopping_results")
        if isinstance(categorized, list):
            for category in categorized:
                if not isinstance(category, dict):
                    continue
                values = category.get("shopping_results")
                if isinstance(values, list):
                    output.extend(item for item in values if isinstance(item, dict))
        return output
