package com.example.jabaviewer.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlin.math.roundToInt

fun interface TimeProvider {
    fun nowMs(): Long
}

object SystemTimeProvider : TimeProvider {
    override fun nowMs(): Long = System.currentTimeMillis()
}

class ResumableDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val timeProvider: TimeProvider,
) {
    // Keeps resume logic testable without WorkManager.
    suspend fun download(
        url: String,
        tempFile: File,
        onProgress: suspend (Int) -> Unit,
        isStopped: () -> Boolean,
    ) = withContext(Dispatchers.IO) {
        var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L
        var resumeAllowed = downloadedBytes > 0L
        var lastProgressUpdate = 0L
        var lastProgressPersisted = 0

        while (true) {
            val requestBuilder = Request.Builder().url(url)
            if (resumeAllowed && downloadedBytes > 0L) {
                requestBuilder.header("Range", "bytes=$downloadedBytes-")
            }
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            var restartFresh = false
            response.use {
                val validation = validateResponse(it, resumeAllowed, downloadedBytes)
                if (validation.shouldRetryFresh) {
                    tempFile.delete()
                    downloadedBytes = 0L
                    resumeAllowed = false
                    restartFresh = true
                    return@use
                }
                if (!it.isSuccessful) {
                    if (it.code >= 500) {
                        throw IOException("Server error: ${it.code}")
                    }
                    throw IllegalStateException("Download failed: ${it.code}")
                }
                val body = it.body ?: throw IllegalStateException("Empty download body")
                val contentLength = body.contentLength()
                if (contentLength == 0L) {
                    throw IOException("Empty download body")
                }
                val totalBytes = when {
                    contentLength <= 0L -> -1L
                    validation.append -> downloadedBytes + contentLength
                    else -> contentLength
                }
                if (!validation.append && tempFile.exists()) {
                    tempFile.delete()
                    downloadedBytes = 0L
                }
                tempFile.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    FileOutputStream(tempFile, validation.append).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytesRead: Int
                        var totalRead = downloadedBytes
                        while (input.read(buffer).also { bytesRead = it } >= 0) {
                            if (isStopped()) {
                                throw IOException("Download interrupted")
                            }
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0L) {
                                val progress = ((totalRead / totalBytes.toDouble()) * 100)
                                    .roundToInt()
                                    .coerceIn(0, 100)
                                val now = timeProvider.nowMs()
                                if (shouldPersistProgress(
                                        progress,
                                        lastProgressPersisted,
                                        lastProgressUpdate,
                                        now,
                                    )
                                ) {
                                    lastProgressUpdate = now
                                    lastProgressPersisted = progress
                                    onProgress(progress.coerceIn(0, 99))
                                }
                            }
                        }
                        // Ensure bytes are on disk before moving into place.
                        output.fd.sync()
                        if (totalBytes > 0L && totalRead != totalBytes) {
                            throw IOException("Truncated download")
                        }
                        if (totalBytes <= 0L && tempFile.length() == 0L) {
                            throw IOException("Empty download")
                        }
                    }
                }
                return@withContext
            }
            if (restartFresh) {
                continue
            }
        }
    }

    private fun validateResponse(
        response: Response,
        resumeAllowed: Boolean,
        downloadedBytes: Long,
    ): ResumeValidation {
        if (!resumeAllowed || downloadedBytes == 0L) {
            return ResumeValidation(append = false, shouldRetryFresh = false)
        }
        if (response.code == 206) {
            val header = response.header("Content-Range")
            val start = header?.let(::parseContentRangeStart)
            if (start != downloadedBytes) {
                return ResumeValidation(append = false, shouldRetryFresh = true)
            }
            return ResumeValidation(append = true, shouldRetryFresh = false)
        }
        return ResumeValidation(append = false, shouldRetryFresh = response.code == 200)
    }

    private fun parseContentRangeStart(value: String): Long? {
        val match = CONTENT_RANGE_REGEX.find(value.trim()) ?: return null
        return match.groupValues.getOrNull(1)?.toLongOrNull()
    }

    private fun shouldPersistProgress(
        progress: Int,
        lastProgress: Int,
        lastUpdateTime: Long,
        now: Long,
    ): Boolean {
        // Reduce DB churn while still keeping UI reasonably fresh.
        if (progress == lastProgress) return false
        if (progress == 100) return true
        return progress - lastProgress >= PROGRESS_STEP || now - lastUpdateTime >= PROGRESS_UPDATE_MS
    }

    private data class ResumeValidation(
        val append: Boolean,
        val shouldRetryFresh: Boolean,
    )

    companion object {
        private const val PROGRESS_STEP = 2
        private const val PROGRESS_UPDATE_MS = 750L
        private val CONTENT_RANGE_REGEX = Regex("bytes (\\d+)-\\d+/\\d+")
    }
}
