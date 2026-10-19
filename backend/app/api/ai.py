import logging
import time
from collections.abc import Awaitable

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import AppState, ai_rate_limit, current_user, get_db, get_state
from app.core.logging import log_extra
from app.db.models import User
from app.providers.base import ProviderError, public_message
from app.providers.catalog import LLM_PROVIDERS, STT_PROVIDERS
from app.providers.gateway import Resolved
from app.schemas import (
    AIConfig,
    CheckKeyRequest,
    CheckKeyResponse,
    CorrectRequest,
    ProcessResponse,
    TextResponse,
    UsageOut,
)
from app.services.audio import read_audio
from app.services.quota import AUDIO_BYTES, CORRECT_CHARS, ensure_quota, record_usage, used_last_24h

router = APIRouter(prefix="/api", tags=["ai"])
log = logging.getLogger("app.ai")

MB = 1024 * 1024


def _form_language(value: str | None, default: str) -> str:
    v = (value or default).strip().lower()
    return v if len(v) == 2 and v.isalpha() else default


def _check_text(text: str, state: AppState) -> None:
    if len(text) > state.settings.max_text_chars:
        raise HTTPException(413, f"Текст длиннее {state.settings.max_text_chars} символов")


def _parse_ai_config(raw: str | None) -> AIConfig | None:
    """ai_config в multipart-запросе приходит JSON-строкой."""
    if not raw:
        return None
    if len(raw) > 16_384:
        raise HTTPException(413, "Слишком большие настройки нейросети")
    try:
        return AIConfig.model_validate_json(raw)
    except ValidationError:
        raise HTTPException(422, "Некорректные настройки нейросети") from None


async def _call[T](r: Resolved, awaitable: Awaitable[T]) -> T:
    """Ошибку провайдера превращаем в понятный ответ: для своего ключа — с подсказкой, что не так."""
    try:
        return await awaitable
    except ProviderError as e:
        raise HTTPException(502, public_message(e, r.user_key)) from e


async def _quota(db: AsyncSession, user: User, r: Resolved, kind: str, amount: int, limit: int) -> None:
    # Квоты защищают бюджет сервера; на своём ключе пользователь платит сам.
    if not r.user_key:
        await ensure_quota(db, user.id, kind, amount, limit)


async def _record(db: AsyncSession, user: User, r: Resolved, kind: str, amount: int) -> None:
    if not r.user_key:
        await record_usage(db, user.id, kind, amount)


@router.post("/transcribe", response_model=TextResponse)
async def transcribe(
    file: UploadFile = File(...),
    language: str | None = Form(None),
    ai_config: str | None = Form(None),
    user: User = Depends(ai_rate_limit),
    state: AppState = Depends(get_state),
    db: AsyncSession = Depends(get_db),
) -> TextResponse:
    """Только распознавание речи."""
    s = state.settings
    cfg = _parse_ai_config(ai_config)
    r = await state.ai.resolve("stt", cfg.stt if cfg else None)
    audio = await read_audio(file, s.max_audio_mb)
    await _quota(db, user, r, AUDIO_BYTES, len(audio.data), s.daily_audio_mb * MB)
    lang = _form_language(language, s.default_language)
    text = await _call(r, state.ai.stt(r).transcribe(audio.data, audio.filename, audio.content_type, lang))
    await _record(db, user, r, AUDIO_BYTES, len(audio.data))
    return TextResponse(text=text)


@router.post("/correct", response_model=TextResponse)
async def correct(
    body: CorrectRequest,
    user: User = Depends(ai_rate_limit),
    state: AppState = Depends(get_state),
    db: AsyncSession = Depends(get_db),
) -> TextResponse:
    """ИИ-коррекция готового текста."""
    s = state.settings
    _check_text(body.text, state)
    r = await state.ai.resolve("llm", body.ai_config.llm if body.ai_config else None)
    await _quota(db, user, r, CORRECT_CHARS, len(body.text), s.daily_correct_chars)
    started = time.perf_counter()
    text = await _call(
        r, state.ai.llm(r).correct(body.text, body.language or s.default_language, body.title, body.previous_text)
    )
    await _record(db, user, r, CORRECT_CHARS, len(body.text))
    elapsed_ms = round((time.perf_counter() - started) * 1000)
    log.info(
        "corrected",
        **log_extra(
            user_id=str(user.id),
            provider=r.info.id if r.info else "mock",
            own_key=r.user_key,
            chars=len(body.text),
            ms=elapsed_ms,
        ),
    )
    return TextResponse(text=text)


