package com.example.jabaviewer.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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

            // Delegate resumable transfer details for reuse and test coverage.
            downloader.download(
                url = url,
                tempFile = tempFile,
                onProgress = { progress ->
                    setProgress(workDataOf(KEY_PROGRESS to progress))
                    libraryRepository.updateDownloadState(
                        itemId,
                        DownloadState.DOWNLOADING,
                        progress = progress.coerceIn(0, 99),
                        encryptedFilePath = destinationFile.absolutePath,
                        downloadedAt = null,
                    )
                },
                isStopped = { isStopped },
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

    companion object {
        const val KEY_ITEM_ID = "item_id"
        const val KEY_PROGRESS = "progress"
    }
}
