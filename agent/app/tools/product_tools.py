from typing import Any

from app.clients.serpapi import SerpApiClient
from app.config import get_settings
from app.schemas import Source, ToolContext, ToolResult


class ProductTools:
    def __init__(self, serpapi: SerpApiClient | None = None):
        self.serpapi = serpapi or SerpApiClient()

    def recommend_internet_products(self, arguments: dict[str, Any], _: ToolContext) -> ToolResult:
        query = str(arguments.get("query") or "").strip()
        budget_max = arguments.get("budget_max")
        preferences = [str(value).strip() for value in arguments.get("preferences") or [] if str(value).strip()]
        if not query:
            return ToolResult(ok=False, error="query_required")
        search_query = " ".join([query, *preferences, f"预算 {budget_max} 元" if budget_max else ""]).strip()
        warnings: list[str] = []
        products: list[dict[str, Any]] = []
        try:
            products = self.serpapi.shopping(search_query, get_settings().agent_max_search_results)
        except Exception as exc:
            warnings.append(f"Google Shopping不可用：{exc}")

        if not products:
            try:
                web_results = self.serpapi.web(search_query, get_settings().agent_max_search_results)
                products = [
                    {
                        "title": item.get("title"),
                        "link": item.get("link"),
                        "source": item.get("source"),
                        "snippet": item.get("snippet"),
                        "search_engine": "google",
                    }
                    for item in web_results
                ]
            except Exception as exc:
                warnings.append(f"Google Search不可用：{exc}")

        try:
            sources = [
                Source(
                    kind="web_result",
                    fragmentId=str(item.get("link") or ""),
                    title=str(item.get("title") or ""),
                    url=str(item.get("link") or ""),
                    snippet=str(item.get("snippet") or item.get("price") or "")[:320],
                    sourceType=f"serpapi_{item.get('search_engine') or 'google_shopping'}",
                )
                for item in products if item.get("link")
            ]
            error = "" if products else "；".join(warnings) or "no_products_found"
            return ToolResult(
                ok=bool(products),
                data={
                    "query": search_query,
                    "products": products,
                    "notice": "价格和库存可能变化，请在购买前打开来源页面核验。",
                    "searchWarnings": warnings,
                },
                error=error,
                sources=sources,
                used_web_search=True,
            )
        except Exception as exc:
            return ToolResult(ok=False, error=f"product_result_format_failed: {exc}", used_web_search=True)
