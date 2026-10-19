package ru.lecturenotes.app

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.lecturenotes.app.ai.AiKeysRepository
import ru.lecturenotes.app.auth.AuthRepository
import ru.lecturenotes.app.auth.TokenStore
import ru.lecturenotes.app.data.AppDatabase
import ru.lecturenotes.app.data.LectureRepository
import ru.lecturenotes.app.data.MediaStorage
import ru.lecturenotes.app.data.SettingsRepository
import ru.lecturenotes.app.network.ApiClient
import ru.lecturenotes.app.processing.AiProcessor

/** Простейший DI-контейнер: все зависимости живут столько же, сколько процесс. */
class AppContainer(app: Application) {
    /** Скоуп для работы, которая не должна обрываться при закрытии экрана. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.w("LectureNotes", "Фоновая задача завершилась с ошибкой", e) },
    )

    val db = AppDatabase.create(app)
    val media = MediaStorage(app)
    val settings = SettingsRepository(app)
    val tokens = TokenStore(app)
    val api = ApiClient(tokens)
    val auth = AuthRepository(tokens, settings, api)
    val repo = LectureRepository(db, media)
    val aiKeys = AiKeysRepository(app)
    val processor = AiProcessor(app, repo, settings, api, aiKeys)
}

class LectureNotesApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            container.tokens.load()
            container.aiKeys.load()
            container.repo.recoverInterruptedRecordings().forEach(container.processor::enqueueAudio)
            container.processor.resumePending()
        }
    }

    /** Кэш картинок ограничен, кадры видео используются как превью. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
            .diskCache {
                DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(64L * 1024 * 1024).build()
            }
            .crossfade(true)
            .build()
}
