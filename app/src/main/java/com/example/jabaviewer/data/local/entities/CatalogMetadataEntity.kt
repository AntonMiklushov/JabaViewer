package com.example.jabaviewer.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "catalog_metadata")
data class CatalogMetadataEntity(
    @PrimaryKey val id: Int = 1,
    val version: String,
    val baseUrl: String,
    val lastSync: Long,
    val itemCount: Int,
)
