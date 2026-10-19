package ru.lecturenotes.app.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.authStore by preferencesDataStore(name = "auth")

@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val email: String,
)

/**
 * Хранилище сессии. На диске — только зашифрованный ключом Keystore блоб;
 * в памяти — текущая сессия для быстрого доступа из сетевого слоя.
 */
class TokenStore(private val context: Context) {

    private val cipher = TokenCipher()
    private val key = stringPreferencesKey("session")
    private val mutex = Mutex()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    /** false, пока сессия не прочитана с диска (показываем заставку, а не экран входа). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    suspend fun load() = mutex.withLock {
        if (_loaded.value) return@withLock
        val stored = context.authStore.data.first()[key]
        _session.value = stored
            ?.let(cipher::decrypt)
            ?.let { runCatching { Json.decodeFromString(Session.serializer(), it) }.getOrNull() }
        if (stored != null && _session.value == null) {
            // Расшифровать не удалось — сессия недействительна, удаляем мусор.
            context.authStore.edit { it.remove(key) }
        }
        _loaded.value = true
    }

    /** Синхронный доступ для OkHttp-потоков (никогда не вызывается с главного потока). */
    fun current(): Session? {
        if (!_loaded.value) runBlocking { load() }
        return _session.value
    }

    suspend fun save(session: Session) = mutex.withLock {
        context.authStore.edit { it[key] = cipher.encrypt(Json.encodeToString(Session.serializer(), session)) }
        _session.value = session
    }

    suspend fun clear() = mutex.withLock {
        context.authStore.edit { it.remove(key) }
        _session.value = null
    }

    fun saveBlocking(session: Session) = runBlocking { save(session) }
    fun clearBlocking() = runBlocking { clear() }
}
