import asyncio
import logging
import random
from collections.abc import Awaitable, Callable
from typing import Protocol

import httpx

from app.core.logging import log_extra

log = logging.getLogger("app.providers")


class ProviderError(Exception):
    """
    Ошибка внешнего ИИ-сервиса. Подробности пишутся только в лог сервера,
    клиенту уходит понятное сообщение без служебных данных (см. public_message).
    """

    def __init__(self, message: str, *, status: int | None = None, retryable: bool = False):
        super().__init__(message)
        self.status = status
        self.retryable = retryable


_USER_KEY_MESSAGES = {
    401: "Ключ не подошёл: сервис его отклонил",
    402: "У сервиса закончился баланс на этом ключе",
    403: "У ключа нет доступа к этой модели или сервису",
    404: "Модель или адрес API не найдены — проверьте настройки",
    429: "Превышен лимит запросов у сервиса нейросети, попробуйте позже",
}


def public_message(err: ProviderError, user_key: bool) -> str:
    """Текст ошибки для пользователя. Для своих ключей — подробнее: человек может их исправить."""
    if user_key and err.status in _USER_KEY_MESSAGES:
        return _USER_KEY_MESSAGES[err.status]
    if err.status in (400, 413, 415, 422):
        return "Сервис нейросети не смог обработать запрос"
    return "Сервис нейросети временно недоступен, попробуйте позже"


class SpeechToText(Protocol):
    async def transcribe(self, audio: bytes, filename: str, content_type: str, language: str | None) -> str: ...


class TextCorrector(Protocol):
    async def correct(self, text: str, language: str | None, title: str | None, previous_text: str | None) -> str: ...


RETRYABLE_STATUS = {408, 409, 425, 429, 500, 502, 503, 504, 529}


async def call_with_retries(
    name: str,
    make_request: Callable[[], Awaitable[httpx.Response]],
    *,
    max_retries: int,
    semaphore: asyncio.Semaphore,
) -> httpx.Response:
    """Запрос к провайдеру с ограничением параллелизма и экспоненциальным повтором."""
    attempt = 0
    while True:
        try:
            async with semaphore:
                resp = await make_request()
        except (httpx.TimeoutException, httpx.TransportError) as e:
            err = ProviderError(f"{name}: сеть: {type(e).__name__}", retryable=True)
        else:
            if resp.status_code < 400:
                return resp
            # Тело ответа — только в лог и усечённым; ключи провайдеры в ответах не возвращают.
            err = ProviderError(
                f"{name}: HTTP {resp.status_code}: {resp.text[:300]}",
                status=resp.status_code,
                retryable=resp.status_code in RETRYABLE_STATUS,
            )

        if not err.retryable or attempt >= max_retries:
            log.warning("provider_failed", **log_extra(provider=name, attempt=attempt, error=str(err)))
            raise err
        delay = min(8.0, 0.5 * 2**attempt) + random.uniform(0, 0.3)  # noqa: S311 — джиттер, не криптография
        log.info("provider_retry", **log_extra(provider=name, attempt=attempt, delay_s=round(delay, 2)))
        await asyncio.sleep(delay)
        attempt += 1


def json_or_error(name: str, resp: httpx.Response) -> dict:
    try:
        data = resp.json()
    except ValueError as e:
        raise ProviderError(f"{name}: ответ не JSON") from e
    if not isinstance(data, dict):
        raise ProviderError(f"{name}: неожиданный формат ответа")
    return data
