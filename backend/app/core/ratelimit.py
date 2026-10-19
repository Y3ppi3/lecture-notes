import asyncio
import time
from typing import Protocol

from redis.asyncio import Redis


class RateLimiter(Protocol):
    async def hit(self, key: str, limit: int, window_s: int) -> tuple[bool, int]:
        """Учитывает обращение. Возвращает (разрешено, секунд до сброса окна)."""
        ...

    async def close(self) -> None: ...


class RedisRateLimiter:
    """Фиксированное окно на INCR + EXPIRE: дёшево и общее для всех процессов."""

    def __init__(self, url: str):
        self._redis = Redis.from_url(url, decode_responses=True)

    async def hit(self, key: str, limit: int, window_s: int) -> tuple[bool, int]:
        window = int(time.time() // window_s)
        k = f"rl:{key}:{window}"
        async with self._redis.pipeline(transaction=True) as pipe:
            pipe.incr(k)
            pipe.expire(k, window_s + 1)
            count, _ = await pipe.execute()
        retry_after = window_s - int(time.time() % window_s)
        return int(count) <= limit, retry_after

    async def ping(self) -> bool:
        try:
            return bool(await self._redis.ping())
        except Exception:
            return False

    async def close(self) -> None:
        await self._redis.aclose()


class MemoryRateLimiter:
    """Для разработки и тестов: счётчики в памяти одного процесса."""

    def __init__(self) -> None:
        self._counters: dict[str, int] = {}
        self._lock = asyncio.Lock()

    async def hit(self, key: str, limit: int, window_s: int) -> tuple[bool, int]:
        window = int(time.time() // window_s)
        k = f"{key}:{window}"
        async with self._lock:
            if len(self._counters) > 50_000:
                self._counters.clear()
            self._counters[k] = self._counters.get(k, 0) + 1
            count = self._counters[k]
        return count <= limit, window_s - int(time.time() % window_s)

    async def ping(self) -> bool:
        return True

    async def close(self) -> None:
        return None


def build_rate_limiter(redis_url: str) -> RedisRateLimiter | MemoryRateLimiter:
    return RedisRateLimiter(redis_url) if redis_url else MemoryRateLimiter()
