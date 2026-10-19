import AsyncStorage from '@react-native-async-storage/async-storage';
import { ALLOW_SERVER_OVERRIDE, DEFAULT_API_URL } from './config';

export interface AppSettings {
  serverUrl: string;
  language: string;
  autoCorrect: boolean;
}

export const DEFAULT_SETTINGS: AppSettings = {
  serverUrl: DEFAULT_API_URL,
  language: 'ru',
  autoCorrect: true,
};

const KEY = 'settings.v2';
let cache: AppSettings | null = null;

export async function loadSettings(): Promise<AppSettings> {
  if (cache) return cache;
  let stored: Partial<AppSettings> = {};
  try {
    const raw = await AsyncStorage.getItem(KEY);
    stored = raw ? JSON.parse(raw) : {};
  } catch {
    stored = {};
  }
  cache = {
    ...DEFAULT_SETTINGS,
    ...stored,
    // В production адрес сервера всегда из сборки.
    serverUrl: ALLOW_SERVER_OVERRIDE && stored.serverUrl ? stored.serverUrl : DEFAULT_API_URL,
  };
  return cache;
}

export function normalizeUrl(url: string): string {
  let u = url.trim();
  if (!u) return DEFAULT_API_URL;
  if (!/^https?:\/\//i.test(u)) u = `http://${u}`;
  return u.replace(/\/+$/, '');
}

export function normalizeLanguage(lang: string): string {
  const l = lang.trim().toLowerCase();
  return /^[a-z]{2}$/.test(l) ? l : 'ru';
}

export async function saveSettings(s: AppSettings) {
  cache = {
    serverUrl: ALLOW_SERVER_OVERRIDE ? normalizeUrl(s.serverUrl) : DEFAULT_API_URL,
    language: normalizeLanguage(s.language),
    autoCorrect: s.autoCorrect,
  };
  await AsyncStorage.setItem(KEY, JSON.stringify(cache));
}
