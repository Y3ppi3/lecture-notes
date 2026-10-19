import base64
import json
import shutil
import subprocess
import uuid

import httpx
import pytest
import respx

from app.providers.catalog import SBER_OAUTH_URL, detect_provider
from app.providers.stt import parse_salute_result
from tests.conftest import M4A, auth, make_settings, register

SBER_KEY = base64.b64encode(f"{uuid.uuid4()}:{uuid.uuid4()}".encode()).decode()
GIGA = "https://api.giga.chat/v1"
SALUTE = "https://smartspeech.sber.ru/rest/v1"


def oauth_ok(token: str = "tok-1") -> httpx.Response:
    return httpx.Response(200, json={"access_token": token, "expires_at": 4_102_444_800_000})


def chat_ok(text: str) -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": {"content": text}}]})


def ai(kind: str, **creds) -> dict:
    return {kind: creds}


# ---------- определение сервиса по ключу ----------


@pytest.mark.parametrize(
    ("kind", "key", "expected"),
    [
        ("llm", SBER_KEY, "gigachat"),
        ("stt", SBER_KEY, "salutespeech"),
        ("llm", "sk-ant-api03-" + "x" * 40, "anthropic"),
        ("llm", "sk-proj-" + "x" * 40, "openai"),
        ("llm", "sk-or-v1-" + "a" * 40, "openrouter"),
        ("llm", "sk-" + "0123456789abcdef" * 2, "deepseek"),
        ("llm", "AIzaSy" + "x" * 33, "gemini"),
        ("llm", "gsk_" + "x" * 40, "groq"),
        ("stt", "sk-proj-" + "x" * 40, "openai"),
        ("stt", "sk-ant-" + "x" * 40, None),
        ("llm", "какой-то-ключ-12345", None),
    ],
)
def test_detect_provider(kind, key, expected):
    assert detect_provider(kind, key) == expected


# ---------- каталог и проверка ключа ----------


def test_providers_catalog(client):
    t = register(client)
    r = client.get("/api/ai/providers", headers=auth(t))
    assert r.status_code == 200
    body = r.json()
    ids = {p["id"] for p in body["llm"]}
    assert {"gigachat", "openai", "anthropic", "custom"} <= ids
    assert {p["id"] for p in body["stt"]} >= {"salutespeech", "openai"}
    giga = next(p for p in body["llm"] if p["id"] == "gigachat")
    assert giga["scopes"][0] == "GIGACHAT_API_PERS"
    assert body["server"]["llm"]["available"] is True


def test_check_key_autodetects_gigachat(client):
    t = register(client)
    with respx.mock:
        respx.post(SBER_OAUTH_URL).mock(return_value=oauth_ok())
        r = client.post(
            "/api/ai/check",
            json={"kind": "llm", "credentials": {"provider": "auto", "api_key": SBER_KEY}},
            headers=auth(t),
        )
    assert r.json()["ok"] is True
    assert r.json()["provider"] == "gigachat"


def test_check_key_rejected_by_provider(client):
    t = register(client)
    with respx.mock:
        respx.get("https://api.openai.com/v1/models").mock(return_value=httpx.Response(401, text="bad"))
        r = client.post(
            "/api/ai/check",
            json={"kind": "llm", "credentials": {"provider": "auto", "api_key": "sk-proj-" + "x" * 40}},
            headers=auth(t),
        )
    body = r.json()
    assert body["ok"] is False and body["provider"] == "openai"
    assert "отклонил" in body["message"]


def test_check_unknown_key_format(client):
    t = register(client)
    r = client.post(
        "/api/ai/check",
        json={"kind": "llm", "credentials": {"provider": "auto", "api_key": "непонятный-ключ-123"}},
        headers=auth(t),
    )
    assert r.json()["ok"] is False
    assert "выберите" in r.json()["message"]


# ---------- запросы со своим ключом ----------


