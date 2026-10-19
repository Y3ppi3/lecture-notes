from tests.conftest import make_settings


def test_download_page_without_apk(client):
    r = client.get("/")
    assert r.status_code == 200
    assert "Конспекты лекций" in r.text
    assert "скоро появится" in r.text
    assert r.headers["content-security-policy"].startswith("default-src 'none'; style-src 'unsafe-inline'")
    # Своя политика не дублируется общей из middleware.
    assert r.headers.get_list("content-security-policy") == [r.headers["content-security-policy"]]
    assert "data:image/svg+xml" in r.text  # QR-код


def test_download_page_with_apk_links_is_escaped(make_client):
    c = make_client(
        demo_apk_url='https://example.com/app.apk?a=1&b="x"',
        demo_apk_rn_url="https://example.com/rn.apk",
    )
    r = c.get("/")
    assert "Скачать для Android" in r.text and "React Native" in r.text
    assert 'b="x"' not in r.text and "&amp;b=&quot;x&quot;" in r.text


def test_render_database_url_is_converted(tmp_path):
    s = make_settings(tmp_path, database_url="postgres://u:p@host:5432/db")
    assert s.database_url == "postgresql+asyncpg://u:p@host:5432/db"
    s = make_settings(tmp_path, database_url="postgresql://u:p@host/db")
    assert s.database_url == "postgresql+asyncpg://u:p@host/db"
