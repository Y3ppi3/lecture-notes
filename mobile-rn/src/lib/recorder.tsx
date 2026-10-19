import {
  RecordingPresets,
  requestRecordingPermissionsAsync,
  setAudioModeAsync,
  useAudioRecorder,
  useAudioRecorderState,
  type RecordingOptions,
} from 'expo-audio';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { addBlock, deleteBlock, getBlock, updateBlock } from './db';
import { deleteFile, fileExists, importFile } from './media';
import { enqueueAudio } from './processor';

/** Длина одного аудиофрагмента: текст появляется в конспекте по ходу лекции. */
export const CHUNK_MS = 3 * 60 * 1000;
/** Слишком короткие фрагменты (случайное нажатие) не отправляем. */
const MIN_CHUNK_MS = 1500;

/** Моно 16 кГц, 32 кбит/с: ~0,7 МБ на 3 минуты — далеко от лимита STT-API в 25 МБ. */
const OPTIONS: RecordingOptions = {
  ...RecordingPresets.HIGH_QUALITY,
  isMeteringEnabled: true,
  sampleRate: 16000,
  numberOfChannels: 1,
  bitRate: 32000,
};

export interface RecState {
  lectureId: number | null;
  paused: boolean;
  elapsedMs: number;
  /** Громкость 0..1. */
  level: number;
}

interface RecorderApi {
  state: RecState;
  error: string | null;
  clearError(): void;
  start(lectureId: number): Promise<boolean>;
  stop(): Promise<void>;
  pause(): void;
  resume(): void;
  /** Закрыть текущий фрагмент в момент `at` (мс) — используется при съёмке слайда. */
  split(at: number): Promise<void>;
}

const Ctx = createContext<RecorderApi | null>(null);

export function useRecorder(): RecorderApi {
  const v = useContext(Ctx);
  if (!v) throw new Error('useRecorder вне RecorderProvider');
  return v;
}

const IDLE: RecState = { lectureId: null, paused: false, elapsedMs: 0, level: 0 };

