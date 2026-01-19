package com.example.jabaviewer.core

import okhttp3.HttpUrl.Companion.toHttpUrl

fun combineUrl(baseUrl: String, path: String): String {
    val cleanBase = baseUrl.trim().trimEnd('/')
    val cleanPath = path.trim().trimStart('/')
    val base = cleanBase.toHttpUrl()
    return base.newBuilder()
        .addPathSegments(cleanPath)
        .build()
        .toString()
}
