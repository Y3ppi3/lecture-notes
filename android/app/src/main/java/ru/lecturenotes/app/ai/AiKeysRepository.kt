package ru.lecturenotes.app.ai

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import ru.lecturenotes.app.auth.TokenCipher
import ru.lecturenotes.app.network.AiConfig
import ru.lecturenotes.app.network.AiKey

private val Context.aiKeysStore by preferencesDataStore(name = "ai_keys")

/**
 * Ключи нейросетей пользователя. Хранятся только на телефоне, зашифрованными ключом
 * из Android Keystore, и отправляются на сервер вместе с каждым запросом (сервер их не сохраняет).
 */
class AiKeysRepository(private val context: Context) {

    private val cipher = TokenCipher(alias = "lecture_notes_ai_keys")
    private val prefKey = stringPreferencesKey("keys")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val mutex = Mutex()

    private val _keys = MutableStateFlow(AiConfig())
    val keys: StateFlow<AiConfig> = _keys.asStateFlow()
    @Volatile private var loaded = false

    suspend fun load(): AiConfig = mutex.withLock {
        if (!loaded) {
            val stored = context.aiKeysStore.data.first()[prefKey]
            _keys.value = stored
                ?.let(cipher::decrypt)
                ?.let { runCatching { json.decodeFromString(AiConfig.serializer(), it) }.getOrNull() }
                ?: AiConfig()
            loaded = true
        }
        _keys.value
    }

    /** Настройки для запроса или null, если пользователь работает на нейросетях сервера. */
    suspend fun requestConfig(): AiConfig? = load().takeIf { it.llm != null || it.stt != null }

    suspend fun set(kind: String, key: AiKey?) {
        val current = load()
        val updated = if (kind == KIND_LLM) current.copy(llm = key) else current.copy(stt = key)
        mutex.withLock {
            context.aiKeysStore.edit { it[prefKey] = cipher.encrypt(json.encodeToString(AiConfig.serializer(), updated)) }
            _keys.value = updated
        }
    }

    suspend fun clear() = mutex.withLock {
        context.aiKeysStore.edit { it.remove(prefKey) }
        _keys.value = AiConfig()
    }

    companion object {
        const val KIND_LLM = "llm"
        const val KIND_STT = "stt"

        fun encodeForRequest(config: AiConfig): String =
            Json { explicitNulls = false }.encodeToString(AiConfig.serializer(), config.forRequest())
    }
}
