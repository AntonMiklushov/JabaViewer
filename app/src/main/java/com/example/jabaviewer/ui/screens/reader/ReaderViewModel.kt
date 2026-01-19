package com.example.jabaviewer.ui.screens.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jabaviewer.core.isPdfValid
import com.example.jabaviewer.data.crypto.CryptoEngine
import com.example.jabaviewer.data.local.entities.CatalogItemEntity
import com.example.jabaviewer.data.local.entities.LocalDocumentEntity
import com.example.jabaviewer.data.repository.LibraryRepository
import com.example.jabaviewer.data.repository.SettingsRepository
import com.example.jabaviewer.data.security.PassphraseStore
import com.example.jabaviewer.data.settings.OrientationLock
import com.example.jabaviewer.data.settings.ReaderMode
import com.example.jabaviewer.data.storage.DecryptedCacheManager
import com.example.jabaviewer.data.storage.DocumentStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.crypto.AEADBadTagException
import javax.inject.Inject


data class ReaderUiState(
    val title: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val readerMode: ReaderMode = ReaderMode.CONTINUOUS,
    val nightMode: Boolean = false,
    val keepScreenOn: Boolean = false,
    val orientationLock: OrientationLock = OrientationLock.SYSTEM,
    val decryptedFilePath: String? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val passphraseStore: PassphraseStore,
    private val cryptoEngine: CryptoEngine,
    private val storage: DocumentStorage,
    private val cacheManager: DecryptedCacheManager,
) : ViewModel() {
    private val itemId = savedStateHandle.get<String>("itemId").orEmpty()
    private var cacheLimitMb: Int = 200

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    private val pageUpdates = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collectLatest { settings ->
                cacheLimitMb = settings.decryptedCacheLimitMb
                _uiState.value = _uiState.value.copy(
                    readerMode = settings.readerMode,
                    nightMode = settings.nightMode,
                    keepScreenOn = settings.keepScreenOn,
                    orientationLock = settings.orientationLock,
                )
            }
        }
        viewModelScope.launch {
            pageUpdates
                .distinctUntilChanged()
                .debounce(750)
                .collect { pageIndex ->
                    libraryRepository.updateReadingState(
                        itemId = itemId,
                        decryptedCachePath = null,
                        lastPage = pageIndex,
                        lastOpenedAt = System.currentTimeMillis(),
                    )
                }
        }
        viewModelScope.launch {
            loadDocument()
        }
    }

    fun updateCurrentPage(pageIndex: Int) {
        if (pageIndex < 0) return
        val pageCount = _uiState.value.pageCount
        if (pageCount > 0 && pageIndex >= pageCount) return
        if (_uiState.value.currentPage == pageIndex) return
        _uiState.value = _uiState.value.copy(currentPage = pageIndex)
        pageUpdates.tryEmit(pageIndex)
    }

    fun updatePageCount(count: Int) {
        if (count <= 0) return
        val clampedPage = _uiState.value.currentPage.coerceIn(0, (count - 1).coerceAtLeast(0))
        _uiState.value = _uiState.value.copy(pageCount = count, currentPage = clampedPage)
    }

    private suspend fun loadDocument() {
        if (itemId.isBlank()) {
            _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Missing document id")
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        try {
            val loaded = withContext(Dispatchers.IO) {
                val item = libraryRepository.getCatalogItem(itemId)
                    ?: throw IllegalStateException("Document not found")
                val local = libraryRepository.getLocalDocument(itemId)
                val encryptedFile = local?.encryptedFilePath?.let { File(it) }
                    ?: storage.encryptedFileFor(item.objectKey)
                if (!encryptedFile.exists()) {
                    throw IllegalStateException("Document is not downloaded")
                }
                val decryptedFile = storage.decryptedFileFor(item.id)
                if (!decryptedFile.exists()) {
                    decryptToFile(encryptedFile, decryptedFile)
                } else if (!isPdfValid(decryptedFile)) {
                    decryptedFile.delete()
                    decryptToFile(encryptedFile, decryptedFile)
                }
                decryptedFile.setLastModified(System.currentTimeMillis())
                val evicted = cacheManager.pruneCache(cacheLimitMb, protectedFiles = setOf(decryptedFile))
                LoadedDocument(item, local, decryptedFile, evicted)
            }

            if (loaded.evictedItemIds.isNotEmpty()) {
                libraryRepository.clearDecryptedPaths(loaded.evictedItemIds)
            }

            val lastPage = (loaded.local?.lastPage ?: 0).coerceAtLeast(0)
            _uiState.value = _uiState.value.copy(
                title = loaded.item.title,
                pageCount = PAGE_COUNT_UNKNOWN,
                currentPage = lastPage,
                isLoading = false,
                decryptedFilePath = loaded.decryptedFile.absolutePath,
            )
            libraryRepository.updateReadingState(
                itemId = loaded.item.id,
                decryptedCachePath = loaded.decryptedFile.absolutePath,
                lastPage = lastPage,
                lastOpenedAt = System.currentTimeMillis(),
            )
        } catch (error: Exception) {
            val message = when (error) {
                is AEADBadTagException -> "Wrong passphrase or corrupted file"
                else -> error.message ?: "Failed to open document"
            }
            _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = message)
        }
    }

    private suspend fun decryptToFile(encryptedFile: File, decryptedFile: File) {
        withContext(Dispatchers.IO) {
            val passphrase = passphraseStore.getPassphrase()
                ?: throw IllegalStateException("Passphrase is missing")
            cryptoEngine.decryptToFile(encryptedFile, decryptedFile, passphrase.toCharArray())
        }
        if (!isPdfValid(decryptedFile)) {
            decryptedFile.delete()
            throw IllegalStateException("Wrong passphrase or corrupted file")
        }
    }

    private data class LoadedDocument(
        val item: CatalogItemEntity,
        val local: LocalDocumentEntity?,
        val decryptedFile: File,
        val evictedItemIds: List<String>,
    )

    private companion object {
        private const val PAGE_COUNT_UNKNOWN = 0
    }
}
