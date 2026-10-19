import { Ionicons } from '@expo/vector-icons';
import { router, Stack } from 'expo-router';
import { useState } from 'react';
import { Alert, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors } from '../components/theme';
import { LectureDialog } from '../components/LectureDialog';
import { Button, IconButton } from '../components/ui';
import { createLecture, deleteLecture, useLectures } from '../lib/db';
import { useRecorder } from '../lib/recorder';
import type { Lecture } from '../lib/types';

const fmt = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });

export default function LecturesScreen() {
  const lectures = useLectures();
  const rec = useRecorder();
  const insets = useSafeAreaInsets();
  const [creating, setCreating] = useState(false);

  const confirmDelete = (l: Lecture) => {
    if (rec.state.lectureId === l.id) {
      Alert.alert('Идёт запись', 'Остановите запись этой лекции перед удалением.');
      return;
    }
    Alert.alert('Удалить лекцию?', `«${l.title}» будет удалена вместе со всеми фото, видео и записями.`, [
      { text: 'Отмена', style: 'cancel' },
      { text: 'Удалить', style: 'destructive', onPress: () => deleteLecture(l.id) },
    ]);
  };

  return (
    <View style={s.screen}>
      <Stack.Screen
        options={{
          headerRight: () => (
            <IconButton name="settings-outline" label="Настройки" onPress={() => router.push('/settings')} />
          ),
        }}
      />
      <FlatList
        data={lectures}
        keyExtractor={(l) => String(l.id)}
        contentContainerStyle={[s.list, lectures.length === 0 && { flex: 1 }]}
        ListEmptyComponent={
          <View style={s.empty}>
            <Ionicons name="book-outline" size={64} color={colors.primary} />
            <Text style={s.emptyTitle}>Пока нет ни одной лекции</Text>
            <Text style={s.emptyText}>
              Создайте лекцию и включите запись — текст появится сам. Фотографируйте слайды прямо во время записи.
            </Text>
          </View>
        }
        renderItem={({ item }) => (
          <Pressable
            style={({ pressed }) => [s.card, pressed && { opacity: 0.7 }]}
            onPress={() => router.push(`/lecture/${item.id}`)}
          >
            <View style={{ flex: 1 }}>
              <Text style={s.title} numberOfLines={2}>{item.title}</Text>
              {!!item.subject && <Text style={s.subject}>{item.subject}</Text>}
              <Text style={s.date}>{fmt.format(new Date(item.updatedAt))}</Text>
            </View>
            {rec.state.lectureId === item.id ? (
              <Ionicons name="radio-button-on" size={22} color={colors.danger} />
            ) : (
              <IconButton name="trash-outline" color={colors.muted} label="Удалить" onPress={() => confirmDelete(item)} />
            )}
          </Pressable>
        )}
      />
      <View style={[s.fab, { bottom: insets.bottom + 20 }]}>
        <Button title="Новая лекция" icon="add" onPress={() => setCreating(true)} />
      </View>

      <LectureDialog
        visible={creating}
        title="Новая лекция"
        submitLabel="Создать"
        onCancel={() => setCreating(false)}
        onSubmit={async (t, sub) => {
          setCreating(false);
          const id = await createLecture(t, sub);
          router.push(`/lecture/${id}`);
        }}
      />
    </View>
  );
}

const s = StyleSheet.create({
  screen: { flex: 1 },
  list: { padding: 16, paddingBottom: 110, gap: 10 },
  card: {
    flexDirection: 'row', alignItems: 'center', gap: 8,
    backgroundColor: colors.card, borderRadius: 14, padding: 16, borderWidth: 1, borderColor: colors.border,
  },
  title: { fontSize: 17, fontWeight: '600', color: colors.text },
  subject: { fontSize: 14, color: colors.primary, marginTop: 2 },
  date: { fontSize: 12, color: colors.muted, marginTop: 4 },
  empty: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 10, padding: 32 },
  emptyTitle: { fontSize: 18, fontWeight: '600', color: colors.text },
  emptyText: { fontSize: 15, color: colors.muted, textAlign: 'center' },
  fab: { position: 'absolute', right: 20 },
});
