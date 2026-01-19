package com.example.jabaviewer.ui.util

import java.text.DateFormat
import java.util.Date
import kotlin.math.log10
import kotlin.math.pow

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    val unit = 1024
    val exp = (log10(bytes.toDouble()) / log10(unit.toDouble())).toInt()
    val prefix = "KMGTPE"[exp - 1]
    val value = bytes / unit.toDouble().pow(exp.toDouble())
    return String.format("%.1f %sB", value, prefix)
}

fun formatDate(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0L) return "Never opened"
    val formatter = DateFormat.getDateInstance(DateFormat.MEDIUM)
    return formatter.format(Date(timestamp))
}
