import { Ionicons } from '@expo/vector-icons';
import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { colors } from '../components/theme';
import { ActionSheet, Button, ui } from '../components/ui';
import { setAiKey, useAiKeys, type AiKey, type AiKind } from '../lib/aiKeys';
import { aiProviders, ApiError, checkAiKey, type ProviderInfo, type ProvidersResponse } from '../lib/api';

const AUTO = 'auto';

interface Draft {
  provider: string;
  apiKey: string;
  model: string;
  baseUrl: string;
  scope: string;
}

const EMPTY: Draft = { provider: AUTO, apiKey: '', model: '', baseUrl: '', scope: '' };

function scopeLabel(scope: string) {
  if (scope.endsWith('_PERS')) return 'Физическое лицо';
  if (scope.endsWith('_B2B')) return 'ИП или юрлицо (пакеты)';
  if (scope.endsWith('_CORP')) return 'ИП или юрлицо (оплата по факту)';
  return scope;
}

export default function AiKeysScreen() {
  const [catalog, setCatalog] = useState<ProvidersResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const fetchCatalog = useCallback(() => {
    aiProviders()
      .then(setCatalog)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Нет связи с сервером'));
  }, []);
  useEffect(fetchCatalog, [fetchCatalog]);
  const load = () => {
    setError(null);
    fetchCatalog();
  };

  return (
    <ScrollView contentContainerStyle={s.page} keyboardShouldPersistTaps="handled">
      <Text style={s.hint}>
        Можно пользоваться нейросетями сервера или подключить свой ключ: GigaChat, ChatGPT, Claude, Gemini,
        DeepSeek и другие. Ключ хранится только на этом телефоне в зашифрованном виде и передаётся серверу лишь
        вместе с запросом.
      </Text>
      {!catalog ? (
        error ? (
          <View style={{ gap: 8 }}>
            <Text style={s.error}>{error}</Text>
            <View style={{ alignItems: 'flex-start' }}><Button title="Повторить" variant="ghost" onPress={load} /></View>
          </View>
        ) : (
          <ActivityIndicator color={colors.primary} />
        )
      ) : !catalog.allow_user_keys ? (
        <Text style={s.value}>Сервер не принимает собственные ключи — используются нейросети сервера.</Text>
      ) : (
        <>
          <KindSection
            kind="llm"
            title="Коррекция текста"
            providers={catalog.llm}
            serverTitle={catalog.server.llm.available ? catalog.server.llm.title : null}
          />
          <KindSection
            kind="stt"
            title="Распознавание речи"
            providers={catalog.stt}
            serverTitle={catalog.server.stt.available ? catalog.server.stt.title : null}
          />
        </>
      )}
    </ScrollView>
  );
}

