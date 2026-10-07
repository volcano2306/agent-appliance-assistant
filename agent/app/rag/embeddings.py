from functools import lru_cache

from fastembed import TextEmbedding

from app.config import get_settings


@lru_cache(maxsize=1)
def get_embedding_model() -> TextEmbedding:
    settings = get_settings()
    return TextEmbedding(model_name=settings.embedding_model)

#接受文本，返回向量，写入qdrant
def embed_texts(texts: list[str]) -> list[list[float]]:
    if not texts:
        return []
    return [vector.tolist() for vector in get_embedding_model().embed(texts)]

#接收用户查询，返回查询向量，内部复用了embed_texts方法
def embed_query(text: str) -> list[float]:
    vectors = embed_texts([text])
    return vectors[0] if vectors else []

