from types import SimpleNamespace

from fastapi.testclient import TestClient

from app.agent import AgentService
from app.clients.serpapi import SerpApiClient
from app.clients.tencent_asr import TencentAsrClient
from app.config import Settings, get_settings
from app.main import app
from app.rag.hybrid_retriever import (
    HYBRID_CANDIDATE_LIMIT,
    HYBRID_RESULT_LIMIT,
    HybridRetriever,
    _bm25_scores,
)
from app.schemas import AgentRequest, ToolContext, ToolResult
from app.tools.business_tools import BusinessTools
from app.tools.product_tools import ProductTools
from app.tools.registry import CHAT_COMPLETION_TOOLS


class FakeJavaClient:
    def __init__(self):
        self.added_manual_ids = []

    def add_manual(self, user_id: str, manual_id: str):
        self.added_manual_ids.append(manual_id)
        return {"id": "mine-1", "ownerUserId": user_id, "manualId": manual_id}

    def find_manuals(self, keyword: str):
        return [{
            "id": "mongo-manual-1",
            "productName": "奥克斯单槽洗衣机说明书",
            "brandName": "奥克斯",
            "modelName": "ASW-100HTB",
        }]

    def create_repair_order(self, payload):
        return {"id": "order-1", "status": "pending", **payload}


class FakeSerpApi:
    def shopping(self, query: str, max_results: int):
        return [{"title": "一级能效空调", "price": "¥2399", "link": "https://shop.example/p1"}]

    def web(self, query: str, max_results: int):
        raise AssertionError("shopping results should prevent the fallback")


class FailingShoppingSerpApi:
    def shopping(self, query: str, max_results: int):
        raise RuntimeError("shopping market unavailable")

    def web(self, query: str, max_results: int):
        return [{
            "title": "三口之家波轮洗衣机选购",
            "link": "https://example.com/washing-machine",
            "snippet": "8公斤容量，预算2000元以内",
            "source": "example.com",
        }]


class ShoppingPayloadSerpApi(SerpApiClient):
    def __init__(self, payload):
        super().__init__(Settings(_env_file=None, serpapi_api_key="test-key"))
        self.payload = payload

    def _search(self, engine: str, query: str, max_results: int):
        assert engine == "google_shopping"
        return self.payload


class FakeToolRegistry:
    def execute(self, name: str, arguments: str, context: ToolContext):
        assert name == "manual_search"
        assert '"query"' in arguments
        return ToolResult(ok=True, data={"text": "按下电源键"})


class FakeFragmentCollection:
    def __init__(self, documents):
        self.documents = documents

    def find(self, _query):
        # A regression to Cursor.limit(1500) would fail here and would also
        # hide the relevant fragment placed at the end of this test corpus.
        return self.documents


class FakeChatCompletions:
    def __init__(self):
        self.requests = []

    def create(self, **kwargs):
        self.requests.append(kwargs)
        if len(self.requests) == 1:
            function = SimpleNamespace(name="manual_search", arguments='{"query":"怎么开机"}')
            call = SimpleNamespace(id="call-1", function=function)
            message = SimpleNamespace(content="", reasoning_content="需要先查询说明书。", tool_calls=[call])
        else:
            content = '{"answerText":"按下电源键。","structuredAnswer":{"summary":"按下电源键。","steps":[],"cautions":[],"basisNote":""},"insufficientContext":false}'
            message = SimpleNamespace(content=content, reasoning_content="", tool_calls=[])
        return SimpleNamespace(choices=[SimpleNamespace(message=message)])


def test_bm25_prefers_matching_fault_document():
    scores = _bm25_scores(
        "空调 E5 故障",
        [{"text": "空调显示E5故障代码及处理方法"}, {"text": "洗衣机日常清洁"}],
    )
    assert scores[0] > scores[1]


