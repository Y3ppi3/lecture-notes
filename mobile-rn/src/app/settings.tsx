import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { Alert, ScrollView, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { colors } from '../components/theme';
import { Button, Dialog, ui } from '../components/ui';
import { clearAiKeys, useAiKeys } from '../lib/aiKeys';
import { ApiError, deleteAccount, health, logout, usage, type UsageResponse } from '../lib/api';
import { ALLOW_SERVER_OVERRIDE, APP_VERSION } from '../lib/config';
import { wipeAll } from '../lib/db';
import { useRecorder } from '../lib/recorder';
import { clearSession } from '../lib/session';
import { loadSettings, normalizeUrl, saveSettings, type AppSettings } from '../lib/settings';
import { useSession } from '../lib/useSession';

export default function SettingsScreen() {
  const { session } = useSession();
  const rec = useRecorder();
  const aiKeys = useAiKeys();
  const [form, setForm] = useState<AppSettings | null>(null);
  const [stats, setStats] = useState<UsageResponse | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [password, setPassword] = useState('');

  useEffect(() => {
    loadSettings().then(setForm);
    usage().then(setStats).catch(() => undefined);
  }, []);

  if (!form) return null;
  const edit = (patch: Partial<AppSettings>) => {
    setForm({ ...form, ...patch });
    setStatus(null);
  };

  const check = async () => {
    setBusy(true);
    try {
      await health(normalizeUrl(form.serverUrl));
      setStatus('Сервер доступен');
    } catch (e) {
      setStatus(e instanceof ApiError ? e.message : 'Нет связи с сервером');
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    const before = (await loadSettings()).serverUrl;
    await saveSettings(form);
    // Токены выданы конкретным сервером — при смене адреса нужно войти заново.
    if ((await loadSettings()).serverUrl !== before) await logout();
    else router.back();
  };

  const confirmLogout = () =>
    Alert.alert(
      'Выйти из аккаунта?',
      'Конспекты останутся на телефоне. Новые записи не будут распознаваться, пока вы не войдёте снова.',
      [
        { text: 'Отмена', style: 'cancel' },
        {
          text: 'Выйти',
          style: 'destructive',
          // Ключи нейросетей привязаны к человеку — при выходе удаляем их с телефона.
          onPress: () => { void clearAiKeys().then(logout); },
        },
      ],
    );

  const removeAccount = async () => {
    setBusy(true);
    try {
      await deleteAccount(password);
    } catch (e) {
      setBusy(false);
      Alert.alert('Не удалось удалить аккаунт', e instanceof ApiError ? e.message : 'Непредвиденная ошибка');
      return;
    }
    setDeleting(false);
    await rec.stop().catch(() => undefined);
    await wipeAll();
    await clearAiKeys();
    await clearSession();
  };

  return (
    <ScrollView contentContainerStyle={s.page} keyboardShouldPersistTaps="handled">
      <Text style={s.section}>Аккаунт</Text>
      <Text style={s.value}>{session?.email}</Text>
      {stats && (
        <Text style={s.hint}>
          За последние сутки: аудио {stats.audio_mb_used} из {stats.audio_mb_limit} МБ, коррекция{' '}
          {stats.correct_chars_used} из {stats.correct_chars_limit} символов
        </Text>
      )}
      <View style={s.row}>
        <Button title="Выйти" variant="ghost" onPress={confirmLogout} />
        <Button title="Удалить аккаунт" variant="ghost" onPress={() => { setPassword(''); setDeleting(true); }} />
      </View>

      <Text style={s.section}>Нейросети</Text>
      <Text style={s.hint}>
        {[
          aiKeys.llm && `коррекция — свой ключ (${aiKeys.llm.title ?? aiKeys.llm.provider})`,
          aiKeys.stt && `распознавание — свой ключ (${aiKeys.stt.title ?? aiKeys.stt.provider})`,
        ].filter(Boolean).join('; ') || 'Используются нейросети сервера'}
      </Text>
      <View style={{ alignItems: 'flex-start' }}>
        <Button title="GigaChat, ChatGPT, Claude и другие: свои ключи" variant="ghost" onPress={() => router.push('/ai')} />
      </View>

      {ALLOW_SERVER_OVERRIDE && (
        <>
          <Text style={s.section}>Сервер (только dev-сборка)</Text>
          <TextInput
            style={ui.input} value={form.serverUrl} onChangeText={(v) => edit({ serverUrl: v })}
            autoCapitalize="none" autoCorrect={false} keyboardType="url"
          />
          <Text style={s.hint}>Эмулятор Android: http://10.0.2.2:8000 · телефон: http://&lt;IP компьютера&gt;:8000</Text>
          <View style={{ alignItems: 'flex-start' }}>
            <Button title="Проверить соединение" variant="ghost" onPress={check} disabled={busy} />
          </View>
        </>
      )}
      {status && <Text style={s.value}>{status}</Text>}

      <Text style={s.section}>Распознавание</Text>
      <Text style={s.label}>Язык лекций (ru, en, de…)</Text>
      <TextInput
        style={ui.input} value={form.language} maxLength={2} onChangeText={(v) => edit({ language: v })}
        autoCapitalize="none" autoCorrect={false}
      />
      <View style={s.switchRow}>
        <View style={{ flex: 1 }}>
          <Text style={s.value}>ИИ-коррекция текста</Text>
          <Text style={s.hint}>Исправлять ошибки распознавания, пунктуацию и разбивать на абзацы</Text>
        </View>
        <Switch value={form.autoCorrect} onValueChange={(v) => edit({ autoCorrect: v })} />
      </View>

      <Button title="Сохранить" onPress={save} />
      <Text style={[s.hint, { marginTop: 12 }]}>Версия {APP_VERSION}</Text>

      <Dialog
        visible={deleting}
        title="Удалить аккаунт?"
        onClose={() => setDeleting(false)}
        actions={
          <>
            <Button title="Отмена" variant="ghost" onPress={() => setDeleting(false)} />
            <Button title="Удалить" variant="danger" onPress={removeAccount} disabled={!password || busy} />
          </>
        }
      >
        <Text style={s.value}>
          Аккаунт будет удалён с сервера, а все конспекты, фото и записи — с этого телефона. Это нельзя отменить.
        </Text>
        <TextInput
          style={ui.input} value={password} onChangeText={setPassword} placeholder="Пароль"
          secureTextEntry autoCapitalize="none" autoCorrect={false}
        />
      </Dialog>
    </ScrollView>
  );
}

const s = StyleSheet.create({
  page: { padding: 16, gap: 8 },
  section: { fontSize: 18, fontWeight: '700', color: colors.text, marginTop: 12 },
  label: { fontSize: 14, color: colors.text, marginTop: 6 },
  value: { fontSize: 15, color: colors.text },
  hint: { fontSize: 12, color: colors.muted },
  row: { flexDirection: 'row', gap: 8, flexWrap: 'wrap' },
  switchRow: { flexDirection: 'row', alignItems: 'center', gap: 12, marginVertical: 12 },
});
