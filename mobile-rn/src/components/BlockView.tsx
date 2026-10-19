import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useEffect, useState } from 'react';
import { ActivityIndicator, Animated, Pressable, StyleSheet, Text, View } from 'react-native';
import type { Block } from '../lib/types';
import { Markdown } from './Markdown';
import { colors } from './theme';
import { Button, IconButton } from './ui';

export interface BlockHandlers {
  onMenu(block: Block): void;
  onEdit(block: Block): void;
  onRetry(block: Block): void;
  onDelete(block: Block): void;
  onOpenImage(block: Block): void;
  onOpenVideo(block: Block): void;
  onOpenLink(block: Block): void;
}

export function BlockView({ block, h }: { block: Block; h: BlockHandlers }) {
  if (block.type === 'TEXT') {
    switch (block.status) {
      case 'RECORDING':
        return <RecordingRow />;
      case 'PROCESSING':
        return (
          <View style={s.status}>
            <ActivityIndicator color={colors.primary} />
            <Text style={s.statusText}>
              {block.audioUri && block.rawText == null ? 'Распознаю речь и исправляю текст…' : 'ИИ исправляет текст…'}
            </Text>
          </View>
        );
      case 'ERROR':
        return (
          <View style={s.error}>
            <View style={s.row}>
              <Ionicons name="alert-circle-outline" size={20} color={colors.danger} />
              <Text style={s.errorTitle}>Фрагмент не обработан</Text>
            </View>
            {block.error && <Text style={s.errorText}>{block.error}</Text>}
            <View style={s.row}>
              <Button title="Повторить" icon="refresh" onPress={() => h.onRetry(block)} />
              <Button title="Удалить" variant="ghost" onPress={() => h.onDelete(block)} />
            </View>
          </View>
        );
      default:
        return (
          <View style={s.textBlock}>
            <Pressable style={s.flex} onPress={() => h.onEdit(block)} onLongPress={() => h.onMenu(block)}>
              <Markdown text={block.text} />
            </Pressable>
            <IconButton name="ellipsis-vertical" size={18} color={colors.muted} label="Действия" onPress={() => h.onMenu(block)} />
          </View>
        );
    }
  }

  return (
    <View style={s.card}>
      {block.type === 'IMAGE' && (
        <Pressable onPress={() => h.onOpenImage(block)}>
          <Image source={{ uri: block.mediaUri ?? undefined }} style={s.image} contentFit="contain" />
        </Pressable>
      )}
      {block.type === 'VIDEO' && (
        <Pressable style={s.video} onPress={() => h.onOpenVideo(block)}>
          <View style={s.play}><Ionicons name="play" size={34} color="#fff" /></View>
          <Text style={s.videoLabel}>Видео · нажмите, чтобы посмотреть</Text>
        </Pressable>
      )}
      {block.type === 'VIDEO_LINK' && (
        <Pressable style={s.link} onPress={() => h.onOpenLink(block)}>
          <Ionicons name="logo-youtube" size={26} color={colors.danger} />
          <View style={s.flex}>
            <Text style={s.linkLabel}>Видео по ссылке</Text>
            <Text style={s.linkUrl} numberOfLines={2}>{block.text}</Text>
          </View>
        </Pressable>
      )}
      <View style={s.cardFooter}>
        <Text style={[s.caption, !block.caption && { color: colors.muted }]} numberOfLines={3}>
          {block.caption || (block.type === 'IMAGE' ? 'Фото слайда' : 'Видео')}
        </Text>
        <IconButton name="ellipsis-vertical" size={18} color={colors.muted} label="Действия" onPress={() => h.onMenu(block)} />
      </View>
    </View>
  );
}

function RecordingRow() {
  const [opacity] = useState(() => new Animated.Value(1));
  useEffect(() => {
    const loop = Animated.loop(Animated.sequence([
      Animated.timing(opacity, { toValue: 0.25, duration: 700, useNativeDriver: true }),
      Animated.timing(opacity, { toValue: 1, duration: 700, useNativeDriver: true }),
    ]));
    loop.start();
    return () => loop.stop();
  }, [opacity]);
  return (
    <View style={s.status}>
      <Animated.View style={{ opacity }}><Ionicons name="mic" size={20} color={colors.danger} /></Animated.View>
      <Text style={s.statusText}>Идёт запись фрагмента…</Text>
    </View>
  );
}

const s = StyleSheet.create({
  flex: { flex: 1 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  textBlock: { flexDirection: 'row', alignItems: 'flex-start', gap: 4 },
  status: {
    flexDirection: 'row', alignItems: 'center', gap: 12, padding: 14,
    borderRadius: 12, backgroundColor: colors.primaryLight,
  },
  statusText: { color: colors.muted, fontSize: 15 },
  error: { padding: 12, borderRadius: 12, backgroundColor: colors.dangerLight, gap: 8 },
  errorTitle: { fontWeight: '600', color: colors.danger, fontSize: 15 },
  errorText: { color: colors.text, fontSize: 13 },
  card: { backgroundColor: colors.card, borderRadius: 14, overflow: 'hidden', borderWidth: 1, borderColor: colors.border },
  image: { width: '100%', aspectRatio: 4 / 3, backgroundColor: '#000' },
  video: { width: '100%', aspectRatio: 16 / 9, backgroundColor: '#111', alignItems: 'center', justifyContent: 'center', gap: 10 },
  play: { width: 60, height: 60, borderRadius: 30, backgroundColor: 'rgba(255,255,255,0.2)', alignItems: 'center', justifyContent: 'center' },
  videoLabel: { color: '#ddd', fontSize: 13 },
  link: { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 14 },
  linkLabel: { fontSize: 12, color: colors.muted },
  linkUrl: { fontSize: 14, color: colors.primary },
  cardFooter: { flexDirection: 'row', alignItems: 'center', paddingLeft: 12, paddingRight: 4, paddingVertical: 4 },
  caption: { flex: 1, fontSize: 14, color: colors.text },
});
