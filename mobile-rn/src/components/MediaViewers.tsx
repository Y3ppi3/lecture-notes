import { Image } from 'expo-image';
import { useVideoPlayer, VideoView } from 'expo-video';
import { Modal, ScrollView, StyleSheet, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { IconButton } from './ui';

/** Полноэкранный просмотр фото с масштабированием (ScrollView zoom работает на iOS; на Android — просмотр). */
export function ImageViewer({ uri, onClose }: { uri: string | null; onClose: () => void }) {
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  return (
    <Modal visible={!!uri} animationType="fade" onRequestClose={onClose}>
      <View style={s.black}>
        <ScrollView
          contentContainerStyle={s.center}
          maximumZoomScale={5}
          minimumZoomScale={1}
          centerContent
        >
          {uri && <Image source={{ uri }} style={{ width, height }} contentFit="contain" />}
        </ScrollView>
        <View style={[s.close, { top: insets.top + 8 }]}>
          <IconButton name="close" color="#fff" size={28} label="Закрыть" onPress={onClose} />
        </View>
      </View>
    </Modal>
  );
}

export function VideoViewer({ uri, onClose }: { uri: string | null; onClose: () => void }) {
  return (
    <Modal visible={!!uri} animationType="fade" onRequestClose={onClose}>
      {uri && <Player uri={uri} onClose={onClose} />}
    </Modal>
  );
}

function Player({ uri, onClose }: { uri: string; onClose: () => void }) {
  const insets = useSafeAreaInsets();
  const player = useVideoPlayer(uri, (p) => p.play());
  return (
    <View style={s.black}>
      <VideoView player={player} style={s.full} nativeControls contentFit="contain" />
      <View style={[s.close, { top: insets.top + 8 }]}>
        <IconButton name="close" color="#fff" size={28} label="Закрыть" onPress={onClose} />
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  black: { flex: 1, backgroundColor: '#000' },
  center: { flexGrow: 1, justifyContent: 'center' },
  full: { width: '100%', height: '100%' },
  close: { position: 'absolute', right: 12, backgroundColor: 'rgba(0,0,0,0.4)', borderRadius: 24 },
});
