package com.example.jabaviewer

import com.example.jabaviewer.data.local.entities.DownloadState
import com.example.jabaviewer.data.repository.CatalogRepository
import com.example.jabaviewer.data.repository.DownloadRepository
import com.example.jabaviewer.data.repository.LibraryRepository
import com.example.jabaviewer.data.repository.SettingsRepository
import com.example.jabaviewer.data.settings.AppSettings
import com.example.jabaviewer.data.settings.LibraryLayout
import com.example.jabaviewer.data.settings.OrientationLock
import com.example.jabaviewer.data.settings.ReaderMode
import com.example.jabaviewer.data.settings.SortOrder
import com.example.jabaviewer.core.DocumentFormat
import com.example.jabaviewer.domain.model.LibraryItem
import com.example.jabaviewer.ui.screens.library.LibraryViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun searchAndTagFilters_applyBeforeSorting() = runTest {
        val itemsFlow = MutableStateFlow(sampleItems())
        val settingsState = MutableStateFlow(defaultSettings())

        val libraryRepository = mockk<LibraryRepository> {
            every { observeLibrary() } returns itemsFlow
        }
        val settingsRepository = mockk<SettingsRepository> {
            every { settingsFlow } returns settingsState
        }
        val catalogRepository = mockk<CatalogRepository>(relaxed = true)
        val downloadRepository = mockk<DownloadRepository>(relaxed = true)

        val viewModel = LibraryViewModel(
            libraryRepository = libraryRepository,
            settingsRepository = settingsRepository,
            catalogRepository = catalogRepository,
            downloadRepository = downloadRepository,
        )

        val job = backgroundScope.launch { viewModel.uiState.collect() }
        advanceUntilIdle()

        viewModel.updateSearch("beta")
        advanceUntilIdle()
        assertEquals(listOf("Beta Guide"), viewModel.uiState.value.items.map { it.title })

        viewModel.toggleTag("history")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), viewModel.uiState.value.items.map { it.title })

        viewModel.toggleTag("beta")
        advanceUntilIdle()
        assertEquals(listOf("Beta Guide"), viewModel.uiState.value.items.map { it.title })

        job.cancel()
    }

    @Test
    fun sortOrder_title_sortsAlphabetically() = runTest {
        val itemsFlow = MutableStateFlow(sampleItems())
        val settingsState = MutableStateFlow(defaultSettings(sortOrder = SortOrder.TITLE))

        val libraryRepository = mockk<LibraryRepository> {
            every { observeLibrary() } returns itemsFlow
        }
        val settingsRepository = mockk<SettingsRepository> {
            every { settingsFlow } returns settingsState
        }
        val catalogRepository = mockk<CatalogRepository>(relaxed = true)
        val downloadRepository = mockk<DownloadRepository>(relaxed = true)

        val viewModel = LibraryViewModel(
            libraryRepository = libraryRepository,
            settingsRepository = settingsRepository,
            catalogRepository = catalogRepository,
            downloadRepository = downloadRepository,
        )

        val job = backgroundScope.launch { viewModel.uiState.collect() }
        advanceUntilIdle()

        assertEquals(
            listOf("Alpha Manual", "Beta Guide"),
            viewModel.uiState.value.items.map { it.title },
        )

        job.cancel()
    }

    private fun sampleItems(): List<LibraryItem> = listOf(
        LibraryItem(
            id = "a",
            title = "Beta Guide",
            objectKey = "beta.pdf",
            size = 120,
            tags = listOf("beta", "guide"),
            format = DocumentFormat.PDF,
            updatedAt = 2L,
            downloadState = DownloadState.DOWNLOADED,
            downloadProgress = 100,
            lastOpenedAt = null,
        ),
        LibraryItem(
            id = "b",
            title = "Alpha Manual",
            objectKey = "alpha.pdf",
            size = 200,
            tags = listOf("history"),
            format = DocumentFormat.PDF,
            updatedAt = 3L,
            downloadState = DownloadState.NOT_DOWNLOADED,
            downloadProgress = 0,
            lastOpenedAt = null,
        )
    )

    private fun defaultSettings(sortOrder: SortOrder = SortOrder.RECENT): AppSettings = AppSettings(
        baseUrl = "https://example.com",
        catalogPath = "library/catalog.enc",
        libraryLayout = LibraryLayout.LIST,
        sortOrder = sortOrder,
        readerMode = ReaderMode.CONTINUOUS,
        nightMode = false,
        keepScreenOn = false,
        orientationLock = OrientationLock.SYSTEM,
        decryptedCacheLimitMb = 200,
        djvuConversionDpi = 250,
    )
}