def test_gigachat_user_key_and_token_cache(client):
    t = register(client)
    cfg = json.dumps(ai("llm", provider="gigachat", api_key=SBER_KEY, model="GigaChat-2-Pro"))
    with respx.mock:
        oauth = respx.post(SBER_OAUTH_URL).mock(return_value=oauth_ok())
        chat = respx.post(f"{GIGA}/chat/completions").mock(return_value=chat_ok("Исправлено."))
        for _ in range(2):
            r = client.post(
                "/api/process",
                files={"file": ("x.m4a", M4A, "audio/mp4")},
                data={"ai_config": cfg},
                headers=auth(t),
            )
            assert r.status_code == 200, r.text
            assert r.json()["corrected_text"] == "Исправлено."
    # Токен получен один раз и переиспользован.
    assert oauth.call_count == 1
    req = oauth.calls[0].request
    assert req.headers["Authorization"] == f"Basic {SBER_KEY}"
    assert uuid.UUID(req.headers["RqUID"])
    assert b"scope=GIGACHAT_API_PERS" in req.content
    assert chat.calls[0].request.headers["Authorization"] == "Bearer tok-1"
    assert json.loads(chat.calls[0].request.content)["model"] == "GigaChat-2-Pro"


def test_gigachat_expired_token_is_refreshed(client):
    t = register(client)
    body = {"text": "текст", "ai_config": ai("llm", provider="gigachat", api_key=SBER_KEY)}
    with respx.mock:
        oauth = respx.post(SBER_OAUTH_URL).mock(side_effect=[oauth_ok("old"), oauth_ok("new")])
        respx.post(f"{GIGA}/chat/completions").mock(side_effect=[httpx.Response(401), chat_ok("Ок.")])
        r = client.post("/api/correct", json=body, headers=auth(t))
    assert r.json()["text"] == "Ок."
    assert oauth.call_count == 2


def test_user_key_does_not_consume_server_quota(make_client):
    c = make_client(daily_correct_chars=5)
    t = register(c)
    body = {"text": "длинный текст лекции", "ai_config": ai("llm", provider="openai", api_key="sk-proj-" + "x" * 40)}
    with respx.mock:
        respx.post("https://api.openai.com/v1/chat/completions").mock(return_value=chat_ok("Ок."))
        assert c.post("/api/correct", json=body, headers=auth(t)).status_code == 200
        assert c.post("/api/correct", json=body, headers=auth(t)).status_code == 200
    assert c.get("/api/usage", headers=auth(t)).json()["correct_chars_used"] == 0
    # На ключе сервера квота действует.
    assert c.post("/api/correct", json={"text": "длинный текст"}, headers=auth(t)).status_code == 429


def test_user_key_error_message_is_specific(client):
    t = register(client)
    body = {"text": "текст", "ai_config": ai("llm", provider="anthropic", api_key="sk-ant-" + "x" * 40)}
    with respx.mock:
        respx.post("https://api.anthropic.com/v1/messages").mock(return_value=httpx.Response(402, text="no money"))
        r = client.post("/api/correct", json=body, headers=auth(t))
    assert r.status_code == 502
    assert "баланс" in r.json()["detail"]


def test_user_keys_can_be_disabled(make_client):
    c = make_client(allow_user_keys=False)
    t = register(c)
    body = {"text": "текст", "ai_config": ai("llm", provider="openai", api_key="sk-proj-" + "x" * 40)}
    assert c.post("/api/correct", json=body, headers=auth(t)).status_code == 403


def test_server_without_keys_asks_for_user_key(make_client):
    c = make_client(llm_provider="none")
    t = register(c)
    r = c.post("/api/correct", json={"text": "текст"}, headers=auth(t))
    assert r.status_code == 400
    assert "свой ключ" in r.json()["detail"]


def test_api_key_is_not_echoed_on_validation_error(client):
    t = register(client)
    body = {"text": "т", "ai_config": ai("llm", provider="BAD PROVIDER", api_key="sk-very-secret-value")}
    r = client.post("/api/correct", json=body, headers=auth(t))
    assert r.status_code == 422
    assert "sk-very-secret-value" not in r.text


# ---------- произвольный адрес API: защита от SSRF ----------


