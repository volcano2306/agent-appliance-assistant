import base64
import hashlib
import hmac
import json
import time
import uuid
from urllib.parse import quote, urlencode

from websockets.sync.client import connect

from app.config import Settings, get_settings


VOICE_FORMATS = {
    "pcm": 1,
    "mp3": 8,
    "opus": 10,
    "wav": 12,
    "m4a": 14,
    "aac": 16,
}


class TencentAsrClient:
    def __init__(self, settings: Settings | None = None):
        self.settings = settings or get_settings()

    def transcribe(self, audio: bytes, file_format: str = "pcm") -> dict:
        if not self.settings.has_tencent_asr_credentials():
            raise RuntimeError("Tencent ASR credentials are not configured")
        normalized_format = file_format.lower().lstrip(".")
        voice_format = VOICE_FORMATS.get(normalized_format)
        if voice_format is None:
            raise ValueError(f"Unsupported audio format: {file_format}")

        url, voice_id = self._signed_url(voice_format)
        stable_segments: dict[int, str] = {}
        latest_text = ""
        with connect(url, open_timeout=10, close_timeout=5, max_size=4 * 1024 * 1024) as websocket:
            # 16 kHz, 16-bit, mono PCM uses 6400 bytes per 200 ms.
            frame_size = 6400 if normalized_format == "pcm" else 4096
            for offset in range(0, len(audio), frame_size):
                websocket.send(audio[offset:offset + frame_size])
                time.sleep(0.2)
            websocket.send(json.dumps({"type": "end"}, ensure_ascii=False))

            while True:
                raw = websocket.recv(timeout=20)
                message = json.loads(raw)
                if int(message.get("code", 0)) != 0:
                    raise RuntimeError(message.get("message") or f"Tencent ASR error {message.get('code')}")
                result = message.get("result") or {}
                if result.get("voice_text_str"):
                    index = int(result.get("index", len(stable_segments)))
                    if int(result.get("slice_type", 0)) == 2:
                        stable_segments[index] = str(result["voice_text_str"])
                    latest_text = str(result["voice_text_str"])
                for sentence in message.get("sentences") or []:
                    sentence_id = int(sentence.get("sentence_id", len(stable_segments)))
                    text = str(sentence.get("sentence") or "")
                    if int(sentence.get("sentence_type", 0)) == 1:
                        stable_segments[sentence_id] = text
                    latest_text = text or latest_text
                if int(message.get("final", 0)) == 1:
                    break

        text = "".join(stable_segments[index] for index in sorted(stable_segments)).strip() or latest_text.strip()
        return {"text": text, "voiceId": voice_id, "provider": "tencent-cloud-asr"}

    def _signed_url(self, voice_format: int) -> tuple[str, str]:
        timestamp = int(time.time())
        voice_id = str(uuid.uuid4())
        params = {
            "engine_model_type": self.settings.tencent_asr_engine_model_type,
            "expired": timestamp + 3600,
            "filter_dirty": 1,
            "filter_modal": 1,
            "filter_punc": 1,
            "needvad": 1,
            "nonce": timestamp,
            "secretid": self.settings.tencent_asr_secret_id,
            "timestamp": timestamp,
            "voice_format": voice_format,
            "voice_id": voice_id,
        }
        query = urlencode(sorted(params.items()))
        base = self.settings.tencent_asr_ws_base.rstrip("/")
        host_and_path = base.removeprefix("wss://").removeprefix("ws://")
        sign_source = f"{host_and_path}/{self.settings.tencent_asr_app_id}?{query}"
        digest = hmac.new(
            self.settings.tencent_asr_secret_key.encode("utf-8"),
            sign_source.encode("utf-8"),
            hashlib.sha1,
        ).digest()
        signature = quote(base64.b64encode(digest).decode("ascii"), safe="")
        return f"{base}/{self.settings.tencent_asr_app_id}?{query}&signature={signature}", voice_id

