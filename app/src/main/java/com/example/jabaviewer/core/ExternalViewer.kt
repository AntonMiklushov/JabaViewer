package com.example.jabaviewer.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

fun openExternalViewer(context: Context, file: File, mimeType: String): Boolean {
    val canOpen = if (!file.exists()) {
        false
    } else {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.clipData = ClipData.newUri(context.contentResolver, "document", uri)
        val resolved = intent.resolveActivity(context.packageManager) != null
        if (resolved) {
            context.startActivity(intent)
        }
        resolved
    }
    return canOpen
}
