"""
Служебные команды:
  python -m app.cli create-user EMAIL        — создать пользователя (пароль спрашивается в терминале)
  python -m app.cli deactivate-user EMAIL    — заблокировать пользователя и отозвать его сессии
  python -m app.cli purge                    — удалить старую статистику и просроченные токены
"""

import argparse
import asyncio
import getpass
import sys
from datetime import UTC, datetime

from sqlalchemy import delete, select, update

from app.core.config import get_settings
from app.core.security import hash_password
from app.db.models import RefreshToken, User
from app.db.session import create_engine, create_sessionmaker
from app.services.quota import purge_old_usage


async def _run(args: argparse.Namespace) -> int:
    settings = get_settings()
    engine = create_engine(settings)
    maker = create_sessionmaker(engine)
    try:
        async with maker() as db:
            if args.cmd == "create-user":
                password = getpass.getpass("Пароль: ")
                if len(password) < settings.password_min_length:
                    print(f"Пароль короче {settings.password_min_length} символов", file=sys.stderr)
                    return 1
                db.add(User(email=args.email.lower(), password_hash=hash_password(password)))
                await db.commit()
                print("Пользователь создан")
            elif args.cmd == "deactivate-user":
                user = (await db.execute(select(User).where(User.email == args.email.lower()))).scalar_one_or_none()
                if user is None:
                    print("Пользователь не найден", file=sys.stderr)
                    return 1
                user.is_active = False
                await db.execute(
                    update(RefreshToken)
                    .where(RefreshToken.user_id == user.id, RefreshToken.revoked_at.is_(None))
                    .values(revoked_at=datetime.now(UTC))
                )
                await db.commit()
                print("Пользователь заблокирован")
            elif args.cmd == "purge":
                await purge_old_usage(db)
                await db.execute(delete(RefreshToken).where(RefreshToken.expires_at < datetime.now(UTC)))
                await db.commit()
                print("Готово")
    finally:
        await engine.dispose()
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(prog="python -m app.cli")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("create-user").add_argument("email")
    sub.add_parser("deactivate-user").add_argument("email")
    sub.add_parser("purge")
    sys.exit(asyncio.run(_run(parser.parse_args())))


if __name__ == "__main__":
    main()
