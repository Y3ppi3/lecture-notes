import { Ionicons } from '@expo/vector-icons';
import * as ImagePicker from 'expo-image-picker';
import { router, Stack, useLocalSearchParams } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { Alert, FlatList, Linking, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { BlockView, type BlockHandlers } from '../../components/BlockView';
import { LectureDialog } from '../../components/LectureDialog';
import { ImageViewer, VideoViewer } from '../../components/MediaViewers';
import { colors } from '../../components/theme';
import { ActionSheet, Button, Dialog, IconButton, TextPrompt, ui, type MenuItem } from '../../components/ui';
import {
  addBlock,
  deleteBlock,
  deleteLecture,
  moveBlock,
  positionAfter,
  renameLecture,
  updateBlock,
  useBlocks,
  useLecture,
} from '../../lib/db';
import { copyText, shareMarkdownFile, shareText } from '../../lib/export';
import { compressImage, importFile } from '../../lib/media';
import { correctTextBlock, retryBlock } from '../../lib/processor';
import { useRecorder } from '../../lib/recorder';
import type { Block } from '../../lib/types';

type TextEdit = { block?: Block; after?: Block; initial: string } | null;

export default function LectureScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const lectureId = Number(id);
  const lecture = useLecture(lectureId);
  const blocks = useBlocks(lectureId);
  const rec = useRecorder();
  const recordingHere = rec.state.lectureId === lectureId;
  const insets = useSafeAreaInsets();
  const listRef = useRef<FlatList<Block>>(null);

  const [textEdit, setTextEdit] = useState<TextEdit>(null);
  const [captionOf, setCaptionOf] = useState<Block | null>(null);
  const [originalOf, setOriginalOf] = useState<Block | null>(null);
  const [menuOf, setMenuOf] = useState<Block | null>(null);
  const [imageUri, setImageUri] = useState<string | null>(null);
  const [videoUri, setVideoUri] = useState<string | null>(null);
  const [videoSheet, setVideoSheet] = useState(false);
  const [moreSheet, setMoreSheet] = useState(false);
  const [linkDialog, setLinkDialog] = useState(false);
  const [renaming, setRenaming] = useState(false);

  // Ошибки записи — всплывающим окном.
  useEffect(() => {
    if (rec.error) {
      Alert.alert('Запись', rec.error);
      rec.clearError();
    }
  }, [rec.error, rec]);

  // Во время записи держим в поле зрения конец конспекта.
  useEffect(() => {
    if (recordingHere && blocks.length) {
      setTimeout(() => listRef.current?.scrollToEnd({ animated: true }), 150);
    }
  }, [blocks.length, recordingHere]);

  /**
   * Позиции для новых медиа-блоков. Если сейчас пишется эта лекция — режем аудио
   * в текущий момент, чтобы фото встало точно между фрагментами речи.
   */
  const mediaPositions = async (count: number): Promise<(number | undefined)[]> => {
    if (!recordingHere) return Array(count).fill(undefined);
    const at = Date.now();
    await rec.split(at);
    return Array.from({ length: count }, (_, i) => at + i * 0.01);
  };

  const addAssets = async (assets: ImagePicker.ImagePickerAsset[], kind: 'images' | 'videos') => {
    try {
      const positions = await mediaPositions(assets.length);
      for (let i = 0; i < assets.length; i++) {
        const a = assets[i];
        if (kind === 'images' && a.type && a.type !== 'image') continue;
        if (kind === 'videos' && a.type && a.type !== 'video') continue;
        // Фото сжимаем перед сохранением: меньше места на телефоне и быстрее экспорт.
        const source = kind === 'images' ? await compressImage(a.uri, a.width, a.height) : a.uri;
        const uri = importFile(source, lectureId, kind, kind === 'images');
        await addBlock({ lectureId, type: kind === 'images' ? 'IMAGE' : 'VIDEO', mediaUri: uri, position: positions[i] });
      }
    } catch {
      Alert.alert('Не удалось добавить файл');
    }
  };

  const capture = async (kind: 'images' | 'videos') => {
    const perm = await ImagePicker.requestCameraPermissionsAsync();
    if (!perm.granted) {
      Alert.alert('Нет доступа к камере', 'Разрешите доступ к камере в настройках телефона.');
      return;
    }
    const res = await ImagePicker.launchCameraAsync({ mediaTypes: kind, quality: 1, exif: false, videoMaxDuration: 600 });
    if (!res.canceled) await addAssets(res.assets, kind);
  };

  const pick = async (kind: 'images' | 'videos') => {
    const res = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: kind,
      quality: 1,
      exif: false,
      allowsMultipleSelection: kind === 'images',
      selectionLimit: 20,
    });
    if (!res.canceled) await addAssets(res.assets, kind);
  };

  const startRecording = async () => {
    if (rec.state.lectureId != null && !recordingHere) {
      Alert.alert('Идёт запись', 'Сейчас записывается другая лекция — сначала остановите её.');
      return;
    }
    await rec.start(lectureId);
  };

  const handlers: BlockHandlers = {
    onMenu: setMenuOf,
    onEdit: (b) => setTextEdit({ block: b, initial: b.text }),
    onRetry: (b) => { retryBlock(b.id); },
    onDelete: (b) => deleteBlock(b),
    onOpenImage: (b) => setImageUri(b.mediaUri),
    onOpenVideo: (b) => setVideoUri(b.mediaUri),
    onOpenLink: (b) => Linking.openURL(b.text).catch(() => Alert.alert('Не удалось открыть ссылку')),
  };

  const blockMenu = (b: Block): MenuItem[] => {
    const idx = blocks.findIndex((x) => x.id === b.id);
    const items: MenuItem[] = [];
    if (b.type === 'TEXT') {
      items.push({ label: 'Редактировать', icon: 'create-outline', onPress: () => handlers.onEdit(b) });
      items.push({
        label: 'Исправить с ИИ', icon: 'sparkles-outline',
        onPress: async () => {
          const err = await correctTextBlock(b.id);
          if (err) Alert.alert('ИИ-коррекция', err);
        },
      });
      if (b.rawText != null && b.rawText !== b.text) {
        items.push({ label: 'Исходный текст', icon: 'time-outline', onPress: () => setOriginalOf(b) });
      }
    } else {
      items.push({ label: 'Подпись', icon: 'text-outline', onPress: () => setCaptionOf(b) });
    }
    items.push({ label: 'Вставить текст ниже', icon: 'add-circle-outline', onPress: () => setTextEdit({ after: b, initial: '' }) });
    if (idx > 0) items.push({ label: 'Переместить выше', icon: 'arrow-up', onPress: () => moveBlock(b, -1) });
    if (idx < blocks.length - 1) items.push({ label: 'Переместить ниже', icon: 'arrow-down', onPress: () => moveBlock(b, 1) });
    items.push({ label: 'Удалить', icon: 'trash-outline', danger: true, onPress: () => deleteBlock(b) });
    return items;
  };

  const minutes = Math.floor(rec.state.elapsedMs / 60000);
  const seconds = Math.floor((rec.state.elapsedMs % 60000) / 1000);
  const elapsed = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;

  return (
    <View style={s.screen}>
      <Stack.Screen
        options={{
          title: lecture?.title ?? '',
          headerRight: () => (
            <View style={s.row}>
              <IconButton name="share-outline" label="Поделиться" onPress={() => lecture && shareText(lecture, blocks)} />
              <IconButton name="ellipsis-vertical" label="Ещё" onPress={() => setMoreSheet(true)} />
            </View>
          ),
        }}
      />

      {recordingHere && (
        <View style={s.banner}>
          <View style={s.row}>
            <Ionicons name="mic" size={18} color={rec.state.paused ? colors.muted : colors.danger} />
            <Text style={s.bannerText}>{rec.state.paused ? 'Пауза' : 'Запись'} · {elapsed}</Text>
            <Text style={s.bannerHint}>Фото встанет в текст по времени</Text>
          </View>
          <View style={s.levelTrack}>
            <View style={[s.levelBar, { width: `${Math.round(rec.state.level * 100)}%` }]} />
          </View>
        </View>
      )}

      <FlatList
        ref={listRef}
        data={blocks}
        keyExtractor={(b) => String(b.id)}
        contentContainerStyle={[s.list, blocks.length === 0 && { flex: 1 }]}
        renderItem={({ item }) => <BlockView block={item} h={handlers} />}
        ListEmptyComponent={
          <View style={s.empty}>
            <Text style={s.emptyTitle}>Лекция пока пустая</Text>
            <Text style={s.emptyText}>
              Нажмите «Запись» — речь будет расшифрована и исправлена ИИ каждые 3 минуты.
              Фото слайдов, видео и свои заметки добавляются кнопками внизу.
            </Text>
          </View>
        }
      />

      <View style={[s.toolbar, { paddingBottom: insets.bottom + 8 }]}>
        <IconButton name="camera-outline" label="Сфотографировать слайд" onPress={() => capture('images')} />
        <IconButton name="images-outline" label="Фото из галереи" onPress={() => pick('images')} />
        <IconButton name="videocam-outline" label="Добавить видео" onPress={() => setVideoSheet(true)} />
        <IconButton name="document-text-outline" label="Добавить текст" onPress={() => setTextEdit({ initial: '' })} />
        <View style={{ flex: 1 }} />
        {recordingHere ? (
          <View style={s.row}>
            <IconButton
              name={rec.state.paused ? 'play-circle' : 'pause-circle'}
              size={40} color={colors.primary}
              label={rec.state.paused ? 'Продолжить' : 'Пауза'}
              onPress={rec.state.paused ? rec.resume : rec.pause}
            />
            <IconButton name="stop-circle" size={40} color={colors.danger} label="Остановить" onPress={() => rec.stop()} />
          </View>
        ) : (
          <Button title="Запись" icon="mic" variant="danger" onPress={startRecording} />
        )}
      </View>

      {/* ---------- меню и диалоги ---------- */}

      <ActionSheet
        visible={!!menuOf}
        items={menuOf ? blockMenu(menuOf) : []}
        onClose={() => setMenuOf(null)}
      />

      <ActionSheet
        visible={videoSheet}
        title="Добавить видео"
        onClose={() => setVideoSheet(false)}
        items={[
          { label: 'Снять видео', icon: 'videocam-outline', onPress: () => capture('videos') },
          { label: 'Видео из галереи', icon: 'film-outline', onPress: () => pick('videos') },
          { label: 'Ссылка на видео', icon: 'link-outline', onPress: () => setLinkDialog(true) },
        ]}
      />

      <ActionSheet
        visible={moreSheet}
        onClose={() => setMoreSheet(false)}
        items={[
          { label: 'Переименовать', icon: 'create-outline', onPress: () => setRenaming(true) },
          { label: 'Копировать текст', icon: 'copy-outline', onPress: () => lecture && copyText(lecture, blocks) },
          { label: 'Экспорт в .md файл', icon: 'document-outline', onPress: () => lecture && shareMarkdownFile(lecture, blocks) },
          { label: 'Настройки', icon: 'settings-outline', onPress: () => router.push('/settings') },
          {
            label: 'Удалить лекцию', icon: 'trash-outline', danger: true,
            onPress: () => {
              if (recordingHere) {
                Alert.alert('Идёт запись', 'Остановите запись перед удалением.');
                return;
              }
              Alert.alert('Удалить лекцию?', 'Лекция будет удалена вместе со всеми фото, видео и записями.', [
                { text: 'Отмена', style: 'cancel' },
                { text: 'Удалить', style: 'destructive', onPress: async () => { await deleteLecture(lectureId); router.back(); } },
              ]);
            },
          },
        ]}
      />

      <TextPrompt
        visible={!!textEdit}
        title={textEdit?.block ? 'Редактирование' : 'Новый текст'}
        initial={textEdit?.initial ?? ''}
        multiline
        onCancel={() => setTextEdit(null)}
        onSubmit={async (v) => {
          const edit = textEdit;
          setTextEdit(null);
          if (!edit || !v.trim()) return;
          if (edit.block) {
            await updateBlock(edit.block.id, { text: v.trim() });
          } else {
            const position = edit.after ? await positionAfter(edit.after) : undefined;
            await addBlock({ lectureId, type: 'TEXT', text: v.trim(), position });
          }
        }}
      />

      <TextPrompt
        visible={!!captionOf}
        title="Подпись"
        initial={captionOf?.caption ?? ''}
        onCancel={() => setCaptionOf(null)}
        onSubmit={async (v) => {
          const b = captionOf;
          setCaptionOf(null);
          if (b) await updateBlock(b.id, { caption: v.trim() });
        }}
      />

      <Dialog
        visible={!!originalOf}
        title="Исходный текст"
        onClose={() => setOriginalOf(null)}
        actions={
          <>
            <Button title="Закрыть" variant="ghost" onPress={() => setOriginalOf(null)} />
            <Button
              title="Вернуть"
              onPress={async () => {
                const b = originalOf;
                setOriginalOf(null);
                if (b?.rawText != null) await updateBlock(b.id, { text: b.rawText });
              }}
            />
          </>
        }
      >
        <ScrollView style={{ maxHeight: 360 }}>
          <Text style={{ fontSize: 15, color: colors.text }}>{originalOf?.rawText}</Text>
        </ScrollView>
      </Dialog>

      <VideoLinkDialog
        visible={linkDialog}
        onCancel={() => setLinkDialog(false)}
        onSubmit={async (url, caption) => {
          setLinkDialog(false);
          if (!/^https?:\/\//i.test(url.trim())) {
            Alert.alert('Ссылка должна начинаться с http:// или https://');
            return;
          }
          await addBlock({ lectureId, type: 'VIDEO_LINK', text: url.trim(), caption: caption.trim() });
        }}
      />

      <LectureDialog
        visible={renaming}
        title="Лекция"
        submitLabel="Сохранить"
        initialTitle={lecture?.title}
        initialSubject={lecture?.subject}
        onCancel={() => setRenaming(false)}
        onSubmit={(t, sub) => { setRenaming(false); renameLecture(lectureId, t, sub); }}
      />

      <ImageViewer uri={imageUri} onClose={() => setImageUri(null)} />
      <VideoViewer uri={videoUri} onClose={() => setVideoUri(null)} />
    </View>
  );
}