export function RecorderProvider({ children }: { children: ReactNode }) {
  const recorder = useAudioRecorder(OPTIONS);
  const recorderState = useAudioRecorderState(recorder, 500);
  const [state, setState] = useState<RecState>(IDLE);
  const [error, setError] = useState<string | null>(null);

  const r = useRef({
    lectureId: null as number | null,
    paused: false,
    active: false, // рекордер сейчас пишет фрагмент
    needNewChunk: false,
    blockId: null as Promise<number> | null,
    chunkActiveMs: 0,
    totalMs: 0,
    lastTick: 0,
    busy: false,
    metering: -160,
  });
  useEffect(() => {
    r.current.metering = recorderState.metering ?? -160;
  }, [recorderState.metering]);

  // Все операции с рекордером выполняются последовательно.
  const chain = useRef<Promise<void>>(Promise.resolve());
  const run = useCallback((fn: () => Promise<void>) => {
    chain.current = chain.current
      .then(async () => {
        r.current.busy = true;
        try {
          await fn();
        } finally {
          r.current.busy = false;
        }
      })
      .catch((e) => setError(`Ошибка записи: ${(e as Error).message}`));
    return chain.current;
  }, []);

  const startChunk = useCallback(async (position: number) => {
    const lectureId = r.current.lectureId;
    if (lectureId == null) return;
    await recorder.prepareToRecordAsync();
    recorder.record();
    r.current.active = true;
    r.current.chunkActiveMs = 0;
    r.current.lastTick = Date.now();
    r.current.blockId = addBlock({ lectureId, type: 'TEXT', status: 'RECORDING', position });
  }, [recorder]);

  const finishChunk = useCallback(async () => {
    if (!r.current.active) return;
    const lectureId = r.current.lectureId!;
    const blockIdPromise = r.current.blockId;
    const activeMs = r.current.chunkActiveMs;
    r.current.active = false;
    r.current.blockId = null;

    await recorder.stop();
    const uri = recorder.uri;

    // Сохранение и отправку не ждём — запись продолжается сразу.
    void (async () => {
      const id = await blockIdPromise;
      if (id == null) return;
      const block = await getBlock(id);
      if (!block) return;
      if (!uri || activeMs < MIN_CHUNK_MS || !fileExists(uri)) {
        deleteFile(uri);
        await deleteBlock(block);
        return;
      }
      const stored = importFile(uri, lectureId, 'audio', true);
      await updateBlock(id, { audioUri: stored, status: 'PROCESSING' });
      enqueueAudio(id);
    })().catch((e) => setError(`Не удалось сохранить фрагмент: ${(e as Error).message}`));
  }, [recorder]);

  // Таймер: длительность, уровень звука, нарезка фрагментов.
  useEffect(() => {
    if (state.lectureId == null) return;
    const t = setInterval(() => {
      const s = r.current;
      const now = Date.now();
      const delta = now - s.lastTick;
      s.lastTick = now;
      if (!s.paused && s.active) {
        s.chunkActiveMs += delta;
        s.totalMs += delta;
      }
      const level = s.paused ? 0 : Math.min(1, Math.max(0, (s.metering + 60) / 60));
      setState((prev) => ({ ...prev, elapsedMs: s.totalMs, level }));

      if (!s.paused && s.active && !s.busy && s.chunkActiveMs >= CHUNK_MS) {
        run(async () => {
          await finishChunk();
          await startChunk(Date.now());
        });
      }
    }, 500);
    return () => clearInterval(t);
  }, [state.lectureId, run, finishChunk, startChunk]);

  const start = useCallback(async (lectureId: number) => {
    if (r.current.lectureId != null) return r.current.lectureId === lectureId;
    const perm = await requestRecordingPermissionsAsync();
    if (!perm.granted) {
      setError('Без доступа к микрофону запись невозможна');
      return false;
    }
    await setAudioModeAsync({
      allowsRecording: true,
      playsInSilentMode: true,
      allowsBackgroundRecording: true,
    });
    Object.assign(r.current, {
      lectureId, paused: false, needNewChunk: false, totalMs: 0, lastTick: Date.now(),
    });
    setState({ ...IDLE, lectureId });
    await run(() => startChunk(Date.now()));
    return true;
  }, [run, startChunk]);

  const stop = useCallback(async () => {
    if (r.current.lectureId == null) return;
    await run(finishChunk);
    Object.assign(r.current, { lectureId: null, paused: false, needNewChunk: false, active: false });
    setState(IDLE);
    await setAudioModeAsync({ allowsRecording: false, allowsBackgroundRecording: false }).catch(() => {});
  }, [run, finishChunk]);

  const pause = useCallback(() => {
    if (r.current.lectureId == null || r.current.paused) return;
    void run(async () => {
      if (r.current.active) recorder.pause();
      r.current.paused = true;
      setState((p) => ({ ...p, paused: true, level: 0 }));
    });
  }, [run, recorder]);

  const resume = useCallback(() => {
    if (r.current.lectureId == null || !r.current.paused) return;
    void run(async () => {
      r.current.paused = false;
      r.current.lastTick = Date.now();
      if (r.current.needNewChunk || !r.current.active) {
        r.current.needNewChunk = false;
        await startChunk(Date.now());
      } else {
        recorder.record();
      }
      setState((p) => ({ ...p, paused: false }));
    });
  }, [run, recorder, startChunk]);

  const split = useCallback(async (at: number) => {
    if (r.current.lectureId == null) return;
    await run(async () => {
      await finishChunk();
      if (r.current.paused) r.current.needNewChunk = true;
      else await startChunk(at + 1);
    });
  }, [run, finishChunk, startChunk]);

  const api = useMemo<RecorderApi>(() => ({
    state, error, clearError: () => setError(null), start, stop, pause, resume, split,
  }), [state, error, start, stop, pause, resume, split]);

  return <Ctx.Provider value={api}>{children}</Ctx.Provider>;
}
