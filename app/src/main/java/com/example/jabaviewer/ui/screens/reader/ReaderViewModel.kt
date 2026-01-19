package com.example.jabaviewer.ui.screens.reader

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview

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
    val scale: Float = 1f,
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
    private var controller: PdfDocumentController? = null
    private var cacheLimitMb: Int = 200

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    val pageBitmaps = mutableStateMapOf<PageRenderKey, androidx.compose.ui.graphics.ImageBitmap>()
    val thumbnailBitmaps = mutableStateMapOf<Int, androidx.compose.ui.graphics.ImageBitmap>()

    private val thumbnailCache = object : LruCache<Int, androidx.compose.ui.graphics.ImageBitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: Int, value: androidx.compose.ui.graphics.ImageBitmap): Int {
            return value.width * value.height * BYTES_PER_PIXEL
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: Int,
            oldValue: androidx.compose.ui.graphics.ImageBitmap,
            newValue: androidx.compose.ui.graphics.ImageBitmap?,
        ) {
            if (evicted && thumbnailBitmaps[key] === oldValue) {
                thumbnailBitmaps.remove(key)
            }
        }
    }

    private val inFlightPages = mutableSetOf<PageRenderKey>()
    private val inFlightThumbs = mutableSetOf<Int>()
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

    fun requestPage(pageIndex: Int, renderWidthPx: Int) {
        if (pageIndex !in 0 until _uiState.value.pageCount) return
        if (renderWidthPx <= 0) return
        val key = PageRenderKey(pageIndex, renderWidthPx)
        if (pageBitmaps.containsKey(key) || inFlightPages.contains(key)) return
        inFlightPages.add(key)
        viewModelScope.launch {
            val bitmap = renderBitmap { controller?.renderPage(pageIndex, renderWidthPx) }
            bitmap?.let {
                pageBitmaps[key] = it.asImageBitmap()
                prunePageBitmaps(_uiState.value.currentPage)
            }
            inFlightPages.remove(key)
        }
    }

    fun requestThumbnail(pageIndex: Int, renderWidthPx: Int) {
        if (pageIndex !in 0 until _uiState.value.pageCount) return
        if (renderWidthPx <= 0) return
        thumbnailCache.get(pageIndex)?.let { cached ->
            if (thumbnailBitmaps[pageIndex] !== cached) {
                thumbnailBitmaps[pageIndex] = cached
            }
            return
        }
        if (thumbnailBitmaps.containsKey(pageIndex) || inFlightThumbs.contains(pageIndex)) return
        inFlightThumbs.add(pageIndex)
        viewModelScope.launch {
            val bitmap = renderBitmap { controller?.renderThumbnail(pageIndex, renderWidthPx) }
            bitmap?.let {
                val image = it.asImageBitmap()
                thumbnailCache.put(pageIndex, image)
                thumbnailBitmaps[pageIndex] = image
            }
            inFlightThumbs.remove(pageIndex)
        }
    }

    fun updateCurrentPage(pageIndex: Int) {
        if (pageIndex !in 0 until _uiState.value.pageCount) return
        if (_uiState.value.currentPage == pageIndex) return
        _uiState.value = _uiState.value.copy(currentPage = pageIndex)
        pageUpdates.tryEmit(pageIndex)
        prunePageBitmaps(pageIndex)
    }

    fun updateScale(scale: Float) {
        if (abs(_uiState.value.scale - scale) < SCALE_UPDATE_EPSILON) return
        _uiState.value = _uiState.value.copy(scale = scale)
        // Drop prior renders so we do not pin large bitmaps after zoom.
        pageBitmaps.clear()
        inFlightPages.clear()
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
                val newController = PdfDocumentController(
                    pdfFile = decryptedFile,
                    cacheSizeBytes = calculatePageCacheSize(),
                    thumbnailCacheSizeBytes = THUMBNAIL_CACHE_BYTES,
                )
                LoadedDocument(item, local, decryptedFile, newController, evicted)
            }

            if (loaded.evictedItemIds.isNotEmpty()) {
                libraryRepository.clearDecryptedPaths(loaded.evictedItemIds)
            }

            controller?.close()
            pageBitmaps.clear()
            thumbnailBitmaps.clear()
            thumbnailCache.evictAll()
            controller = loaded.controller
            val pageCount = loaded.controller.pageCount
            val lastPage = (loaded.local?.lastPage ?: 0).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            _uiState.value = _uiState.value.copy(
                title = loaded.item.title,
                pageCount = pageCount,
                currentPage = lastPage,
                isLoading = false,
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

    private fun isPdfValid(file: File): Boolean {
        if (!file.exists()) return false
        return try {
            val header = ByteArray(5)
            file.inputStream().use { input ->
                val read = input.read(header)
                read == 5 && String(header, Charsets.US_ASCII).startsWith("%PDF-")
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun renderBitmap(block: suspend () -> Bitmap?): Bitmap? = withContext(Dispatchers.Default) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun calculatePageCacheSize(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory().toInt()
        // Cap bitmap cache to balance smooth paging with avoiding memory pressure on mid-range devices.
        return (maxMemory / 8).coerceAtMost(48 * 1024 * 1024)
    }

    override fun onCleared() {
        super.onCleared()
        pageBitmaps.clear()
        thumbnailBitmaps.clear()
        thumbnailCache.evictAll()
        controller?.close()
    }

    data class PageRenderKey(val pageIndex: Int, val widthPx: Int)

    private data class LoadedDocument(
        val item: CatalogItemEntity,
        val local: LocalDocumentEntity?,
        val decryptedFile: File,
        val controller: PdfDocumentController,
        val evictedItemIds: List<String>,
    )

    private fun prunePageBitmaps(currentPage: Int) {
        val minPage = (currentPage - PAGE_CACHE_RADIUS).coerceAtLeast(0)
        val maxPage = (currentPage + PAGE_CACHE_RADIUS).coerceAtLeast(minPage)
        val keysToRemove = pageBitmaps.keys.filter { it.pageIndex !in minPage..maxPage }
        keysToRemove.forEach { pageBitmaps.remove(it) }
    }

    private companion object {
        private const val BYTES_PER_PIXEL = 4
        private const val THUMBNAIL_CACHE_BYTES = 8 * 1024 * 1024
        private const val PAGE_CACHE_RADIUS = 3
        private const val SCALE_UPDATE_EPSILON = 0.02f
    }
}
