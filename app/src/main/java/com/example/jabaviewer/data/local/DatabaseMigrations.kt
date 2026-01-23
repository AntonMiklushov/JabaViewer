package com.example.jabaviewer.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE catalog_items ADD COLUMN format TEXT NOT NULL DEFAULT 'pdf'"
            )
        }
    }
}
