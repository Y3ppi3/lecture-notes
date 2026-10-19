"""Страница скачивания прототипа: GET / — ссылка на APK, QR-код и инструкция по установке."""

from html import escape

import segno
from fastapi import APIRouter, Depends, Request
from fastapi.responses import HTMLResponse

from app.api.deps import AppState, get_state

router = APIRouter(tags=["demo"])

# Своя политика CSP: встроенные стили и картинки data: (QR-код), никаких скриптов и внешних ресурсов.
CSP = (
    "default-src 'none'; style-src 'unsafe-inline'; img-src data:; "
    "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
)

ICON = """<svg viewBox="0 0 108 108" width="88" height="88" aria-hidden="true">
<defs><linearGradient id="g" x1="0" y1="0" x2="108" y2="108" gradientUnits="userSpaceOnUse">
<stop offset="0" stop-color="#5664E3"/><stop offset="1" stop-color="#2A3496"/></linearGradient></defs>
<rect width="108" height="108" rx="24" fill="url(#g)"/>
<g transform="translate(54 54) scale(1.45) translate(-54 -54)">
<rect x="35" y="30" width="32" height="46" rx="5" fill="#fff"/>
<g fill="#3A47C2"><rect x="38.7" y="43.5" width="3" height="7" rx="1.5"/><rect x="44.1" y="39.5" width="3" height="15" rx="1.5"/>
<rect x="49.5" y="36.5" width="3" height="21" rx="1.5"/><rect x="54.9" y="40.5" width="3" height="13" rx="1.5"/>
<rect x="60.3" y="44" width="3" height="6" rx="1.5"/></g>
<g fill="#C3C9F4"><rect x="39" y="61" width="24" height="3" rx="1.5"/><rect x="39" y="67" width="15" height="3" rx="1.5"/></g>
<path d="M67,24C67.8,29.2 69.8,31.2 75,32C69.8,32.8 67.8,34.8 67,40C66.2,34.8 64.2,32.8 59,32C64.2,31.2 66.2,29.2 67,24Z" fill="#FFC83D"/>
</g></svg>"""

PAGE = """<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Конспекты лекций — прототип</title>
<style>
  :root {{ --bg:#F4F5FB; --card:#fff; --text:#1B1B1F; --muted:#62626E; --accent:#3A47C2; --btn:#3A47C2; --border:#E2E3EE; }}
  @media (prefers-color-scheme: dark) {{
    :root {{ --bg:#14151C; --card:#1E2029; --text:#ECECF2; --muted:#A3A4B2; --accent:#A9B1FA; --btn:#4F5BD5; --border:#2E3040; }}
  }}
  * {{ box-sizing: border-box; }}
  body {{ margin:0; background:var(--bg); color:var(--text);
         font:16px/1.5 -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; }}
  main {{ max-width:720px; margin:0 auto; padding:40px 16px 56px; }}
  header {{ display:flex; gap:20px; align-items:center; margin-bottom:28px; }}
  header svg {{ flex-shrink:0; }}
  h1 {{ font-size:28px; line-height:1.2; margin:0 0 4px; }}
  .sub {{ color:var(--muted); margin:0; }}
  .card {{ background:var(--card); border:1px solid var(--border); border-radius:16px; padding:24px; margin-bottom:16px; }}
  .download {{ display:flex; gap:24px; align-items:center; flex-wrap:wrap; }}
  .buttons {{ flex:1 1 260px; display:flex; flex-direction:column; gap:10px; }}
  a.btn {{ display:block; text-align:center; text-decoration:none; font-weight:600; padding:14px 18px;
          border-radius:12px; background:var(--btn); color:#fff; }}
  a.btn.secondary {{ background:transparent; color:var(--accent); border:1.5px solid var(--accent); }}
  .qr {{ text-align:center; color:var(--muted); font-size:13px; }}
  .qr img {{ width:160px; height:160px; background:#fff; padding:8px; border-radius:12px; display:block; margin:0 auto 6px; }}
  ol {{ margin:8px 0 0; padding-left:22px; }} li {{ margin:6px 0; }}
  h2 {{ font-size:18px; margin:0 0 8px; }}
  .status {{ font-size:14px; color:var(--muted); }}
  .dot {{ display:inline-block; width:8px; height:8px; border-radius:50%; background:#2EAD5B; margin-right:6px; }}
  .note {{ font-size:14px; color:var(--muted); }}
  @media (max-width:560px) {{ .qr {{ display:none; }} header svg {{ width:64px; height:64px; }} h1 {{ font-size:24px; }} }}
</style>
</head>
<body>
<main>
  <header>
    {icon}
    <div>
      <h1>Конспекты лекций</h1>
      <p class="sub">Прототип: запись лекций, распознавание речи и ИИ-коррекция текста, фото слайдов и видео прямо в конспекте</p>
    </div>
  </header>

  <section class="card download">
    <div class="buttons">{buttons}</div>
    <div class="qr"><img src="{qr}" alt="QR-код этой страницы">Откройте на телефоне</div>
  </section>

  <section class="card">
    <h2>Как установить</h2>
    <ol>
      <li>Скачайте APK на телефон с Android 8 или новее.</li>
      <li>Откройте файл. Если Android попросит, разрешите установку из этого источника.</li>
      <li>Зарегистрируйтесь в приложении по email — адрес сервера уже встроен.</li>
      <li>Создайте лекцию и нажмите «Запись». Фото слайдов делайте прямо во время записи.</li>
    </ol>
  </section>

  <section class="card">
    <h2>Что попробовать</h2>
    <ol>
      <li>Записать пару минут речи — текст появится в конспекте с исправленной пунктуацией и абзацами.</li>
      <li>Сфотографировать слайд во время записи — он встанет в текст в момент съёмки.</li>
      <li>Добавить видео или ссылку на ролик, переставить блоки, исправить фрагмент с помощью ИИ.</li>
      <li>В настройках подключить свой ключ GigaChat, ChatGPT, Claude и других нейросетей.</li>
    </ol>
  </section>

  <p class="status"><span class="dot"></span>Сервер работает · распознавание: {stt} · коррекция: {llm}</p>
  <p class="note">Это демонстрационная версия. Конспекты хранятся только на телефоне.</p>
</main>
</body>
</html>
"""


@router.get("/", response_class=HTMLResponse, include_in_schema=False)
async def download_page(request: Request, state: AppState = Depends(get_state)) -> HTMLResponse:
    s = state.settings
    scheme = "https" if s.is_production else request.url.scheme
    page_url = f"{scheme}://{request.headers.get('host', request.url.netloc)}/"

    buttons: list[str] = []
    if s.demo_apk_url:
        buttons.append(f'<a class="btn" href="{escape(s.demo_apk_url)}" rel="noopener">Скачать для Android (APK)</a>')
    if s.demo_apk_rn_url:
        buttons.append(
            f'<a class="btn secondary" href="{escape(s.demo_apk_rn_url)}" rel="noopener">'
            "Версия на React Native (APK)</a>"
        )
    if not buttons:
        buttons.append('<p class="note">Сборка приложения скоро появится на этой странице.</p>')

    status = state.ai.server_status()

    def title(kind: str) -> str:
        item = status[kind]
        return escape(item["title"]) if item["available"] and item["title"] else "свой ключ в приложении"

    html = PAGE.format(
        icon=ICON,
        buttons="\n".join(buttons),
        qr=segno.make(page_url, error="m").svg_data_uri(scale=4, border=0, dark="#1B1B1F"),
        stt=title("stt"),
        llm=title("llm"),
    )
    return HTMLResponse(html, headers={"Content-Security-Policy": CSP})
