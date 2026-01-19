package com.example.jabaviewer.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.jabaviewer.core.combineUrl
import com.example.jabaviewer.data.local.dao.CatalogDao
import com.example.jabaviewer.data.local.entities.DownloadState
import com.example.jabaviewer.data.repository.LibraryRepository
import com.example.jabaviewer.data.storage.DocumentStorage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt

@HiltWorker
class DownloadDocumentWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: OkHttpClient,
    private val catalogDao: CatalogDao,
    private val libraryRepository: LibraryRepository,
    private val storage: DocumentStorage,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return@withContext Result.failure()
        val item = libraryRepository.getCatalogItem(itemId)
            ?: return@withContext Result.failure()
        val metadata = catalogDao.getMetadata()
            ?: return@withContext Result.failure()

        val url = combineUrl(metadata.baseUrl, item.objectKey)
        val destinationFile = storage.encryptedFileFor(item.objectKey)
        val tempFile = File(destinationFile.absolutePath + ".part")

        try {
            libraryRepository.updateDownloadState(
                itemId,
                DownloadState.DOWNLOADING,
                progress = 0,
                encryptedFilePath = destinationFile.absolutePath,
                downloadedAt = null,
            )

            downloadToFile(
                url = url,
                tempFile = tempFile,
                destinationFile = destinationFile,
                itemId = itemId,
            )

            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            if (!tempFile.renameTo(destinationFile)) {
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
            }

            libraryRepository.updateDownloadState(
                itemId,
                DownloadState.DOWNLOADED,
                progress = 100,
                encryptedFilePath = destinationFile.absolutePath,
                downloadedAt = System.currentTimeMillis(),
            )
            Result.success()
        } catch (error: Exception) {
            val retry = error is IOException
            libraryRepository.updateDownloadState(
                itemId,
                if (retry) DownloadState.DOWNLOADING else DownloadState.FAILED,
                progress = 0,
                encryptedFilePath = destinationFile.absolutePath,
                downloadedAt = null,
            )
            if (retry) Result.retry() else Result.failure()
        }
    }

    private suspend fun downloadToFile(
        url: String,
        tempFile: File,
        destinationFile: File,
        itemId: String,
    ) {
        var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L
        var resumeAllowed = downloadedBytes > 0L
        var progress = 0
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
                    // Range mismatch means we should restart cleanly to avoid corrupt files.
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
                            if (isStopped) {
                                throw IOException("Download interrupted")
                            }
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0L) {
                                progress = ((totalRead / totalBytes.toDouble()) * 100).roundToInt().coerceIn(0, 100)
                                val now = System.currentTimeMillis()
                                if (shouldPersistProgress(progress, lastProgressPersisted, lastProgressUpdate, now)) {
                                    lastProgressUpdate = now
                                    lastProgressPersisted = progress
                                    setProgress(workDataOf(KEY_PROGRESS to progress))
                                    libraryRepository.updateDownloadState(
                                        itemId,
                                        DownloadState.DOWNLOADING,
                                        progress = progress.coerceIn(0, 99),
                                        encryptedFilePath = destinationFile.absolutePath,
                                        downloadedAt = null,
                                    )
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
                return
            }
            if (restartFresh) {
                continue
            }
        }
    }

    private fun validateResponse(response: Response, resumeAllowed: Boolean, downloadedBytes: Long): ResumeValidation {
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
        const val KEY_ITEM_ID = "item_id"
        const val KEY_PROGRESS = "progress"
        private const val PROGRESS_STEP = 2
        private const val PROGRESS_UPDATE_MS = 750L
        private val CONTENT_RANGE_REGEX = Regex("bytes (\\d+)-\\d+/\\d+")
    }
}
