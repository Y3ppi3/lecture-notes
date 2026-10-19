import * as SQLite from 'expo-sqlite';
import { useEffect, useRef, useState } from 'react';
import { deleteAllMedia, deleteFile, deleteLectureMedia } from './media';
import type { Block, BlockStatus, BlockType, Lecture } from './types';

const db = SQLite.openDatabaseSync('lectures.db');

db.execSync(`
  PRAGMA journal_mode = WAL;
  PRAGMA foreign_keys = ON;
  CREATE TABLE IF NOT EXISTS lectures (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    subject TEXT NOT NULL DEFAULT '',
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS blocks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    lectureId INTEGER NOT NULL REFERENCES lectures(id) ON DELETE CASCADE,
    position REAL NOT NULL,
    type TEXT NOT NULL,
    text TEXT NOT NULL DEFAULT '',
    rawText TEXT,
    mediaUri TEXT,
    audioUri TEXT,
    caption TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'READY',
    error TEXT,
    createdAt INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_blocks_lecture ON blocks(lectureId);
`);

// ---------- простая реактивность: подписчики перечитывают данные после записи ----------

type Listener = () => void;
const listeners = new Set<Listener>();
function changed() {
  listeners.forEach((l) => l());
}
/** Результат запроса, который перечитывается после каждой записи в БД. key — смена параметров запроса. */
function useDbQuery<T>(query: () => Promise<T>, initial: T, key: string): T {
  const [value, setValue] = useState<T>(initial);
  const queryRef = useRef(query);
  useEffect(() => {
    queryRef.current = query;
  });
  useEffect(() => {
    let alive = true;
    const reload = () => {
      queryRef.current().then((v) => alive && setValue(v)).catch(() => undefined);
    };
    reload();
    listeners.add(reload);
    return () => {
      alive = false;
      listeners.delete(reload);
    };
  }, [key]);
  return value;
}

// ---------- лекции ----------

export function useLectures(): Lecture[] {
  return useDbQuery(() => db.getAllAsync<Lecture>('SELECT * FROM lectures ORDER BY updatedAt DESC'), [], 'lectures');
}

export function useLecture(id: number): Lecture | null {
  return useDbQuery(() => getLecture(id), null, `lecture:${id}`);
}

export function getLecture(id: number) {
  return db.getFirstAsync<Lecture>('SELECT * FROM lectures WHERE id = ?', id);
}

export async function createLecture(title: string, subject: string): Promise<number> {
  const now = Date.now();
  const r = await db.runAsync(
    'INSERT INTO lectures (title, subject, createdAt, updatedAt) VALUES (?, ?, ?, ?)',
    title.trim().slice(0, 200) || 'Лекция', subject.trim().slice(0, 200), now, now,
  );
  changed();
  return r.lastInsertRowId;
}

export async function renameLecture(id: number, title: string, subject: string) {
  await db.runAsync(
    'UPDATE lectures SET title = ?, subject = ?, updatedAt = ? WHERE id = ?',
    title.trim().slice(0, 200) || 'Лекция', subject.trim().slice(0, 200), Date.now(), id,
  );
  changed();
}

export async function deleteLecture(id: number) {
  await db.runAsync('DELETE FROM lectures WHERE id = ?', id);
  deleteLectureMedia(id);
  changed();
}

async function touch(lectureId: number) {
  await db.runAsync('UPDATE lectures SET updatedAt = ? WHERE id = ?', Date.now(), lectureId);
}

// ---------- блоки ----------

export function useBlocks(lectureId: number): Block[] {
  return useDbQuery(() => getBlocks(lectureId), [], `blocks:${lectureId}`);
}

export function getBlocks(lectureId: number) {
  return db.getAllAsync<Block>(
    'SELECT * FROM blocks WHERE lectureId = ? ORDER BY position ASC, id ASC', lectureId,
  );
}

export function getBlock(id: number) {
  return db.getFirstAsync<Block>('SELECT * FROM blocks WHERE id = ?', id);
}

