package com.example.jabaviewer.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val catalogItemId: String,
    val pageIndex: Int,
    val label: String,
    val createdAt: Long,
)
