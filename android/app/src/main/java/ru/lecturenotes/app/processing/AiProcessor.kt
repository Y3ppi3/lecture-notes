package ru.lecturenotes.app.processing

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.lecturenotes.app.ai.AiKeysRepository
import ru.lecturenotes.app.data.BlockEntity
import ru.lecturenotes.app.data.BlockStatus
import ru.lecturenotes.app.data.BlockType
import ru.lecturenotes.app.data.LectureRepository
import ru.lecturenotes.app.data.SettingsRepository
import ru.lecturenotes.app.network.ApiClient
import ru.lecturenotes.app.network.CorrectRequest
import java.util.concurrent.TimeUnit

/**
 * Обработка аудиофрагментов через WorkManager: задачи переживают закрытие приложения
 * и перезагрузку телефона, ждут появления сети и повторяются с экспоненциальной задержкой.
 * Фрагменты идут одной цепочкой строго по порядку — каждому нужен контекст из предыдущего.
 */
class AiProcessor(
    private val context: Context,
    private val repo: LectureRepository,
    private val settings: SettingsRepository,
    private val api: ApiClient,
    private val aiKeys: AiKeysRepository,
) {
    private val workManager get() = WorkManager.getInstance(context)

    fun enqueueAudio(blockId: Long) {
        val request = OneTimeWorkRequestBuilder<AudioUploadWorker>()
            .setInputData(workDataOf(AudioUploadWorker.KEY_BLOCK_ID to blockId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Повтор для блока в статусе ERROR (кнопка «Повторить»). */
    suspend fun retry(blockId: Long) {
        val block = repo.getBlock(blockId) ?: return
        repo.updateBlock(block.copy(status = BlockStatus.PROCESSING, error = null))
        enqueueAudio(blockId)
    }

    /**
     * При запуске приложения: если очередь пуста, а блоки «в обработке» остались
     * (например, задачи отменила система), ставим их заново.
     */
    suspend fun resumePending() = withContext(Dispatchers.IO) {
        val active = workManager.getWorkInfosForUniqueWork(QUEUE).get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
        if (active) return@withContext
        repo.getUnfinishedBlocks()
            .filter { it.status == BlockStatus.PROCESSING && it.audioPath != null }
            .sortedBy { it.position }
            .forEach { enqueueAudio(it.id) }
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }

    /** ИИ-коррекция текстового блока. Возвращает null при успехе или текст ошибки. */
    suspend fun correctTextBlock(blockId: Long): String? {
        val block = repo.getBlock(blockId) ?: return null
        if (block.type != BlockType.TEXT || block.text.isBlank()) return null
        repo.updateBlock(block.copy(status = BlockStatus.PROCESSING, error = null))
        return try {
            val s = settings.current()
            val lecture = repo.getLecture(block.lectureId)
            val resp = api.get(s.serverUrl).lecture.correct(
                CorrectRequest(
                    text = block.text,
                    language = s.language,
                    title = lecture?.title,
                    previousText = previousText(block).ifBlank { null },
                    aiConfig = aiKeys.requestConfig()?.forRequest(),
                ),
            )
            val fresh = repo.getBlock(blockId) ?: return null
            repo.updateBlock(
                fresh.copy(
                    text = resp.text,
                    // Сохраняем самый первый вариант, чтобы «Исходный текст» был полезен.
                    rawText = fresh.rawText ?: block.text,
                    status = BlockStatus.READY,
                ),
            )
            null
        } catch (e: CancellationException) {
            repo.getBlock(blockId)?.let { repo.updateBlock(it.copy(status = BlockStatus.READY)) }
            throw e
        } catch (e: Exception) {
            repo.getBlock(blockId)?.let { repo.updateBlock(it.copy(status = BlockStatus.READY)) }
            ApiClient.describe(e)
        }
    }

    suspend fun previousText(block: BlockEntity): String =
        repo.getBlocks(block.lectureId)
            .filter { it.type == BlockType.TEXT && it.position < block.position && it.status == BlockStatus.READY }
            .joinToString("\n\n") { it.text }
            .takeLast(1500)

    companion object {
        const val QUEUE = "audio-processing"
        const val TAG = "audio"
    }
}
