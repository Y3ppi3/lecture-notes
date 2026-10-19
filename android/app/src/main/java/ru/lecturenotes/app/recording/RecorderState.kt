package ru.lecturenotes.app.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecState(
    /** id лекции, в которую идёт запись, или null — запись не идёт. */
    val lectureId: Long? = null,
    val isPaused: Boolean = false,
    val elapsedMs: Long = 0,
    /** Громкость 0..1 для индикатора. */
    val level: Float = 0f,
    val error: String? = null,
) {
    val isRecording: Boolean get() = lectureId != null
}

/** Общее состояние записи: пишет [RecordingService], читает UI. */
object RecorderState {
    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state.asStateFlow()

    internal fun update(transform: (RecState) -> RecState) {
        _state.value = transform(_state.value)
    }

    internal fun reset(error: String? = null) {
        _state.value = RecState(error = error)
    }

    fun clearError() = update { it.copy(error = null) }
}
