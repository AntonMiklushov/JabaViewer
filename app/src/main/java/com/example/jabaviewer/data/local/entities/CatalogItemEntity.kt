package com.example.jabaviewer.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "catalog_items")
data class CatalogItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val objectKey: String,
    val size: Long,
    val tags: String,
    val updatedAt: Long,
)
