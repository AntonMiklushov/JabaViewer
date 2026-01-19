package com.example.jabaviewer.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.jabaviewer.data.local.dao.LocalDocumentDao
import com.example.jabaviewer.data.local.entities.DownloadState
import com.example.jabaviewer.data.local.entities.LocalDocumentEntity
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
class LocalDocumentDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var localDao: LocalDocumentDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localDao = db.localDocumentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun clearDecryptedPaths_targetsOnlySelectedItems() = runTest {
        val docA = LocalDocumentEntity(
            catalogItemId = "a",
            encryptedFilePath = "/tmp/a.enc",
            decryptedCachePath = "/tmp/a.pdf",
            downloadedAt = 1L,
            lastOpenedAt = null,
            lastPage = null,
            downloadState = DownloadState.DOWNLOADED,
            downloadProgress = 100,
        )
        val docB = LocalDocumentEntity(
            catalogItemId = "b",
            encryptedFilePath = "/tmp/b.enc",
            decryptedCachePath = "/tmp/b.pdf",
            downloadedAt = 2L,
            lastOpenedAt = null,
            lastPage = null,
            downloadState = DownloadState.DOWNLOADED,
            downloadProgress = 100,
        )
        localDao.upsert(docA)
        localDao.upsert(docB)

        localDao.clearDecryptedPaths(listOf("a"))

        assertNull(localDao.getLocalDocument("a")?.decryptedCachePath)
        assertEquals("/tmp/b.pdf", localDao.getLocalDocument("b")?.decryptedCachePath)
    }
}
