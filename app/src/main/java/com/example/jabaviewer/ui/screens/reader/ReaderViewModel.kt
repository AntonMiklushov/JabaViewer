package com.example.jabaviewer.ui.screens.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.example.jabaviewer.core.AppConstants
import com.example.jabaviewer.core.DocumentFormat
import com.example.jabaviewer.core.isPdfValid
import com.example.jabaviewer.data.local.entities.CatalogItemEntity
import com.example.jabaviewer.data.local.entities.LocalDocumentEntity
import com.example.jabaviewer.data.documents.DocumentPreparer
import com.example.jabaviewer.data.repository.LibraryRepository
import com.example.jabaviewer.data.repository.SettingsRepository
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
    val loadingMessage: String? = null,
    val errorMessage: String? = null,
    val requiresDjvuAction: Boolean = false,
    val djvuActionError: String? = null,
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
    private var djvuConversionDpi: Int = AppConstants.DEFAULT_DJVU_CONVERSION_DPI
    private var pendingDjvu: PendingDjvu? = null

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    private val pageUpdates = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch {
            dependencies.settingsRepository.settingsFlow.collectLatest { settings ->
                cacheLimitMb = settings.decryptedCacheLimitMb
                djvuConversionDpi = settings.djvuConversionDpi
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

    @Suppress("LongMethod")
    private suspend fun loadDocument() {
        if (itemId.isBlank()) {
            _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Missing document id")
            return
        }
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            loadingMessage = "Preparing document...",
            requiresDjvuAction = false,
            djvuActionError = null,
            errorMessage = null,
        )
        try {
            when (val result = loadDocumentInternal()) {
                is LoadResult.Ready -> applyLoadedDocument(result.loaded)
                is LoadResult.NeedsDjvuAction -> {
                    pendingDjvu = PendingDjvu(result.item, result.local, result.encryptedFile)
                    _uiState.value = _uiState.value.copy(
                        title = result.item.title,
                        isLoading = false,
                        loadingMessage = null,
                        errorMessage = null,
                        requiresDjvuAction = true,
                        djvuActionError = null,
                        decryptedFilePath = null,
                    )
                }
            }
        } catch (error: AEADBadTagException) {
            Log.e(TAG, "Failed to open document: bad tag", error)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                loadingMessage = null,
                requiresDjvuAction = false,
                errorMessage = "Wrong passphrase or corrupted file",
            )
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Failed to open document: state", error)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                loadingMessage = null,
                requiresDjvuAction = false,
                errorMessage = error.message ?: "Failed to open document",
            )
        } catch (error: java.io.IOException) {
            Log.e(TAG, "Failed to open document: IO", error)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                loadingMessage = null,
                requiresDjvuAction = false,
                errorMessage = error.message ?: "Failed to open document",
            )
        } catch (error: SecurityException) {
            Log.e(TAG, "Failed to open document: security", error)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                loadingMessage = null,
                errorMessage = error.message ?: "Failed to open document",
            )
        }
    }

    private suspend fun loadDocumentInternal(): LoadResult = withContext(Dispatchers.IO) {
        val item = checkNotNull(dependencies.libraryRepository.getCatalogItem(itemId)) {
            "Document not found"
        }
        val local = dependencies.libraryRepository.getLocalDocument(itemId)
        val encryptedFile = local?.encryptedFilePath?.let { File(it) }
            ?: dependencies.storage.encryptedFileFor(item.objectKey)
        check(encryptedFile.exists()) { "Document is not downloaded" }
        val decryptedFile = dependencies.storage.decryptedFileFor(item.id)
        val format = DocumentFormat.fromRaw(item.format)
        val isCachedPdf = decryptedFile.exists() &&
            isPdfValid(decryptedFile) &&
            decryptedFile.lastModified() >= encryptedFile.lastModified()
        if (format == DocumentFormat.DJVU && !isCachedPdf) {
            return@withContext LoadResult.NeedsDjvuAction(item, local, encryptedFile)
        }
        if (!isCachedPdf) {
            updateLoadingMessage(
                "Decrypting PDF..."
            )
            dependencies.documentPreparer.preparePdf(
                encryptedFile = encryptedFile,
                itemId = item.id,
                formatHint = format,
                targetDpi = djvuConversionDpi,
            )
        }
        decryptedFile.setLastModified(System.currentTimeMillis())
        val evicted = dependencies.cacheManager.pruneCache(
            cacheLimitMb,
            protectedFiles = setOf(decryptedFile),
        )
        LoadResult.Ready(LoadedDocument(item, local, decryptedFile, evicted))
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
            loadingMessage = null,
            requiresDjvuAction = false,
            djvuActionError = null,
            decryptedFilePath = loaded.decryptedFile.absolutePath,
        )
        dependencies.libraryRepository.updateReadingState(
            itemId = loaded.item.id,
            decryptedCachePath = loaded.decryptedFile.absolutePath,
            lastPage = lastPage,
            lastOpenedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun updateLoadingMessage(message: String) {
        withContext(Dispatchers.Main) {
            _uiState.value = _uiState.value.copy(loadingMessage = message)
        }
    }

    fun convertPendingDjvu() {
        val pending = pendingDjvu ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                loadingMessage = "Converting DJVU to PDF...",
                requiresDjvuAction = false,
                djvuActionError = null,
                errorMessage = null,
            )
            try {
                val decryptedFile = dependencies.storage.decryptedFileFor(pending.item.id)
                dependencies.documentPreparer.preparePdf(
                    encryptedFile = pending.encryptedFile,
                    itemId = pending.item.id,
                    formatHint = DocumentFormat.DJVU,
                    targetDpi = djvuConversionDpi,
                )
                decryptedFile.setLastModified(System.currentTimeMillis())
                val evicted = dependencies.cacheManager.pruneCache(
                    cacheLimitMb,
                    protectedFiles = setOf(decryptedFile),
                )
                applyLoadedDocument(LoadedDocument(pending.item, pending.local, decryptedFile, evicted))
            } catch (error: AEADBadTagException) {
                Log.e(TAG, "Failed to convert DJVU: bad tag", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = null,
                    requiresDjvuAction = true,
                    djvuActionError = "Wrong passphrase or corrupted file",
                )
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Failed to convert DJVU: state", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = null,
                    requiresDjvuAction = true,
                    djvuActionError = error.message ?: "Failed to convert DJVU",
                )
            } catch (error: java.io.IOException) {
                Log.e(TAG, "Failed to convert DJVU: IO", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = null,
                    requiresDjvuAction = true,
                    djvuActionError = error.message ?: "Failed to convert DJVU",
                )
            } catch (error: SecurityException) {
                Log.e(TAG, "Failed to convert DJVU: security", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = null,
                    requiresDjvuAction = true,
                    djvuActionError = error.message ?: "Failed to convert DJVU",
                )
            }
        }
    }

    fun saveDjvuCopy(contentResolver: android.content.ContentResolver, targetUri: android.net.Uri) {
        val pending = pendingDjvu ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                loadingMessage = "Decrypting DJVU...",
                requiresDjvuAction = false,
                djvuActionError = null,
                errorMessage = null,
            )
            try {
                val prepared = dependencies.documentPreparer.decryptOriginal(
                    encryptedFile = pending.encryptedFile,
                    itemId = pending.item.id,
                    formatHint = DocumentFormat.DJVU,
                )
                writeDecryptedCopy(contentResolver, targetUri, prepared.file)
                prepared.file.delete()
                prepared.file.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = null,
                    requiresDjvuAction = true,
                )
            } catch (error: AEADBadTagException) {
                Log.e(TAG, "Failed to save DJVU: bad tag", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = null,
                    requiresDjvuAction = true,
                    djvuActionError = "Wrong passphrase or corrupted file",
                )
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Failed to save DJVU: state", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = null,
                    requiresDjvuAction = true,
                    djvuActionError = error.message ?: "Failed to save DJVU",
                )
            } catch (error: java.io.IOException) {
                Log.e(TAG, "Failed to save DJVU: IO", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = null,
                    requiresDjvuAction = true,
                    djvuActionError = error.message ?: "Failed to save DJVU",
                )
            } catch (error: SecurityException) {
                Log.e(TAG, "Failed to save DJVU: security", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = null,
                    requiresDjvuAction = true,
                    djvuActionError = error.message ?: "Failed to save DJVU",
                )
            }
        }
    }

    @Suppress("LongMethod")
    fun openExternalDjvu(context: android.content.Context) {
        val pending = pendingDjvu ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                loadingMessage = "Preparing DJVU...",
                requiresDjvuAction = false,
                djvuActionError = null,
                errorMessage = null,
            )
            try {
                val prepared = dependencies.documentPreparer.prepareShareOriginal(
                    encryptedFile = pending.encryptedFile,
                    itemId = pending.item.id,
                    formatHint = DocumentFormat.DJVU,
                )
                val opened = com.example.jabaviewer.core.openExternalViewer(
                    context,
                    prepared.file,
                    DocumentFormat.DJVU.mimeType,
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = null,
                    requiresDjvuAction = true,
                    djvuActionError = if (opened) null else "No app found to open DJVU",
                )
            } catch (error: AEADBadTagException) {
                Log.e(TAG, "Failed to open DJVU: bad tag", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = null,
                    requiresDjvuAction = true,
                    djvuActionError = "Wrong passphrase or corrupted file",
                )
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Failed to open DJVU: state", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = null,
                    requiresDjvuAction = true,
                    djvuActionError = error.message ?: "Failed to open DJVU",
                )
            } catch (error: java.io.IOException) {
                Log.e(TAG, "Failed to open DJVU: IO", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = null,
                    requiresDjvuAction = true,
                    djvuActionError = error.message ?: "Failed to open DJVU",
                )
            } catch (error: SecurityException) {
                Log.e(TAG, "Failed to open DJVU: security", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    loadingMessage = null,
                    requiresDjvuAction = true,
                    djvuActionError = error.message ?: "Failed to open DJVU",
                )
            }
        }
    }

    private fun writeDecryptedCopy(
        contentResolver: android.content.ContentResolver,
        targetUri: android.net.Uri,
        decryptedFile: File,
    ) {
        val outputStream = contentResolver.openOutputStream(targetUri)
        checkNotNull(outputStream) { "Unable to open destination" }
        outputStream.use { output ->
            decryptedFile.inputStream().use { input ->
                input.copyTo(output)
                output.flush()
            }
        }
    }

    private data class LoadedDocument(
        val item: CatalogItemEntity,
        val local: LocalDocumentEntity?,
        val decryptedFile: File,
        val evictedItemIds: List<String>,
    )

    private sealed interface LoadResult {
        data class Ready(val loaded: LoadedDocument) : LoadResult
        data class NeedsDjvuAction(
            val item: CatalogItemEntity,
            val local: LocalDocumentEntity?,
            val encryptedFile: File,
        ) : LoadResult
    }

    private data class PendingDjvu(
        val item: CatalogItemEntity,
        val local: LocalDocumentEntity?,
        val encryptedFile: File,
    )

    private companion object {
        private const val PAGE_COUNT_UNKNOWN = 0
        private const val TAG = "ReaderViewModel"
    }
}

class ReaderDependencies @Inject constructor(
    val libraryRepository: LibraryRepository,
    val settingsRepository: SettingsRepository,
    val documentPreparer: DocumentPreparer,
    val storage: DocumentStorage,
    val cacheManager: DecryptedCacheManager,
)
