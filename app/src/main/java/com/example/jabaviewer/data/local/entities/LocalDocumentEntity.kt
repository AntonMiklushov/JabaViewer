package com.example.jabaviewer.data.local.entities

import androidx.room.Entity

@Entity(
    tableName = "local_documents",
    primaryKeys = ["catalogItemId"],
)
data class LocalDocumentEntity(
    val catalogItemId: String,
    val encryptedFilePath: String?,
    val decryptedCachePath: String?,
    val downloadedAt: Long?,
    val lastOpenedAt: Long?,
    val lastPage: Int?,
    val downloadState: DownloadState,
    val downloadProgress: Int,
)
