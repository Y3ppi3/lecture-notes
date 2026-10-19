import os
from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.providers.catalog import get_provider

DEV_SECRET = "dev-insecure-secret-change-me-0123456789"  # noqa: S105 — только для разработки


class Settings(BaseSettings):
    """Настройки сервера из переменных окружения / файла .env."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    environment: Literal["development", "test", "production"] = "development"
    log_level: str = "INFO"

    # --- База данных и кэш ---
    database_url: str = "sqlite+aiosqlite:///./dev.db"
    db_pool_size: int = 10
    db_max_overflow: int = 10
    # Пусто — лимиты считаются в памяти процесса (только для разработки и тестов).
    redis_url: str = ""

    # --- Аутентификация ---
    jwt_secret: SecretStr = SecretStr(DEV_SECRET)
    jwt_issuer: str = "lecture-notes"
    access_token_ttl_min: int = 15
    refresh_token_ttl_days: int = 30
    # Открытая регистрация. Для закрытого запуска выключите и создавайте пользователей скриптом.
    registration_enabled: bool = True

    # --- HTTP ---
    # Через запятую. Мобильным приложениям CORS не нужен; пусто = CORS выключен.
    cors_origins: str = ""
    # Разрешённые значения заголовка Host (через запятую). "*" — без проверки.
    allowed_hosts: str = "*"
    # Сколько прокси стоит перед приложением (для определения IP клиента из X-Forwarded-For).
    trusted_proxy_count: int = 0

    # --- Ключи сервера (используются, если пользователь не указал свой ключ) ---
    # Провайдер — id из каталога (app/providers/catalog.py), "mock" (заглушка) или "none" (нет ключа сервера).
    stt_provider: str = "mock"
    stt_api_key: SecretStr = SecretStr("")
    stt_model: str = ""
    stt_base_url: str = ""
    stt_scope: str = ""

    llm_provider: str = "mock"
    llm_api_key: SecretStr = SecretStr("")
    llm_model: str = ""
    llm_base_url: str = ""
    llm_scope: str = ""
    llm_max_tokens: int = 4096
    llm_temperature: float = 0.2

    # --- Ключи пользователей ---
    # Разрешить пользователям подключать свои ключи нейросетей в приложении.
    allow_user_keys: bool = True
    # Разрешить произвольный адрес OpenAI-совместимого API (провайдер "custom").
    allow_custom_base_url: bool = True

    # --- Страница прототипа ---
    # Ссылки на APK для страницы скачивания (GET /). Пусто — кнопка не показывается.
    demo_apk_url: str = ""
    demo_apk_rn_url: str = ""

    # Файл с корневыми сертификатами Минцифры — нужен для API Сбера (GigaChat, SaluteSpeech).
    sber_ca_file: str = "certs/russian_trusted_ca.pem"

    provider_timeout_s: float = 120.0
    provider_max_retries: int = 2
    # Одновременных запросов к внешним ИИ-сервисам на один процесс.
    provider_concurrency: int = 8

    # --- Ограничения ---
    default_language: str = "ru"
    max_audio_mb: int = 25
    max_text_chars: int = 30_000
    # Запросов к ИИ-эндпоинтам на пользователя в минуту.
    rate_limit_per_minute: int = 30
    # Попыток входа на IP в минуту.
    login_attempts_per_minute: int = 10
    # Регистраций с одного IP в час.
    registrations_per_hour: int = 5
    # Суточная квота на пользователя при работе на ключах сервера (на своих ключах не ограничивается).
    daily_audio_mb: int = 200
    daily_correct_chars: int = 500_000

    password_min_length: int = Field(default=8, ge=8)

    @property
    def is_production(self) -> bool:
        return self.environment == "production"

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]

    @property
    def allowed_host_list(self) -> list[str]:
        return [h.strip() for h in self.allowed_hosts.split(",") if h.strip()] or ["*"]

    @field_validator("database_url")
    @classmethod
    def _async_driver(cls, v: str) -> str:
        """Хостинги (Render, Heroku и др.) выдают postgres:// — переводим на асинхронный драйвер."""
        for prefix in ("postgres://", "postgresql://"):
            if v.startswith(prefix):
                return "postgresql+asyncpg://" + v[len(prefix) :]
        return v

    @field_validator("allowed_hosts")
    @classmethod
    def _render_host(cls, v: str) -> str:
        """На Render адрес сервиса известен из RENDER_EXTERNAL_HOSTNAME — используем его, если хосты не заданы."""
        host = os.environ.get("RENDER_EXTERNAL_HOSTNAME", "")
        if v.strip() in ("", "*") and host:
            return f"{host},localhost,127.0.0.1"
        return v

    @model_validator(mode="after")
    def _validate(self) -> "Settings":
        if not self.jwt_secret.get_secret_value():
            if self.is_production:
                raise ValueError("JWT_SECRET не задан")
            self.jwt_secret = SecretStr(DEV_SECRET)

        problems: list[str] = []
        for kind, provider, key in (
            ("stt", self.stt_provider, self.stt_api_key),
            ("llm", self.llm_provider, self.llm_api_key),
        ):
            name = f"{kind.upper()}_PROVIDER"
            if provider in ("mock", "none"):
                if provider == "mock" and self.is_production:
                    problems.append(f"{name}=mock недопустим в продакшене")
                continue
            if get_provider(kind, provider) is None:  # type: ignore[arg-type]
                problems.append(f"{name}: неизвестный провайдер «{provider}»")
            elif not key.get_secret_value():
                problems.append(f"{kind.upper()}_API_KEY не задан для {name}={provider}")
        if self.is_production and not self.allow_user_keys and "none" in (self.stt_provider, self.llm_provider):
            problems.append("Без ключей сервера нужно разрешить ключи пользователей (ALLOW_USER_KEYS=true)")

        if self.is_production:
            secret = self.jwt_secret.get_secret_value()
            if len(secret) < 32 or "insecure" in secret:
                problems.append("JWT_SECRET должен быть случайной строкой длиной от 32 символов")
            if self.database_url.startswith("sqlite"):
                problems.append("DATABASE_URL: в продакшене нужен PostgreSQL")
            if not self.redis_url:
                problems.append("REDIS_URL обязателен в продакшене (лимиты между процессами)")
            for url in (self.demo_apk_url, self.demo_apk_rn_url):
                if url and not url.startswith("https://"):
                    problems.append("DEMO_APK_URL должен начинаться с https://")
            if "*" in self.cors_origin_list:
                problems.append("CORS_ORIGINS='*' недопустим в продакшене")
        if problems:
            raise ValueError("Небезопасная или неполная конфигурация: " + "; ".join(problems))
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
