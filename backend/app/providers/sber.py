"""Общая часть API Сбера (GigaChat и SaluteSpeech): OAuth-токены по ключу авторизации."""

import asyncio
import hashlib
import time
import uuid

import httpx

from app.providers.base import ProviderError, call_with_retries, json_or_error
from app.providers.catalog import SBER_OAUTH_URL

# Токен живёт 30 минут; обновляем заранее.
_REFRESH_MARGIN_MS = 60_000
_MAX_CACHED = 2000


class SberTokenCache:
    """
    Кэш access-токенов в памяти процесса. Ключом служит SHA-256 от ключа авторизации
    и scope — сам ключ пользователя нигде не сохраняется.
    """

    def __init__(self) -> None:
        self._tokens: dict[str, tuple[str, int]] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    @staticmethod
    def _cache_key(auth_key: str, scope: str) -> str:
        return hashlib.sha256(f"{scope}:{auth_key}".encode()).hexdigest()

    def invalidate(self, auth_key: str, scope: str) -> None:
        self._tokens.pop(self._cache_key(auth_key, scope), None)

    async def get(
        self,
        auth_key: str,
        scope: str,
        client: httpx.AsyncClient,
        *,
        semaphore: asyncio.Semaphore,
        timeout_s: float,
        max_retries: int,
    ) -> str:
        ck = self._cache_key(auth_key, scope)
        now_ms = int(time.time() * 1000)
        cached = self._tokens.get(ck)
        if cached and cached[1] - _REFRESH_MARGIN_MS > now_ms:
            return cached[0]

        lock = self._locks.setdefault(ck, asyncio.Lock())
        async with lock:
            cached = self._tokens.get(ck)
            if cached and cached[1] - _REFRESH_MARGIN_MS > int(time.time() * 1000):
                return cached[0]
            resp = await call_with_retries(
                "sber-oauth",
                lambda: client.post(
                    SBER_OAUTH_URL,
                    headers={
                        "Authorization": f"Basic {auth_key}",
                        "RqUID": str(uuid.uuid4()),
                        "Accept": "application/json",
                    },
                    data={"scope": scope},
                    timeout=timeout_s,
                ),
                max_retries=max_retries,
                semaphore=semaphore,
            )
            data = json_or_error("sber-oauth", resp)
            token = data.get("access_token")
            expires = data.get("expires_at")
            if not isinstance(token, str) or not isinstance(expires, int | float):
                raise ProviderError("sber-oauth: в ответе нет токена")
            expires_ms = int(expires) if expires > 10**12 else int(expires * 1000)
            if len(self._tokens) >= _MAX_CACHED:
                self._tokens.clear()
                self._locks.clear()
            self._tokens[ck] = (token, expires_ms)
            return token