def test_hybrid_retrieval_defaults_to_20_candidates_and_10_results():
    assert HYBRID_CANDIDATE_LIMIT == 20
    assert HYBRID_RESULT_LIMIT == 10
    retriever = object.__new__(HybridRetriever)
    retriever._lexical_search = lambda query, manual_id, limit: [
        {"fragment_id": f"item-{index}"} for index in range(limit)
    ]
    retriever._vector_search = lambda query, manual_id, limit: [
        {"fragment_id": f"item-{index}"} for index in range(limit)
    ]
    assert len(retriever.search("test")) == 10


def test_bm25_scores_complete_mongo_corpus_without_1500_limit():
    retriever = object.__new__(HybridRetriever)
    retriever.database = SimpleNamespace(fragments=FakeFragmentCollection(
        [{"_id": str(index), "text": "无关内容"} for index in range(1500)]
        + [{"_id": "target", "text": "洗衣机排水故障代码 E03"}]
    ))

    results = retriever._lexical_search("洗衣机 E03", "", 20)

    assert results[0]["fragment_id"] == "target"


def test_rrf_uses_equal_weight_for_bm25_and_vector_rankings():
    retriever = object.__new__(HybridRetriever)
    results = retriever._rrf(
        [{"fragment_id": "bm25-only", "text": "关键词"}],
        [{"fragment_id": "vector-only", "text": "语义"}],
    )

    scores = {item["fragment_id"]: item["rrf_score"] for item in results}
    assert scores["bm25-only"] == scores["vector-only"]


def test_business_actions_use_context_user_and_require_confirmation():
    java_client = FakeJavaClient()
    tools = BusinessTools(java_client=java_client)
    context = ToolContext(user_id="user-1", manual_id="manual-1", conversation_id="conversation-1")

    saved = tools.add_manual_to_account({"manual_id": "manual-1"}, context)
    assert saved.ok is True
    assert saved.data["ownerUserId"] == "user-1"
    assert java_client.added_manual_ids == ["manual-1"]

    rejected = tools.create_repair_order({"issue_desc": "空调不制冷", "confirmed": False}, context)
    assert rejected.ok is False
    assert rejected.error == "explicit_confirmation_required"

    created = tools.create_repair_order({"issue_desc": "空调不制冷", "confirmed": True}, context)
    assert created.ok is True
    assert created.data["userId"] == "user-1"


def test_add_manual_prefers_trusted_context_over_model_argument():
    java_client = FakeJavaClient()
    result = BusinessTools(java_client=java_client).add_manual_to_account(
        {"manual_id": "ASW-100HTB"},
        ToolContext(user_id="user-1", manual_id="mongo-manual-1"),
    )

    assert result.ok is True
    assert java_client.added_manual_ids == ["mongo-manual-1"]


def test_add_manual_can_resolve_product_model_from_platform_catalog():
    java_client = FakeJavaClient()
    result = BusinessTools(java_client=java_client).add_manual_to_account(
        {"manual_query": "ASW-100HTB说明书"},
        ToolContext(user_id="user-1"),
    )

    assert result.ok is True
    assert result.data["manualId"] == "mongo-manual-1"


def test_platform_manual_id_is_preferred_over_my_manual_reference_id():
    request = AgentRequest(
        query="加入我的说明书",
        manualId="platform-manual-1",
        manualRefId="my-manual-1",
    )

    assert request.lookup_manual_id == "platform-manual-1"


def test_product_search_returns_sources():
    result = ProductTools(serpapi=FakeSerpApi()).recommend_internet_products(
        {"query": "卧室空调", "budget_max": 2500, "preferences": ["安静", "省电"]},
        ToolContext(),
    )
    assert result.ok is True
    assert result.used_web_search is True
    assert result.sources[0].url == "https://shop.example/p1"


def test_product_search_falls_back_to_google_when_shopping_fails():
    result = ProductTools(serpapi=FailingShoppingSerpApi()).recommend_internet_products(
        {"query": "一家三口洗衣机", "budget_max": 2000, "preferences": []},
        ToolContext(),
    )
    assert result.ok is True
    assert result.sources[0].sourceType == "serpapi_google"
    assert result.data["searchWarnings"]


