package ru.lecturenotes.app.auth

import ru.lecturenotes.app.data.SettingsRepository
import ru.lecturenotes.app.network.ApiClient
import ru.lecturenotes.app.network.Apis
import ru.lecturenotes.app.network.Credentials
import ru.lecturenotes.app.network.PasswordRequest
import ru.lecturenotes.app.network.RefreshRequest
import ru.lecturenotes.app.network.TokenPair
import ru.lecturenotes.app.network.UsageOut

class AuthRepository(
    private val tokens: TokenStore,
    private val settings: SettingsRepository,
    private val api: ApiClient,
) {
    val session = tokens.session

    suspend fun apis(): Apis = api.get(settings.current().serverUrl)

    suspend fun login(email: String, password: String) =
        store(email, apis().auth.login(Credentials(email.trim(), password)))

    suspend fun register(email: String, password: String) =
        store(email, apis().auth.register(Credentials(email.trim(), password)))

    private suspend fun store(email: String, pair: TokenPair) {
        tokens.save(Session(pair.accessToken, pair.refreshToken, email.trim().lowercase()))
    }

    /** Выход: отзываем сессию на сервере (если есть сеть) и забываем токены на устройстве. */
    suspend fun logout() {
        val refresh = tokens.session.value?.refreshToken
        if (refresh != null) runCatching { apis().auth.logout(RefreshRequest(refresh)) }
        tokens.clear()
    }

    /** Удаляет аккаунт на сервере. Локальные данные и токены очищает вызывающий код. */
    suspend fun deleteAccount(password: String) {
        apis().lecture.deleteAccount(PasswordRequest(password))
    }

    suspend fun forgetSession() = tokens.clear()

    suspend fun usage(): UsageOut = apis().lecture.usage()
}