function VideoLinkDialog({
  visible, onCancel, onSubmit,
}: { visible: boolean; onCancel: () => void; onSubmit: (url: string, caption: string) => void }) {
  return (
    <Dialog visible={visible} title="Ссылка на видео" onClose={onCancel}>
      {/* Форма монтируется заново при каждом открытии — поля всегда пустые. */}
      {visible && <VideoLinkForm onCancel={onCancel} onSubmit={onSubmit} />}
    </Dialog>
  );
}

function VideoLinkForm({ onCancel, onSubmit }: { onCancel: () => void; onSubmit: (url: string, caption: string) => void }) {
  const [url, setUrl] = useState('');
  const [caption, setCaption] = useState('');
  return (
    <>
      <TextInput
        style={ui.input} value={url} onChangeText={setUrl} placeholder="https://…"
        autoCapitalize="none" autoCorrect={false} keyboardType="url" autoFocus
      />
      <TextInput style={ui.input} value={caption} onChangeText={setCaption} placeholder="Подпись (необязательно)" />
      <View style={ui.dialogActions}>
        <Button title="Отмена" variant="ghost" onPress={onCancel} />
        <Button title="Добавить" onPress={() => onSubmit(url, caption)} disabled={!url.trim()} />
      </View>
    </>
  );
}

const s = StyleSheet.create({
  screen: { flex: 1 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  list: { padding: 16, gap: 12, paddingBottom: 24 },
  banner: { paddingHorizontal: 16, paddingVertical: 8, backgroundColor: colors.card, borderBottomWidth: 1, borderColor: colors.border, gap: 6 },
  bannerText: { fontSize: 15, fontWeight: '600', color: colors.text },
  bannerHint: { marginLeft: 'auto', fontSize: 12, color: colors.muted },
  levelTrack: { height: 4, borderRadius: 2, backgroundColor: colors.primaryLight, overflow: 'hidden' },
  levelBar: { height: 4, backgroundColor: colors.danger },
  toolbar: {
    flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingTop: 8,
    backgroundColor: colors.card, borderTopWidth: 1, borderColor: colors.border,
  },
  empty: { flex: 1, justifyContent: 'center', alignItems: 'center', gap: 8, padding: 24 },
  emptyTitle: { fontSize: 18, fontWeight: '600', color: colors.text },
  emptyText: { fontSize: 15, color: colors.muted, textAlign: 'center' },
});
