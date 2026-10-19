import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { colors } from '../components/theme';
import { recoverInterruptedRecordings } from '../lib/db';
import { resumePending } from '../lib/processor';
import { RecorderProvider } from '../lib/recorder';
import { useSession } from '../lib/useSession';

export default function RootLayout() {
  const { session, loaded } = useSession();

  useEffect(() => {
    recoverInterruptedRecordings()
      .then(resumePending)
      .catch(() => undefined);
  }, []);

  if (!loaded) return <View style={{ flex: 1, backgroundColor: colors.bg }} />;

  return (
    <SafeAreaProvider>
      <StatusBar style="dark" />
      <RecorderProvider>
        <Stack
          screenOptions={{
            headerTintColor: colors.primary,
            headerTitleStyle: { color: colors.text },
            contentStyle: { backgroundColor: colors.bg },
          }}
        >
          {/* Без сессии доступен только экран входа; после входа — остальные. */}
          <Stack.Protected guard={!session}>
            <Stack.Screen name="login" options={{ headerShown: false }} />
          </Stack.Protected>
          <Stack.Protected guard={!!session}>
            <Stack.Screen name="index" options={{ title: 'Мои лекции' }} />
            <Stack.Screen name="lecture/[id]" options={{ title: '' }} />
            <Stack.Screen name="settings" options={{ title: 'Настройки' }} />
            <Stack.Screen name="ai" options={{ title: 'Нейросети' }} />
          </Stack.Protected>
        </Stack>
      </RecorderProvider>
    </SafeAreaProvider>
  );
}
