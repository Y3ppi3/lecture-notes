# ---------- сборка зависимостей ----------
FROM python:3.12-slim AS build
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /build
COPY requirements.txt .
RUN python -m venv /opt/venv && /opt/venv/bin/pip install -r requirements.txt

# ---------- рабочий образ ----------
FROM python:3.12-slim
ENV PATH=/opt/venv/bin:$PATH \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    ENVIRONMENT=production

# ffmpeg нужен для перекодирования аудио под SaluteSpeech (он не принимает AAC/M4A).
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app

COPY --from=build /opt/venv /opt/venv
WORKDIR /app
COPY --chown=root:root app ./app
COPY --chown=root:root certs ./certs
COPY --chown=root:root alembic ./alembic
COPY --chown=root:root alembic.ini ./
COPY --chown=root:root scripts/start.sh ./start.sh
RUN chmod 755 ./start.sh

USER app
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:' + __import__('os').environ.get('PORT', '8000') + '/api/health', timeout=2).status == 200 else 1)"

CMD ["./start.sh"]
