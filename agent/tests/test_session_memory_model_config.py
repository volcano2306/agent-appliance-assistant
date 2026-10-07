import io
import pytest
from session_memory_model_config import extract_model_config, verified_context_window, model_config_audit, redact_model_secret


def source(extra="", model="deepseek-v4-flash", url="https://api.deepseek.com"):
    return io.StringIO(f'DEEPSEEK_API_KEY="synthetic-not-a-secret"\nDEEPSEEK_BASE_URL={url}\nDEEPSEEK_MODEL={model}\n' + extra)


def test_extracts_only_model_fields_without_loading_other_configuration():
    values = extract_model_config(source("MONGODB_URI=synthetic-other-value\nSERPAPI_API_KEY=synthetic-other-key\n"))
    assert set(values) == {"API_KEY", "BASE_URL", "MODEL"}
    assert "synthetic-other" not in repr(values)


def test_accepts_comments_export_and_case_insensitive_fields():
    values = extract_model_config(io.StringIO('export deepseek_api_key="synthetic-key" # ignored\nDEEPSEEK_BASE_URL=https://api.deepseek.com\nDEEPSEEK_MODEL=deepseek-flash\n'))
    assert values["API_KEY"] == "synthetic-key"


def test_rejects_interpolation_without_exposing_input():
    with pytest.raises(ValueError, match="^project_model_configuration_invalid$"):
        extract_model_config(source("DEEPSEEK_API_KEY=${OTHER_SECRET}\n"))


@pytest.mark.parametrize("url", ["http://provider.invalid", "https://user:pass@provider.invalid", "https://provider.invalid?token=synthetic", "https://provider.invalid#token"])
def test_rejects_unsafe_urls(url):
    with pytest.raises(ValueError, match="^project_model_configuration_invalid$"):
        extract_model_config(source(url=url))


def test_requires_all_three_fields():
    with pytest.raises(ValueError, match="^project_model_configuration_incomplete$"):
        extract_model_config(io.StringIO("DEEPSEEK_API_KEY=synthetic\n"))


def test_context_window_only_from_verified_official_models():
    assert verified_context_window(extract_model_config(source())) == 1_000_000
    assert verified_context_window(extract_model_config(source(url="https://gateway.invalid/v1"))) is None
    assert verified_context_window(extract_model_config(source(model="unverified-model"))) is None


def test_audit_grant_is_exact_read_only_and_closed_after_loading(tmp_path):
    # Synthetic paths only: no file is created or opened.
    authorized = (tmp_path / "synthetic" / ".env").resolve()
    permit = [True]
    audit = model_config_audit(authorized, permit)
    audit("open", (str(authorized), "r", 0))
    for candidate, mode in [(authorized, "w"), (tmp_path / "copy" / ".env", "r"), (tmp_path / ".env.example", "r")]:
        with pytest.raises(PermissionError): audit("open", (str(candidate), mode, 0))
    permit[0] = False
    with pytest.raises(PermissionError): audit("open", (str(authorized), "r", 0))


def test_provider_key_echo_is_redacted_recursively():
    value = {"synthetic-key": ["prefix synthetic-key suffix", {"answer": "synthetic-key"}], "ok": True}
    assert "synthetic-key" not in repr(redact_model_secret(value, "synthetic-key"))
    assert redact_model_secret("ordinary", "") == "ordinary"
