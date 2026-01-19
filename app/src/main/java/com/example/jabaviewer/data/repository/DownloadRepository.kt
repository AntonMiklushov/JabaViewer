package com.example.jabaviewer.data.repository

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.jabaviewer.workers.DownloadDocumentWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueueDownload(itemId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadDocumentWorker>()
            .setInputData(workDataOf(DownloadDocumentWorker.KEY_ITEM_ID to itemId))
            .setConstraints(constraints)
            // Back off retries to avoid hammering flaky endpoints.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Avoid restarting in-flight downloads when users tap multiple times.
        workManager.enqueueUniqueWork(workName(itemId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(itemId: String) {
        workManager.cancelUniqueWork(workName(itemId))
    }

    private fun workName(itemId: String) = "download-$itemId"
}