@router.post("/process", response_model=ProcessResponse)
async def process(
    file: UploadFile = File(...),
    language: str | None = Form(None),
    title: str | None = Form(None),
    previous_text: str | None = Form(None),
    correct_text: bool = Form(True),
    ai_config: str | None = Form(None),
    user: User = Depends(ai_rate_limit),
    state: AppState = Depends(get_state),
    db: AsyncSession = Depends(get_db),
) -> ProcessResponse:
    """Аудиофрагмент → распознавание → ИИ-коррекция за один запрос."""
    s = state.settings
    lang = _form_language(language, s.default_language)
    title = (title or "")[:200] or None
    previous_text = (previous_text or "")[-5000:] or None
    cfg = _parse_ai_config(ai_config)

    # Провайдеров проверяем до чтения аудио — ошибку настроек отдаём сразу.
    stt_r = await state.ai.resolve("stt", cfg.stt if cfg else None)
    llm_r = await state.ai.resolve("llm", cfg.llm if cfg else None) if correct_text else None

    audio = await read_audio(file, s.max_audio_mb)
    await _quota(db, user, stt_r, AUDIO_BYTES, len(audio.data), s.daily_audio_mb * MB)

    started = time.perf_counter()
    raw = await _call(stt_r, state.ai.stt(stt_r).transcribe(audio.data, audio.filename, audio.content_type, lang))
    await _record(db, user, stt_r, AUDIO_BYTES, len(audio.data))
    stt_ms = round((time.perf_counter() - started) * 1000)

    corrected = raw
    if raw.strip() and llm_r is not None:
        raw_for_llm = raw[: s.max_text_chars]
        await _quota(db, user, llm_r, CORRECT_CHARS, len(raw_for_llm), s.daily_correct_chars)
        corrected = await _call(llm_r, state.ai.llm(llm_r).correct(raw_for_llm, lang, title, previous_text))
        await _record(db, user, llm_r, CORRECT_CHARS, len(raw_for_llm))

    log.info(
        "processed",
        **log_extra(
            user_id=str(user.id),
            stt=stt_r.info.id if stt_r.info else "mock",
            llm=(llm_r.info.id if llm_r.info else "mock") if llm_r else None,
            own_keys=bool(stt_r.user_key or (llm_r and llm_r.user_key)),
            audio_kb=len(audio.data) // 1024,
            stt_ms=stt_ms,
            total_ms=round((time.perf_counter() - started) * 1000),
            chars=len(raw),
        ),
    )
    return ProcessResponse(raw_text=raw, corrected_text=corrected)


@router.get("/ai/providers")
async def ai_providers(_: User = Depends(current_user), state: AppState = Depends(get_state)) -> dict:
    """Каталог нейросетей для экрана настроек и то, что настроено на сервере."""
    s = state.settings
    return {
        "allow_user_keys": s.allow_user_keys,
        "allow_custom_base_url": s.allow_custom_base_url,
        "server": state.ai.server_status(),
        "llm": [p.public() for p in LLM_PROVIDERS if s.allow_custom_base_url or not p.custom_base_url],
        "stt": [p.public() for p in STT_PROVIDERS if s.allow_custom_base_url or not p.custom_base_url],
    }


@router.post("/ai/check", response_model=CheckKeyResponse)
async def ai_check(
    body: CheckKeyRequest,
    _: User = Depends(ai_rate_limit),
    state: AppState = Depends(get_state),
) -> CheckKeyResponse:
    """Проверяет ключ (и определяет сервис, если provider=auto). Ключ не сохраняется."""
    try:
        r = await state.ai.resolve(body.kind, body.credentials)
    except HTTPException as e:
        return CheckKeyResponse(ok=False, message=str(e.detail))
    info = r.info
    if info is None:  # ключ пользователя всегда даёт реального провайдера
        return CheckKeyResponse(ok=False, message="Неизвестный сервис нейросети")
    try:
        await state.ai.check(r)
    except ProviderError as e:
        return CheckKeyResponse(
            ok=False, provider=info.id, title=info.title, model=r.model, message=public_message(e, True)
        )
    return CheckKeyResponse(ok=True, provider=info.id, title=info.title, model=r.model, message="Ключ работает")


@router.get("/usage", response_model=UsageOut)
async def usage(
    user: User = Depends(current_user),
    state: AppState = Depends(get_state),
    db: AsyncSession = Depends(get_db),
) -> UsageOut:
    s = state.settings
    return UsageOut(
        audio_mb_used=round(await used_last_24h(db, user.id, AUDIO_BYTES) / MB, 1),
        audio_mb_limit=s.daily_audio_mb,
        correct_chars_used=await used_last_24h(db, user.id, CORRECT_CHARS),
        correct_chars_limit=s.daily_correct_chars,
    )
