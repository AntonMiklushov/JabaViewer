package com.example.jabaviewer.data.storage

import androidx.test.core.app.ApplicationProvider
import com.example.jabaviewer.core.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentStorageTest {
    @Test
    fun decryptedFileFor_usesFormatExtension() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = DocumentStorage(context)
        val file = storage.decryptedFileFor("item_djvu", DocumentFormat.DJVU)

        assertEquals("document.djvu", file.name)

        storage.deleteDecryptedFiles("item_djvu")
    }

    @Test
    fun decryptedFileFor_keepsLegacyPdfForPdfItems() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = DocumentStorage(context)
        val dir = storage.decryptedDirFor("item_pdf")
        val legacy = java.io.File(dir, "document.pdf")
        legacy.parentFile?.mkdirs()
        legacy.writeText("pdf")

        val resolved = storage.decryptedFileFor("item_pdf", DocumentFormat.PDF)
        assertEquals(legacy.absolutePath, resolved.absolutePath)

        storage.deleteDecryptedFiles("item_pdf")
    }

    @Test
    fun decryptedFileFor_doesNotReusePdfForDjvu() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = DocumentStorage(context)
        val dir = storage.decryptedDirFor("item_mix")
        val legacyPdf = java.io.File(dir, "document.pdf")
        legacyPdf.parentFile?.mkdirs()
        legacyPdf.writeText("pdf")

        val resolved = storage.decryptedFileFor("item_mix", DocumentFormat.DJVU)
        assertNotEquals(legacyPdf.absolutePath, resolved.absolutePath)
        assertEquals("document.djvu", resolved.name)

        storage.deleteDecryptedFiles("item_mix")
    }
}
