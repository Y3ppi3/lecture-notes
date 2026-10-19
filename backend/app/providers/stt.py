import asyncio
import json
import time
from dataclasses import dataclass

import httpx

from app.providers.base import ProviderError, call_with_retries, json_or_error
from app.providers.sber import SberTokenCache
from app.services.transcode import to_wav_16k_mono


@dataclass(frozen=True)
class STTParams:
    base_url: str
    api_key: str
    model: str
    timeout_s: float
    max_retries: int
    scope: str = ""


class OpenAICompatibleSTT:
    """POST {base_url}/audio/transcriptions — OpenAI Whisper, Groq, faster-whisper-server и др."""

    def __init__(self, p: STTParams, client: httpx.AsyncClient, semaphore: asyncio.Semaphore):
        self._p = p
        self._client = client
        self._sem = semaphore

    async def transcribe(self, audio: bytes, filename: str, content_type: str, language: str | None) -> str:
        p = self._p
        data = {"model": p.model, "response_format": "json"}
        if language:
            data["language"] = language
        resp = await call_with_retries(
            "stt",
            lambda: self._client.post(
                f"{p.base_url}/audio/transcriptions",
                headers={"Authorization": f"Bearer {p.api_key}"},
                data=data,
                files={"file": (filename, audio, content_type)},
                timeout=p.timeout_s,
            ),
            max_retries=p.max_retries,
            semaphore=self._sem,
        )
        return str(json_or_error("stt", resp).get("text", "")).strip()


# SaluteSpeech поддерживает ограниченный набор языков; остальное распознаём как русский.
_SALUTE_LANGUAGES = {"ru": "ru-RU", "en": "en-US", "kk": "kk-KZ", "ky": "ky-KG", "uz": "uz-UZ"}


class SaluteSpeechSTT:
    """
    SaluteSpeech (Сбер), асинхронное распознавание: загрузка файла → задача → опрос статуса →
    скачивание результата. Синхронный метод ограничен минутой аудио, асинхронный — 1 ГБ.
    Формат AAC/M4A сервис не принимает, поэтому аудио перекодируется в WAV 16 кГц моно.
    """

    POLL_INTERVAL_S = 1.0
    MAX_WAIT_S = 300.0

    def __init__(self, p: STTParams, client: httpx.AsyncClient, semaphore: asyncio.Semaphore, tokens: SberTokenCache):
        self._p = p
        self._client = client
        self._sem = semaphore
        self._tokens = tokens

    async def _call(
        self, name: str, method: str, path: str, *, extra_headers: dict | None = None, **kwargs
    ) -> httpx.Response:
        p = self._p
        for attempt in range(2):
            token = await self._tokens.get(
                p.api_key,
                p.scope,
                self._client,
                semaphore=self._sem,
                timeout_s=p.timeout_s,
                max_retries=p.max_retries,
            )
            headers = {"Authorization": f"Bearer {token}", **(extra_headers or {})}
            try:
                return await call_with_retries(
                    name,
                    lambda h=headers: self._client.request(
                        method, f"{p.base_url}/{path}", headers=h, timeout=p.timeout_s, **kwargs
                    ),
                    max_retries=p.max_retries,
                    semaphore=self._sem,
                )
            except ProviderError as e:
                # Токен мог истечь раньше срока — получаем новый и пробуем ещё раз.
                if e.status == 401 and attempt == 0:
                    self._tokens.invalidate(p.api_key, p.scope)
                    continue
                raise
        raise ProviderError(f"{name}: не удалось авторизоваться", status=401)

    @staticmethod
    def _result(name: str, resp: httpx.Response) -> dict:
        data = json_or_error(name, resp)
        result = data.get("result")
        if not isinstance(result, dict):
            raise ProviderError(f"{name}: неожиданный формат ответа")
        return result

    async def transcribe(self, audio: bytes, filename: str, content_type: str, language: str | None) -> str:
        wav = await to_wav_16k_mono(audio)

        upload = await self._call(
            "salute-upload",
            "POST",
            "data:upload",
            content=wav,
            extra_headers={"Content-Type": "application/octet-stream"},
        )
        file_id = self._result("salute-upload", upload).get("request_file_id")
        if not file_id:
            raise ProviderError("salute-upload: нет request_file_id")

        options = {
            "language": _SALUTE_LANGUAGES.get((language or "ru")[:2], "ru-RU"),
            "audio_encoding": "PCM_S16LE",
            "sample_rate": 16000,
            "channels_count": 1,
            "hypotheses_count": 1,
            "enable_profanity_filter": False,
            "max_speech_timeout": "20s",
            "no_speech_timeout": "7s",
            "hints": {},
            "insight_models": [],
            "speaker_separation_options": {},
        }
        task = await self._call(
            "salute-recognize",
            "POST",
            "speech:async_recognize",
            json={"options": options, "request_file_id": file_id},
        )
        task_id = self._result("salute-recognize", task).get("id")
        if not task_id:
            raise ProviderError("salute-recognize: нет id задачи")

        deadline = time.monotonic() + self.MAX_WAIT_S
        while True:
            status = self._result(
                "salute-task", await self._call("salute-task", "GET", "task:get", params={"id": task_id})
            )
            state = status.get("status")
            if state == "DONE":
                response_file_id = status.get("response_file_id")
                break
            if state in ("ERROR", "CANCELED"):
                raise ProviderError(f"salute-task: {state}: {str(status.get('error', ''))[:200]}")
            if time.monotonic() > deadline:
                raise ProviderError("salute-task: превышено время ожидания", retryable=False)
            await asyncio.sleep(self.POLL_INTERVAL_S)

        result = await self._call(
            "salute-download", "GET", "data:download", params={"response_file_id": response_file_id}
        )
        return parse_salute_result(result.text)


def parse_salute_result(raw: str) -> str:
    """Результат — JSON-массив фраз; текст берётся из results[0].normalized_text."""
    try:
        items = json.loads(raw)
    except json.JSONDecodeError as e:
        raise ProviderError("salute-download: результат не JSON") from e
    if not isinstance(items, list):
        raise ProviderError("salute-download: неожиданный формат результата")
    texts: list[str] = []
    for item in items:
        results = item.get("results") if isinstance(item, dict) else None
        if isinstance(results, list) and results and isinstance(results[0], dict):
            t = str(results[0].get("normalized_text") or results[0].get("text") or "").strip()
            if t:
                texts.append(t)
    return " ".join(texts).strip()


class MockSTT:
    """Заглушка без внешних запросов — для разработки и тестов."""

    async def transcribe(self, audio: bytes, filename: str, content_type: str, language: str | None) -> str:
        return (
            "сегодня мы поговорим о том как устроены базы данных и зачем нужны индексы "
            "индекс это структура данных которая ускоряет поиск"
        )
