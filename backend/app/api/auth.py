import logging
import uuid
from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from sqlalchemy import delete, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import AppState, client_ip, current_user, enforce_limit, get_db, get_state
from app.core.logging import log_extra
from app.core.security import (
    create_access_token,
    hash_password,
    hash_token,
    new_refresh_token,
    password_needs_rehash,
    verify_password,
)
from app.db.models import RefreshToken, User
from app.schemas import Credentials, DeleteAccountRequest, RefreshRequest, TokenPair, UserOut

router = APIRouter(prefix="/api/auth", tags=["auth"])
log = logging.getLogger("app.auth")


def _as_utc(dt: datetime) -> datetime:
    # SQLite возвращает naive datetime — приводим к UTC для сравнения.
    return dt if dt.tzinfo else dt.replace(tzinfo=UTC)


async def _issue_tokens(
    db: AsyncSession, state: AppState, user_id: uuid.UUID, family_id: uuid.UUID | None = None
) -> TokenPair:
    access, expires_in = create_access_token(user_id, state.settings)
    refresh = new_refresh_token()
    db.add(
        RefreshToken(
            user_id=user_id,
            family_id=family_id or uuid.uuid4(),
            token_hash=hash_token(refresh),
            expires_at=datetime.now(UTC) + timedelta(days=state.settings.refresh_token_ttl_days),
        )
    )
    await db.commit()
    return TokenPair(access_token=access, refresh_token=refresh, expires_in=expires_in)


@router.post("/register", response_model=TokenPair, status_code=201)
async def register(
    body: Credentials,
    request: Request,
    state: AppState = Depends(get_state),
    db: AsyncSession = Depends(get_db),
) -> TokenPair:
    if not state.settings.registration_enabled:
        raise HTTPException(403, "Регистрация закрыта")
    ip = client_ip(request, state.settings)
    await enforce_limit(
        state,
        f"register:{ip}",
        state.settings.registrations_per_hour,
        3600,
        "Слишком много регистраций, попробуйте позже",
    )
    email = body.email.lower()
    user = User(email=email, password_hash=hash_password(body.password))
    db.add(user)
    try:
        await db.flush()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "Пользователь с таким email уже зарегистрирован") from None
    log.info("user_registered", **log_extra(user_id=str(user.id)))
    return await _issue_tokens(db, state, user.id)


@router.post("/login", response_model=TokenPair)
async def login(
    body: Credentials,
    request: Request,
    state: AppState = Depends(get_state),
    db: AsyncSession = Depends(get_db),
) -> TokenPair:
    email = body.email.lower()
    ip = client_ip(request, state.settings)
    limit = state.settings.login_attempts_per_minute
    await enforce_limit(state, f"login-ip:{ip}", limit, 60, "Слишком много попыток входа, подождите минуту")
    await enforce_limit(state, f"login-email:{email}", limit, 60, "Слишком много попыток входа, подождите минуту")

    user = (await db.execute(select(User).where(User.email == email))).scalar_one_or_none()
    # verify_password выполняется и для несуществующего пользователя — одинаковое время ответа.
    ok = verify_password(body.password, user.password_hash if user else None)
    if not ok or user is None or not user.is_active:
        log.info("login_failed")
        raise HTTPException(401, "Неверный email или пароль")

    if password_needs_rehash(user.password_hash):
        user.password_hash = hash_password(body.password)
    # Чистим просроченные токены пользователя, чтобы таблица не росла бесконечно.
    await db.execute(
        delete(RefreshToken).where(RefreshToken.user_id == user.id, RefreshToken.expires_at < datetime.now(UTC))
    )
    return await _issue_tokens(db, state, user.id)


@router.post("/refresh", response_model=TokenPair)
async def refresh(
    body: RefreshRequest,
    state: AppState = Depends(get_state),
    db: AsyncSession = Depends(get_db),
) -> TokenPair:
    invalid = HTTPException(401, "Сессия истекла, войдите снова")
    stmt = select(RefreshToken).where(RefreshToken.token_hash == hash_token(body.refresh_token))
    if db.bind.dialect.name == "postgresql":
        stmt = stmt.with_for_update()
    token = (await db.execute(stmt)).scalar_one_or_none()
    if token is None or token.revoked_at is not None:
        raise invalid

    now = datetime.now(UTC)
    if token.used_at is not None:
        # Повторное использование уже обменянного токена — признак кражи: отзываем всю сессию.
        await db.execute(
            update(RefreshToken)
            .where(RefreshToken.family_id == token.family_id, RefreshToken.revoked_at.is_(None))
            .values(revoked_at=now)
        )
        await db.commit()
        log.warning("refresh_token_reuse", **log_extra(user_id=str(token.user_id)))
        raise invalid
    if _as_utc(token.expires_at) <= now:
        raise invalid

    user = await db.get(User, token.user_id)
    if user is None or not user.is_active:
        raise invalid
    token.used_at = now
    return await _issue_tokens(db, state, token.user_id, token.family_id)


@router.post("/logout", status_code=204)
async def logout(body: RefreshRequest, db: AsyncSession = Depends(get_db)) -> Response:
    token = (
        await db.execute(select(RefreshToken).where(RefreshToken.token_hash == hash_token(body.refresh_token)))
    ).scalar_one_or_none()
    if token is not None:
        await db.execute(
            update(RefreshToken)
            .where(RefreshToken.family_id == token.family_id, RefreshToken.revoked_at.is_(None))
            .values(revoked_at=datetime.now(UTC))
        )
        await db.commit()
    return Response(status_code=204)


@router.get("/me", response_model=UserOut)
async def me(user: User = Depends(current_user)) -> UserOut:
    return UserOut(id=str(user.id), email=user.email)


@router.delete("/me", status_code=204)
async def delete_account(
    body: DeleteAccountRequest,
    user: User = Depends(current_user),
    db: AsyncSession = Depends(get_db),
) -> Response:
    """Удаление аккаунта со всеми токенами и статистикой. Требует пароль."""
    if not verify_password(body.password, user.password_hash):
        raise HTTPException(403, "Неверный пароль")
    await db.delete(user)
    await db.commit()
    log.info("user_deleted", **log_extra(user_id=str(user.id)))
    return Response(status_code=204)
