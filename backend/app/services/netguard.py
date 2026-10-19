import asyncio
import ipaddress
import socket
from urllib.parse import urlsplit

from fastapi import HTTPException


async def validate_public_base_url(url: str, *, allow_http: bool = False) -> str:
    """
    Проверяет адрес произвольного API, присланный пользователем (защита от SSRF):
    только https, без логина/пароля в адресе, и хост должен указывать на публичные IP —
    не на localhost, внутреннюю сеть сервера, link-local (метаданные облака) и т.п.
    """
    try:
        parts = urlsplit(url.strip())
    except ValueError:
        raise HTTPException(400, "Некорректный адрес API") from None
    allowed_schemes = ("https", "http") if allow_http else ("https",)
    if parts.scheme not in allowed_schemes:
        raise HTTPException(400, "Адрес API должен начинаться с https://")
    if not parts.hostname or parts.username or parts.password:
        raise HTTPException(400, "Некорректный адрес API")
    if parts.query or parts.fragment:
        raise HTTPException(400, "Адрес API не должен содержать параметров")

    if not allow_http:
        try:
            port = parts.port or 443
        except ValueError:
            raise HTTPException(400, "Некорректный порт в адресе API") from None
        try:
            infos = await asyncio.get_running_loop().getaddrinfo(parts.hostname, port, type=socket.SOCK_STREAM)
        except OSError:
            raise HTTPException(400, "Не удалось найти сервер по адресу API") from None
        for info in infos:
            ip = ipaddress.ip_address(info[4][0])
            if not ip.is_global or ip.is_multicast:
                raise HTTPException(400, "Адрес API указывает во внутреннюю сеть — такой адрес запрещён")

    return f"{parts.scheme}://{parts.netloc}{parts.path}".rstrip("/")
