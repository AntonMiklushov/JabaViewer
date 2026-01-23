package com.example.jabaviewer.data.storage

import android.content.Context
import com.example.jabaviewer.core.AppConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class DocumentStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val encryptedRoot = File(context.filesDir, "remote/${AppConstants.REMOTE_ID}")
    private val decryptedRoot = File(context.noBackupFilesDir, "decrypted_cache/${AppConstants.REMOTE_ID}")
    private val shareRoot = File(context.cacheDir, "share/${AppConstants.REMOTE_ID}")

    fun encryptedFileFor(objectKey: String): File {
        val safeKey = sanitizeKey(objectKey)
        val file = File(encryptedRoot, safeKey)
        file.parentFile?.mkdirs()
        return file
    }

    fun decryptedFileFor(itemId: String): File {
        val safeId = sanitizeKey(itemId)
        val dir = File(decryptedRoot, safeId)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "document.pdf")
    }

    fun createTempDecryptedFile(itemId: String, extension: String): File {
        val safeId = sanitizeKey(itemId)
        val dir = File(decryptedRoot, safeId)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val safeExt = extension.trim().trimStart('.').ifBlank { "bin" }
        return File.createTempFile("source_", ".$safeExt", dir)
    }

    fun createShareFile(itemId: String, extension: String): File {
        val safeId = sanitizeKey(itemId)
        val dir = File(shareRoot, safeId)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val safeExt = extension.trim().trimStart('.').ifBlank { "bin" }
        return File.createTempFile("share_", ".$safeExt", dir)
    }

    fun clearDecryptedCache() {
        if (decryptedRoot.exists()) {
            decryptedRoot.deleteRecursively()
        }
    }

    fun clearEncryptedFiles() {
        if (encryptedRoot.exists()) {
            encryptedRoot.deleteRecursively()
        }
    }

    fun decryptedRootDir(): File = decryptedRoot

    private fun sanitizeKey(key: String): String {
        val normalized = key.trim()
            .trimStart('/')
            .replace('\\', '/')
        val safeSegments = normalized.split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .map { segment -> segment.replace(':', '_') }
        // Collapse unexpected paths to a safe leaf to avoid traversal.
        return safeSegments.joinToString("/").ifBlank { "unknown" }
    }
}
