import * as Clipboard from 'expo-clipboard';
import * as Sharing from 'expo-sharing';
import { Share } from 'react-native';
import { writeCacheFile } from './media';
import type { Block, Lecture } from './types';

const fileName = (uri: string | null) => (uri ? uri.split('/').pop() ?? '' : '');

/** Конспект в Markdown. Фото и видео — ссылками на имена файлов. */
export function toMarkdown(lecture: Lecture, blocks: Block[]): string {
  const out: string[] = [`# ${lecture.title}`];
  if (lecture.subject) out.push(`_${lecture.subject}_`);
  out.push('');
  let slide = 0;
  for (const b of blocks) {
    switch (b.type) {
      case 'TEXT':
        if (b.status === 'READY' && b.text.trim()) out.push(b.text.trim(), '');
        break;
      case 'IMAGE':
        slide++;
        out.push(`![${b.caption || `Слайд ${slide}`}](${fileName(b.mediaUri)})`, '');
        break;
      case 'VIDEO':
        out.push(`Видео: ${b.caption || fileName(b.mediaUri)} (${fileName(b.mediaUri)})`, '');
        break;
      case 'VIDEO_LINK':
        out.push(`Видео: [${b.caption || 'Видео'}](${b.text})`, '');
        break;
    }
  }
  return out.join('\n').trimEnd() + '\n';
}

export async function shareText(lecture: Lecture, blocks: Block[]) {
  await Share.share({ title: lecture.title, message: toMarkdown(lecture, blocks) });
}

export async function shareMarkdownFile(lecture: Lecture, blocks: Block[]) {
  const safe = lecture.title.replace(/[^\p{L}\p{N} _-]+/gu, '').trim() || 'lecture';
  const uri = writeCacheFile(`${safe}.md`, toMarkdown(lecture, blocks));
  if (await Sharing.isAvailableAsync()) {
    await Sharing.shareAsync(uri, { mimeType: 'text/markdown', dialogTitle: 'Конспект лекции' });
  }
}

export async function copyText(lecture: Lecture, blocks: Block[]) {
  await Clipboard.setStringAsync(toMarkdown(lecture, blocks));
}
