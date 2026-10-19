package ru.lecturenotes.app.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import retrofit2.HttpException
import ru.lecturenotes.app.LectureNotesApp
import ru.lecturenotes.app.ai.AiKeysRepository
import ru.lecturenotes.app.data.BlockStatus
import ru.lecturenotes.app.network.ApiClient
import java.io.File

/**
 * Отправляет один аудиофрагмент на распознавание и ИИ-коррекцию и записывает текст в блок.
 *
 * Временные ошибки (нет сети, 5xx, 429) — Result.retry() с экспоненциальной задержкой.
 * Постоянные ошибки помечают блок как ERROR и возвращают success(), чтобы не блокировать
 * следующие фрагменты в цепочке; пользователь может нажать «Повторить».
 */
class AudioUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LectureNotesApp).container
        val repo = container.repo
        val blockId = inputData.getLong(KEY_BLOCK_ID, -1L)
        val block = repo.getBlock(blockId) ?: return Result.success()
        if (block.status == BlockStatus.READY) return Result.success() // уже обработан

        val audio = block.audioPath?.let(::File)
        if (audio == null || !audio.exists() || audio.length() == 0L) {
            repo.updateBlock(block.copy(status = BlockStatus.ERROR, error = "Аудиофайл не найден"))
            return Result.success()
        }
        if (container.tokens.current() == null) {
            repo.updateBlock(block.copy(status = BlockStatus.ERROR, error = "Войдите в аккаунт и нажмите «Повторить»"))
            return Result.success()
        }
        repo.updateBlock(block.copy(status = BlockStatus.PROCESSING, error = null))

        return try {
            val s = container.settings.current()
            val lecture = repo.getLecture(block.lectureId)
            val title = listOfNotNull(lecture?.title, lecture?.subject?.takeIf { it.isNotBlank() }).joinToString(" — ")
            val resp = container.api.get(s.serverUrl).lecture.process(
                file = ApiClient.audioPart(audio),
                language = ApiClient.textPart(s.language),
                title = ApiClient.textPart(title),
                previousText = ApiClient.textPart(container.processor.previousText(block)),
                correctText = ApiClient.textPart(s.autoCorrect.toString()),
                aiConfig = container.aiKeys.requestConfig()
                    ?.let { ApiClient.textPart(AiKeysRepository.encodeForRequest(it)) },
            )
            val fresh = repo.getBlock(blockId) ?: return Result.success() // блок удалили, пока ждали
            if (resp.rawText.isBlank()) {
                repo.deleteBlock(fresh) // во фрагменте тишина
            } else {
                repo.updateBlock(
                    fresh.copy(
                        text = resp.correctedText.ifBlank { resp.rawText },
                        rawText = resp.rawText,
                        status = BlockStatus.READY,
                        error = null,
                    ),
                )
            }
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val fresh = repo.getBlock(blockId) ?: return Result.success()
            val unauthorized = e is HttpException && e.code() == 401
            if (ApiClient.isTransient(e) && runAttemptCount < MAX_ATTEMPTS) {
                repo.updateBlock(fresh.copy(status = BlockStatus.PROCESSING, error = "Нет связи, повторю автоматически"))
                Result.retry()
            } else {
                val message = if (unauthorized) "Войдите в аккаунт и нажмите «Повторить»" else ApiClient.describe(e)
                repo.updateBlock(fresh.copy(status = BlockStatus.ERROR, error = message))
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_BLOCK_ID = "block_id"
        private const val MAX_ATTEMPTS = 6
    }
}
