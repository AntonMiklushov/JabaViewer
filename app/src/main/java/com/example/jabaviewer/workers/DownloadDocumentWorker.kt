package com.example.jabaviewer.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.util.Log
import com.example.jabaviewer.core.combineUrl
import com.example.jabaviewer.data.local.dao.CatalogDao
import com.example.jabaviewer.data.local.entities.DownloadState
import com.example.jabaviewer.data.remote.ResumableDownloader
import com.example.jabaviewer.data.repository.LibraryRepository
import com.example.jabaviewer.data.storage.DocumentStorage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@HiltWorker
class DownloadDocumentWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: ResumableDownloader,
    private val catalogDao: CatalogDao,
    private val libraryRepository: LibraryRepository,
    private val storage: DocumentStorage,
) : CoroutineWorker(context, params) {
    @Suppress("LongMethod")
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
            updateDownloadState(
                itemId = itemId,
                state = DownloadState.DOWNLOADING,
                progress = 0,
                encryptedFilePath = destinationFile.absolutePath,
                downloadedAt = null,
            )
            performDownload(
                itemId = itemId,
                url = url,
                tempFile = tempFile,
                destinationFile = destinationFile,
            )
            updateDownloadState(
                itemId = itemId,
                state = DownloadState.DOWNLOADED,
                progress = 100,
                encryptedFilePath = destinationFile.absolutePath,
                downloadedAt = System.currentTimeMillis(),
            )
            Result.success()
        } catch (error: IOException) {
            Log.w(TAG, "Download failed, retrying", error)
            updateDownloadState(
                itemId = itemId,
                state = DownloadState.DOWNLOADING,
                progress = 0,
                encryptedFilePath = destinationFile.absolutePath,
                downloadedAt = null,
            )
            Result.retry()
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Download failed", error)
            updateDownloadState(
                itemId = itemId,
                state = DownloadState.FAILED,
                progress = 0,
                encryptedFilePath = destinationFile.absolutePath,
                downloadedAt = null,
            )
            Result.failure()
        } catch (error: SecurityException) {
            Log.e(TAG, "Download failed: security", error)
            updateDownloadState(
                itemId = itemId,
                state = DownloadState.FAILED,
                progress = 0,
                encryptedFilePath = destinationFile.absolutePath,
                downloadedAt = null,
            )
            Result.failure()
        }
    }

    private suspend fun performDownload(
        itemId: String,
        url: String,
        tempFile: File,
        destinationFile: File,
    ) {
        downloader.download(
            url = url,
            tempFile = tempFile,
            onProgress = { progress ->
                setProgress(workDataOf(KEY_PROGRESS to progress))
                updateDownloadState(
                    itemId = itemId,
                    state = DownloadState.DOWNLOADING,
                    progress = progress.coerceIn(0, 99),
                    encryptedFilePath = destinationFile.absolutePath,
                    downloadedAt = null,
                )
            },
            isStopped = { isStopped },
        )
        replaceFile(tempFile, destinationFile)
    }

    private suspend fun updateDownloadState(
        itemId: String,
        state: DownloadState,
        progress: Int,
        encryptedFilePath: String?,
        downloadedAt: Long?,
    ) {
        libraryRepository.updateDownloadState(
            itemId,
            state,
            progress,
            encryptedFilePath,
            downloadedAt,
        )
    }

    private fun replaceFile(tempFile: File, destinationFile: File) {
        if (destinationFile.exists()) {
            destinationFile.delete()
        }
        if (!tempFile.renameTo(destinationFile)) {
            tempFile.copyTo(destinationFile, overwrite = true)
            tempFile.delete()
        }
    }

    companion object {
        const val KEY_ITEM_ID = "item_id"
        const val KEY_PROGRESS = "progress"
        private const val TAG = "DownloadDocumentWorker"
    }
}
