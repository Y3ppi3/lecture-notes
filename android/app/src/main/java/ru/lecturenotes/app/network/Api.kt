package ru.lecturenotes.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

// ---------- модели ----------

@Serializable
data class Credentials(val email: String, val password: String)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class PasswordRequest(val password: String)

@Serializable
data class TokenPair(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class UserOut(val id: String, val email: String)

@Serializable
data class UsageOut(
    @SerialName("audio_mb_used") val audioMbUsed: Double,
    @SerialName("audio_mb_limit") val audioMbLimit: Int,
    @SerialName("correct_chars_used") val correctCharsUsed: Int,
    @SerialName("correct_chars_limit") val correctCharsLimit: Int,
)

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class ProcessResponse(
    @SerialName("raw_text") val rawText: String,
    @SerialName("corrected_text") val correctedText: String,
)

@Serializable
data class CorrectRequest(
    val text: String,
    val language: String? = null,
    val title: String? = null,
    @SerialName("previous_text") val previousText: String? = null,
    @SerialName("ai_config") val aiConfig: AiConfig? = null,
)

// ---------- ключи нейросетей ----------

/** Ключ нейросети пользователя. title — только для показа в приложении, на сервер не уходит. */
@Serializable
data class AiKey(
    val provider: String,
    @SerialName("api_key") val apiKey: String,
    val model: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
    val scope: String? = null,
    val title: String? = null,
)

@Serializable
data class AiConfig(val llm: AiKey? = null, val stt: AiKey? = null) {
    fun forRequest() = AiConfig(llm?.copy(title = null), stt?.copy(title = null))
}

@Serializable
data class ProviderInfo(
    val id: String,
    val title: String,
    val kind: String,
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("default_model") val defaultModel: String = "",
    @SerialName("key_hint") val keyHint: String = "",
    @SerialName("custom_base_url") val customBaseUrl: Boolean = false,
    val scopes: List<String> = emptyList(),
    val models: List<String> = emptyList(),
)

@Serializable
data class ServerAi(val available: Boolean, val title: String? = null)

@Serializable
data class ServerAiStatus(val llm: ServerAi, val stt: ServerAi)

@Serializable
data class ProvidersResponse(
    @SerialName("allow_user_keys") val allowUserKeys: Boolean,
    @SerialName("allow_custom_base_url") val allowCustomBaseUrl: Boolean,
    val server: ServerAiStatus,
    val llm: List<ProviderInfo>,
    val stt: List<ProviderInfo>,
)

@Serializable
data class CheckKeyRequest(val kind: String, val credentials: AiKey)

@Serializable
data class CheckKeyResponse(
    val ok: Boolean,
    val provider: String? = null,
    val title: String? = null,
    val model: String? = null,
    val message: String,
)

@Serializable
data class TextResponse(val text: String)

// ---------- эндпоинты ----------

/** Эндпоинты без access-токена. */
interface AuthApi {
    @POST("api/auth/register")
    suspend fun register(@Body body: Credentials): TokenPair

    @POST("api/auth/login")
    suspend fun login(@Body body: Credentials): TokenPair

    /** Синхронный вызов — используется в OkHttp Authenticator. */
    @POST("api/auth/refresh")
    fun refreshCall(@Body body: RefreshRequest): Call<TokenPair>

    @POST("api/auth/logout")
    suspend fun logout(@Body body: RefreshRequest)

    @GET("api/health")
    suspend fun health(): HealthResponse
}

/** Эндпоинты, требующие входа. */
interface LectureApi {
    @GET("api/auth/me")
    suspend fun me(): UserOut

    @HTTP(method = "DELETE", path = "api/auth/me", hasBody = true)
    suspend fun deleteAccount(@Body body: PasswordRequest)

    @GET("api/usage")
    suspend fun usage(): UsageOut

    @Multipart
    @POST("api/process")
    suspend fun process(
        @Part file: MultipartBody.Part,
        @Part("language") language: RequestBody,
        @Part("title") title: RequestBody,
        @Part("previous_text") previousText: RequestBody,
        @Part("correct_text") correctText: RequestBody,
        @Part("ai_config") aiConfig: RequestBody?,
    ): ProcessResponse

    @POST("api/correct")
    suspend fun correct(@Body body: CorrectRequest): TextResponse

    @GET("api/ai/providers")
    suspend fun aiProviders(): ProvidersResponse

    @POST("api/ai/check")
    suspend fun checkKey(@Body body: CheckKeyRequest): CheckKeyResponse
}
