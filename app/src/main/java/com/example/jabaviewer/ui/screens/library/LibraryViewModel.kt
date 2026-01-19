package com.example.jabaviewer.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jabaviewer.data.local.entities.DownloadState
import com.example.jabaviewer.data.repository.CatalogRepository
import com.example.jabaviewer.data.repository.CatalogSyncResult
import com.example.jabaviewer.data.repository.DownloadRepository
import com.example.jabaviewer.data.repository.LibraryRepository
import com.example.jabaviewer.data.repository.SettingsRepository
import com.example.jabaviewer.data.settings.LibraryLayout
import com.example.jabaviewer.data.settings.SortOrder
import com.example.jabaviewer.domain.model.LibraryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val items: List<LibraryItem> = emptyList(),
    val searchQuery: String = "",
    val selectedTags: Set<String> = emptySet(),
    val availableTags: List<String> = emptyList(),
    val layout: LibraryLayout = LibraryLayout.LIST,
    val sortOrder: SortOrder = SortOrder.RECENT,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val catalogRepository: CatalogRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {
    private val searchQuery = MutableStateFlow("")
    private val selectedTags = MutableStateFlow<Set<String>>(emptySet())
    private val isRefreshing = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    private val filterState = combine(
        searchQuery,
        selectedTags,
        isRefreshing,
        errorMessage,
    ) { query, tags, refreshing, error ->
        FilterState(query, tags, refreshing, error)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryRepository.observeLibrary(),
        settingsRepository.settingsFlow,
        filterState,
    ) { items, settings, filter ->
        val filtered = applyFilters(items, filter.query, filter.tags)
        val sorted = applySort(filtered, settings.sortOrder)
        val availableTags = items.flatMap { it.tags }.distinct().sorted()
        LibraryUiState(
            items = sorted,
            searchQuery = filter.query,
            selectedTags = filter.tags,
            availableTags = availableTags,
            layout = settings.libraryLayout,
            sortOrder = settings.sortOrder,
            isRefreshing = filter.refreshing,
            errorMessage = filter.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    fun updateSearch(query: String) {
        searchQuery.value = query
    }

    fun toggleTag(tag: String) {
        val current = selectedTags.value.toMutableSet()
        if (current.contains(tag)) {
            current.remove(tag)
        } else {
            current.add(tag)
        }
        selectedTags.value = current
    }

    fun clearTags() {
        selectedTags.value = emptySet()
    }

    fun toggleLayout() {
        viewModelScope.launch {
            val next = if (uiState.value.layout == LibraryLayout.LIST) {
                LibraryLayout.GRID
            } else {
                LibraryLayout.LIST
            }
            settingsRepository.updateLibraryLayout(next)
        }
    }

    fun updateSort(order: SortOrder) {
        viewModelScope.launch {
            settingsRepository.updateSortOrder(order)
        }
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            isRefreshing.value = true
            errorMessage.value = null
            when (val result = catalogRepository.syncCatalog()) {
                is CatalogSyncResult.Success -> Unit
                is CatalogSyncResult.Error -> errorMessage.value = result.message
            }
            isRefreshing.value = false
        }
    }

    fun downloadItem(itemId: String) {
        downloadRepository.enqueueDownload(itemId)
    }

    fun cancelDownload(itemId: String) {
        downloadRepository.cancelDownload(itemId)
    }

    private fun applyFilters(
        items: List<LibraryItem>,
        query: String,
        tags: Set<String>,
    ): List<LibraryItem> {
        val trimmed = query.trim()
        return items.filter { item ->
            val matchesQuery = trimmed.isBlank() ||
                item.title.contains(trimmed, ignoreCase = true) ||
                item.tags.any { it.contains(trimmed, ignoreCase = true) }
            val matchesTags = tags.isEmpty() || item.tags.any { tags.contains(it) }
            matchesQuery && matchesTags
        }
    }

    private fun applySort(items: List<LibraryItem>, sortOrder: SortOrder): List<LibraryItem> {
        return when (sortOrder) {
            SortOrder.RECENT -> items.sortedByDescending { it.lastOpenedAt ?: it.updatedAt }
            SortOrder.TITLE -> items.sortedBy { it.title.lowercase() }
            SortOrder.SIZE -> items.sortedByDescending { it.size }
            SortOrder.DOWNLOADED -> items.sortedByDescending { it.downloadState == DownloadState.DOWNLOADED }
        }
    }

    private data class FilterState(
        val query: String,
        val tags: Set<String>,
        val refreshing: Boolean,
        val error: String?,
    )
}
