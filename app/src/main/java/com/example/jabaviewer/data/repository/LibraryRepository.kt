package com.example.jabaviewer.data.repository

import com.example.jabaviewer.data.local.dao.CatalogDao
import com.example.jabaviewer.data.local.dao.LocalDocumentDao
import com.example.jabaviewer.data.local.entities.DownloadState
import com.example.jabaviewer.data.local.entities.LocalDocumentEntity
import com.example.jabaviewer.domain.model.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@Suppress("TooManyFunctions")
class LibraryRepository @Inject constructor(
    private val catalogDao: CatalogDao,
    private val localDocumentDao: LocalDocumentDao,
) {
    fun observeLibrary(): Flow<List<LibraryItem>> =
        catalogDao.observeLibraryItems().map { items ->
            items.map { pair ->
                val local = pair.local
                LibraryItem(
                    id = pair.item.id,
                    title = pair.item.title,
                    objectKey = pair.item.objectKey,
                    size = pair.item.size,
                    tags = pair.item.tags.split("|").filter { it.isNotBlank() },
                    updatedAt = pair.item.updatedAt,
                    downloadState = local?.downloadState ?: DownloadState.NOT_DOWNLOADED,
                    downloadProgress = local?.downloadProgress ?: 0,
                    lastOpenedAt = local?.lastOpenedAt,
                )
            }
        }

    suspend fun getCatalogItem(itemId: String) = catalogDao.getCatalogItem(itemId)

    suspend fun getLocalDocument(itemId: String) = localDocumentDao.getLocalDocument(itemId)

    suspend fun upsertLocalDocument(entity: LocalDocumentEntity) {
        localDocumentDao.upsert(entity)
    }

    suspend fun updateDownloadState(
        itemId: String,
        state: DownloadState,
        progress: Int,
        encryptedFilePath: String?,
        downloadedAt: Long?,
    ) {
        val current = localDocumentDao.getLocalDocument(itemId)
        val updated = (current ?: LocalDocumentEntity(
            catalogItemId = itemId,
            encryptedFilePath = null,
            decryptedCachePath = null,
            downloadedAt = null,
            lastOpenedAt = null,
            lastPage = null,
            downloadState = DownloadState.NOT_DOWNLOADED,
            downloadProgress = 0,
        )).copy(
            encryptedFilePath = encryptedFilePath ?: current?.encryptedFilePath,
            downloadedAt = downloadedAt ?: current?.downloadedAt,
            downloadState = state,
            downloadProgress = progress,
        )
        localDocumentDao.upsert(updated)
    }

    suspend fun updateReadingState(
        itemId: String,
        decryptedCachePath: String?,
        lastPage: Int?,
        lastOpenedAt: Long?,
    ) {
        val current = localDocumentDao.getLocalDocument(itemId)
        val updated = (current ?: LocalDocumentEntity(
            catalogItemId = itemId,
            encryptedFilePath = null,
            decryptedCachePath = null,
            downloadedAt = null,
            lastOpenedAt = null,
            lastPage = null,
            downloadState = DownloadState.NOT_DOWNLOADED,
            downloadProgress = 0,
        )).copy(
            decryptedCachePath = decryptedCachePath ?: current?.decryptedCachePath,
            lastPage = lastPage ?: current?.lastPage,
            lastOpenedAt = lastOpenedAt ?: current?.lastOpenedAt,
        )
        localDocumentDao.upsert(updated)
    }

    suspend fun deleteLocalDocument(itemId: String) {
        localDocumentDao.delete(itemId)
    }

    suspend fun clearDownloadState(itemId: String) {
        val current = localDocumentDao.getLocalDocument(itemId) ?: return
        val cleared = current.copy(
            encryptedFilePath = null,
            downloadedAt = null,
            downloadState = DownloadState.NOT_DOWNLOADED,
            downloadProgress = 0,
        )
        localDocumentDao.upsert(cleared)
    }

    suspend fun clearDecryptedPaths() {
        localDocumentDao.clearDecryptedPaths()
    }

    suspend fun clearDecryptedPaths(itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        localDocumentDao.clearDecryptedPaths(itemIds)
    }

    suspend fun clearAllLocalDocuments() {
        localDocumentDao.clearAll()
    }
}
