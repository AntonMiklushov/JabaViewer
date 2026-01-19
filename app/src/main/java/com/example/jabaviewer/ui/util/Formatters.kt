package com.example.jabaviewer.ui.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    val unit = 1024
    return if (value < unit) {
        "$value B"
    } else {
        val exp = (log10(value.toDouble()) / log10(unit.toDouble())).toInt()
        val prefix = "KMGTPE"[exp - 1]
        val scaled = value / unit.toDouble().pow(exp.toDouble())
        String.format(Locale.US, "%.1f %sB", scaled, prefix)
    }
}

fun formatDate(timestamp: Long?): String {
    return if (timestamp == null || timestamp <= 0L) {
        "Never opened"
    } else {
        val formatter = DateFormat.getDateInstance(DateFormat.MEDIUM)
        formatter.format(Date(timestamp))
    }
}
