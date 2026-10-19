package ru.lecturenotes.app.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import ru.lecturenotes.app.auth.Session
import ru.lecturenotes.app.auth.TokenStore
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class Apis(val auth: AuthApi, val lecture: LectureApi)

/**
 * Сетевой слой. Access-токен подставляется интерсептором; при ответе 401
 * Authenticator один раз обновляет пару токенов (refresh с ротацией) и повторяет запрос.
 * Если обновить не удалось — сессия очищается, и приложение показывает экран входа.
 */
class ApiClient(private val tokens: TokenStore) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val converter = json.asConverterFactory("application/json".toMediaType())
    private val refreshLock = Any()

    @Volatile private var cached: Pair<String, Apis>? = null

    fun get(baseUrl: String): Apis {
        cached?.let { (url, apis) -> if (url == baseUrl) return apis }
        synchronized(this) {
            cached?.let { (url, apis) -> if (url == baseUrl) return apis }
            return build(baseUrl).also { cached = baseUrl to it }
        }
    }

    private fun build(baseUrl: String): Apis {
        val base = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(false)
            .also(NetworkDebug::install)
            .build()

        val authApi = Retrofit.Builder().baseUrl(baseUrl).client(base)
            .addConverterFactory(converter).build().create(AuthApi::class.java)

        val authed = base.newBuilder()
            .addInterceptor(AuthInterceptor())
            .authenticator(TokenAuthenticator(authApi))
            .build()
        val lectureApi = Retrofit.Builder().baseUrl(baseUrl).client(authed)
            .addConverterFactory(converter).build().create(LectureApi::class.java)
        return Apis(authApi, lectureApi)
    }

    private inner class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val token = tokens.current()?.accessToken
                ?: return chain.proceed(chain.request())
            return chain.proceed(chain.request().withBearer(token))
        }
    }

    private inner class TokenAuthenticator(private val authApi: AuthApi) : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            // Уже повторяли этот запрос — второй раз не пытаемся.
            if (response.priorResponse != null) return null
            val sentToken = response.request.header("Authorization")?.removePrefix("Bearer ")

            synchronized(refreshLock) {
                val session = tokens.current() ?: return null
                // Пока ждали блокировку, токен уже обновил другой запрос.
                if (sentToken != null && sentToken != session.accessToken) {
                    return response.request.withBearer(session.accessToken)
                }
                val refreshed = try {
                    authApi.refreshCall(RefreshRequest(session.refreshToken)).execute()
                } catch (e: IOException) {
                    return null // нет сети — сессию не трогаем, запрос завершится ошибкой
                }
                val pair = refreshed.body()
                if (!refreshed.isSuccessful || pair == null) {
                    if (refreshed.code() in 400..499) tokens.clearBlocking()
                    return null
                }
                tokens.saveBlocking(Session(pair.accessToken, pair.refreshToken, session.email))
                return response.request.withBearer(pair.accessToken)
            }
        }
    }

    companion object {
        private val TEXT = "text/plain".toMediaType()

        private fun Request.withBearer(token: String): Request =
            newBuilder().header("Authorization", "Bearer $token").build()

        fun textPart(value: String): RequestBody = value.toRequestBody(TEXT)

        fun audioPart(file: File): MultipartBody.Part =
            MultipartBody.Part.createFormData("file", "audio.m4a", file.asRequestBody("audio/mp4".toMediaType()))

        /** Понятное пользователю описание ошибки. Текст ошибки сервера уже локализован. */
        fun describe(e: Throwable): String = when (e) {
            is HttpException -> {
                val detail = runCatching {
                    val body = e.response()?.errorBody()?.string().orEmpty()
                    Json.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.content
                }.getOrNull()
                when (e.code()) {
                    401 -> "Сессия истекла — войдите снова"
                    else -> detail ?: "Ошибка сервера (${e.code()})"
                }
            }
            is IOException -> "Нет связи с сервером"
            else -> "Непредвиденная ошибка"
        }

        fun isTransient(e: Throwable): Boolean = when (e) {
            is IOException -> true
            is HttpException -> e.code() == 429 || e.code() >= 500
            else -> false
        }
    }
}
