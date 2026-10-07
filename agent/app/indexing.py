import argparse
import uuid

from pymongo import MongoClient
from qdrant_client import QdrantClient, models

from app.config import get_settings
from app.rag.embeddings import embed_texts


def rebuild_index(batch_size: int = 64, recreate: bool = False) -> int:
    settings = get_settings()
    database = MongoClient(settings.mongodb_uri)[settings.mongodb_database]
    qdrant = QdrantClient(url=settings.qdrant_url, api_key=settings.qdrant_api_key or None)

    first = database.fragments.find_one({"text": {"$type": "string", "$ne": ""}})
    if first is None:
        return 0
    dimension = len(embed_texts([str(first["text"])])[0])

    if recreate and qdrant.collection_exists(settings.qdrant_collection):
        qdrant.delete_collection(settings.qdrant_collection)
    if not qdrant.collection_exists(settings.qdrant_collection):
        qdrant.create_collection(
            collection_name=settings.qdrant_collection,
            vectors_config=models.VectorParams(size=dimension, distance=models.Distance.COSINE),
        )
        qdrant.create_payload_index(settings.qdrant_collection, "manual_id", models.PayloadSchemaType.KEYWORD)

    indexed = 0
    cursor = database.fragments.find({"text": {"$type": "string", "$ne": ""}})
    batch: list[dict] = []
    for document in cursor:
        batch.append(document)
        if len(batch) >= batch_size:
            indexed += _upsert_batch(qdrant, settings.qdrant_collection, batch)
            batch.clear()
    if batch:
        indexed += _upsert_batch(qdrant, settings.qdrant_collection, batch)
    return indexed


def _upsert_batch(client: QdrantClient, collection: str, documents: list[dict]) -> int:
    vectors = embed_texts([str(document.get("text") or "") for document in documents])
    points = []
    for document, vector in zip(documents, vectors):
        fragment_id = str(document.get("_id"))
        points.append(models.PointStruct(
            id=str(uuid.uuid5(uuid.NAMESPACE_URL, f"sms-fragment:{fragment_id}")),
            vector=vector,
            payload={
                "fragment_id": fragment_id,
                "manual_id": str(document.get("manualId") or ""),
                "product_id": str(document.get("productId") or ""),
                "page": document.get("page"),
                "chunk_index": document.get("chunkIndex"),
                "text": str(document.get("text") or ""),
                "source_type": str(document.get("source") or "manual"),
            },
        ))
    client.upsert(collection_name=collection, points=points, wait=True)
    return len(points)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Index MongoDB manual fragments into Qdrant")
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--recreate", action="store_true")
    arguments = parser.parse_args()
    count = rebuild_index(arguments.batch_size, arguments.recreate)
    print(f"indexed_fragments={count}")

