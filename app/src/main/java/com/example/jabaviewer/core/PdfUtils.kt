package com.example.jabaviewer.core

import java.io.File

fun isPdfValid(file: File): Boolean {
    if (!file.exists()) return false
    return try {
        val header = ByteArray(5)
        file.inputStream().use { input ->
            val read = input.read(header)
            read == 5 && String(header, Charsets.US_ASCII).startsWith("%PDF-")
        }
    } catch (_: Exception) {
        false
    }
}