def test_serpapi_uses_separate_google_and_shopping_parameters():
    client = SerpApiClient(Settings(_env_file=None, serpapi_api_key="test-key"))
    shopping = client._request_params("google_shopping", "洗衣机", 5)
    web = client._request_params("google", "洗衣机", 5)

    assert shopping["engine"] == "google_shopping"
    assert shopping["gl"] == "us"
    assert shopping["hl"] == "zh-cn"
    assert "safe" not in shopping
    assert "num" not in shopping
    assert web["engine"] == "google"
    assert web["gl"] == "cn"
    assert web["safe"] == "active"
    assert web["num"] == 5


def test_serpapi_parses_all_google_shopping_result_shapes():
    payload = {
        "inline_shopping_results": [
            {"title": "商品A", "link": "https://example.com/a", "price": "¥1000"},
        ],
        "categorized_shopping_results": [
            {"shopping_results": [
                {"title": "商品B", "product_link": "https://example.com/b", "price": "¥1500"},
            ]},
        ],
    }
    products = ShoppingPayloadSerpApi(payload).shopping("洗衣机", 5)
    assert [item["title"] for item in products] == ["商品A", "商品B"]
    assert all(item["search_engine"] == "google_shopping" for item in products)


def test_tencent_signature_contains_required_fields():
    settings = Settings(
        _env_file=None,
        tencent_asr_app_id="123456",
        tencent_asr_secret_id="secret-id",
        tencent_asr_secret_key="secret-key",
    )
    url, voice_id = TencentAsrClient(settings)._signed_url(1)
    assert url.startswith("wss://asr.cloud.tencent.com/asr/v2/123456?")
    assert "signature=" in url
    assert f"voice_id={voice_id}" in url


def test_health_and_unconfigured_model_response(monkeypatch):
    monkeypatch.setattr("app.main.get_settings", lambda: Settings(_env_file=None))
    client = TestClient(app)
    health = client.get("/health")
    assert health.status_code == 200
    assert health.json()["provider"] == "newapi"
    assert health.json()["model"] == "qwen3.8-flash-next"

    service = AgentService(Settings(
        _env_file=None,
        deepseek_api_key="replace-with-your-deepseek-key",
        deepseek_model="deepseek-v4-flash",
    ))
    response = service.run(AgentRequest(query="空调怎么清洗"))
    assert response.error == "model_not_configured"


def test_chat_completion_tool_schema_wraps_function_definition():
    tool = CHAT_COMPLETION_TOOLS[0]
    assert tool["type"] == "function"
    assert tool["function"]["name"] == "manual_search"
    assert tool["function"]["parameters"]["type"] == "object"


def test_deepseek_chat_completion_tool_round_trip():
    service = AgentService(
        Settings(_env_file=None, deepseek_api_key="test-key",
                 deepseek_base_url="https://api.deepseek.com", deepseek_model="deepseek-v4-flash"),
        registry=FakeToolRegistry(),
    )
    completions = FakeChatCompletions()
    service.client = SimpleNamespace(chat=SimpleNamespace(completions=completions))

    response = service.run(AgentRequest(query="洗衣机怎么开机"))

    assert response.status == "ok"
    assert response.model == "deepseek-v4-flash"
    assert response.target == "deepseek"
    assert response.answerText == "按下电源键。"
    assert response.toolCalls[0]["name"] == "manual_search"
    assert completions.requests[0]["tools"][0]["function"]["name"] == "manual_search"
    assert completions.requests[0]["extra_body"] == {"thinking": {"type": "disabled"}}
    assert completions.requests[1]["messages"][-2]["reasoning_content"] == "需要先查询说明书。"
    assert completions.requests[1]["messages"][-1]["role"] == "tool"
