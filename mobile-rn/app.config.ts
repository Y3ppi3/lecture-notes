import type { ConfigContext, ExpoConfig } from 'expo/config';

/**
 * Конфигурация зависит от окружения:
 *   APP_ENV=development (по умолчанию) — HTTP к локальному серверу разрешён, адрес сервера можно сменить в настройках;
 *   APP_ENV=production — только HTTPS, адрес сервера зашит в сборку (API_URL).
 */
const APP_ENV = process.env.APP_ENV ?? 'development';
const IS_PROD = APP_ENV === 'production';
const API_URL = process.env.API_URL ?? (IS_PROD ? 'https://api.example.com' : 'http://10.0.2.2:8000');

if (IS_PROD && !API_URL.startsWith('https://')) {
  throw new Error('В production API_URL должен начинаться с https://');
}

export default ({ config }: ConfigContext): ExpoConfig => ({
  ...config,
  name: IS_PROD ? 'Конспекты лекций' : 'Конспекты (dev)',
  slug: 'lecture-notes',
  scheme: 'lecturenotes',
  version: '1.1.0',
  orientation: 'portrait',
  icon: './assets/icon.png',
  ios: {
    supportsTablet: true,
    bundleIdentifier: IS_PROD ? 'ru.lecturenotes.mobile' : 'ru.lecturenotes.mobile.dev',
    // В production ATS включён полностью: только HTTPS.
    infoPlist: IS_PROD ? {} : { NSAppTransportSecurity: { NSAllowsLocalNetworking: true, NSAllowsArbitraryLoads: true } },
  },
  android: {
    package: IS_PROD ? 'ru.lecturenotes.mobile' : 'ru.lecturenotes.mobile.dev',
    versionCode: 2,
    allowBackup: false,
    adaptiveIcon: {
      backgroundColor: '#3A47C2',
      foregroundImage: './assets/android-icon-foreground.png',
      backgroundImage: './assets/android-icon-background.png',
      monochromeImage: './assets/android-icon-monochrome.png',
    },
    predictiveBackGestureEnabled: false,
  },
  web: { favicon: './assets/favicon.png' },
  plugins: [
    'expo-router',
    'expo-sqlite',
    'expo-secure-store',
    [
      'expo-audio',
      {
        microphonePermission: 'Приложению нужен микрофон, чтобы записывать лекции.',
        enableBackgroundRecording: true,
      },
    ],
    [
      'expo-image-picker',
      {
        cameraPermission: 'Камера нужна, чтобы фотографировать слайды и снимать видео.',
        photosPermission: 'Доступ к галерее нужен, чтобы добавлять фото и видео в конспект.',
        microphonePermission: 'Микрофон нужен для записи видео со звуком.',
      },
    ],
    'expo-video',
    [
      'expo-build-properties',
      {
        android: {
          usesCleartextTraffic: !IS_PROD,
          enableProguardInReleaseBuilds: true,
          enableShrinkResourcesInReleaseBuilds: true,
        },
      },
    ],
  ],
  extra: {
    appEnv: APP_ENV,
    apiUrl: API_URL,
  },
});
