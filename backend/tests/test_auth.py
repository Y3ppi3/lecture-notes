from tests.conftest import auth, register


def test_register_login_me(client):
    t = register(client, "Student@Example.com")
    me = client.get("/api/auth/me", headers=auth(t))
    assert me.status_code == 200
    assert me.json()["email"] == "student@example.com"

    r = client.post("/api/auth/login", json={"email": "STUDENT@example.com", "password": t["password"]})
    assert r.status_code == 200
    assert r.json()["access_token"]


def test_duplicate_email_rejected(client):
    register(client, "a@example.com")
    r = client.post("/api/auth/register", json={"email": "a@example.com", "password": "another-pass-1"})
    assert r.status_code == 409


def test_weak_or_invalid_input_rejected_without_echo(client):
    r = client.post("/api/auth/register", json={"email": "not-an-email", "password": "short"})
    assert r.status_code == 422
    assert "short" not in r.text  # пароль не возвращается в ответе об ошибке


def test_wrong_password_generic_error(client):
    t = register(client)
    r1 = client.post("/api/auth/login", json={"email": t["email"], "password": "wrong-password"})
    r2 = client.post("/api/auth/login", json={"email": "nobody@example.com", "password": "wrong-password"})
    assert r1.status_code == r2.status_code == 401
    assert r1.json() == r2.json()


def test_protected_endpoints_require_token(client):
    assert client.get("/api/auth/me").status_code == 401
    assert client.post("/api/correct", json={"text": "a"}).status_code == 401
    bad = {"Authorization": "Bearer not.a.jwt"}
    assert client.post("/api/correct", json={"text": "a"}, headers=bad).status_code == 401


def test_refresh_rotation_and_reuse_detection(client):
    t = register(client)
    r = client.post("/api/auth/refresh", json={"refresh_token": t["refresh_token"]})
    assert r.status_code == 200
    t2 = r.json()
    assert t2["refresh_token"] != t["refresh_token"]

    # Повторное использование старого токена — отзыв всей сессии.
    assert client.post("/api/auth/refresh", json={"refresh_token": t["refresh_token"]}).status_code == 401
    assert client.post("/api/auth/refresh", json={"refresh_token": t2["refresh_token"]}).status_code == 401


def test_logout_revokes_session(client):
    t = register(client)
    assert client.post("/api/auth/logout", json={"refresh_token": t["refresh_token"]}).status_code == 204
    assert client.post("/api/auth/refresh", json={"refresh_token": t["refresh_token"]}).status_code == 401


def test_login_rate_limited(make_client):
    c = make_client(login_attempts_per_minute=3)
    t = register(c)
    codes = [
        c.post("/api/auth/login", json={"email": t["email"], "password": "wrong-password"}).status_code
        for _ in range(5)
    ]
    assert codes[:3] == [401, 401, 401]
    assert codes[3] == 429


def test_registration_can_be_disabled(make_client):
    c = make_client(registration_enabled=False)
    r = c.post("/api/auth/register", json={"email": "x@example.com", "password": "password-123"})
    assert r.status_code == 403


def test_delete_account(client):
    t = register(client)
    assert client.request("DELETE", "/api/auth/me", json={"password": "nope"}, headers=auth(t)).status_code == 403
    r = client.request("DELETE", "/api/auth/me", json={"password": t["password"]}, headers=auth(t))
    assert r.status_code == 204
    assert client.get("/api/auth/me", headers=auth(t)).status_code == 401


def test_client_ip_respects_trusted_proxy_count(tmp_path):
    from starlette.requests import Request

    from app.api.deps import client_ip
    from tests.conftest import make_settings

    def req(xff: str) -> Request:
        return Request({"type": "http", "headers": [(b"x-forwarded-for", xff.encode())], "client": ("10.0.0.2", 1)})

    direct = make_settings(tmp_path)
    behind_proxy = make_settings(tmp_path, trusted_proxy_count=1)
    # Без доверенного прокси заголовок игнорируется — его может подделать клиент.
    assert client_ip(req("1.1.1.1"), direct) == "10.0.0.2"
    # За одним прокси берётся последний адрес (его добавил наш прокси), а не подделанный первый.
    assert client_ip(req("6.6.6.6, 2.2.2.2"), behind_proxy) == "2.2.2.2"


def test_empty_jwt_secret_rejected_in_production(tmp_path):
    import pytest

    from tests.conftest import make_settings

    with pytest.raises(ValueError):
        make_settings(tmp_path, environment="production", jwt_secret="")
