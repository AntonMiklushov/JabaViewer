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
            val request = buildRequest(url, resumeAllowed, downloadedBytes)
            val response = okHttpClient.newCall(request).execute()
            var restartFresh = false
            response.use {
                val validation = validateResponse(it, resumeAllowed, downloadedBytes)
                if (validation.shouldRetryFresh) {
                    restartFresh = true
                    return@use
                }
                handleResponseStatus(it)
                val body = checkNotNull(it.body) { "Empty download body" }
                val contentLength = body.contentLength()
                check(contentLength != 0L) { "Empty download body" }
                val totalBytes = totalBytesFor(contentLength, downloadedBytes, validation.append)
                downloadedBytes = prepareTempFile(tempFile, validation.append, downloadedBytes)
                val totalRead = writeBodyToFile(
                    inputStream = body.byteStream(),
                    tempFile = tempFile,
                    append = validation.append,
                    startingBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    onProgress = onProgress,
                    isStopped = isStopped,
                    lastProgressPersisted = lastProgressPersisted,
                    lastProgressUpdate = lastProgressUpdate,
                ).also { readState ->
                    lastProgressPersisted = readState.lastProgressPersisted
                    lastProgressUpdate = readState.lastProgressUpdate
                }.totalRead
                validateDownloadSize(totalBytes, totalRead, tempFile)
                return@withContext
            }
            if (restartFresh) {
                resetTempFile(tempFile)
                downloadedBytes = 0L
                resumeAllowed = false
                continue
            }
        }
    }

    private fun validateResponse(
        response: Response,
        resumeAllowed: Boolean,
        downloadedBytes: Long,
    ): ResumeValidation {
        return when {
            !resumeAllowed || downloadedBytes == 0L -> ResumeValidation(append = false, shouldRetryFresh = false)
            response.code == 206 -> {
                val header = response.header("Content-Range")
                val start = header?.let(::parseContentRangeStart)
                ResumeValidation(append = start == downloadedBytes, shouldRetryFresh = start != downloadedBytes)
            }
            else -> ResumeValidation(append = false, shouldRetryFresh = response.code == 200)
        }
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
        return when {
            progress == lastProgress -> false
            progress == 100 -> true
            else -> progress - lastProgress >= PROGRESS_STEP || now - lastUpdateTime >= PROGRESS_UPDATE_MS
        }
    }

    private fun buildRequest(url: String, resumeAllowed: Boolean, downloadedBytes: Long): Request {
        val requestBuilder = Request.Builder().url(url)
        if (resumeAllowed && downloadedBytes > 0L) {
            requestBuilder.header("Range", "bytes=$downloadedBytes-")
        }
        return requestBuilder.build()
    }

    private fun handleResponseStatus(response: Response) {
        if (response.isSuccessful) return
        if (response.code >= 500) {
            throw IOException("Server error: ${response.code}")
        }
        error("Download failed: ${response.code}")
    }

    private fun totalBytesFor(contentLength: Long, downloadedBytes: Long, append: Boolean): Long {
        return when {
            contentLength <= 0L -> -1L
            append -> downloadedBytes + contentLength
            else -> contentLength
        }
    }

    private fun prepareTempFile(tempFile: File, append: Boolean, downloadedBytes: Long): Long {
        if (!append && tempFile.exists()) {
            tempFile.delete()
            return 0L
        }
        tempFile.parentFile?.mkdirs()
        return downloadedBytes
    }

    private fun resetTempFile(tempFile: File) {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    private data class ProgressState(
        val totalRead: Long,
        val lastProgressPersisted: Int,
        val lastProgressUpdate: Long,
    )

    private suspend fun writeBodyToFile(
        inputStream: java.io.InputStream,
        tempFile: File,
        append: Boolean,
        startingBytes: Long,
        totalBytes: Long,
        onProgress: suspend (Int) -> Unit,
        isStopped: () -> Boolean,
        lastProgressPersisted: Int,
        lastProgressUpdate: Long,
    ): ProgressState {
        var totalRead = startingBytes
        var progressPersisted = lastProgressPersisted
        var progressUpdate = lastProgressUpdate
        inputStream.use { input ->
            FileOutputStream(tempFile, append).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesRead: Int
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
                        if (shouldPersistProgress(progress, progressPersisted, progressUpdate, now)) {
                            progressUpdate = now
                            progressPersisted = progress
                            onProgress(progress.coerceIn(0, 99))
                        }
                    }
                }
                output.fd.sync()
            }
        }
        return ProgressState(totalRead, progressPersisted, progressUpdate)
    }

    private fun validateDownloadSize(totalBytes: Long, totalRead: Long, tempFile: File) {
        if (totalBytes > 0L) {
            check(totalRead == totalBytes) { "Truncated download" }
            return
        }
        check(tempFile.length() > 0L) { "Empty download" }
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
