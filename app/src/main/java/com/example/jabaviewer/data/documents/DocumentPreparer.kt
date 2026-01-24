package com.example.jabaviewer.data.documents

import com.example.jabaviewer.core.AppConstants
import com.example.jabaviewer.core.DocumentFormat
import com.example.jabaviewer.core.detectDocumentFormat
import com.example.jabaviewer.core.isPdfValid
import com.example.jabaviewer.data.crypto.CryptoEngine
import com.example.jabaviewer.data.djvu.DjvuConverter
import com.example.jabaviewer.data.security.PassphraseStore
import com.example.jabaviewer.data.storage.DocumentStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.crypto.AEADBadTagException
import javax.inject.Inject

data class PreparedPdf(
    val file: File,
    val wasCreated: Boolean,
    val format: DocumentFormat,
)

data class PreparedOriginal(
    val file: File,
    val format: DocumentFormat,
)

data class PreparedDocument(
    val file: File,
    val format: DocumentFormat,
    val wasCreated: Boolean,
)

class DocumentPreparer @Inject constructor(
    private val passphraseStore: PassphraseStore,
    private val cryptoEngine: CryptoEngine,
    private val storage: DocumentStorage,
    private val djvuConverter: DjvuConverter,
) {
    suspend fun preparePdf(
        encryptedFile: File,
        itemId: String,
        formatHint: DocumentFormat,
        targetDpi: Int = DEFAULT_TARGET_DPI,
    ): PreparedPdf = withContext(Dispatchers.IO) {
        val outputFile = storage.decryptedFileFor(itemId, DocumentFormat.PDF)
        val upToDate = outputFile.exists() &&
            isPdfValid(outputFile) &&
            outputFile.lastModified() >= encryptedFile.lastModified()
        if (upToDate) {
            return@withContext PreparedPdf(outputFile, wasCreated = false, format = formatHint)
        }

        if (outputFile.exists()) {
            outputFile.delete()
        }

        val tempFile = storage.createTempDecryptedFile(itemId, formatHint.extension)
        var detectedFormat = formatHint
        try {
            decryptToFile(encryptedFile, tempFile)
            val detected = detectDocumentFormat(tempFile)
                ?: error("Unsupported or corrupted document")
            detectedFormat = detected
            when (detected) {
                DocumentFormat.PDF -> moveOrCopy(tempFile, outputFile)
                DocumentFormat.DJVU -> {
                    djvuConverter.convertToPdf(tempFile, outputFile, targetDpi)
                }
            }
            check(isPdfValid(outputFile)) { "Generated PDF is invalid" }
            check(outputFile.exists()) { "Failed to generate PDF" }
        } finally {
            tempFile.delete()
        }
        outputFile.setLastModified(System.currentTimeMillis())
        return@withContext PreparedPdf(outputFile, wasCreated = true, format = detectedFormat)
    }

    suspend fun decryptOriginal(
        encryptedFile: File,
        itemId: String,
        formatHint: DocumentFormat,
    ): PreparedOriginal = withContext(Dispatchers.IO) {
        val tempFile = storage.createTempDecryptedFile(itemId, formatHint.extension)
        var success = false
        try {
            decryptToFile(encryptedFile, tempFile)
            val detected = detectDocumentFormat(tempFile)
                ?: error("Unsupported or corrupted document")
            check(detected == formatHint) { "Unexpected document format: ${detected.label}" }
            success = true
            return@withContext PreparedOriginal(tempFile, detected)
        } finally {
            if (!success) {
                tempFile.delete()
            }
        }
    }

    suspend fun prepareShareOriginal(
        encryptedFile: File,
        itemId: String,
        formatHint: DocumentFormat,
    ): PreparedOriginal = withContext(Dispatchers.IO) {
        val shareFile = storage.createShareFile(itemId, formatHint.extension)
        var success = false
        try {
            decryptToFile(encryptedFile, shareFile)
            val detected = detectDocumentFormat(shareFile)
                ?: error("Unsupported or corrupted document")
            check(detected == formatHint) { "Unexpected document format: ${detected.label}" }
            success = true
            return@withContext PreparedOriginal(shareFile, detected)
        } finally {
            if (!success) {
                shareFile.delete()
            }
        }
    }

    suspend fun prepareDecryptedDocument(
        encryptedFile: File,
        itemId: String,
        formatHint: DocumentFormat,
    ): PreparedDocument = withContext(Dispatchers.IO) {
        val outputFile = storage.decryptedFileFor(itemId, formatHint)
        val upToDate = outputFile.exists() &&
            outputFile.lastModified() >= encryptedFile.lastModified() &&
            detectDocumentFormat(outputFile) == formatHint
        if (upToDate) {
            return@withContext PreparedDocument(outputFile, formatHint, wasCreated = false)
        }

        if (outputFile.exists()) {
            outputFile.delete()
        }

        val tempFile = storage.createTempDecryptedFile(itemId, formatHint.extension)
        var detectedFormat = formatHint
        val resolvedFile: File
        try {
            decryptToFile(encryptedFile, tempFile)
            detectedFormat = detectDocumentFormat(tempFile)
                ?: error("Unsupported or corrupted document")
            resolvedFile = storage.decryptedFileFor(itemId, detectedFormat)
            moveOrCopy(tempFile, resolvedFile)
            check(resolvedFile.exists()) { "Failed to prepare document" }
        } finally {
            tempFile.delete()
        }
        resolvedFile.setLastModified(System.currentTimeMillis())
        return@withContext PreparedDocument(resolvedFile, detectedFormat, wasCreated = true)
    }

    private fun moveOrCopy(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
        }
    }

    @Throws(AEADBadTagException::class)
    private suspend fun decryptToFile(encryptedFile: File, decryptedFile: File) {
        withContext(Dispatchers.IO) {
            val passphrase = checkNotNull(passphraseStore.getPassphrase()) {
                "Passphrase is missing"
            }
            cryptoEngine.decryptToFile(encryptedFile, decryptedFile, passphrase.toCharArray())
        }
    }

    private companion object {
        private const val DEFAULT_TARGET_DPI = AppConstants.DEFAULT_DJVU_CONVERSION_DPI
    }
}
