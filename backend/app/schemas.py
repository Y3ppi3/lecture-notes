from typing import Literal

from pydantic import BaseModel, EmailStr, Field, SecretStr


class Credentials(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=128)


class RefreshRequest(BaseModel):
    refresh_token: str = Field(min_length=20, max_length=200)


class DeleteAccountRequest(BaseModel):
    password: str = Field(min_length=1, max_length=128)


class TokenPair(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"  # noqa: S105
    expires_in: int


class UserOut(BaseModel):
    id: str
    email: str


class UsageOut(BaseModel):
    audio_mb_used: float
    audio_mb_limit: int
    correct_chars_used: int
    correct_chars_limit: int


# ---------- ключи нейросетей пользователя ----------


class ProviderCredentials(BaseModel):
    """Ключ нейросети, который пользователь указал в приложении. На сервере не сохраняется."""

    # id из каталога (GET /api/ai/providers) или "auto" — определить по формату ключа.
    provider: str = Field(pattern=r"^[a-z0-9_-]{2,32}$")
    api_key: SecretStr = Field(min_length=8, max_length=4096)
    model: str | None = Field(None, max_length=200, pattern=r"^[\w.:/@+-]+$")
    # Только для провайдера "custom" (произвольный OpenAI-совместимый API).
    base_url: str | None = Field(None, max_length=500)
    # Тип доступа для API Сбера, например GIGACHAT_API_PERS.
    scope: str | None = Field(None, max_length=40, pattern=r"^[A-Z_]+$")


class AIConfig(BaseModel):
    llm: ProviderCredentials | None = None
    stt: ProviderCredentials | None = None


class CheckKeyRequest(BaseModel):
    kind: Literal["llm", "stt"]
    credentials: ProviderCredentials


class CheckKeyResponse(BaseModel):
    ok: bool
    provider: str | None = None
    title: str | None = None
    model: str | None = None
    message: str


# ---------- ИИ ----------


class CorrectRequest(BaseModel):
    text: str = Field(min_length=1, description="Текст для коррекции")
    language: str | None = Field(None, pattern=r"^[a-z]{2}$")
    title: str | None = Field(None, max_length=200)
    previous_text: str | None = Field(None, max_length=5000)
    ai_config: AIConfig | None = None


class TextResponse(BaseModel):
    text: str


class ProcessResponse(BaseModel):
    raw_text: str
    corrected_text: str


class HealthResponse(BaseModel):
    status: str
