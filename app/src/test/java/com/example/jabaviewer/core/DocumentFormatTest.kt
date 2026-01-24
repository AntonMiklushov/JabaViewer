package com.example.jabaviewer.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DocumentFormatTest {
    @Test
    fun detectDocumentFormat_recognizesPdfHeader() {
        val file = File.createTempFile("test", ".pdf")
        try {
            file.writeText("%PDF-1.7\n")
            assertEquals(DocumentFormat.PDF, detectDocumentFormat(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun detectDocumentFormat_recognizesDjvuHeader() {
        val file = File.createTempFile("test", ".djvu")
        try {
            val bytes = ByteArray(64)
            "AT&TFORM".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
            "DJVU".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
            file.writeBytes(bytes)
            assertEquals(DocumentFormat.DJVU, detectDocumentFormat(file))
        } finally {
            file.delete()
        }
    }
}
