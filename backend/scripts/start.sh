#!/bin/sh
# Запуск API в контейнере. Миграции применяются перед стартом
# (на Render free нет отдельного шага pre-deploy; в docker compose это делает сервис migrate).
set -e
if [ "${RUN_MIGRATIONS:-1}" = "1" ]; then
  alembic upgrade head
fi
# IP клиента за прокси определяется приложением по TRUSTED_PROXY_COUNT,
# поэтому --proxy-headers uvicorn не включаем (он доверял бы подделанному X-Forwarded-For).
exec uvicorn app.main:build --factory --host 0.0.0.0 --port "${PORT:-8000}" \
  --workers "${WEB_CONCURRENCY:-2}" --no-proxy-headers --no-server-header
