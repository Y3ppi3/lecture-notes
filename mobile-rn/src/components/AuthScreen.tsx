import { Ionicons } from '@expo/vector-icons';
import { useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { ApiError, login, register } from '../lib/api';
import { colors } from './theme';
import { Button, ui } from './ui';

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const MIN_PASSWORD = 8;

export function AuthScreen() {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    if (!EMAIL.test(email.trim())) return setError('Введите корректный email');
    if (password.length < MIN_PASSWORD) return setError(`Пароль — не короче ${MIN_PASSWORD} символов`);
    if (password.length > 128) return setError('Слишком длинный пароль');
    setBusy(true);
    setError(null);
    try {
      await (mode === 'login' ? login : register)(email, password);
      // Экран сменится сам: layout подписан на сессию.
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Непредвиденная ошибка');
      setBusy(false);
    }
  };

  return (
    <SafeAreaView style={s.screen}>
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
        <ScrollView contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
          <Ionicons name="book-outline" size={56} color={colors.primary} />
          <Text style={s.title}>{mode === 'login' ? 'Вход' : 'Регистрация'}</Text>
          <Text style={s.hint}>
            Аккаунт нужен для распознавания речи и ИИ-коррекции. Конспекты хранятся только на телефоне.
          </Text>
          <TextInput
            style={[ui.input, s.full]} value={email} onChangeText={setEmail} placeholder="Email"
            autoCapitalize="none" autoCorrect={false} keyboardType="email-address" autoComplete="email"
            textContentType="emailAddress"
          />
          <View style={[s.full, s.passwordRow]}>
            <TextInput
              style={[ui.input, { flex: 1 }]} value={password} onChangeText={setPassword} placeholder="Пароль"
              secureTextEntry={!showPassword} autoCapitalize="none" autoCorrect={false}
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              textContentType={mode === 'login' ? 'password' : 'newPassword'}
              onSubmitEditing={() => !busy && submit()}
            />
            <Pressable onPress={() => setShowPassword((v) => !v)} hitSlop={8} accessibilityLabel="Показать пароль">
              <Ionicons name={showPassword ? 'eye-off-outline' : 'eye-outline'} size={22} color={colors.muted} />
            </Pressable>
          </View>
          {mode === 'register' && <Text style={s.hintSmall}>Не короче 8 символов</Text>}
          {error && <Text style={s.error}>{error}</Text>}
          <View style={s.full}>
            {busy ? (
              <ActivityIndicator color={colors.primary} />
            ) : (
              <Button title={mode === 'login' ? 'Войти' : 'Создать аккаунт'} onPress={submit} />
            )}
          </View>
          <Button
            title={mode === 'login' ? 'Нет аккаунта? Зарегистрироваться' : 'Уже есть аккаунт? Войти'}
            variant="ghost"
            onPress={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(null); }}
          />
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const s = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.bg },
  content: { flexGrow: 1, justifyContent: 'center', alignItems: 'center', padding: 24, gap: 12 },
  title: { fontSize: 24, fontWeight: '700', color: colors.text },
  hint: { fontSize: 14, color: colors.muted, textAlign: 'center', marginBottom: 8 },
  hintSmall: { fontSize: 12, color: colors.muted, alignSelf: 'flex-start' },
  error: { color: colors.danger, textAlign: 'center' },
  full: { alignSelf: 'stretch' },
  passwordRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
});
