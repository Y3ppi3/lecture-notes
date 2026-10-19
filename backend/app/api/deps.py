from collections.abc import AsyncIterator
from dataclasses import dataclass

from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.core.config import Settings
from app.core.ratelimit import MemoryRateLimiter, RedisRateLimiter
from app.core.security import decode_access_token
from app.db.models import User
from app.providers.gateway import AIGateway


@dataclass
class AppState:
    settings: Settings
    sessionmaker: async_sessionmaker[AsyncSession]
    limiter: RedisRateLimiter | MemoryRateLimiter
    ai: AIGateway


def get_state(request: Request) -> AppState:
    return request.app.state.ctx


def get_settings_dep(state: AppState = Depends(get_state)) -> Settings:
    return state.settings


async def get_db(state: AppState = Depends(get_state)) -> AsyncIterator[AsyncSession]:
    async with state.sessionmaker() as session:
        yield session


def client_ip(request: Request, settings: Settings) -> str:
    """IP клиента. X-Forwarded-For учитывается только при известном числе доверенных прокси."""
    if settings.trusted_proxy_count > 0:
        forwarded = request.headers.get("x-forwarded-for", "")
        hops = [h.strip() for h in forwarded.split(",") if h.strip()]
        if len(hops) >= settings.trusted_proxy_count:
            return hops[-settings.trusted_proxy_count]
    return request.client.host if request.client else "unknown"


async def enforce_limit(state: AppState, key: str, limit: int, window_s: int, message: str) -> None:
    allowed, retry_after = await state.limiter.hit(key, limit, window_s)
    if not allowed:
        raise HTTPException(429, message, headers={"Retry-After": str(retry_after)})


_bearer = HTTPBearer(auto_error=False)


async def current_user(
    creds: HTTPAuthorizationCredentials | None = Depends(_bearer),
    state: AppState = Depends(get_state),
    db: AsyncSession = Depends(get_db),
) -> User:
    unauthorized = HTTPException(401, "Требуется вход", headers={"WWW-Authenticate": "Bearer"})
    if creds is None or creds.scheme.lower() != "bearer":
        raise unauthorized
    user_id = decode_access_token(creds.credentials, state.settings)
    if user_id is None:
        raise unauthorized
    user = await db.get(User, user_id)
    if user is None or not user.is_active:
        raise unauthorized
    return user


async def ai_rate_limit(user: User = Depends(current_user), state: AppState = Depends(get_state)) -> User:
    await enforce_limit(
        state,
        f"ai:{user.id}",
        state.settings.rate_limit_per_minute,
        60,
        "Слишком много запросов, подождите минуту",
    )
    return user
