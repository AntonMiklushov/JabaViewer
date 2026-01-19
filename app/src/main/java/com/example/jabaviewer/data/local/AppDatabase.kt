package com.example.jabaviewer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.jabaviewer.data.local.dao.BookmarkDao
import com.example.jabaviewer.data.local.dao.CatalogDao
import com.example.jabaviewer.data.local.dao.LocalDocumentDao
import com.example.jabaviewer.data.local.entities.BookmarkEntity
import com.example.jabaviewer.data.local.entities.CatalogItemEntity
import com.example.jabaviewer.data.local.entities.CatalogMetadataEntity
import com.example.jabaviewer.data.local.entities.LocalDocumentEntity

@Database(
    entities = [
        CatalogItemEntity::class,
        LocalDocumentEntity::class,
        CatalogMetadataEntity::class,
        BookmarkEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun localDocumentDao(): LocalDocumentDao
    abstract fun bookmarkDao(): BookmarkDao
}