@pytest.mark.parametrize(
    "url",
    [
        "http://api.example.com/v1",  # не https
        "https://127.0.0.1/v1",
        "https://localhost/v1",
        "https://10.0.0.5/v1",
        "https://169.254.169.254/latest",  # метаданные облака
        "https://user:pass@api.example.com/v1",
        "file:///etc/passwd",
    ],
)
def test_custom_base_url_ssrf_blocked(make_client, url):
    c = make_client(environment="test")
    t = register(c)
    body = {
        "text": "текст",
        "ai_config": ai("llm", provider="custom", api_key="key-12345678", model="m", base_url=url),
    }
    r = c.post("/api/correct", json=body, headers=auth(t))
    assert r.status_code == 400, r.text


def test_custom_base_url_can_be_disabled(make_client):
    c = make_client(allow_custom_base_url=False)
    t = register(c)
    body = {
        "text": "т",
        "ai_config": ai("llm", provider="custom", api_key="key-12345678", model="m", base_url="https://8.8.8.8/v1"),
    }
    assert c.post("/api/correct", json=body, headers=auth(t)).status_code == 403
    ids = {p["id"] for p in c.get("/api/ai/providers", headers=auth(t)).json()["llm"]}
    assert "custom" not in ids


# ---------- SaluteSpeech ----------


def test_parse_salute_result():
    raw = json.dumps(
        [
            {"results": [{"normalized_text": "Привет.", "start": "0s", "end": "1s"}]},
            {"results": [{"normalized_text": ""}]},
            {"results": [{"normalized_text": "Как дела?"}]},
        ]
    )
    assert parse_salute_result(raw) == "Привет. Как дела?"


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="нужен ffmpeg")
def test_salutespeech_async_flow(client, tmp_path):
    audio = tmp_path / "a.m4a"
    subprocess.run(
        [
            "ffmpeg",
            "-loglevel",
            "error",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:duration=1",
            "-c:a",
            "aac",
            str(audio),
        ],
        check=True,
    )
    t = register(client)
    cfg = json.dumps(ai("stt", provider="salutespeech", api_key=SBER_KEY, scope="SALUTE_SPEECH_CORP"))
    with respx.mock:
        oauth = respx.post(SBER_OAUTH_URL).mock(return_value=oauth_ok())
        upload = respx.post(f"{SALUTE}/data:upload").mock(
            return_value=httpx.Response(200, json={"status": 200, "result": {"request_file_id": "f1"}})
        )
        task = respx.post(f"{SALUTE}/speech:async_recognize").mock(
            return_value=httpx.Response(200, json={"status": 200, "result": {"id": "t1", "status": "NEW"}})
        )
        respx.get(f"{SALUTE}/task:get").mock(
            side_effect=[
                httpx.Response(200, json={"status": 200, "result": {"status": "RUNNING"}}),
                httpx.Response(200, json={"status": 200, "result": {"status": "DONE", "response_file_id": "r1"}}),
            ]
        )
        respx.get(f"{SALUTE}/data:download").mock(
            return_value=httpx.Response(200, text=json.dumps([{"results": [{"normalized_text": "Тест."}]}]))
        )
        r = client.post(
            "/api/transcribe",
            files={"file": ("a.m4a", audio.read_bytes(), "audio/mp4")},
            data={"ai_config": cfg},
            headers=auth(t),
        )
    assert r.status_code == 200, r.text
    assert r.json()["text"] == "Тест."
    assert b"scope=SALUTE_SPEECH_CORP" in oauth.calls[0].request.content
    sent = upload.calls[0].request.content
    assert sent[:4] == b"RIFF" and sent[8:12] == b"WAVE"  # перекодировано в WAV
    options = json.loads(task.calls[0].request.content)["options"]
    assert options["audio_encoding"] == "PCM_S16LE" and options["sample_rate"] == 16000
    assert options["language"] == "ru-RU"


# ---------- конфигурация ----------


def test_config_rejects_unknown_server_provider(tmp_path):
    with pytest.raises(ValueError):
        make_settings(tmp_path, llm_provider="nonexistent")


def test_config_requires_key_for_server_provider(tmp_path):
    with pytest.raises(ValueError):
        make_settings(tmp_path, llm_provider="gigachat")
