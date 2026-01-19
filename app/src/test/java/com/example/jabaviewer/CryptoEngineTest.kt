package com.example.jabaviewer

import com.example.jabaviewer.data.crypto.CryptoEngine
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException

class CryptoEngineTest {
    private val engine = CryptoEngine()

    @Test
    fun parseContainerHeader_matchesFixture() {
        val bytes = readFixture("fixtures/catalog_fixture.enc")
        val container = engine.parseContainer(bytes)
        assertEquals(10000, container.iterations)
        assertEquals(16, container.salt.size)
        assertEquals(12, container.iv.size)
        assertTrue(container.salt.contentEquals((1..16).map { it.toByte() }.toByteArray()))
        assertTrue(container.iv.contentEquals((21..32).map { it.toByte() }.toByteArray()))
    }

    @Test
    fun decryptCatalogFixture_returnsJson() {
        val bytes = readFixture("fixtures/catalog_fixture.enc")
        val decrypted = engine.decryptToBytes(bytes, TEST_PASSPHRASE.toCharArray())
        val json = String(decrypted, StandardCharsets.UTF_8)
        assertTrue(json.contains("\"version\":\"1\""))
        assertTrue(json.contains("\"items\""))
    }

    @Test
    fun decryptPdfFixture_startsWithPdfHeader() {
        val bytes = readFixture("fixtures/pdf_fixture.enc")
        val decrypted = engine.decryptToBytes(bytes, TEST_PASSPHRASE.toCharArray())
        val header = String(decrypted.copyOfRange(0, 5), StandardCharsets.US_ASCII)
        assertEquals("%PDF-", header)
    }

    @Test
    fun parseContainer_rejectsInvalidMagic() {
        val container = "NOPE".toByteArray(StandardCharsets.US_ASCII) + ByteArray(32)
        assertThrows(IllegalArgumentException::class.java) {
            engine.parseContainer(container)
        }
    }

    @Test
    fun parseContainer_rejectsEmptyCiphertext() {
        val container = buildContainer(ByteArray(0))
        assertThrows(IllegalArgumentException::class.java) {
            engine.parseContainer(container)
        }
    }

    @Test
    fun parseContainer_rejectsZeroIterations() {
        val container = buildContainer(ByteArray(16), iterations = 0)
        assertThrows(IllegalArgumentException::class.java) {
            engine.parseContainer(container)
        }
    }

    @Test
    fun parseContainer_rejectsExcessiveIterations() {
        val container = buildContainer(ByteArray(16), iterations = 10_000_001)
        assertThrows(IllegalArgumentException::class.java) {
            engine.parseContainer(container)
        }
    }

    @Test
    fun parseContainer_rejectsTruncatedHeader() {
        val truncated = "LIB1".toByteArray(StandardCharsets.US_ASCII) + ByteArray(3)
        assertThrows(IllegalArgumentException::class.java) {
            engine.parseContainer(truncated)
        }
    }

    @Test
    fun decrypt_rejectsModifiedCiphertext() {
        val bytes = readFixture("fixtures/catalog_fixture.enc")
        val tampered = bytes.clone()
        tampered[tampered.lastIndex] = (tampered.last() + 1).toByte()
        assertThrows(AEADBadTagException::class.java) {
            engine.decryptToBytes(tampered, TEST_PASSPHRASE.toCharArray())
        }
    }

    private fun readFixture(path: String): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: throw IllegalStateException("Missing fixture: $path")
        return stream.readBytes()
    }

    private fun buildContainer(ciphertext: ByteArray, iterations: Int = 10000): ByteArray {
        val header = ByteArray(4 + 4 + 16 + 12)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buffer.put("LIB1".toByteArray(StandardCharsets.US_ASCII))
        buffer.putInt(iterations)
        buffer.put((1..16).map { it.toByte() }.toByteArray())
        buffer.put((21..32).map { it.toByte() }.toByteArray())
        return header + ciphertext
    }

    private companion object {
        private const val TEST_PASSPHRASE = "test-passphrase"
    }
}
