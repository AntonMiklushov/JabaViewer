package com.example.jabaviewer.di

import android.content.Context
import androidx.room.Room
import com.example.jabaviewer.data.local.AppDatabase
import com.example.jabaviewer.data.local.DatabaseMigrations
import com.example.jabaviewer.data.local.dao.BookmarkDao
import com.example.jabaviewer.data.local.dao.CatalogDao
import com.example.jabaviewer.data.local.dao.LocalDocumentDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "jaba_viewer.db")
            .addMigrations(DatabaseMigrations.MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideCatalogDao(database: AppDatabase): CatalogDao = database.catalogDao()

    @Provides
    fun provideLocalDocumentDao(database: AppDatabase): LocalDocumentDao = database.localDocumentDao()

    @Provides
    fun provideBookmarkDao(database: AppDatabase): BookmarkDao = database.bookmarkDao()
}
