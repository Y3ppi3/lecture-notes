import asyncio
import logging
import ssl
from contextlib import asynccontextmanager
from pathlib import Path

import certifi
import httpx
from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import text
from starlette.middleware.trustedhost import TrustedHostMiddleware

from app.api import ai, auth, demo
from app.api.deps import AppState, get_state
from app.core.config import Settings, get_settings
from app.core.logging import setup_logging
from app.core.middleware import MaxBodySizeMiddleware, RequestContextMiddleware
from app.core.ratelimit import build_rate_limiter
from app.db.base import Base
from app.db.session import create_engine, create_sessionmaker
from app.providers.base import ProviderError, public_message
from app.providers.gateway import AIGateway
from app.schemas import HealthResponse

log = logging.getLogger("app")

DOCS_PATHS = ("/docs", "/redoc", "/openapi.json")
BACKEND_DIR = Path(__file__).resolve().parent.parent


def sber_ssl_context(settings: Settings) -> ssl.SSLContext:
    """Стандартные корневые сертификаты + сертификаты Минцифры (только для API Сбера)."""
    ctx = ssl.create_default_context(cafile=certifi.where())
    ca = Path(settings.sber_ca_file)
    if not ca.is_absolute():
        ca = BACKEND_DIR / ca
    if ca.exists():
        ctx.load_verify_locations(cafile=str(ca))
    else:
        log.warning("sber_ca_missing")
    return ctx


def create_app(settings: Settings | None = None, *, create_tables: bool = False) -> FastAPI:
    settings = settings or get_settings()
    setup_logging(settings.log_level)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        engine = create_engine(settings)
        if create_tables:
            async with engine.begin() as conn:
                await conn.run_sync(Base.metadata.create_all)
        # Один HTTP-клиент на процесс: пул соединений к провайдерам переиспользуется.
        limits = httpx.Limits(max_connections=50, max_keepalive_connections=20)
        client = httpx.AsyncClient(limits=limits, follow_redirects=False)
        sber_client = httpx.AsyncClient(limits=limits, follow_redirects=False, verify=sber_ssl_context(settings))
        semaphore = asyncio.Semaphore(settings.provider_concurrency)
        limiter = build_rate_limiter(settings.redis_url)
        app.state.ctx = AppState(
            settings=settings,
            sessionmaker=create_sessionmaker(engine),
            limiter=limiter,
            ai=AIGateway(settings, client, sber_client, semaphore),
        )
        log.info("startup")
        try:
            yield
        finally:
            await client.aclose()
            await sber_client.aclose()
            await limiter.close()
            await engine.dispose()

    docs = not settings.is_production
    app = FastAPI(
        title="Lecture Notes API",
        version="1.2.0",
        lifespan=lifespan,
        docs_url="/docs" if docs else None,
        redoc_url=None,
        openapi_url="/openapi.json" if docs else None,
    )

    # Порядок: последний добавленный middleware выполняется первым.
    app.add_middleware(GZipMiddleware, minimum_size=1024)
    if settings.cors_origin_list:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_origin_list,
            allow_methods=["GET", "POST", "DELETE"],
            allow_headers=["Authorization", "Content-Type", "X-Request-ID"],
            max_age=600,
        )
    # Запас 1 МБ на служебные поля multipart.
    app.add_middleware(MaxBodySizeMiddleware, max_bytes=(settings.max_audio_mb + 1) * 1024 * 1024)
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=settings.allowed_host_list)
    app.add_middleware(RequestContextMiddleware, hsts=settings.is_production, docs_paths=DOCS_PATHS)

    @app.exception_handler(ProviderError)
    async def provider_error(_: Request, exc: ProviderError) -> JSONResponse:
        return JSONResponse(status_code=502, content={"detail": public_message(exc, user_key=False)})

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
        # Не возвращаем клиенту присланные значения (в них может быть пароль).
        fields = sorted({".".join(str(p) for p in e.get("loc", ())[1:]) or "body" for e in exc.errors()})
        return JSONResponse(status_code=422, content={"detail": "Некорректные данные", "fields": fields})

    @app.exception_handler(Exception)
    async def unhandled(_: Request, exc: Exception) -> JSONResponse:
        log.exception("unhandled_error")
        return JSONResponse(status_code=500, content={"detail": "Внутренняя ошибка сервера"})

    @app.get("/api/health", response_model=HealthResponse, tags=["system"])
    async def health() -> HealthResponse:
        """Liveness: процесс жив."""
        return HealthResponse(status="ok")

    @app.get("/api/ready", response_model=HealthResponse, tags=["system"])
    async def ready(state: AppState = Depends(get_state)) -> JSONResponse:
        """Readiness: доступны БД и Redis."""
        try:
            async with state.sessionmaker() as s:
                await s.execute(text("SELECT 1"))
            db_ok = True
        except Exception:
            db_ok = False
        cache_ok = await state.limiter.ping()
        ok = db_ok and cache_ok
        return JSONResponse(status_code=200 if ok else 503, content={"status": "ok" if ok else "degraded"})

    app.include_router(auth.router)
    app.include_router(ai.router)
    app.include_router(demo.router)
    return app


def build() -> FastAPI:
    """Точка входа для uvicorn: `uvicorn app.main:build --factory`."""
    s = get_settings()
    return create_app(s, create_tables=s.environment == "development" and s.database_url.startswith("sqlite"))
