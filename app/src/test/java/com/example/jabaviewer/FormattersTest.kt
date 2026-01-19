package com.example.jabaviewer

import com.example.jabaviewer.ui.util.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {
    @Test
    fun formatBytes_handlesSmallValues() {
        assertEquals("512 B", formatBytes(512))
    }

    @Test
    fun formatBytes_formatsKilobytes() {
        val result = formatBytes(2048)
        assertTrue(result.startsWith("2.0"))
        assertTrue(result.endsWith("KB"))
    }
}
