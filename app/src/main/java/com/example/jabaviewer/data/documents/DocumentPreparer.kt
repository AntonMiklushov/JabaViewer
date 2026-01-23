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
)

data class PreparedOriginal(
    val file: File,
    val format: DocumentFormat,
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
        val outputFile = storage.decryptedFileFor(itemId)
        val upToDate = outputFile.exists() &&
            isPdfValid(outputFile) &&
            outputFile.lastModified() >= encryptedFile.lastModified()
        if (upToDate) {
            return@withContext PreparedPdf(outputFile, wasCreated = false)
        }

        if (outputFile.exists()) {
            outputFile.delete()
        }

        val tempFile = storage.createTempDecryptedFile(itemId, formatHint.extension)
        try {
            decryptToFile(encryptedFile, tempFile)
            val detected = detectDocumentFormat(tempFile)
                ?: error("Unsupported or corrupted document")
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
        return@withContext PreparedPdf(outputFile, wasCreated = true)
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