function KindSection({
  kind, title, providers, serverTitle,
}: { kind: AiKind; title: string; providers: ProviderInfo[]; serverTitle: string | null }) {
  const keys = useAiKeys();
  const saved = keys[kind];
  const [ownKeyChoice, setOwnKeyChoice] = useState(false);
  const ownKey = ownKeyChoice || !!saved || !serverTitle;
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [status, setStatus] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [picker, setPicker] = useState(false);
  const [showKey, setShowKey] = useState(false);

  const selected = providers.find((p) => p.id === draft.provider);
  const edit = (patch: Partial<Draft>) => {
    setDraft((d) => ({ ...d, ...patch }));
    setStatus(null);
  };

  const useServer = async () => {
    setOwnKeyChoice(false);
    if (saved) await setAiKey(kind, undefined);
  };

  const save = async () => {
    if (!draft.apiKey.trim()) return setStatus('Вставьте ключ');
    const key: AiKey = {
      provider: draft.provider,
      api_key: draft.apiKey.trim(),
      ...(draft.model.trim() ? { model: draft.model.trim() } : {}),
      ...(draft.baseUrl.trim() ? { base_url: draft.baseUrl.trim() } : {}),
      ...(draft.scope ? { scope: draft.scope } : {}),
    };
    setBusy(true);
    setStatus(null);
    try {
      const r = await checkAiKey(kind, key);
      if (r.ok && r.provider) {
        await setAiKey(kind, { ...key, provider: r.provider, title: r.title ?? undefined });
        setDraft(EMPTY);
        setStatus(`Ключ подключён: ${r.title}`);
      } else {
        setStatus(r.message);
      }
    } catch (e) {
      setStatus(e instanceof ApiError ? e.message : 'Нет связи с сервером');
    } finally {
      setBusy(false);
    }
  };

  const scopes = selected?.scopes ?? [];
  const currentScope = draft.scope || scopes[0];
  const models = selected?.models ?? [];

  return (
    <View style={s.card}>
      <Text style={s.section}>{title}</Text>
      {serverTitle && <Choice label={`Нейросеть сервера: ${serverTitle}`} selected={!ownKey} onPress={useServer} />}
      <Choice label="Свой ключ" selected={ownKey} onPress={() => setOwnKeyChoice(true)} />

      {ownKey && (
        <>
          {saved && (
            <View style={s.savedRow}>
              <Ionicons name="checkmark-circle" size={22} color={colors.primary} />
              <View style={{ flex: 1 }}>
                <Text style={s.value}>{saved.title ?? saved.provider}</Text>
                <Text style={s.hint}>
                  Ключ ••••{saved.api_key.slice(-4)}{saved.model ? ` · ${saved.model}` : ''}
                </Text>
              </View>
              <Button title="Удалить" variant="ghost" onPress={() => { void setAiKey(kind, undefined); }} />
            </View>
          )}
          {saved && <Text style={s.hint}>Чтобы заменить ключ, заполните поля ниже.</Text>}

          <Pressable style={[ui.input, s.picker]} onPress={() => setPicker(true)}>
            <Text style={s.value}>Сервис: {selected?.title ?? 'Определить по ключу'}</Text>
            <Ionicons name="chevron-down" size={18} color={colors.muted} />
          </Pressable>

          <View style={s.keyRow}>
            <TextInput
              style={[ui.input, { flex: 1 }]} value={draft.apiKey} onChangeText={(v) => edit({ apiKey: v })}
              placeholder="Ключ API" secureTextEntry={!showKey} autoCapitalize="none" autoCorrect={false}
            />
            <Pressable onPress={() => setShowKey((v) => !v)} hitSlop={8} accessibilityLabel="Показать ключ">
              <Ionicons name={showKey ? 'eye-off-outline' : 'eye-outline'} size={22} color={colors.muted} />
            </Pressable>
          </View>
          <Text style={s.hint}>{selected?.key_hint ?? 'Сервис определится по формату ключа'}</Text>

          {selected?.custom_base_url && (
            <TextInput
              style={ui.input} value={draft.baseUrl} onChangeText={(v) => edit({ baseUrl: v })}
              placeholder="Адрес API (https://…/v1)" autoCapitalize="none" autoCorrect={false} keyboardType="url"
            />
          )}

          {scopes.length > 0 && (
            <>
              <Text style={s.label}>Тип доступа</Text>
              {scopes.map((sc) => (
                <Choice key={sc} label={scopeLabel(sc)} selected={currentScope === sc} onPress={() => edit({ scope: sc })} />
              ))}
            </>
          )}

          {(kind === 'llm' || selected?.custom_base_url) && (
            <>
              <TextInput
                style={ui.input} value={draft.model} onChangeText={(v) => edit({ model: v })}
                placeholder={`Модель (необязательно)${selected?.default_model ? `: ${selected.default_model}` : ''}`}
                autoCapitalize="none" autoCorrect={false}
              />
              {models.length > 0 && <Text style={s.hint}>Например: {models.join(', ')}</Text>}
            </>
          )}

          {busy ? (
            <ActivityIndicator color={colors.primary} />
          ) : (
            <Button title="Проверить и сохранить" onPress={save} />
          )}
        </>
      )}
      {status && <Text style={s.value}>{status}</Text>}

      <ActionSheet
        visible={picker}
        title="Сервис нейросети"
        onClose={() => setPicker(false)}
        items={[
          { label: 'Определить по ключу', icon: 'sparkles-outline', onPress: () => edit({ provider: AUTO, scope: '', model: '' }) },
          ...providers.map((p) => ({
            label: p.title,
            icon: 'cube-outline' as const,
            onPress: () => edit({ provider: p.id, scope: '', model: '' }),
          })),
        ]}
      />
    </View>
  );
}

function Choice({ label, selected, onPress }: { label: string; selected: boolean; onPress: () => void }) {
  return (
    <Pressable style={s.choice} onPress={onPress} accessibilityRole="radio" accessibilityState={{ selected }}>
      <Ionicons name={selected ? 'radio-button-on' : 'radio-button-off'} size={22} color={colors.primary} />
      <Text style={[s.value, { flex: 1 }]}>{label}</Text>
    </Pressable>
  );
}

const s = StyleSheet.create({
  page: { padding: 16, gap: 16 },
  card: { backgroundColor: colors.card, borderRadius: 14, padding: 16, gap: 10, borderWidth: 1, borderColor: colors.border },
  section: { fontSize: 18, fontWeight: '700', color: colors.text },
  label: { fontSize: 14, color: colors.text, marginTop: 4 },
  value: { fontSize: 15, color: colors.text },
  hint: { fontSize: 12, color: colors.muted },
  error: { color: colors.danger },
  choice: { flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 4 },
  savedRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  picker: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  keyRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
});
