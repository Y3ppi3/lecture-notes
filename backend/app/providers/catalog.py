"""
Каталог поддерживаемых ИИ-провайдеров.

Чтобы добавить новый сервис с OpenAI-совместимым API, достаточно дописать его сюда:
приложения получают список через GET /api/ai/providers и строят экран настроек сами.
"""

import base64
import binascii
import re
import uuid
from dataclasses import asdict, dataclass, field
from typing import Literal

Kind = Literal["llm", "stt"]
# Протокол определяет, каким кодом выполняется запрос.
Protocol = Literal["openai", "anthropic", "gigachat", "salutespeech"]

SBER_OAUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"


@dataclass(frozen=True)
class ProviderInfo:
    id: str
    title: str
    kind: Kind
    protocol: Protocol
    base_url: str
    default_model: str
    # Подсказка для поля ключа в приложении.
    key_hint: str
    # Пользователь сам указывает адрес API (произвольный OpenAI-совместимый сервис).
    custom_base_url: bool = False
    # У сервисов Сбера есть тип доступа (scope): физлицо / ИП и юрлица.
    scopes: tuple[str, ...] = ()
    models: tuple[str, ...] = field(default_factory=tuple)

    def public(self) -> dict:
        d = asdict(self)
        d.pop("protocol")
        d["scopes"] = list(self.scopes)
        d["models"] = list(self.models)
        return d


GIGACHAT_SCOPES = ("GIGACHAT_API_PERS", "GIGACHAT_API_B2B", "GIGACHAT_API_CORP")
SALUTE_SCOPES = ("SALUTE_SPEECH_PERS", "SALUTE_SPEECH_CORP", "SALUTE_SPEECH_B2B")

LLM_PROVIDERS: tuple[ProviderInfo, ...] = (
    ProviderInfo(
        "gigachat",
        "GigaChat (Сбер)",
        "llm",
        "gigachat",
        "https://api.giga.chat/v1",
        "GigaChat-2",
        "Ключ авторизации из личного кабинета developers.sber.ru",
        scopes=GIGACHAT_SCOPES,
        models=("GigaChat-2", "GigaChat-2-Pro", "GigaChat-2-Max"),
    ),
    ProviderInfo(
        "openai",
        "ChatGPT (OpenAI)",
        "llm",
        "openai",
        "https://api.openai.com/v1",
        "gpt-4o-mini",
        "Ключ вида sk-...",
        models=("gpt-4o-mini", "gpt-4o"),
    ),
    ProviderInfo(
        "anthropic",
        "Claude (Anthropic)",
        "llm",
        "anthropic",
        "https://api.anthropic.com/v1",
        "claude-haiku-5-5",
        "Ключ вида sk-ant-...",
        models=("claude-haiku-5-5", "claude-sonnet-5-5"),
    ),
    ProviderInfo(
        "gemini",
        "Gemini (Google)",
        "llm",
        "openai",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        "gemini-2.5-flash",
        "Ключ вида AIza...",
    ),
    ProviderInfo(
        "deepseek",
        "DeepSeek",
        "llm",
        "openai",
        "https://api.deepseek.com/v1",
        "deepseek-chat",
        "Ключ вида sk-...",
    ),
    ProviderInfo(
        "openrouter",
        "OpenRouter (сотни моделей)",
        "llm",
        "openai",
        "https://openrouter.ai/api/v1",
        "openai/gpt-4o-mini",
        "Ключ вида sk-or-...",
    ),
    ProviderInfo(
        "mistral",
        "Mistral",
        "llm",
        "openai",
        "https://api.mistral.ai/v1",
        "mistral-small-latest",
        "Ключ Mistral",
    ),
    ProviderInfo(
        "groq",
        "Groq",
        "llm",
        "openai",
        "https://api.groq.com/openai/v1",
        "llama-3.3-70b-versatile",
        "Ключ вида gsk_...",
    ),
    ProviderInfo(
        "custom",
        "Другой (OpenAI-совместимый API)",
        "llm",
        "openai",
        "",
        "",
        "Ключ сервиса; укажите адрес API и модель",
        custom_base_url=True,
    ),
)

STT_PROVIDERS: tuple[ProviderInfo, ...] = (
    ProviderInfo(
        "salutespeech",
        "SaluteSpeech (Сбер)",
        "stt",
        "salutespeech",
        "https://smartspeech.sber.ru/rest/v1",
        "",
        "Ключ авторизации SaluteSpeech из developers.sber.ru",
        scopes=SALUTE_SCOPES,
    ),
    ProviderInfo(
        "openai",
        "Whisper (OpenAI)",
        "stt",
        "openai",
        "https://api.openai.com/v1",
        "whisper-1",
        "Ключ вида sk-...",
    ),
    ProviderInfo(
        "groq",
        "Whisper (Groq)",
        "stt",
        "openai",
        "https://api.groq.com/openai/v1",
        "whisper-large-v3-turbo",
        "Ключ вида gsk_...",
    ),
    ProviderInfo(
        "custom",
        "Другой (OpenAI-совместимый /audio/transcriptions)",
        "stt",
        "openai",
        "",
        "whisper-1",
        "Ключ сервиса; укажите адрес API",
        custom_base_url=True,
    ),
)

_BY_KIND: dict[str, dict[str, ProviderInfo]] = {
    "llm": {p.id: p for p in LLM_PROVIDERS},
    "stt": {p.id: p for p in STT_PROVIDERS},
}


def get_provider(kind: Kind, provider_id: str) -> ProviderInfo | None:
    return _BY_KIND[kind].get(provider_id)


def providers(kind: Kind) -> tuple[ProviderInfo, ...]:
    return LLM_PROVIDERS if kind == "llm" else STT_PROVIDERS


def is_sber_authorization_key(key: str) -> bool:
    """Ключ авторизации Сбера — base64 от «client_id:client_secret», оба UUID."""
    try:
        decoded = base64.b64decode(key, validate=True).decode()
    except (binascii.Error, UnicodeDecodeError, ValueError):
        return False
    parts = decoded.split(":")
    if len(parts) != 2:
        return False
    try:
        uuid.UUID(parts[0])
        uuid.UUID(parts[1])
    except ValueError:
        return False
    return True


_DEEPSEEK_RE = re.compile(r"^sk-[0-9a-f]{32}$")


def detect_provider(kind: Kind, key: str) -> str | None:
    """Определяет провайдера по формату ключа. None — формат не узнан."""
    k = key.strip()
    if is_sber_authorization_key(k):
        return "gigachat" if kind == "llm" else "salutespeech"
    if k.startswith("gsk_"):
        return "groq"
    if kind == "stt":
        return "openai" if k.startswith("sk-") and not k.startswith(("sk-ant-", "sk-or-")) else None
    if k.startswith("sk-ant-"):
        return "anthropic"
    if k.startswith("sk-or-"):
        return "openrouter"
    if k.startswith("AIza"):
        return "gemini"
    if _DEEPSEEK_RE.match(k):
        return "deepseek"
    if k.startswith("sk-"):
        return "openai"
    return None
