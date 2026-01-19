package com.example.jabaviewer.ui.screens.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
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
    private val dependencies: ReaderDependencies,
) : ViewModel() {
    private val itemId = savedStateHandle.get<String>("itemId").orEmpty()
    private var cacheLimitMb: Int = 200

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    private val pageUpdates = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch {
            dependencies.settingsRepository.settingsFlow.collectLatest { settings ->
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
                    dependencies.libraryRepository.updateReadingState(
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
        val pageCount = _uiState.value.pageCount
        val isInvalid = pageIndex < 0 ||
            (pageCount > 0 && pageIndex >= pageCount) ||
            _uiState.value.currentPage == pageIndex
        if (isInvalid) return
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
            val loaded = loadDocumentInternal()
            applyLoadedDocument(loaded)
        } catch (error: AEADBadTagException) {
            Log.e(TAG, "Failed to open document: bad tag", error)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = "Wrong passphrase or corrupted file",
            )
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Failed to open document: state", error)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = error.message ?: "Failed to open document",
            )
        } catch (error: java.io.IOException) {
            Log.e(TAG, "Failed to open document: IO", error)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = error.message ?: "Failed to open document",
            )
        } catch (error: SecurityException) {
            Log.e(TAG, "Failed to open document: security", error)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = error.message ?: "Failed to open document",
            )
        }
    }

    private suspend fun loadDocumentInternal(): LoadedDocument = withContext(Dispatchers.IO) {
        val item = checkNotNull(dependencies.libraryRepository.getCatalogItem(itemId)) {
            "Document not found"
        }
        val local = dependencies.libraryRepository.getLocalDocument(itemId)
        val encryptedFile = local?.encryptedFilePath?.let { File(it) }
            ?: dependencies.storage.encryptedFileFor(item.objectKey)
        check(encryptedFile.exists()) { "Document is not downloaded" }
        val decryptedFile = dependencies.storage.decryptedFileFor(item.id)
        if (!decryptedFile.exists()) {
            decryptToFile(encryptedFile, decryptedFile)
        } else if (!isPdfValid(decryptedFile)) {
            decryptedFile.delete()
            decryptToFile(encryptedFile, decryptedFile)
        }
        decryptedFile.setLastModified(System.currentTimeMillis())
        val evicted = dependencies.cacheManager.pruneCache(
            cacheLimitMb,
            protectedFiles = setOf(decryptedFile),
        )
        LoadedDocument(item, local, decryptedFile, evicted)
    }

    private suspend fun applyLoadedDocument(loaded: LoadedDocument) {
        if (loaded.evictedItemIds.isNotEmpty()) {
            dependencies.libraryRepository.clearDecryptedPaths(loaded.evictedItemIds)
        }

        val lastPage = (loaded.local?.lastPage ?: 0).coerceAtLeast(0)
        _uiState.value = _uiState.value.copy(
            title = loaded.item.title,
            pageCount = PAGE_COUNT_UNKNOWN,
            currentPage = lastPage,
            isLoading = false,
            decryptedFilePath = loaded.decryptedFile.absolutePath,
        )
        dependencies.libraryRepository.updateReadingState(
            itemId = loaded.item.id,
            decryptedCachePath = loaded.decryptedFile.absolutePath,
            lastPage = lastPage,
            lastOpenedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun decryptToFile(encryptedFile: File, decryptedFile: File) {
        withContext(Dispatchers.IO) {
            val passphrase = checkNotNull(dependencies.passphraseStore.getPassphrase()) {
                "Passphrase is missing"
            }
            dependencies.cryptoEngine.decryptToFile(encryptedFile, decryptedFile, passphrase.toCharArray())
        }
        if (!isPdfValid(decryptedFile)) {
            decryptedFile.delete()
            error("Wrong passphrase or corrupted file")
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
        private const val TAG = "ReaderViewModel"
    }
}

class ReaderDependencies @Inject constructor(
    val libraryRepository: LibraryRepository,
    val settingsRepository: SettingsRepository,
    val passphraseStore: PassphraseStore,
    val cryptoEngine: CryptoEngine,
    val storage: DocumentStorage,
    val cacheManager: DecryptedCacheManager,
)
