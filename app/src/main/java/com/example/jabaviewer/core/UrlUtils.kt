package com.example.jabaviewer.core

fun combineUrl(baseUrl: String, path: String): String {
    val cleanBase = baseUrl.trim().trimEnd('/')
    val cleanPath = path.trim().trimStart('/')
    return "$cleanBase/$cleanPath"
}
