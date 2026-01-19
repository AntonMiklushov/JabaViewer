package com.example.jabaviewer.data.crypto

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Suppress("TooManyFunctions")
class CryptoEngine {
    fun parseContainer(bytes: ByteArray): Lib1Container {
        val stream = bytes.inputStream()
        return parseContainer(stream)
    }

    fun parseContainer(stream: InputStream): Lib1Container {
        val header = parseHeader(stream)
        val ciphertext = stream.readBytes()
        require(ciphertext.size >= GCM_TAG_BYTES) { "Ciphertext is too short" }
        return Lib1Container(
            iterations = header.iterations,
            salt = header.salt,
            iv = header.iv,
            ciphertext = ciphertext,
        )
    }

    fun decryptToBytes(containerBytes: ByteArray, passphrase: CharArray): ByteArray {
        return try {
            val container = parseContainer(containerBytes)
            val cipher = createCipher(passphrase, container)
            cipher.doFinal(container.ciphertext)
        } finally {
            wipeCharArray(passphrase)
        }
    }

    fun decryptToFile(containerFile: File, outputFile: File, passphrase: CharArray) {
        val tempFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
        var success = false
        try {
            decryptToTempFile(containerFile, tempFile, passphrase)
            replaceOutputFile(tempFile, outputFile)
            success = true
        } finally {
            if (!success) {
                tempFile.delete()
                outputFile.delete()
            }
            wipeCharArray(passphrase)
        }
    }

    private fun createCipher(passphrase: CharArray, header: Lib1Header): Cipher {
        val key = deriveKey(passphrase, header.salt, header.iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, header.iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher
    }

    private fun createCipher(passphrase: CharArray, container: Lib1Container): Cipher {
        return createCipher(
            passphrase,
            Lib1Header(
                iterations = container.iterations,
                salt = container.salt,
                iv = container.iv,
            )
        )
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return try {
            val keyBytes = factory.generateSecret(spec).encoded
            try {
                SecretKeySpec(keyBytes, "AES")
            } finally {
                Arrays.fill(keyBytes, 0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    private fun readExactly(stream: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = stream.read(buffer, offset, size - offset)
            require(read != -1) { "Unexpected end of container" }
            offset += read
        }
        return buffer
    }

    private fun parseHeader(stream: InputStream): Lib1Header {
        // Matches the LIB1 container format from the reference project: magic + iterations + salt + iv.
        val magic = readExactly(stream, 4)
        require(magic.contentEquals(MAGIC_BYTES)) { "Invalid container magic" }
        val iterationsBytes = readExactly(stream, 4)
        val iterations = parseIterations(iterationsBytes)
        val salt = readExactly(stream, 16)
        val iv = readExactly(stream, 12)
        return Lib1Header(iterations = iterations, salt = salt, iv = iv)
    }

    private data class Lib1Header(
        val iterations: Int,
        val salt: ByteArray,
        val iv: ByteArray,
    )

    private companion object {
        private val MAGIC_BYTES = "LIB1".toByteArray(Charsets.US_ASCII)
        private const val GCM_TAG_BYTES = 16
        // Prevent pathological iteration counts from locking up the UI on corrupted inputs.
        private const val MAX_PBKDF2_ITERATIONS = 10_000_000
    }

    private fun parseIterations(bytes: ByteArray): Int {
        // Treat the 4-byte iteration count as unsigned per spec.
        val unsigned = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFF_FFFFL
        require(unsigned in 1..MAX_PBKDF2_ITERATIONS.toLong()) { "Invalid iterations" }
        return unsigned.toInt()
    }

    private fun wipeCharArray(buffer: CharArray) {
        // Minimize passphrase exposure in memory after use.
        Arrays.fill(buffer, '\u0000')
    }

    private fun decryptToTempFile(containerFile: File, tempFile: File, passphrase: CharArray) {
        containerFile.inputStream().use { input ->
            val header = parseHeader(input)
            val cipher = createCipher(passphrase, header)
            tempFile.parentFile?.mkdirs()
            java.io.FileOutputStream(tempFile).use { output ->
                CipherInputStream(input, cipher).use { cipherInput ->
                    cipherInput.copyTo(output)
                }
                output.fd.sync()
            }
        }
    }

    private fun replaceOutputFile(tempFile: File, outputFile: File) {
        if (outputFile.exists()) {
            check(outputFile.delete()) { "Failed to replace existing output" }
        }
        if (!tempFile.renameTo(outputFile)) {
            tempFile.copyTo(outputFile, overwrite = true)
            tempFile.delete()
        }
    }
}
