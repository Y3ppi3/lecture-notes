package ru.lecturenotes.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "lectures")
data class LectureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val subject: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Тип блока в тексте лекции. */
enum class BlockType {
    /** Текст: расшифровка аудиофрагмента или набранный вручную. */
    TEXT,

    /** Фото слайда / доски. */
    IMAGE,

    /** Видеофайл, снятый на телефон или выбранный из галереи. */
    VIDEO,

    /** Ссылка на видео в интернете (YouTube, RuTube, ...). */
    VIDEO_LINK,
}

enum class BlockStatus {
    READY,

    /** Аудиофрагмент сейчас записывается. */
    RECORDING,

    /** Аудио отправлено на распознавание и ИИ-коррекцию. */
    PROCESSING,

    /** Ошибка обработки — можно повторить. */
    ERROR,
}

/**
 * Блок содержимого лекции. Блоки сортируются по [position].
 * Для новых блоков position = время создания, поэтому фото, снятое во время записи,
 * встаёт ровно между фрагментами речи «до» и «после» снимка.
 */
@Entity(
    tableName = "blocks",
    foreignKeys = [
        ForeignKey(
            entity = LectureEntity::class,
            parentColumns = ["id"],
            childColumns = ["lectureId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("lectureId")],
)
data class BlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lectureId: Long,
    val position: Double,
    val type: BlockType,
    /** Текст блока (для TEXT) или URL (для VIDEO_LINK). */
    val text: String = "",
    /** Исходная расшифровка / текст до ИИ-коррекции — чтобы можно было откатить. */
    val rawText: String? = null,
    /** Абсолютный путь к фото/видео во внутреннем хранилище приложения. */
    val mediaPath: String? = null,
    /** Путь к аудиофрагменту, из которого получен текст. */
    val audioPath: String? = null,
    val caption: String = "",
    val status: BlockStatus = BlockStatus.READY,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
