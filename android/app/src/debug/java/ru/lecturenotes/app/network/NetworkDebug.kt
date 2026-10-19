package ru.lecturenotes.app.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/** Только debug-сборка: краткий лог запросов без заголовков авторизации и тел. */
object NetworkDebug {
    fun install(builder: OkHttpClient.Builder) {
        builder.addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
            },
        )
    }
}
