import * as SecureStore from 'expo-secure-store';

/**
 * Токены — в SecureStore (Android Keystore / iOS Keychain), только на этом устройстве.
 * В памяти держится копия для быстрого доступа из сетевого слоя.
 */
export interface Session {
  accessToken: string;
  refreshToken: string;
  email: string;
}

const KEY = 'session.v1';
const OPTIONS: SecureStore.SecureStoreOptions = {
  keychainAccessible: SecureStore.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
};

let current: Session | null = null;
let loaded = false;
const listeners = new Set<(s: Session | null) => void>();

export function getSession(): Session | null {
  return current;
}

export function isSessionLoaded() {
  return loaded;
}

export function subscribeSession(listener: (s: Session | null) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function emit() {
  listeners.forEach((l) => l(current));
}

export async function loadSession(): Promise<Session | null> {
  if (loaded) return current;
  try {
    const raw = await SecureStore.getItemAsync(KEY, OPTIONS);
    const parsed = raw ? (JSON.parse(raw) as Session) : null;
    current = parsed?.accessToken && parsed.refreshToken ? parsed : null;
  } catch {
    // Хранилище недоступно или данные повреждены — просто просим войти заново.
    current = null;
    await SecureStore.deleteItemAsync(KEY, OPTIONS).catch(() => {});
  }
  loaded = true;
  emit();
  return current;
}

export async function saveSession(s: Session) {
  current = s;
  await SecureStore.setItemAsync(KEY, JSON.stringify(s), OPTIONS);
  emit();
}

export async function clearSession() {
  current = null;
  await SecureStore.deleteItemAsync(KEY, OPTIONS).catch(() => {});
  emit();
}
