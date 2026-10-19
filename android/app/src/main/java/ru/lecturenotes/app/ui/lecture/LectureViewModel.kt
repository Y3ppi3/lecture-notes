package ru.lecturenotes.app.ui.lecture

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.lecturenotes.app.AppContainer
import ru.lecturenotes.app.data.BlockEntity
import ru.lecturenotes.app.data.BlockType
import ru.lecturenotes.app.data.LectureEntity
import ru.lecturenotes.app.recording.RecorderState
import ru.lecturenotes.app.recording.RecordingService
import java.io.File

class LectureViewModel(
    private val c: AppContainer,
    private val saved: SavedStateHandle,
    val lectureId: Long,
) : ViewModel() {

    val lecture: StateFlow<LectureEntity?> = c.repo.observeLecture(lectureId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val blocks: StateFlow<List<BlockEntity>> = c.repo.observeBlocks(lectureId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    /**
     * Файл, в который системная камера сохраняет фото/видео. Хранится в SavedStateHandle:
     * пока открыта камера, система может выгрузить процесс приложения из памяти.
     */
    private var pendingCapture: String?
        get() = saved[KEY_PENDING]
        set(value) { saved[KEY_PENDING] = value }

    /** Блок, по которому сейчас идёт ИИ-коррекция (для индикатора). */
    var correctingBlockId by mutableStateOf<Long?>(null)
        private set

    fun showMessage(msg: String) {
        _messages.tryEmit(msg)
    }

    private val isRecordingHere: Boolean
        get() = RecorderState.state.value.lectureId == lectureId

    /** Изменения данных выполняются в скоупе приложения — их не прервёт закрытие экрана. */
    private fun persist(block: suspend () -> Unit) {
        c.appScope.launch { block() }
    }

    // ---------- запись ----------

    /** Возвращает false, если сейчас пишется другая лекция. */
    fun canStartRecording(): Boolean {
        val rec = RecorderState.state.value
        if (rec.isRecording && rec.lectureId != lectureId) {
            showMessage("Сейчас идёт запись другой лекции — сначала остановите её")
            return false
        }
        return true
    }

    fun startRecording(context: Context) {
        if (canStartRecording()) RecordingService.start(context, lectureId)
    }

    fun pauseRecording(context: Context) = RecordingService.pause(context)
    fun resumeRecording(context: Context) = RecordingService.resume(context)
    fun stopRecording(context: Context) = RecordingService.stop(context)

    /**
     * Позиция для нового медиа-блока. Если идёт запись этой лекции — режем аудио
     * в текущий момент, чтобы фото встало точно между фрагментами речи.
     */
    private fun mediaPosition(context: Context): Double? {
        if (!isRecordingHere) return null
        val at = System.currentTimeMillis()
        RecordingService.split(context, at)
        return at.toDouble()
    }

    // ---------- фото и видео ----------

    fun newPhotoUri(): Uri {
        val file = c.media.newImageFile(lectureId)
        pendingCapture = file.absolutePath
        return c.media.uriFor(file)
    }

    fun newVideoUri(): Uri {
        val file = c.media.newVideoFile(lectureId)
        pendingCapture = file.absolutePath
        return c.media.uriFor(file)
    }

    fun onCaptured(context: Context, success: Boolean, type: BlockType) {
        val path = pendingCapture ?: return
        pendingCapture = null
        val file = File(path)
        if (!success || !file.exists() || file.length() == 0L) {
            file.delete()
            return
        }
        val position = mediaPosition(context)
        persist {
            if (type == BlockType.IMAGE) c.media.compressImage(file)
            c.repo.addMedia(lectureId, file, type, position)
        }
    }

    fun onPicked(context: Context, uris: List<Uri>, type: BlockType) {
        if (uris.isEmpty()) return
        val position = mediaPosition(context)
        persist {
            uris.forEachIndexed { i, uri ->
                try {
                    val file = c.media.importFromUri(uri, lectureId, isVideo = type == BlockType.VIDEO)
                    c.repo.addMedia(lectureId, file, type, position?.plus(i * 0.01))
                } catch (e: Exception) {
                    showMessage("Не удалось добавить файл")
                }
            }
        }
    }

    fun addVideoLink(url: String, caption: String) {
        val u = url.trim()
        val parsed = runCatching { Uri.parse(u) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || parsed?.host.isNullOrBlank()) {
            showMessage("Ссылка должна начинаться с http:// или https://")
            return
        }
        persist { c.repo.addVideoLink(lectureId, u, caption) }
    }

    // ---------- текст ----------

    fun addText(text: String, after: BlockEntity? = null) {
        if (text.isBlank()) return
        persist {
            val pos = after?.let { c.repo.positionAfter(it) }
            c.repo.addText(lectureId, text.trim(), pos)
        }
    }

    fun updateText(block: BlockEntity, text: String) = persist {
        c.repo.getBlock(block.id)?.let { c.repo.updateBlock(it.copy(text = text.trim())) }
    }

    fun updateCaption(block: BlockEntity, caption: String) = persist {
        c.repo.getBlock(block.id)?.let { c.repo.updateBlock(it.copy(caption = caption.trim().take(200))) }
    }

    fun restoreOriginal(block: BlockEntity) {
        val raw = block.rawText ?: return
        persist { c.repo.getBlock(block.id)?.let { c.repo.updateBlock(it.copy(text = raw)) } }
    }

    fun correctWithAi(block: BlockEntity) {
        if (correctingBlockId != null) return
        correctingBlockId = block.id
        viewModelScope.launch {
            val error = c.processor.correctTextBlock(block.id)
            correctingBlockId = null
            if (error != null) showMessage(error)
        }
    }

    fun retry(block: BlockEntity) = persist { c.processor.retry(block.id) }

    // ---------- порядок и удаление ----------

    fun move(block: BlockEntity, direction: Int) = persist { c.repo.moveBlock(block, direction) }

    fun delete(block: BlockEntity) = persist { c.repo.deleteBlock(block) }

    fun rename(title: String, subject: String) = persist { c.repo.renameLecture(lectureId, title, subject) }

    fun deleteLecture(onDone: () -> Unit) {
        if (isRecordingHere) {
            showMessage("Остановите запись перед удалением")
            return
        }
        viewModelScope.launch {
            c.repo.deleteLecture(lectureId)
            onDone()
        }
    }

    fun uriFor(path: String): Uri = c.media.uriFor(File(path))

    private companion object {
        const val KEY_PENDING = "pending_capture"
    }
}
