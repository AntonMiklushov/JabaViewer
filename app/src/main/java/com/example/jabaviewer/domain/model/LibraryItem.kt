package com.example.jabaviewer.domain.model

import com.example.jabaviewer.data.local.entities.DownloadState

data class LibraryItem(
    val id: String,
    val title: String,
    val objectKey: String,
    val size: Long,
    val tags: List<String>,
    val updatedAt: Long,
    val downloadState: DownloadState,
    val downloadProgress: Int,
    val lastOpenedAt: Long?,
)
