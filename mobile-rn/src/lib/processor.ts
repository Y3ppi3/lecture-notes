import NetInfo from '@react-native-community/netinfo';
import { ApiError, correctText, processAudio } from './api';
import { deleteBlock, getBlock, getBlocks, getLecture, getProcessingBlocks, updateBlock } from './db';
import { fileExists } from './media';
import { getSession } from './session';
import type { Block } from './types';

/**
 * Очередь обработки аудиофрагментов: строго по одному и по порядку, чтобы каждому
 * передавался контекст из уже обработанного текста. Временные ошибки (нет сети,
 * перегрузка сервера) повторяются с экспоненциальной задержкой.
 */
const MAX_ATTEMPTS = 6;
let queue: Promise<void> = Promise.resolve();
const queued = new Set<number>();

export function enqueueAudio(blockId: number) {
  if (queued.has(blockId)) return;
  queued.add(blockId);
  queue = queue
    .then(() => processWithRetry(blockId))
    .catch(() => undefined)
    .finally(() => queued.delete(blockId));
}

/** При запуске: блоки «в обработке», оставшиеся с прошлого раза, снова в очередь. */
export async function resumePending() {
  const pending = await getProcessingBlocks();
  pending.sort((a, b) => a.position - b.position).forEach((b) => enqueueAudio(b.id));
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function waitForNetwork(maxMs: number) {
  const state = await NetInfo.fetch();
  if (state.isConnected) return;
  await new Promise<void>((resolve) => {
    const timer = setTimeout(done, maxMs);
    const unsubscribe = NetInfo.addEventListener((s) => s.isConnected && done());
    function done() {
      clearTimeout(timer);
      unsubscribe();
      resolve();
    }
  });
}

async function processWithRetry(blockId: number) {
  for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
    const result = await processOnce(blockId);
    if (result !== 'retry') return;
    await updateBlock(blockId, { status: 'PROCESSING', error: 'Нет связи, повторю автоматически' });
    await waitForNetwork(60_000);
    await sleep(Math.min(60_000, 2_000 * 2 ** attempt) + Math.random() * 500);
  }
  if (await getBlock(blockId)) {
    await updateBlock(blockId, { status: 'ERROR', error: 'Не удалось связаться с сервером — нажмите «Повторить»' });
  }
}

async function lectureTitle(lectureId: number) {
  const l = await getLecture(lectureId);
  return [l?.title, l?.subject].filter(Boolean).join(' — ');
}

async function previousText(block: Block) {
  const blocks = await getBlocks(block.lectureId);
  return blocks
    .filter((b) => b.type === 'TEXT' && b.status === 'READY' && b.position < block.position)
    .map((b) => b.text)
    .join('\n\n')
    .slice(-1500);
}

async function processOnce(blockId: number): Promise<'done' | 'retry'> {
  const block = await getBlock(blockId);
  if (!block || block.status === 'READY') return 'done';
  if (!fileExists(block.audioUri)) {
    await updateBlock(blockId, { status: 'ERROR', error: 'Аудиофайл не найден' });
    return 'done';
  }
  if (!getSession()) {
    await updateBlock(blockId, { status: 'ERROR', error: 'Войдите в аккаунт и нажмите «Повторить»' });
    return 'done';
  }
  await updateBlock(blockId, { status: 'PROCESSING', error: null });
  try {
    const resp = await processAudio(block.audioUri!, {
      title: await lectureTitle(block.lectureId),
      previousText: await previousText(block),
    });
    const fresh = await getBlock(blockId);
    if (!fresh) return 'done'; // блок удалили, пока ждали ответа
    if (!resp.raw_text.trim()) {
      await deleteBlock(fresh); // во фрагменте тишина
      return 'done';
    }
    await updateBlock(blockId, {
      text: resp.corrected_text || resp.raw_text,
      rawText: resp.raw_text,
      status: 'READY',
      error: null,
    });
    return 'done';
  } catch (e) {
    if (!(await getBlock(blockId))) return 'done';
    if (e instanceof ApiError && e.transient) return 'retry';
    const message = e instanceof ApiError && e.status === 401
      ? 'Войдите в аккаунт и нажмите «Повторить»'
      : e instanceof ApiError ? e.message : 'Непредвиденная ошибка';
    await updateBlock(blockId, { status: 'ERROR', error: message });
    return 'done';
  }
}

/** Повтор по кнопке для блока в статусе ERROR. */
export async function retryBlock(blockId: number) {
  await updateBlock(blockId, { status: 'PROCESSING', error: null });
  enqueueAudio(blockId);
}

/** ИИ-коррекция текстового блока. Возвращает текст ошибки или null. */
export async function correctTextBlock(blockId: number): Promise<string | null> {
  const block = await getBlock(blockId);
  if (!block || block.type !== 'TEXT' || !block.text.trim()) return null;
  await updateBlock(blockId, { status: 'PROCESSING' });
  try {
    const resp = await correctText({
      text: block.text,
      title: await lectureTitle(block.lectureId),
      previousText: await previousText(block),
    });
    await updateBlock(blockId, { text: resp.text, rawText: block.rawText ?? block.text, status: 'READY' });
    return null;
  } catch (e) {
    await updateBlock(blockId, { status: 'READY' });
    return e instanceof ApiError ? e.message : 'Непредвиденная ошибка';
  }
}
