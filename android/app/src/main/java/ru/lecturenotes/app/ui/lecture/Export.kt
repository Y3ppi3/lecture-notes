package ru.lecturenotes.app.ui.lecture

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import ru.lecturenotes.app.data.BlockEntity
import ru.lecturenotes.app.data.BlockStatus
import ru.lecturenotes.app.data.BlockType
import ru.lecturenotes.app.data.LectureEntity
import java.io.File

object Export {

    /** Конспект в Markdown. Фото и видео — ссылками на имена файлов из вложений. */
    fun toMarkdown(lecture: LectureEntity, blocks: List<BlockEntity>): String = buildString {
        append("# ").append(lecture.title).append("\n")
        if (lecture.subject.isNotBlank()) append("_").append(lecture.subject).append("_\n")
        append("\n")
        var slide = 0
        for (b in blocks) {
            when (b.type) {
                BlockType.TEXT -> if (b.status == BlockStatus.READY && b.text.isNotBlank()) {
                    append(b.text.trim()).append("\n\n")
                }
                BlockType.IMAGE -> {
                    slide++
                    val name = b.mediaPath?.let { File(it).name } ?: "image"
                    val alt = b.caption.ifBlank { "Слайд $slide" }
                    append("![").append(alt).append("](").append(name).append(")\n\n")
                }
                BlockType.VIDEO -> {
                    val name = b.mediaPath?.let { File(it).name } ?: "video"
                    append("Видео: ").append(b.caption.ifBlank { name }).append(" (").append(name).append(")\n\n")
                }
                BlockType.VIDEO_LINK -> {
                    append("Видео: [").append(b.caption.ifBlank { "Видео" }).append("](").append(b.text).append(")\n\n")
                }
            }
        }
    }.trimEnd() + "\n"

    /** Делится текстом конспекта и прикладывает фото слайдов. */
    fun share(
        context: Context,
        lecture: LectureEntity,
        blocks: List<BlockEntity>,
        uriFor: (String) -> Uri,
    ) {
        val text = toMarkdown(lecture, blocks)
        val images = blocks.filter { it.type == BlockType.IMAGE }.mapNotNull { it.mediaPath }.map(uriFor)

        val intent = if (images.isEmpty()) {
            Intent(Intent.ACTION_SEND).setType("text/plain")
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(images))
                clipData = ClipData.newRawUri("", images.first()).also { clip ->
                    images.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        intent.putExtra(Intent.EXTRA_SUBJECT, lecture.title)
        intent.putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, "Поделиться конспектом"))
    }
}
