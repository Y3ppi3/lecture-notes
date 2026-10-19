import asyncio
import os
import shutil
import tempfile

from app.providers.base import ProviderError

_TIMEOUT_S = 90
_MAX_OUTPUT = 200 * 1024 * 1024


async def to_wav_16k_mono(audio: bytes) -> bytes:
    """
    Перекодирует аудио (m4a/aac, mp3, ogg и др.) в WAV PCM 16 кГц моно через ffmpeg.

    Вход пишется во временный файл (у M4A с телефона индекс в конце файла, из канала
    ffmpeg его не прочитает). ffmpeg запускается без сети и без чтения других файлов.
    """
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise ProviderError("transcode: ffmpeg не установлен на сервере")

    fd, path = tempfile.mkstemp(suffix=".audio")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(audio)
        proc = await asyncio.create_subprocess_exec(
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-nostdin",
            "-protocol_whitelist",
            "file,pipe",
            "-i",
            path,
            "-vn",
            "-ac",
            "1",
            "-ar",
            "16000",
            "-c:a",
            "pcm_s16le",
            "-f",
            "wav",
            "pipe:1",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            out, err = await asyncio.wait_for(proc.communicate(), timeout=_TIMEOUT_S)
        except TimeoutError:
            proc.kill()
            await proc.wait()
            raise ProviderError("transcode: превышено время перекодирования") from None
        if proc.returncode != 0 or len(out) < 64:
            raise ProviderError(
                f"transcode: ffmpeg {proc.returncode}: {err.decode(errors='replace')[:200]}", status=400
            )
        if len(out) > _MAX_OUTPUT:
            raise ProviderError("transcode: слишком длинная запись", status=413)
        return out
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass
