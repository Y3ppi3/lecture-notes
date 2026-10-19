package ru.lecturenotes.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.lecturenotes.app.LectureNotesApp
import ru.lecturenotes.app.MainActivity
import ru.lecturenotes.app.R
import ru.lecturenotes.app.data.BlockEntity
import ru.lecturenotes.app.data.BlockStatus
import ru.lecturenotes.app.data.BlockType
import java.io.File

/**
 * Фоновая запись лекции.
 *
 * Звук пишется фрагментами по [CHUNK_MS]. Каждый законченный фрагмент сразу уходит
 * на сервер, поэтому текст появляется в лекции прямо во время занятия, а размер одного
 * файла (~0,7 МБ) остаётся далеко от лимита STT-API.
 *
 * Когда пользователь делает фото слайда во время записи, приложение присылает
 * [ACTION_SPLIT]: текущий фрагмент закрывается, а фото встаёт в текст ровно
 * между речью «до» и «после» снимка.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "ru.lecturenotes.action.START"
        const val ACTION_STOP = "ru.lecturenotes.action.STOP"
        const val ACTION_PAUSE = "ru.lecturenotes.action.PAUSE"
        const val ACTION_RESUME = "ru.lecturenotes.action.RESUME"
        const val ACTION_SPLIT = "ru.lecturenotes.action.SPLIT"
        const val EXTRA_LECTURE_ID = "lecture_id"
        const val EXTRA_SPLIT_AT = "split_at"

        /** Длина одного аудиофрагмента. */
        const val CHUNK_MS = 3 * 60 * 1000L

        /** Фрагменты короче этого не отправляем (случайное нажатие, щелчок). */
        private const val MIN_CHUNK_MS = 1500L

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, lectureId: Long) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LECTURE_ID, lectureId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) = command(context, ACTION_STOP)
        fun pause(context: Context) = command(context, ACTION_PAUSE)
        fun resume(context: Context) = command(context, ACTION_RESUME)

        /** Закрыть текущий фрагмент в момент [at] (мс), новый начнётся сразу после. */
        fun split(context: Context, at: Long) = command(context, ACTION_SPLIT) {
            putExtra(EXTRA_SPLIT_AT, at)
        }

        private fun command(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            if (!RecorderState.state.value.isRecording) return
            context.startService(Intent(context, RecordingService::class.java).setAction(action).apply(extras))
        }
    }

    private val container get() = (application as LectureNotesApp).container
    private val tickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var lectureId = -1L
    private var recorder: MediaRecorder? = null
    private var chunkFile: File? = null
    private var chunkBlockId: Deferred<Long>? = null
    private var chunkActiveMs = 0L
    private var paused = false
    private var needNewChunk = false
    private var lastTick = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent.getLongExtra(EXTRA_LECTURE_ID, -1L))
            ACTION_STOP -> handleStop()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_SPLIT -> handleSplit(intent.getLongExtra(EXTRA_SPLIT_AT, System.currentTimeMillis()))
            else -> if (lectureId <= 0) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------- команды ----------

    private fun handleStart(id: Long) {
        try {
            startInForeground()
        } catch (e: Exception) {
            // Например, нет разрешения на микрофон (Android 14 проверяет это здесь).
            stopEverything("Не удалось запустить запись: ${e.message}")
            return
        }
        if (lectureId > 0) return // запись уже идёт
        if (id <= 0) {
            stopEverything()
            return
        }
        lectureId = id
        RecorderState.update { RecState(lectureId = id) }
        acquireWakeLock()
        if (startChunk(System.currentTimeMillis().toDouble())) {
            startTicker()
            updateNotification()
        }
    }

    private fun handleStop() {
        if (lectureId > 0) {
            tick()
            finishChunk()
        }
        stopEverything()
    }

    private fun handlePause() {
        if (lectureId <= 0 || paused) return
        tick()
        try {
            recorder?.pause()
        } catch (_: IllegalStateException) {
        }
        paused = true
        RecorderState.update { it.copy(isPaused = true, level = 0f) }
        updateNotification()
    }

    private fun handleResume() {
        if (lectureId <= 0 || !paused) return
        paused = false
        lastTick = SystemClock.elapsedRealtime()
        if (needNewChunk || recorder == null) {
            needNewChunk = false
            if (!startChunk(System.currentTimeMillis().toDouble())) return
        } else {
            try {
                recorder?.resume()
            } catch (_: IllegalStateException) {
            }
        }
        RecorderState.update { it.copy(isPaused = false) }
        updateNotification()
    }

    private fun handleSplit(at: Long) {
        if (lectureId <= 0) return
        tick()
        finishChunk()
        if (paused) {
            needNewChunk = true
        } else {
            startChunk(at + 1.0)
        }
    }

    // ---------- фрагменты ----------

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

    private fun startChunk(position: Double): Boolean {
        val lid = lectureId
        val file = container.media.newAudioFile(lid)
        val rec = createRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(16_000)
            rec.setAudioEncodingBitRate(32_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            rec.release()
            file.delete()
            stopEverything("Не удалось начать запись: ${e.message}")
            return false
        }
        recorder = rec
        chunkFile = file
        chunkActiveMs = 0L
        lastTick = SystemClock.elapsedRealtime()
        chunkBlockId = container.appScope.async {
            container.repo.addBlock(
                BlockEntity(
                    lectureId = lid,
                    position = position,
                    type = BlockType.TEXT,
                    status = BlockStatus.RECORDING,
                    audioPath = file.absolutePath,
                ),
            )
        }
        return true
    }

    private fun finishChunk() {
        val rec = recorder ?: return
        val file = chunkFile
        val blockId = chunkBlockId
        val activeMs = chunkActiveMs
        recorder = null
        chunkFile = null
        chunkBlockId = null

        val stopped = try {
            rec.stop()
            true
        } catch (_: RuntimeException) {
            false // stop() бросает исключение, если не было записано ни одного кадра
        }
        rec.release()

        container.appScope.launch {
            val id = blockId?.await() ?: return@launch
            val block = container.repo.getBlock(id) ?: return@launch
            val valid = stopped && file != null && file.exists() && file.length() > 0 && activeMs >= MIN_CHUNK_MS
            if (!valid) {
                container.repo.deleteBlock(block)
            } else {
                container.repo.updateBlock(block.copy(status = BlockStatus.PROCESSING))
                container.processor.enqueueAudio(id)
            }
        }
    }

    // ---------- таймер ----------

    private fun startTicker() {
        lastTick = SystemClock.elapsedRealtime()
        tickerJob?.cancel()
        tickerJob = tickerScope.launch {
            while (isActive) {
                delay(500)
                tick()
                if (!paused && recorder != null && chunkActiveMs >= CHUNK_MS) {
                    finishChunk()
                    startChunk(System.currentTimeMillis().toDouble())
                }
            }
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val delta = now - lastTick
        lastTick = now
        val rec = recorder
        if (paused || rec == null) return
        chunkActiveMs += delta
        val amp = try {
            rec.maxAmplitude
        } catch (_: Exception) {
            0
        }
        RecorderState.update {
            it.copy(elapsedMs = it.elapsedMs + delta, level = (amp / 32767f).coerceIn(0f, 1f))
        }
    }

    // ---------- уведомление / завершение ----------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Запись лекций", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        if (lectureId <= 0) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_LECTURE_ID, lectureId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags,
        )
        fun service(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, RecordingService::class.java).setAction(action), flags,
        )
        val elapsed = RecorderState.state.value.elapsedMs
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (paused) "Запись на паузе" else "Идёт запись лекции")
            .setContentText("Текст появляется в конспекте по ходу записи")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setShowWhen(!paused)
            .setUsesChronometer(!paused)
            .setWhen(System.currentTimeMillis() - elapsed)
            .addAction(
                0,
                if (paused) "Продолжить" else "Пауза",
                if (paused) service(ACTION_RESUME, 2) else service(ACTION_PAUSE, 3),
            )
            .addAction(0, "Стоп", service(ACTION_STOP, 4))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LectureNotes:recording")
            .apply { acquire(6 * 60 * 60 * 1000L) } // не дольше 6 часов
    }

    private fun stopEverything(error: String? = null) {
        tickerJob?.cancel()
        tickerJob = null
        recorder?.let {
            try {
                it.stop()
            } catch (_: RuntimeException) {
            }
            it.release()
        }
        recorder = null
        lectureId = -1L
        paused = false
        needNewChunk = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        RecorderState.reset(error)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (lectureId > 0) {
            tick()
            finishChunk()
            stopEverything()
        }
        tickerScope.cancel()
        super.onDestroy()
    }
}
