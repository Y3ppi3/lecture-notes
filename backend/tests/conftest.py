"""
По умолчанию тесты идут на SQLite и лимитере в памяти.
Чтобы прогнать их на настоящих PostgreSQL и Redis (как в CI), задайте:
  TEST_DATABASE_URL=postgresql+asyncpg://user:pass@host/db
  TEST_REDIS_URL=redis://host:6379/15
Внимание: тестовая БД и Redis-база очищаются перед каждым тестом.
"""

import asyncio
import itertools
import os
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings
from app.main import create_app

M4A = b"\x00\x00\x00\x20ftypM4A \x00\x00\x00\x00" + b"\x00" * 2048
_counter = itertools.count()

TEST_DATABASE_URL = os.getenv("TEST_DATABASE_URL", "")
TEST_REDIS_URL = os.getenv("TEST_REDIS_URL", "")


def make_settings(tmp_path, **overrides) -> Settings:
    base = dict(
        environment="test",
        database_url=TEST_DATABASE_URL or f"sqlite+aiosqlite:///{tmp_path}/test.db",
        redis_url=TEST_REDIS_URL,
        log_level="WARNING",
    )
    base.update(overrides)
    return Settings(_env_file=None, **base)


async def _reset_external() -> None:
    if TEST_DATABASE_URL:
        from sqlalchemy.ext.asyncio import create_async_engine

        from app.db.base import Base

        engine = create_async_engine(TEST_DATABASE_URL)
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.drop_all)
        await engine.dispose()
    if TEST_REDIS_URL:
        from redis.asyncio import Redis

        r = Redis.from_url(TEST_REDIS_URL)
        await r.flushdb()
        await r.aclose()


@pytest.fixture
def make_client(tmp_path) -> Iterator:
    asyncio.run(_reset_external())
    clients: list[TestClient] = []

    def factory(**overrides) -> TestClient:
        if clients:  # второй клиент в одном тесте — с чистыми счётчиками
            asyncio.run(_reset_external())
        app = create_app(make_settings(tmp_path, **overrides), create_tables=True)
        c = TestClient(app)
        c.__enter__()
        clients.append(c)
        return c

    yield factory
    for c in clients:
        c.__exit__(None, None, None)


@pytest.fixture
def client(make_client) -> TestClient:
    return make_client()


def register(client: TestClient, email: str | None = None, password: str = "correct-horse-1") -> dict:
    email = email or f"user{next(_counter)}@example.com"
    r = client.post("/api/auth/register", json={"email": email, "password": password})
    assert r.status_code == 201, r.text
    return r.json() | {"email": email, "password": password}


def auth(tokens: dict) -> dict:
    return {"Authorization": f"Bearer {tokens['access_token']}"}
