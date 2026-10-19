import Constants from 'expo-constants';

const extra = (Constants.expoConfig?.extra ?? {}) as { appEnv?: string; apiUrl?: string };

export const APP_ENV = extra.appEnv ?? 'development';
export const IS_PROD = APP_ENV === 'production';
/** Адрес сервера, зашитый в сборку. В dev-сборке его можно сменить в настройках. */
export const DEFAULT_API_URL = (extra.apiUrl ?? 'http://10.0.2.2:8000').replace(/\/+$/, '');
export const ALLOW_SERVER_OVERRIDE = !IS_PROD;
export const APP_VERSION = Constants.expoConfig?.version ?? '';
