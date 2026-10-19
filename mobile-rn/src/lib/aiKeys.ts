import * as SecureStore from 'expo-secure-store';
import { useEffect, useState } from 'react';

/**
 * Ключи нейросетей пользователя. Хранятся только на этом устройстве (Keystore / Keychain)
 * и передаются серверу вместе с запросом — сервер их не сохраняет.
 */
export type AiKind = 'llm' | 'stt';

export interface AiKey {
  provider: string;
  api_key: string;
  model?: string;
  base_url?: string;
  scope?: string;
  /** Только для показа в приложении, на сервер не отправляется. */
  title?: string;
}

export interface AiKeys {
  llm?: AiKey;
  stt?: AiKey;
}

const KEY = 'ai_keys.v1';
const OPTIONS: SecureStore.SecureStoreOptions = {
  keychainAccessible: SecureStore.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
};

let current: AiKeys = {};
let loaded = false;
const listeners = new Set<(k: AiKeys) => void>();

export async function loadAiKeys(): Promise<AiKeys> {
  if (loaded) return current;
  try {
    const raw = await SecureStore.getItemAsync(KEY, OPTIONS);
    current = raw ? (JSON.parse(raw) as AiKeys) : {};
  } catch {
    current = {};
  }
  loaded = true;
  listeners.forEach((l) => l(current));
  return current;
}

export async function setAiKey(kind: AiKind, key: AiKey | undefined) {
  await loadAiKeys();
  current = { ...current, [kind]: key };
  if (!current.llm && !current.stt) await SecureStore.deleteItemAsync(KEY, OPTIONS).catch(() => {});
  else await SecureStore.setItemAsync(KEY, JSON.stringify(current), OPTIONS);
  listeners.forEach((l) => l(current));
}

export async function clearAiKeys() {
  current = {};
  loaded = true;
  await SecureStore.deleteItemAsync(KEY, OPTIONS).catch(() => {});
  listeners.forEach((l) => l(current));
}

const strip = (k?: AiKey): AiKey | undefined => {
  if (!k) return undefined;
  const { title: _title, ...rest } = k;
  return rest;
};

/** Настройки для запроса или undefined, если используются нейросети сервера. */
export async function aiConfigForRequest(): Promise<AiKeys | undefined> {
  const k = await loadAiKeys();
  if (!k.llm && !k.stt) return undefined;
  return { llm: strip(k.llm), stt: strip(k.stt) };
}

export function useAiKeys(): AiKeys {
  const [keys, setKeys] = useState<AiKeys>(current);
  useEffect(() => {
    listeners.add(setKeys);
    loadAiKeys().then(setKeys).catch(() => undefined);
    return () => {
      listeners.delete(setKeys);
    };
  }, []);
  return keys;
}
