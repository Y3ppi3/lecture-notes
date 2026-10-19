package ru.lecturenotes.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.lecturenotes.app.BuildConfig

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    /** Адрес сервера. В релизе всегда BuildConfig.API_BASE_URL. */
    val serverUrl: String = BuildConfig.API_BASE_URL,
    val language: String = "ru",
    /** Включать ИИ-коррекцию сразу после распознавания. */
    val autoCorrect: Boolean = true,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val language = stringPreferencesKey("language")
        val autoCorrect = booleanPreferencesKey("auto_correct")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            serverUrl = p[Keys.serverUrl]?.takeIf { BuildConfig.ALLOW_SERVER_OVERRIDE } ?: BuildConfig.API_BASE_URL,
            language = p[Keys.language] ?: "ru",
            autoCorrect = p[Keys.autoCorrect] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun save(s: AppSettings) {
        context.dataStore.edit { p ->
            if (BuildConfig.ALLOW_SERVER_OVERRIDE) p[Keys.serverUrl] = normalizeUrl(s.serverUrl)
            p[Keys.language] = normalizeLanguage(s.language)
            p[Keys.autoCorrect] = s.autoCorrect
        }
    }

    companion object {
        fun normalizeUrl(url: String): String {
            var u = url.trim()
            if (u.isEmpty()) return BuildConfig.API_BASE_URL
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            if (!u.endsWith("/")) u += "/"
            return u
        }

        fun normalizeLanguage(lang: String): String =
            lang.trim().lowercase().takeIf { it.length == 2 && it.all(Char::isLetter) } ?: "ru"
    }
}
