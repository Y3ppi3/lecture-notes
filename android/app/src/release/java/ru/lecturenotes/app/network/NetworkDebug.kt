package ru.lecturenotes.app.network

import okhttp3.OkHttpClient

/** Релиз: сетевой лог отключён. */
object NetworkDebug {
    @Suppress("UNUSED_PARAMETER")
    fun install(builder: OkHttpClient.Builder) = Unit
}