/** Позиция в конце лекции (не меньше текущего времени). */
export async function endPosition(lectureId: number): Promise<number> {
  const r = await db.getFirstAsync<{ m: number | null }>(
    'SELECT MAX(position) AS m FROM blocks WHERE lectureId = ?', lectureId,
  );
  return Math.max(Date.now(), (r?.m ?? 0) + 1);
}

/** Позиция сразу после блока (между ним и следующим). */
export async function positionAfter(block: Block): Promise<number> {
  const blocks = await getBlocks(block.lectureId);
  const idx = blocks.findIndex((b) => b.id === block.id);
  const next = blocks[idx + 1];
  return next ? (block.position + next.position) / 2 : endPosition(block.lectureId);
}

export interface NewBlock {
  lectureId: number;
  type: BlockType;
  position?: number;
  text?: string;
  mediaUri?: string | null;
  audioUri?: string | null;
  caption?: string;
  status?: BlockStatus;
}

export async function addBlock(b: NewBlock): Promise<number> {
  const position = b.position ?? (await endPosition(b.lectureId));
  const r = await db.runAsync(
    `INSERT INTO blocks (lectureId, position, type, text, mediaUri, audioUri, caption, status, createdAt)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    b.lectureId, position, b.type, (b.text ?? '').slice(0, MAX_TEXT), b.mediaUri ?? null, b.audioUri ?? null,
    (b.caption ?? '').slice(0, 200), b.status ?? 'READY', Date.now(),
  );
  await touch(b.lectureId);
  changed();
  return r.lastInsertRowId;
}

type BlockPatch = Partial<Pick<Block, 'text' | 'rawText' | 'mediaUri' | 'audioUri' | 'caption' | 'status' | 'error' | 'position'>>;

const PATCHABLE = new Set(['text', 'rawText', 'mediaUri', 'audioUri', 'caption', 'status', 'error', 'position']);
const MAX_TEXT = 30_000;

export async function updateBlock(id: number, patch: BlockPatch) {
  // Имена колонок подставляются в SQL — пропускаем только известные.
  const keys = (Object.keys(patch) as (keyof BlockPatch)[]).filter((k) => PATCHABLE.has(k));
  if (patch.text != null) patch.text = patch.text.slice(0, MAX_TEXT);
  if (patch.caption != null) patch.caption = patch.caption.slice(0, 200);
  if (keys.length === 0) return;
  const sql = `UPDATE blocks SET ${keys.map((k) => `${k} = ?`).join(', ')} WHERE id = ?`;
  await db.runAsync(sql, ...keys.map((k) => patch[k] ?? null), id);
  const b = await getBlock(id);
  if (b) await touch(b.lectureId);
  changed();
}

export async function deleteBlock(block: Block) {
  await db.runAsync('DELETE FROM blocks WHERE id = ?', block.id);
  deleteFile(block.mediaUri);
  deleteFile(block.audioUri);
  await touch(block.lectureId);
  changed();
}

export async function moveBlock(block: Block, direction: -1 | 1) {
  const blocks = await getBlocks(block.lectureId);
  const idx = blocks.findIndex((b) => b.id === block.id);
  const other = blocks[idx + direction];
  if (!other || idx < 0) return;
  const current = blocks[idx];
  await db.withTransactionAsync(async () => {
    await db.runAsync('UPDATE blocks SET position = ? WHERE id = ?', other.position, current.id);
    await db.runAsync('UPDATE blocks SET position = ? WHERE id = ?', current.position, other.id);
  });
  changed();
}

/**
 * После аварийного завершения: блоки RECORDING (аудио не успело сохраниться) удаляются.
 * Блоки PROCESSING остаются — их снова ставит в очередь processor.resumePending().
 */
export async function recoverInterruptedRecordings() {
  const r = await db.runAsync("DELETE FROM blocks WHERE status = 'RECORDING'");
  if (r.changes) changed();
}

export function getProcessingBlocks() {
  return db.getAllAsync<Block>("SELECT * FROM blocks WHERE status = 'PROCESSING' AND audioUri IS NOT NULL");
}

/** Полная очистка (после удаления аккаунта). */
export async function wipeAll() {
  await db.execAsync('DELETE FROM blocks; DELETE FROM lectures;');
  deleteAllMedia();
  changed();
}
