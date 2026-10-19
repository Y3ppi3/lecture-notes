package ru.lecturenotes.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class LectureRepository(
    private val db: AppDatabase,
    private val media: MediaStorage,
) {
    private val dao = db.lectureDao()

    fun observeLectures(): Flow<List<LectureEntity>> = dao.observeLectures()
    fun observeLecture(id: Long): Flow<LectureEntity?> = dao.observeLecture(id)
    fun observeBlocks(lectureId: Long): Flow<List<BlockEntity>> = dao.observeBlocks(lectureId)

    suspend fun getLecture(id: Long) = dao.getLecture(id)
    suspend fun getBlocks(lectureId: Long) = dao.getBlocks(lectureId)
    suspend fun getBlock(id: Long) = dao.getBlock(id)
    suspend fun getUnfinishedBlocks() = dao.getUnfinishedBlocks()

    suspend fun createLecture(title: String, subject: String): Long =
        dao.insertLecture(LectureEntity(title = title.trim().take(MAX_TITLE), subject = subject.trim().take(MAX_TITLE)))

    suspend fun renameLecture(id: Long, title: String, subject: String) {
        val l = dao.getLecture(id) ?: return
        dao.updateLecture(
            l.copy(
                title = title.trim().take(MAX_TITLE),
                subject = subject.trim().take(MAX_TITLE),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteLecture(id: Long) {
        dao.deleteLecture(id)
        withContext(Dispatchers.IO) { media.deleteLectureMedia(id) }
    }

    /** Полная очистка локальных данных (после удаления аккаунта). */
    suspend fun wipeAll() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        media.deleteAll()
    }

    /** Позиция для блока в конце лекции (не меньше текущего времени — см. [BlockEntity]). */
    suspend fun endPosition(lectureId: Long): Double {
        val max = dao.maxPosition(lectureId) ?: 0.0
        return maxOf(System.currentTimeMillis().toDouble(), max + 1.0)
    }

    /** Позиция сразу после блока [after] (между ним и следующим). */
    suspend fun positionAfter(after: BlockEntity): Double {
        val blocks = dao.getBlocks(after.lectureId)
        val idx = blocks.indexOfFirst { it.id == after.id }
        val next = blocks.getOrNull(idx + 1) ?: return endPosition(after.lectureId)
        return (after.position + next.position) / 2.0
    }

    suspend fun addBlock(block: BlockEntity): Long = db.withTransaction {
        val id = dao.insertBlock(block)
        dao.touchLecture(block.lectureId, System.currentTimeMillis())
        id
    }

    suspend fun addText(lectureId: Long, text: String, position: Double? = null): Long =
        addBlock(
            BlockEntity(
                lectureId = lectureId,
                position = position ?: endPosition(lectureId),
                type = BlockType.TEXT,
                text = text.take(MAX_TEXT),
            ),
        )

    suspend fun addMedia(lectureId: Long, file: File, type: BlockType, position: Double? = null): Long =
        addBlock(
            BlockEntity(
                lectureId = lectureId,
                position = position ?: endPosition(lectureId),
                type = type,
                mediaPath = file.absolutePath,
            ),
        )

    suspend fun addVideoLink(lectureId: Long, url: String, caption: String): Long =
        addBlock(
            BlockEntity(
                lectureId = lectureId,
                position = endPosition(lectureId),
                type = BlockType.VIDEO_LINK,
                text = url.trim(),
                caption = caption.trim().take(MAX_TITLE),
            ),
        )

    suspend fun updateBlock(block: BlockEntity) = db.withTransaction {
        dao.updateBlock(block.copy(text = block.text.take(MAX_TEXT)))
        dao.touchLecture(block.lectureId, System.currentTimeMillis())
    }

    suspend fun deleteBlock(block: BlockEntity) {
        db.withTransaction {
            dao.deleteBlock(block)
            dao.touchLecture(block.lectureId, System.currentTimeMillis())
        }
        withContext(Dispatchers.IO) {
            media.deleteFile(block.mediaPath)
            media.deleteFile(block.audioPath)
        }
    }

    /** Сдвигает блок вверх (-1) или вниз (+1), меняясь позицией с соседом. */
    suspend fun moveBlock(block: BlockEntity, direction: Int) = db.withTransaction {
        val blocks = dao.getBlocks(block.lectureId)
        val idx = blocks.indexOfFirst { it.id == block.id }
        if (idx < 0) return@withTransaction
        val other = blocks.getOrNull(idx + direction) ?: return@withTransaction
        val current = blocks[idx]
        dao.updateBlock(current.copy(position = other.position))
        dao.updateBlock(other.copy(position = current.position))
        dao.touchLecture(block.lectureId, System.currentTimeMillis())
    }

    /**
     * После аварийного завершения блоки в статусе RECORDING «зависают».
     * Если аудио успело сохраниться — отправляем его в обработку, иначе удаляем блок.
     * Блоки PROCESSING остаются — их доведёт до конца WorkManager.
     */
    suspend fun recoverInterruptedRecordings(): List<Long> {
        val toProcess = mutableListOf<Long>()
        for (b in dao.getUnfinishedBlocks().filter { it.status == BlockStatus.RECORDING }) {
            val audio = b.audioPath?.let(::File)
            if (audio != null && audio.exists() && audio.length() > MIN_AUDIO_BYTES) {
                dao.updateBlock(b.copy(status = BlockStatus.PROCESSING))
                toProcess += b.id
            } else {
                dao.deleteBlock(b)
                audio?.delete()
            }
        }
        return toProcess
    }

    private companion object {
        const val MAX_TITLE = 200
        const val MAX_TEXT = 30_000
        /** Обрыв записи оставляет файл без заголовка MP4 — такие короткие не отправляем. */
        const val MIN_AUDIO_BYTES = 4_096L
    }
}
