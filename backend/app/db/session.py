from collections.abc import AsyncIterator

from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import Settings


def create_engine(settings: Settings) -> AsyncEngine:
    kwargs: dict = {"pool_pre_ping": True}
    if not settings.database_url.startswith("sqlite"):
        kwargs.update(pool_size=settings.db_pool_size, max_overflow=settings.db_max_overflow, pool_recycle=1800)
    return create_async_engine(settings.database_url, **kwargs)


def create_sessionmaker(engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(engine, expire_on_commit=False)


async def session_scope(maker: async_sessionmaker[AsyncSession]) -> AsyncIterator[AsyncSession]:
    async with maker() as session:
        yield session
