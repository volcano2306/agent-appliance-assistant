import math
import re
from collections import Counter
from functools import lru_cache
from typing import Any

from pymongo import MongoClient
from qdrant_client import QdrantClient, models

from app.config import Settings, get_settings
from app.rag.embeddings import embed_query
from app.schemas import Source


HYBRID_CANDIDATE_LIMIT = 20
HYBRID_RESULT_LIMIT = 10
RRF_K = 60


def _tokens(text: str) -> list[str]:
    normalized = (text or "").lower()
    latin = re.findall(r"[a-z0-9][a-z0-9._/-]*", normalized)
    chinese = re.findall(r"[\u4e00-\u9fff]", normalized)
    bigrams = ["".join(chinese[index:index + 2]) for index in range(max(len(chinese) - 1, 0))]
    return latin + chinese + bigrams


def _bm25_scores(query: str, documents: list[dict[str, Any]]) -> list[float]:
    query_terms = list(dict.fromkeys(_tokens(query)))
    tokenized = [_tokens(str(document.get("text") or "")) for document in documents]
    if not query_terms or not tokenized:
        return [0.0] * len(documents)

    average_length = sum(len(tokens) for tokens in tokenized) / max(len(tokenized), 1)
    document_frequency = Counter()
    for tokens in tokenized:
        document_frequency.update(set(tokens))

    k1, b = 1.5, 0.75
    scores: list[float] = []
    count = len(tokenized)
    for tokens in tokenized:
        frequencies = Counter(tokens)
        length = len(tokens)
        score = 0.0
        for term in query_terms:
            frequency = frequencies.get(term, 0)
            if frequency == 0:
                continue
            frequency_in_docs = document_frequency.get(term, 0)
            inverse_frequency = math.log(1 + (count - frequency_in_docs + 0.5) / (frequency_in_docs + 0.5))
            denominator = frequency + k1 * (1 - b + b * length / max(average_length, 1))
            score += inverse_frequency * frequency * (k1 + 1) / denominator
        scores.append(score)
    return scores


@lru_cache(maxsize=1)
def _mongo_client() -> MongoClient:
    return MongoClient(get_settings().mongodb_uri, serverSelectionTimeoutMS=3000)


@lru_cache(maxsize=1)
def _qdrant_client() -> QdrantClient:
    settings = get_settings()
    return QdrantClient(
        url=settings.qdrant_url,
        api_key=settings.qdrant_api_key or None,
        timeout=5,
    )


class HybridRetriever:
    def __init__(self, settings: Settings | None = None):
        self.settings = settings or get_settings()
        self.database = _mongo_client()[self.settings.mongodb_database]

    def search(self, query: str, manual_id: str = "", top_k: int | None = None) -> list[dict[str, Any]]:
        limit = HYBRID_RESULT_LIMIT if top_k is None else min(top_k, HYBRID_RESULT_LIMIT)
        lexical = self._lexical_search(query, manual_id, HYBRID_CANDIDATE_LIMIT)
        vector = self._vector_search(query, manual_id, HYBRID_CANDIDATE_LIMIT)
        fused = self._rrf(lexical, vector)
        return fused[:limit]

    def _lexical_search(self, query: str, manual_id: str, limit: int) -> list[dict[str, Any]]:
        mongo_filter: dict[str, Any] = {"text": {"$type": "string"}}
        if manual_id:
            mongo_filter["manualId"] = manual_id
        # BM25 must score the complete filtered corpus. A database-side limit here
        # can silently exclude the relevant fragment before ranking begins.
        documents = list(self.database.fragments.find(mongo_filter))
        scores = _bm25_scores(query, documents)
        ranked = sorted(zip(documents, scores), key=lambda pair: pair[1], reverse=True)
        return [self._normalize_mongo(document, score) for document, score in ranked[:limit] if score > 0]

    def _vector_search(self, query: str, manual_id: str, limit: int) -> list[dict[str, Any]]:
        try:
            query_filter = None
            if manual_id:
                query_filter = models.Filter(
                    must=[models.FieldCondition(key="manual_id", match=models.MatchValue(value=manual_id))]
                )
            response = _qdrant_client().query_points(
                collection_name=self.settings.qdrant_collection,
                query=embed_query(query),
                query_filter=query_filter,
                limit=limit,
                with_payload=True,
            )
            results: list[dict[str, Any]] = []
            for point in response.points:
                payload = dict(point.payload or {})
                payload["score"] = float(point.score or 0)
                results.append(payload)
            return results
        except Exception:
            # MongoDB/BM25 remains available while Qdrant is starting or before indexing.
            return []

    def _rrf(self, lexical: list[dict[str, Any]], vector: list[dict[str, Any]]) -> list[dict[str, Any]]:
        combined: dict[str, dict[str, Any]] = {}
        for ranking in (lexical, vector):
            for rank, item in enumerate(ranking, start=1):
                key = str(item.get("fragment_id") or item.get("id") or "")
                if not key:
                    continue
                current = combined.setdefault(key, {**item, "rrf_score": 0.0})
                current["rrf_score"] += 1.0 / (RRF_K + rank)
                if len(str(item.get("text") or "")) > len(str(current.get("text") or "")):
                    current.update(item)
        return sorted(combined.values(), key=lambda item: item["rrf_score"], reverse=True)

    def _normalize_mongo(self, document: dict[str, Any], score: float) -> dict[str, Any]:
        return {
            "fragment_id": str(document.get("_id") or ""),
            "manual_id": str(document.get("manualId") or ""),
            "product_id": str(document.get("productId") or ""),
            "page": document.get("page"),
            "chunk_index": document.get("chunkIndex"),
            "text": str(document.get("text") or ""),
            "source_type": str(document.get("source") or "manual"),
            "score": score,
        }

    def sources(self, results: list[dict[str, Any]]) -> list[Source]:
        output: list[Source] = []
        for item in results:
            manual_id = str(item.get("manual_id") or "")
            output.append(Source(
                kind="manual_fragment",
                fragmentId=str(item.get("fragment_id") or ""),
                page=item.get("page"),
                chunkIndex=item.get("chunk_index"),
                title=self._manual_title(manual_id),
                snippet=str(item.get("text") or "")[:320],
                sourceType=str(item.get("source_type") or "manual"),
            ))
        return output

    def _manual_title(self, manual_id: str) -> str:
        if not manual_id:
            return "说明书"
        manual = self.database.manuals.find_one({"_id": self._coerce_id(manual_id)})
        if manual is None:
            manual = self.database.my_manuals.find_one({"_id": self._coerce_id(manual_id)})
        if not manual:
            return "说明书"
        return str(manual.get("productName") or manual.get("modelName") or manual.get("nickname") or "说明书")

    @staticmethod
    def _coerce_id(value: str) -> Any:
        try:
            from bson import ObjectId
            return ObjectId(value) if ObjectId.is_valid(value) else value
        except Exception:
            return value
