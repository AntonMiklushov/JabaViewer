package com.example.jabaviewer.core

import java.io.File

enum class DocumentFormat(
    val id: String,
    val extension: String,
    val mimeType: String,
    val label: String,
) {
    PDF(id = "pdf", extension = "pdf", mimeType = "application/pdf", label = "PDF"),
    DJVU(id = "djvu", extension = "djvu", mimeType = "image/vnd.djvu", label = "DJVU");

    companion object {
        fun fromRaw(value: String?): DocumentFormat {
            return fromRawOrNull(value) ?: PDF
        }

        fun fromRawOrNull(value: String?): DocumentFormat? {
            val normalized = value?.trim()?.lowercase()
            return when (normalized) {
                "djvu", "djv" -> DJVU
                "pdf" -> PDF
                else -> null
            }
        }

        fun fromFileName(value: String?): DocumentFormat? {
            val extension = value
                ?.substringAfterLast('.', "")
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
            return fromRawOrNull(extension)
        }
    }
}

fun detectDocumentFormat(file: File): DocumentFormat? {
    val bytes = if (!file.exists()) {
        null
    } else {
        runCatching {
            val header = ByteArray(128)
            val read = file.inputStream().use { it.read(header) }
            if (read <= 0) null else header.copyOf(read)
        }.getOrNull()
    }
    return if (bytes == null) {
        null
    } else {
        when {
            looksLikePdf(bytes) -> DocumentFormat.PDF
            looksLikeDjvu(bytes) -> DocumentFormat.DJVU
            else -> null
        }
    }
}

private fun looksLikePdf(bytes: ByteArray): Boolean {
    if (bytes.size < 5) return false
    val signature = "%PDF-".toByteArray(Charsets.US_ASCII)
    return signature.indices.all { bytes[it] == signature[it] }
}

private fun looksLikeDjvu(bytes: ByteArray): Boolean {
    val hasForm = hasAsciiSignature(bytes, "AT&TFORM", 64)
    val hasDjvu = hasAsciiSignature(bytes, "DJVU", 64) ||
        hasAsciiSignature(bytes, "DJVM", 64)
    return hasForm && hasDjvu
}

private fun hasAsciiSignature(bytes: ByteArray, signature: String, maxBytes: Int): Boolean {
    val needle = signature.toByteArray(Charsets.US_ASCII)
    val limit = minOf(bytes.size, maxBytes)
    if (needle.isEmpty() || limit < needle.size) return false
    var found = false
    for (i in 0..(limit - needle.size)) {
        if (matchesAt(bytes, needle, i)) {
            found = true
            break
        }
    }
    return found
}

private fun matchesAt(bytes: ByteArray, needle: ByteArray, offset: Int): Boolean {
    for (j in needle.indices) {
        if (bytes[offset + j] != needle[j]) return false
    }
    return true
}
