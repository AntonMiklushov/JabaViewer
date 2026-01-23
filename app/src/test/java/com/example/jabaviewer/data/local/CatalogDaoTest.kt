package com.example.jabaviewer.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.jabaviewer.data.local.dao.CatalogDao
import com.example.jabaviewer.data.local.entities.CatalogItemEntity
import com.example.jabaviewer.data.local.entities.CatalogMetadataEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CatalogDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var catalogDao: CatalogDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        catalogDao = db.catalogDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun replaceCatalog_replacesItemsAndMetadata() = runTest {
        val initialItems = listOf(
            CatalogItemEntity(
                id = "item-1",
                title = "First",
                objectKey = "first.pdf",
                size = 100,
                tags = "tag",
                format = "pdf",
                updatedAt = 1L,
            )
        )
        val initialMetadata = CatalogMetadataEntity(
            version = "1",
            baseUrl = "https://example.com",
            lastSync = 1L,
            itemCount = initialItems.size,
        )
        catalogDao.replaceCatalog(initialItems, initialMetadata)

        val refreshedItems = listOf(
            CatalogItemEntity(
                id = "item-2",
                title = "Second",
                objectKey = "second.pdf",
                size = 200,
                tags = "tag2",
                format = "pdf",
                updatedAt = 2L,
            )
        )
        val refreshedMetadata = CatalogMetadataEntity(
            version = "2",
            baseUrl = "https://example.com",
            lastSync = 2L,
            itemCount = refreshedItems.size,
        )
        catalogDao.replaceCatalog(refreshedItems, refreshedMetadata)

        assertNull(catalogDao.getCatalogItem("item-1"))
        assertEquals("item-2", catalogDao.getCatalogItem("item-2")?.id)
        assertEquals("2", catalogDao.getMetadata()?.version)
    }
}
