from functools import lru_cache

from typing import Any, Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    deepseek_api_key: str = ""
    # Keep existing environment names compatible with deployed installations.
    deepseek_base_url: str = "https://windhub.cc/v1"
    deepseek_model: str = "qwen3.8-flash-next"
    deepseek_thinking_enabled: bool = False
    # Provider-specific JSON, only when confirmed by the gateway operator.
    model_extra_body: dict[str, Any] = Field(default_factory=dict)

    agent_internal_token: str = ""
    java_base_url: str = "http://localhost:18083"
    agent_host: str = "0.0.0.0"
    agent_port: int = 8001
    agent_max_tool_rounds: int = Field(default=3, ge=1, le=5)
    agent_max_search_results: int = Field(default=5, ge=1, le=10)
    agent_request_timeout_seconds: float = 90
    # One formatting-only call after an invalid final object; never executes tools.
    agent_format_repair_enabled: bool = True

    memory_enabled: bool = False
    memory_execution_enabled: bool = False
    memory_recent_turns: Literal[3, 5] = 5
    memory_compression_strategy: Literal["threshold", "rolling"] = "threshold"
    memory_trigger_tokens: int = Field(default=1500, ge=256)
    memory_min_growth_tokens: int = Field(default=500, ge=1)
    memory_summary_max_tokens: Literal[300, 600, 1000] = 600
    memory_summary_input_budget: int = Field(default=8000, ge=256)
    memory_main_input_budget: int = Field(default=24000, ge=256)
    memory_answer_reserve: int = Field(default=2000, ge=1)
    memory_safety_margin: int = Field(default=1024, ge=0)
    # Deployment must explicitly confirm the provider's actual context window.
    memory_model_context_window: int | None = Field(default=None, ge=1)
    memory_summary_retries: Literal[0, 1] = 1
    memory_summary_timeout_seconds: float = Field(default=30, gt=0)
    memory_policy_version: str = Field(default="session-memory-v1", min_length=1)

    @model_validator(mode="after")
    def memory_budget_configuration(self):
        if self.memory_min_growth_tokens >= self.memory_trigger_tokens:
            raise ValueError("memory_growth_must_be_below_trigger")
        if (self.memory_enabled or self.memory_execution_enabled) and self.memory_model_context_window is None:
            raise ValueError("memory_model_context_window_required")
        if self.memory_model_context_window is not None:
            needed = max(
                self.memory_main_input_budget + self.memory_answer_reserve,
                self.memory_summary_input_budget + self.memory_summary_max_tokens,
            ) + self.memory_safety_margin
            if needed > self.memory_model_context_window:
                raise ValueError("memory_budget_exceeds_model_window")
        return self

    mongodb_uri: str = "mongodb://localhost:27017/jiadian"
    mongodb_database: str = "jiadian"
    qdrant_url: str = "http://localhost:6333"
    qdrant_api_key: str = ""
    qdrant_collection: str = "manual_fragments"
    embedding_model: str = "BAAI/bge-small-zh-v1.5"
    serpapi_api_key: str = ""
    serpapi_base_url: str = "https://serpapi.com/search.json"
    serpapi_country: str = "cn"
    serpapi_language: str = "zh-cn"
    serpapi_shopping_country: str = "us"
    serpapi_shopping_language: str = "zh-cn"
    serpapi_google_domain: str = "google.com"
    serpapi_device: str = "desktop"
    serpapi_timeout_seconds: float = 20

    tencent_asr_app_id: str = ""
    tencent_asr_secret_id: str = ""
    tencent_asr_secret_key: str = ""
    tencent_asr_engine_model_type: str = "16k_zh"
    tencent_asr_ws_base: str = "wss://asr.cloud.tencent.com/asr/v2"

    def has_model_credentials(self) -> bool:
        return bool(self.deepseek_api_key and not self.deepseek_api_key.startswith("replace-"))

    def has_serpapi_credentials(self) -> bool:
        return bool(self.serpapi_api_key and not self.serpapi_api_key.startswith("replace-"))

    def has_tencent_asr_credentials(self) -> bool:
        values = (self.tencent_asr_app_id, self.tencent_asr_secret_id, self.tencent_asr_secret_key)
        return all(values) and not any(value.startswith("replace-") for value in values)


@lru_cache
def get_settings() -> Settings:
    return Settings()
