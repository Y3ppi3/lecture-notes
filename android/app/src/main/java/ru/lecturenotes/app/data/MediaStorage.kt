package ru.lecturenotes.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Медиафайлы во внутренней памяти приложения (недоступны другим приложениям):
 * files/lectures/{lectureId}/{images|videos|audio}/...
 */
class MediaStorage(private val context: Context) {

    private val root: File get() = File(context.filesDir, "lectures")

    fun lectureDir(lectureId: Long): File = File(root, lectureId.toString())

    private fun dir(lectureId: Long, kind: String): File =
        File(lectureDir(lectureId), kind).apply { mkdirs() }

    fun newImageFile(lectureId: Long): File = File(dir(lectureId, "images"), "img_${stamp()}.jpg")

    fun newVideoFile(lectureId: Long, ext: String = "mp4"): File =
        File(dir(lectureId, "videos"), "vid_${stamp()}.$ext")

    fun newAudioFile(lectureId: Long): File = File(dir(lectureId, "audio"), "rec_${stamp()}.m4a")

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Копирует выбранный в галерее файл к себе; фото сразу сжимает. */
    suspend fun importFromUri(source: Uri, lectureId: Long, isVideo: Boolean): File =
        withContext(Dispatchers.IO) {
            val mime = context.contentResolver.getType(source)
            if (isVideo) require(mime?.startsWith("video/") == true) { "Это не видеофайл" }
            else require(mime?.startsWith("image/") == true) { "Это не изображение" }

            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                ?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
                ?: if (isVideo) "mp4" else "jpg"
            val target = if (isVideo) newVideoFile(lectureId, ext) else newImageFile(lectureId)
            context.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "Не удалось открыть файл" }
                target.outputStream().use { input.copyTo(it) }
            }
            if (!isVideo) compressImage(target)
            target
        }

    /**
     * Уменьшает фото до [MAX_SIDE] пикселей по длинной стороне, поворачивает по EXIF
     * и пересохраняет в JPEG. Снимок камеры 4–12 МБ превращается примерно в 0,5–1 МБ;
     * EXIF (в том числе геолокация) при этом отбрасывается.
     */
    suspend fun compressImage(file: File) = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@withContext

        val rotation = when (ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val longSide = max(decoded.width, decoded.height)
        val scale = if (longSide > MAX_SIDE) MAX_SIDE.toFloat() / longSide else 1f
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        val result = if (matrix.isIdentity) decoded else Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, matrix, true,
        )

        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (result !== decoded) result.recycle()
        decoded.recycle()
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun deleteLectureMedia(lectureId: Long) {
        lectureDir(lectureId).deleteRecursively()
    }

    fun deleteAll() {
        root.deleteRecursively()
    }

    fun deleteFile(path: String?) {
        if (path != null) File(path).delete()
    }

    private fun stamp() = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

    private companion object {
        const val MAX_SIDE = 2560
        const val JPEG_QUALITY = 85
    }
}
