import { aiConfigForRequest, type AiKey, type AiKind } from './aiKeys';
import { clearSession, getSession, saveSession } from './session';
import { loadSettings, type AppSettings } from './settings';

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    /** Временная ошибка (нет сети, перегрузка сервера) — запрос стоит повторить позже. */
    readonly transient: boolean,
  ) {
    super(message);
  }
}

interface TokenPair {
  access_token: string;
  refresh_token: string;
  expires_in: number;
}

export interface HealthResponse { status: string }
export interface ProcessResponse { raw_text: string; corrected_text: string }
export interface UsageResponse {
  audio_mb_used: number;
  audio_mb_limit: number;
  correct_chars_used: number;
  correct_chars_limit: number;
}

type Init = Omit<RequestInit, 'headers'> & { headers?: Record<string, string> };

async function rawFetch(url: string, init: Init, timeoutMs: number): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } catch (e) {
    const timeout = (e as Error)?.name === 'AbortError';
    throw new ApiError(timeout ? 'Сервер не ответил вовремя' : 'Нет связи с сервером', 0, true);
  } finally {
    clearTimeout(timer);
  }
}

async function toError(resp: Response): Promise<ApiError> {
  let detail = '';
  try {
    detail = (await resp.json())?.detail ?? '';
  } catch {
    // ответ не JSON
  }
  const transient = resp.status === 429 || resp.status >= 500;
  if (resp.status === 401) return new ApiError('Сессия истекла — войдите снова', 401, false);
  return new ApiError(typeof detail === 'string' && detail ? detail : `Ошибка сервера (${resp.status})`, resp.status, transient);
}

// ---------- обновление токенов (single-flight) ----------

let refreshing: Promise<boolean> | null = null;

/** Обновляет пару токенов. Параллельные вызовы ждут один и тот же запрос. */
function refreshTokens(s: AppSettings): Promise<boolean> {
  if (!refreshing) {
    refreshing = (async () => {
      const session = getSession();
      if (!session) return false;
      let resp: Response;
      try {
        resp = await rawFetch(`${s.serverUrl}/api/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refresh_token: session.refreshToken }),
        }, 20_000);
      } catch {
        return false; // нет сети — сессию не трогаем
      }
      if (!resp.ok) {
        if (resp.status >= 400 && resp.status < 500) await clearSession();
        return false;
      }
      const pair = (await resp.json()) as TokenPair;
      await saveSession({ ...session, accessToken: pair.access_token, refreshToken: pair.refresh_token });
      return true;
    })().finally(() => {
      refreshing = null;
    });
  }
  return refreshing;
}

/** Запрос с access-токеном; при 401 — одно обновление токенов и повтор. */
async function authed<T>(path: string, init: Init, timeoutMs = 240_000): Promise<T> {
  const s = await loadSettings();
  const call = () => {
    const token = getSession()?.accessToken;
    const headers = { ...init.headers, ...(token ? { Authorization: `Bearer ${token}` } : {}) };
    return rawFetch(`${s.serverUrl}${path}`, { ...init, headers }, timeoutMs);
  };
  let resp = await call();
  if (resp.status === 401 && (await refreshTokens(s))) resp = await call();
  if (!resp.ok) throw await toError(resp);
  return resp.status === 204 ? (undefined as T) : ((await resp.json()) as T);
}

async function anonymous<T>(path: string, init: Init, serverUrl?: string, timeoutMs = 30_000): Promise<T> {
  const s = await loadSettings();
  const resp = await rawFetch(`${serverUrl ?? s.serverUrl}${path}`, init, timeoutMs);
  if (!resp.ok) throw await toError(resp);
  return resp.status === 204 ? (undefined as T) : ((await resp.json()) as T);
}

const json = (body: unknown): Init => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

// ---------- аккаунт ----------

async function signIn(path: string, email: string, password: string) {
  const e = email.trim().toLowerCase();
  try {
    const pair = await anonymous<TokenPair>(path, json({ email: e, password }));
    await saveSession({ accessToken: pair.access_token, refreshToken: pair.refresh_token, email: e });
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) throw new ApiError('Неверный email или пароль', 401, false);
    throw err;
  }
}

export const login = (email: string, password: string) => signIn('/api/auth/login', email, password);
export const register = (email: string, password: string) => signIn('/api/auth/register', email, password);

export async function logout() {
  const session = getSession();
  if (session) {
    await anonymous('/api/auth/logout', json({ refresh_token: session.refreshToken })).catch(() => {});
  }
  await clearSession();
}

/** Удаляет аккаунт на сервере. Локальные данные и сессию очищает вызывающий код. */
export function deleteAccount(password: string) {
  return authed<void>('/api/auth/me', {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  });
}

export const usage = () => authed<UsageResponse>('/api/usage', { method: 'GET' }, 15_000);

export function health(serverUrl: string) {
  return anonymous<HealthResponse>('/api/health', { method: 'GET' }, serverUrl, 10_000);
}

// ---------- ИИ ----------

/** Аудиофрагмент → распознавание → ИИ-коррекция. */
export async function processAudio(audioUri: string, opts: { title: string; previousText: string }) {
  const s = await loadSettings();
  const form = new FormData();
  // В React Native файл в FormData передаётся объектом { uri, name, type }.
  form.append('file', { uri: audioUri, name: 'audio.m4a', type: 'audio/mp4' } as unknown as Blob);
  form.append('language', s.language);
  form.append('title', opts.title);
  form.append('previous_text', opts.previousText);
  form.append('correct_text', String(s.autoCorrect));
  const ai = await aiConfigForRequest();
  if (ai) form.append('ai_config', JSON.stringify(ai));
  return authed<ProcessResponse>('/api/process', { method: 'POST', body: form });
}

export async function correctText(body: { text: string; title?: string; previousText?: string }) {
  const s = await loadSettings();
  return authed<{ text: string }>(
    '/api/correct',
    json({
      text: body.text,
      language: s.language,
      title: body.title || null,
      previous_text: body.previousText || null,
      ai_config: (await aiConfigForRequest()) ?? null,
    }),
  );
}

// ---------- ключи нейросетей ----------

export interface ProviderInfo {
  id: string;
  title: string;
  kind: AiKind;
  base_url: string;
  default_model: string;
  key_hint: string;
  custom_base_url: boolean;
  scopes: string[];
  models: string[];
}

export interface ProvidersResponse {
  allow_user_keys: boolean;
  allow_custom_base_url: boolean;
  server: Record<AiKind, { available: boolean; title: string | null }>;
  llm: ProviderInfo[];
  stt: ProviderInfo[];
}

export interface CheckKeyResponse {
  ok: boolean;
  provider?: string | null;
  title?: string | null;
  model?: string | null;
  message: string;
}

export const aiProviders = () => authed<ProvidersResponse>('/api/ai/providers', { method: 'GET' }, 15_000);

export const checkAiKey = (kind: AiKind, credentials: AiKey) =>
  authed<CheckKeyResponse>('/api/ai/check', json({ kind, credentials }), 40_000);
