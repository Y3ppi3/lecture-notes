"""
Выбор провайдера для запроса: ключ пользователя из приложения или ключ сервера.
"""

import asyncio
from dataclasses import dataclass

import httpx
from fastapi import HTTPException

from app.core.config import Settings
from app.providers.base import SpeechToText, TextCorrector, call_with_retries
from app.providers.catalog import Kind, ProviderInfo, detect_provider, get_provider
from app.providers.llm import AnthropicLLM, GigaChatLLM, LLMParams, MockLLM, OpenAICompatibleLLM
from app.providers.sber import SberTokenCache
from app.providers.stt import MockSTT, OpenAICompatibleSTT, SaluteSpeechSTT, STTParams
from app.schemas import ProviderCredentials
from app.services.netguard import validate_public_base_url

SBER_PROTOCOLS = ("gigachat", "salutespeech")


@dataclass(frozen=True)
class Resolved:
    """Провайдер, готовый к запросу. info=None — заглушка (mock)."""

    info: ProviderInfo | None
    api_key: str
    model: str
    base_url: str
    scope: str
    user_key: bool

    @property
    def title(self) -> str:
        return self.info.title if self.info else "Тестовая заглушка"


class AIGateway:
    def __init__(
        self,
        settings: Settings,
        client: httpx.AsyncClient,
        sber_client: httpx.AsyncClient,
        semaphore: asyncio.Semaphore,
    ):
        self._s = settings
        self._client = client
        self._sber = sber_client
        self._sem = semaphore
        self._tokens = SberTokenCache()

    # ---------- выбор провайдера ----------

    def _server(self, kind: Kind) -> Resolved | None:
        s = self._s
        provider = s.llm_provider if kind == "llm" else s.stt_provider
        if provider == "mock":
            return Resolved(None, "", "", "", "", user_key=False)
        if provider == "none":
            what = "коррекции текста" if kind == "llm" else "распознавания речи"
            raise HTTPException(
                400, f"На сервере не настроена нейросеть для {what} — укажите свой ключ в настройках приложения"
            )
        info = get_provider(kind, provider)
        if info is None:  # проверяется при загрузке настроек, сюда не попадаем
            raise HTTPException(500, "Неверная настройка провайдера на сервере")
        key = (s.llm_api_key if kind == "llm" else s.stt_api_key).get_secret_value()
        model = (s.llm_model if kind == "llm" else s.stt_model) or info.default_model
        base_url = ((s.llm_base_url if kind == "llm" else s.stt_base_url) or info.base_url).rstrip("/")
        scope = (s.llm_scope if kind == "llm" else s.stt_scope) or (info.scopes[0] if info.scopes else "")
        return Resolved(info, key, model, base_url, scope, user_key=False)

    async def resolve(self, kind: Kind, creds: ProviderCredentials | None) -> Resolved:
        if creds is None:
            return self._server(kind)  # type: ignore[return-value]
        if not self._s.allow_user_keys:
            raise HTTPException(403, "Сервер не принимает собственные ключи нейросетей")

        key = creds.api_key.get_secret_value().strip()
        provider_id = creds.provider
        if provider_id == "auto":
            provider_id = detect_provider(kind, key) or ""
            if not provider_id:
                raise HTTPException(400, "Не удалось определить сервис по ключу — выберите его в списке")
        info = get_provider(kind, provider_id)
        if info is None:
            raise HTTPException(400, "Неизвестный сервис нейросети")

        if info.custom_base_url:
            if not self._s.allow_custom_base_url:
                raise HTTPException(403, "Сервер не разрешает произвольные адреса API")
            if not creds.base_url:
                raise HTTPException(400, "Укажите адрес API")
            base_url = await validate_public_base_url(creds.base_url, allow_http=self._s.environment == "development")
        else:
            base_url = info.base_url

        model = (creds.model or info.default_model).strip()
        if kind == "llm" and not model:
            raise HTTPException(400, "Укажите модель")
        scope = creds.scope if creds.scope in info.scopes else (info.scopes[0] if info.scopes else "")
        return Resolved(info, key, model, base_url, scope, user_key=True)

    # ---------- клиенты ----------

    def _http(self, r: Resolved) -> httpx.AsyncClient:
        # Сертификаты Минцифры доверяем только для API Сбера, а не для всех внешних запросов.
        return self._sber if r.info and r.info.protocol in SBER_PROTOCOLS else self._client

    def llm(self, r: Resolved) -> TextCorrector:
        if r.info is None:
            return MockLLM()
        s = self._s
        p = LLMParams(
            base_url=r.base_url,
            api_key=r.api_key,
            model=r.model,
            max_tokens=s.llm_max_tokens,
            temperature=s.llm_temperature,
            timeout_s=s.provider_timeout_s,
            max_retries=s.provider_max_retries,
            scope=r.scope,
        )
        if r.info.protocol == "anthropic":
            return AnthropicLLM(p, self._http(r), self._sem)
        if r.info.protocol == "gigachat":
            return GigaChatLLM(p, self._http(r), self._sem, self._tokens)
        return OpenAICompatibleLLM(p, self._http(r), self._sem)

    def stt(self, r: Resolved) -> SpeechToText:
        if r.info is None:
            return MockSTT()
        s = self._s
        p = STTParams(
            base_url=r.base_url,
            api_key=r.api_key,
            model=r.model,
            timeout_s=s.provider_timeout_s,
            max_retries=s.provider_max_retries,
            scope=r.scope,
        )
        if r.info.protocol == "salutespeech":
            return SaluteSpeechSTT(p, self._http(r), self._sem, self._tokens)
        return OpenAICompatibleSTT(p, self._http(r), self._sem)

    # ---------- проверка ключа ----------

    async def check(self, r: Resolved) -> None:
        """Лёгкий запрос, подтверждающий, что ключ рабочий. Бросает ProviderError."""
        if r.info is None:
            return
        http = self._http(r)
        if r.info.protocol in SBER_PROTOCOLS:
            self._tokens.invalidate(r.api_key, r.scope)
            await self._tokens.get(
                r.api_key,
                r.scope,
                http,
                semaphore=self._sem,
                timeout_s=30,
                max_retries=0,
            )
            return
        if r.info.protocol == "anthropic":
            headers = {"x-api-key": r.api_key, "anthropic-version": "2023-06-01"}
        else:
            headers = {"Authorization": f"Bearer {r.api_key}"}
        await call_with_retries(
            "check",
            lambda: http.get(f"{r.base_url}/models", headers=headers, timeout=30),
            max_retries=0,
            semaphore=self._sem,
        )

    def server_status(self) -> dict:
        def one(kind: Kind) -> dict:
            provider = self._s.llm_provider if kind == "llm" else self._s.stt_provider
            if provider == "none":
                return {"available": False, "title": None}
            if provider == "mock":
                return {"available": True, "title": "Тестовая заглушка"}
            info = get_provider(kind, provider)
            return {"available": True, "title": info.title if info else provider}

        return {"llm": one("llm"), "stt": one("stt")}
