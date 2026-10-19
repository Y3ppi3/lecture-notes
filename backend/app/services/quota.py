import uuid
from datetime import UTC, datetime, timedelta

from fastapi import HTTPException
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import UsageRecord

AUDIO_BYTES = "audio_bytes"
CORRECT_CHARS = "correct_chars"


async def used_last_24h(db: AsyncSession, user_id: uuid.UUID, kind: str) -> int:
    since = datetime.now(UTC) - timedelta(days=1)
    result = await db.execute(
        select(func.coalesce(func.sum(UsageRecord.amount), 0)).where(
            UsageRecord.user_id == user_id,
            UsageRecord.kind == kind,
            UsageRecord.created_at >= since,
        )
    )
    return int(result.scalar_one())


async def ensure_quota(db: AsyncSession, user_id: uuid.UUID, kind: str, amount: int, limit: int) -> None:
    """Проверяет суточную (скользящие 24 ч) квоту до обращения к платному провайдеру."""
    if await used_last_24h(db, user_id, kind) + amount > limit:
        raise HTTPException(
            429,
            "Достигнут суточный лимит обработки. Попробуйте позже.",
        )


async def record_usage(db: AsyncSession, user_id: uuid.UUID, kind: str, amount: int) -> None:
    db.add(UsageRecord(user_id=user_id, kind=kind, amount=amount))
    await db.commit()


async def purge_old_usage(db: AsyncSession, keep_days: int = 7) -> None:
    from sqlalchemy import delete

    await db.execute(delete(UsageRecord).where(UsageRecord.created_at < datetime.now(UTC) - timedelta(days=keep_days)))
    await db.commit()
