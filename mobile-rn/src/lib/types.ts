export type BlockType = 'TEXT' | 'IMAGE' | 'VIDEO' | 'VIDEO_LINK';
export type BlockStatus = 'READY' | 'RECORDING' | 'PROCESSING' | 'ERROR';

export interface Lecture {
  id: number;
  title: string;
  subject: string;
  createdAt: number;
  updatedAt: number;
}

/**
 * Блок содержимого лекции. Сортируются по position.
 * Для новых блоков position = время создания, поэтому фото, снятое во время записи,
 * встаёт между фрагментами речи «до» и «после» снимка.
 */
export interface Block {
  id: number;
  lectureId: number;
  position: number;
  type: BlockType;
  /** Текст (TEXT) или URL (VIDEO_LINK). */
  text: string;
  /** Исходный текст до ИИ-коррекции. */
  rawText: string | null;
  /** file:// URI фото или видео. */
  mediaUri: string | null;
  /** file:// URI аудиофрагмента. */
  audioUri: string | null;
  caption: string;
  status: BlockStatus;
  error: string | null;
  createdAt: number;
}
