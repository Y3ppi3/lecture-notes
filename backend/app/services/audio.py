from dataclasses import dataclass

from fastapi import HTTPException, UploadFile


@dataclass(frozen=True)
class AudioFile:
    data: bytes
    filename: str
    content_type: str


def sniff_audio(head: bytes) -> tuple[str, str] | None:
    """
    Определяет формат по сигнатуре файла, а не по имени и Content-Type от клиента.
    Возвращает (расширение, MIME) или None, если это не поддерживаемое аудио.
    """
    if len(head) >= 12 and head[4:8] == b"ftyp":
        brand = head[8:12]
        if brand.startswith(b"3g"):
            return "3gp", "audio/3gpp"
        return "m4a", "audio/mp4"
    if head.startswith(b"ID3") or (len(head) >= 2 and head[0] == 0xFF and head[1] & 0xE0 == 0xE0):
        # MP3 (ID3-тег или кадр MPEG) либо AAC ADTS (0xFFF1 / 0xFFF9)
        if len(head) >= 2 and head[0] == 0xFF and head[1] in (0xF1, 0xF9):
            return "aac", "audio/aac"
        return "mp3", "audio/mpeg"
    if head.startswith(b"RIFF") and head[8:12] == b"WAVE":
        return "wav", "audio/wav"
    if head.startswith(b"OggS"):
        return "ogg", "audio/ogg"
    if head.startswith(b"fLaC"):
        return "flac", "audio/flac"
    if head.startswith(b"\x1a\x45\xdf\xa3"):
        return "webm", "audio/webm"
    return None


async def read_audio(file: UploadFile, max_mb: int) -> AudioFile:
    limit = max_mb * 1024 * 1024
    data = await file.read(limit + 1)
    if len(data) > limit:
        raise HTTPException(413, f"Аудиофрагмент больше {max_mb} МБ")
    if len(data) < 64:
        raise HTTPException(400, "Пустой или повреждённый аудиофайл")
    kind = sniff_audio(data[:16])
    if kind is None:
        raise HTTPException(415, "Неподдерживаемый формат аудио")
    ext, mime = kind
    # Имя файла от клиента провайдеру не передаём.
    return AudioFile(data=data, filename=f"audio.{ext}", content_type=mime)
