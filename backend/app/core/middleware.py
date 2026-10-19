import json
import logging
import re
import time
import uuid

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.logging import log_extra, request_id_var

log = logging.getLogger("app.http")

_REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")

SECURITY_HEADERS = [
    (b"x-content-type-options", b"nosniff"),
    (b"x-frame-options", b"DENY"),
    (b"referrer-policy", b"no-referrer"),
    (b"cross-origin-resource-policy", b"same-origin"),
    (b"content-security-policy", b"default-src 'none'; frame-ancestors 'none'"),
    (b"cache-control", b"no-store"),
]


class RequestContextMiddleware:
    """Request-ID, заголовки безопасности и лог запроса (метод, путь, статус, время — без тела)."""

    def __init__(self, app: ASGIApp, hsts: bool = False, docs_paths: tuple[str, ...] = ()):
        self.app = app
        self.hsts = hsts
        self.docs_paths = docs_paths

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        incoming = dict(scope.get("headers") or []).get(b"x-request-id", b"").decode("latin-1")
        rid = incoming if _REQUEST_ID_RE.match(incoming) else uuid.uuid4().hex
        token = request_id_var.set(rid)
        started = time.perf_counter()
        status = 500
        path: str = scope.get("path", "")
        # Swagger UI грузит скрипты с CDN — строгий CSP для документации не ставим.
        strict_csp = not any(path.startswith(p) for p in self.docs_paths)

        async def send_wrapper(message: Message) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
                headers = list(message.get("headers", []))
                headers.append((b"x-request-id", rid.encode()))
                present = {name.lower() for name, _ in headers}
                for name, value in SECURITY_HEADERS:
                    if name == b"content-security-policy" and not strict_csp:
                        continue
                    # Страница может задать свою политику (например, страница скачивания со стилями).
                    if name not in present:
                        headers.append((name, value))
                if self.hsts:
                    headers.append((b"strict-transport-security", b"max-age=31536000; includeSubDomains"))
                message["headers"] = headers
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        finally:
            elapsed_ms = round((time.perf_counter() - started) * 1000, 1)
            if path not in ("/api/health", "/api/ready"):
                log.info(
                    "request",
                    **log_extra(method=scope.get("method"), path=path, status=status, ms=elapsed_ms),
                )
            request_id_var.reset(token)


class MaxBodySizeMiddleware:
    """Отклоняет слишком большие запросы до разбора multipart (и по Content-Length, и по факту)."""

    def __init__(self, app: ASGIApp, max_bytes: int):
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        headers = dict(scope.get("headers") or [])
        declared = headers.get(b"content-length")
        if declared is not None:
            try:
                too_big = int(declared) > self.max_bytes
            except ValueError:
                too_big = True
            if too_big:
                await _reject(send, 413, "Слишком большой запрос")
                return

        received = 0
        response_started = False

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_bytes:
                    raise _BodyTooLarge
            return message

        async def tracking_send(message: Message) -> None:
            nonlocal response_started
            if message["type"] == "http.response.start":
                response_started = True
            await send(message)

        try:
            await self.app(scope, limited_receive, tracking_send)
        except _BodyTooLarge:
            if not response_started:
                await _reject(send, 413, "Слишком большой запрос")


class _BodyTooLarge(Exception):
    pass


async def _reject(send: Send, status: int, detail: str) -> None:
    body = json.dumps({"detail": detail}, ensure_ascii=False).encode()
    await send(
        {
            "type": "http.response.start",
            "status": status,
            "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())],
        }
    )
    await send({"type": "http.response.body", "body": body})
