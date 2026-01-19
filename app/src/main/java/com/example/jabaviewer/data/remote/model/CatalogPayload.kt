package com.example.jabaviewer.data.remote.model

data class CatalogPayload(
    val version: String,
    val baseUrl: String,
    val items: List<CatalogItemPayload>,
)

data class CatalogItemPayload(
    val id: String,
    val title: String,
    val objectKey: String,
    val size: Long,
    val tags: List<String> = emptyList(),
)
