import { Directory, File as FsFile, Paths } from 'expo-file-system';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';

/** Медиафайлы лежат в documents/lectures/{lectureId}/{images|videos|audio}/ — в песочнице приложения. */
const ROOT = () => new Directory(Paths.document, 'lectures');

function lectureDir(lectureId: number): Directory {
  return new Directory(ROOT(), String(lectureId));
}

function subDir(lectureId: number, kind: 'images' | 'videos' | 'audio'): Directory {
  const dir = new Directory(lectureDir(lectureId), kind);
  if (!dir.exists) dir.create({ intermediates: true });
  return dir;
}

function stamp() {
  return `${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
}

const SAFE_EXT = /^[a-z0-9]{2,5}$/;

function extOf(uri: string, fallback: string) {
  const m = /\.([a-z0-9]{2,5})(?:\?|$)/i.exec(uri);
  const ext = m ? m[1].toLowerCase() : fallback;
  return SAFE_EXT.test(ext) ? ext : fallback;
}

/** Копирует (или переносит) файл во внутреннее хранилище лекции. */
export function importFile(
  sourceUri: string,
  lectureId: number,
  kind: 'images' | 'videos' | 'audio',
  move = false,
): string {
  const fallback = kind === 'images' ? 'jpg' : kind === 'videos' ? 'mp4' : 'm4a';
  const prefix = kind === 'images' ? 'img' : kind === 'videos' ? 'vid' : 'rec';
  const source = new FsFile(sourceUri);
  const target = new FsFile(subDir(lectureId, kind), `${prefix}_${stamp()}.${extOf(sourceUri, fallback)}`);
  if (move) source.moveSync(target);
  else source.copySync(target);
  return target.uri;
}

const MAX_SIDE = 2560;

/**
 * Уменьшает фото до 2560 px по длинной стороне и пересохраняет в JPEG 85%.
 * Снимок камеры 4–12 МБ превращается примерно в 0,5–1 МБ; EXIF (включая геолокацию) отбрасывается.
 * Возвращает URI сжатого файла во временной папке.
 */
export async function compressImage(uri: string, width: number, height: number): Promise<string> {
  const ctx = ImageManipulator.manipulate(uri);
  const longSide = Math.max(width, height);
  if (longSide > MAX_SIDE) {
    ctx.resize(width >= height ? { width: MAX_SIDE } : { height: MAX_SIDE });
  }
  const image = await ctx.renderAsync();
  const result = await image.saveAsync({ compress: 0.85, format: SaveFormat.JPEG });
  return result.uri;
}

export function fileExists(uri: string | null | undefined): boolean {
  if (!uri) return false;
  try {
    const f = new FsFile(uri);
    return f.exists && f.size > 0;
  } catch {
    return false;
  }
}

export function deleteFile(uri: string | null | undefined) {
  if (!uri) return;
  try {
    const f = new FsFile(uri);
    if (f.exists) f.delete();
  } catch {
    // файл уже удалён
  }
}

export function deleteLectureMedia(lectureId: number) {
  try {
    const dir = lectureDir(lectureId);
    if (dir.exists) dir.delete();
  } catch {
    // ignore
  }
}

export function deleteAllMedia() {
  try {
    const root = ROOT();
    if (root.exists) root.delete();
  } catch {
    // ignore
  }
}

export function writeCacheFile(name: string, content: string): string {
  const f = new FsFile(Paths.cache, name);
  if (f.exists) f.delete();
  f.create();
  f.write(content);
  return f.uri;
}
