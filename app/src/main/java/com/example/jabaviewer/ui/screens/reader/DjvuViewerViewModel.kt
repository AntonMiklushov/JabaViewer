package com.example.jabaviewer.ui.screens.reader

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jabaviewer.core.DocumentFormat
import com.example.jabaviewer.data.djvu.DjvuConverter
import com.example.jabaviewer.data.djvu.DjvuDocument
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.crypto.AEADBadTagException
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

data class DjvuViewerUiState(
    val title: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val pageAspectRatios: List<Float> = emptyList(),
    val readerMode: ReaderMode = ReaderMode.CONTINUOUS,
    val keepScreenOn: Boolean = false,
    val orientationLock: OrientationLock = OrientationLock.SYSTEM,
    val decryptedFilePath: String? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class DjvuViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dependencies: DjvuViewerDependencies,
) : ViewModel() {
    private val itemId = savedStateHandle.get<String>("itemId").orEmpty()
    private var cacheLimitMb: Int = 200
    private var decryptedFile: File? = null
    private var document: DjvuDocument? = null
    private val prefetchJobs = mutableMapOf<Int, Job>()
    private val renderMutex = Mutex()
    private val renderCache = object : LruCache<RenderKey, Bitmap>(CACHE_SIZE_KB) {
        override fun sizeOf(key: RenderKey, value: Bitmap): Int = value.byteCount / 1024
    }

    private val _uiState = MutableStateFlow(DjvuViewerUiState())
    val uiState: StateFlow<DjvuViewerUiState> = _uiState

    private val pageUpdates = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch {
            dependencies.settingsRepository.settingsFlow.collectLatest { settings ->
                cacheLimitMb = settings.decryptedCacheLimitMb
                _uiState.value = _uiState.value.copy(
                    readerMode = settings.readerMode,
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
                        decryptedCachePath = decryptedFile?.absolutePath,
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

    fun updateVisiblePages(
        visiblePages: List<Int>,
        bucketWidthPx: Int,
    ) {
        if (visiblePages.isEmpty() || bucketWidthPx <= 0) return
        val current = visiblePages.first()
        updateCurrentPage(current)
        val pageCount = _uiState.value.pageCount
        if (pageCount <= 0) return
        val targets = listOf(current - 1, current + 1)
            .filter { it in 0 until pageCount }
            .toSet()
        val toCancel = prefetchJobs.keys.filter { it !in targets }
        toCancel.forEach { key ->
            prefetchJobs.remove(key)?.cancel()
        }
        targets.forEach { index ->
            if (prefetchJobs.containsKey(index)) return@forEach
            val cached = synchronized(renderCache) {
                renderCache.get(RenderKey(index, bucketWidthPx))
            }
            if (cached != null) return@forEach
            prefetchJobs[index] = viewModelScope.launch {
                val result = runCatching { renderPage(index, bucketWidthPx) }
                val error = result.exceptionOrNull() ?: return@launch
                if (error is kotlinx.coroutines.CancellationException) {
                    throw error
                }
                Log.e(TAG, "Prefetch failed for page $index", error)
            }
        }
    }

    suspend fun renderPage(
        pageIndex: Int,
        bucketWidthPx: Int,
    ): Bitmap {
        val key = RenderKey(pageIndex, bucketWidthPx)
        val cached = synchronized(renderCache) { renderCache.get(key) }
        cached?.let { return it }
        return withContext(Dispatchers.Default) {
            renderMutex.withLock {
                coroutineContext.ensureActive()
                val secondCached = synchronized(renderCache) { renderCache.get(key) }
                secondCached?.let { return@withLock it }
                val handle = checkNotNull(document) { "DjVu document is not loaded" }
                val bitmap = dependencies.djvuConverter.renderPage(handle, pageIndex, bucketWidthPx)
                coroutineContext.ensureActive()
                synchronized(renderCache) { renderCache.put(key, bitmap) }
                return@withLock bitmap
            }
        }
    }

    fun onRenderFailure(error: Throwable) {
        Log.e(TAG, "Failed to render DjVu page", error)
        val message = if (error is UnsatisfiedLinkError) {
            "DjVu viewer is not available on this device build."
        } else {
            "Failed to open DjVu file."
        }
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            errorMessage = message,
        )
    }

    override fun onCleared() {
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
        document?.close()
        document = null
        renderCache.evictAll()
        super.onCleared()
    }

    @Suppress("LongMethod")
    private suspend fun loadDocument() {
        if (itemId.isBlank()) {
            _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Missing document id")
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        val result = runCatching { loadDocumentInternal() }
        val loaded = result.getOrElse { error ->
            handleLoadError(error)
            return
        }
        decryptedFile = loaded.decryptedFile
        document = loaded.handle
        applyLoadedDocument(loaded)
    }

    private suspend fun loadDocumentInternal(): LoadedDjvu {
        val item = checkNotNull(dependencies.libraryRepository.getCatalogItem(itemId)) {
            "Document not found"
        }
        val local = dependencies.libraryRepository.getLocalDocument(itemId)
        val encryptedFile = local?.encryptedFilePath?.let { File(it) }
            ?: dependencies.storage.encryptedFileFor(item.objectKey)
        check(encryptedFile.exists()) { "Document is not downloaded" }
        val prepared = dependencies.documentPreparer.prepareDecryptedDocument(
            encryptedFile = encryptedFile,
            itemId = item.id,
            formatHint = DocumentFormat.DJVU,
        )
        if (prepared.format != DocumentFormat.DJVU) {
            dependencies.libraryRepository.updateCatalogItemFormat(item.id, prepared.format)
            error("Unexpected document format: ${prepared.format.label}")
        }
        check(prepared.file.exists()) { "Cache file missing" }
        val evicted = dependencies.cacheManager.pruneCache(
            cacheLimitMb,
            protectedFiles = setOf(prepared.file),
        )
        if (evicted.isNotEmpty()) {
            dependencies.libraryRepository.clearDecryptedPaths(evicted)
        }
        val handle = dependencies.djvuConverter.openDocument(prepared.file)
        return runCatching {
            val pageCount = dependencies.djvuConverter.getPageCount(handle)
            check(pageCount > 0) { "DjVu contains no pages" }
            val ratios = buildList(pageCount) {
                repeat(pageCount) { index ->
                    val info = dependencies.djvuConverter.getPageInfo(handle, index)
                    val ratio = if (info.width > 0) {
                        info.height.toFloat() / info.width.toFloat()
                    } else {
                        DEFAULT_PAGE_RATIO
                    }
                    add(ratio)
                }
            }
            val initialPage = (local?.lastPage ?: 0).coerceIn(0, pageCount - 1)
            LoadedDjvu(
                title = item.title,
                pageCount = pageCount,
                initialPage = initialPage,
                pageAspectRatios = ratios,
                decryptedFile = prepared.file,
                handle = handle,
            )
        }.getOrElse { error ->
            handle.close()
            throw error
        }
    }

    private suspend fun applyLoadedDocument(loaded: LoadedDjvu) {
        _uiState.value = _uiState.value.copy(
            title = loaded.title,
            pageCount = loaded.pageCount,
            currentPage = loaded.initialPage,
            isLoading = false,
            errorMessage = null,
            pageAspectRatios = loaded.pageAspectRatios,
            decryptedFilePath = loaded.decryptedFile.absolutePath,
        )
        dependencies.libraryRepository.updateReadingState(
            itemId = itemId,
            decryptedCachePath = loaded.decryptedFile.absolutePath,
            lastPage = loaded.initialPage,
            lastOpenedAt = System.currentTimeMillis(),
        )
    }

    private fun handleLoadError(error: Throwable) {
        if (error is CancellationException) {
            throw error
        }
        when (error) {
            is AEADBadTagException -> {
                Log.e(TAG, "Decryption failed", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Decryption failed.",
                )
            }
            is UnsatisfiedLinkError -> {
                Log.e(TAG, "DjVu native library is missing", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "DjVu viewer is not available on this device build.",
                )
            }
            is IllegalStateException -> {
                Log.e(TAG, "Failed to open DjVu document", error)
                val message = if (error.message == "Cache file missing") {
                    "File not found in cache."
                } else {
                    "Failed to open DjVu file."
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = message,
                )
            }
            is java.io.IOException -> {
                Log.e(TAG, "Failed to open DjVu document: IO", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to open DjVu file.",
                )
            }
            is SecurityException -> {
                Log.e(TAG, "Failed to open DjVu document: security", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to open DjVu file.",
                )
            }
            else -> {
                Log.e(TAG, "Failed to open DjVu document", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to open DjVu file.",
                )
            }
        }
    }

    private data class RenderKey(
        val pageIndex: Int,
        val bucketWidthPx: Int,
    )

    private data class LoadedDjvu(
        val title: String,
        val pageCount: Int,
        val initialPage: Int,
        val pageAspectRatios: List<Float>,
        val decryptedFile: File,
        val handle: DjvuDocument,
    )

    private companion object {
        private const val TAG = "DjvuViewerViewModel"
        private const val DEFAULT_PAGE_RATIO = 1.4f
        private val CACHE_SIZE_KB: Int =
            (Runtime.getRuntime().maxMemory() / 1024).toInt() / 8
    }
}

class DjvuViewerDependencies @Inject constructor(
    val libraryRepository: LibraryRepository,
    val settingsRepository: SettingsRepository,
    val documentPreparer: DocumentPreparer,
    val storage: DocumentStorage,
    val cacheManager: DecryptedCacheManager,
    val djvuConverter: DjvuConverter,
)
