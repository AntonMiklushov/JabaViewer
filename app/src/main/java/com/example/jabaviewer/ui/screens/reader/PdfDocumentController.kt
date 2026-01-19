package com.example.jabaviewer.ui.screens.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

class PdfDocumentController(
    pdfFile: File,
    cacheSizeBytes: Int,
    private val thumbnailCacheSizeBytes: Int,
) : Closeable {
    private val fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fileDescriptor)
    private val renderLock = Mutex()
    // Avoid rendering after teardown to prevent PdfRenderer crashes.
    private val closed = AtomicBoolean(false)

    // Cache is sized to keep a handful of full-width pages in memory without risking OOM on mid-range phones.
    private val pageCache = object : LruCache<PageKey, Bitmap>(cacheSizeBytes) {
        override fun sizeOf(key: PageKey, value: Bitmap): Int = value.byteCount
    }

    private val thumbnailCache = object : LruCache<PageKey, Bitmap>(thumbnailCacheSizeBytes) {
        override fun sizeOf(key: PageKey, value: Bitmap): Int = value.byteCount
    }

    val pageCount: Int = renderer.pageCount

    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? =
        renderBitmap(pageIndex, targetWidthPx, pageCache)

    suspend fun renderThumbnail(pageIndex: Int, targetWidthPx: Int): Bitmap? =
        renderBitmap(pageIndex, targetWidthPx, thumbnailCache)

    private suspend fun renderBitmap(
        pageIndex: Int,
        targetWidthPx: Int,
        cache: LruCache<PageKey, Bitmap>,
    ): Bitmap? = withContext(Dispatchers.Default) {
        val width = max(1, targetWidthPx)
        val key = PageKey(pageIndex, width)
        cache.get(key)?.let { return@withContext it }
        renderLock.withLock {
            if (closed.get()) return@withContext null
            cache.get(key)?.let { return@withContext it }
            renderer.openPage(pageIndex).use { page ->
                val height = max(1, (width * page.height.toFloat() / page.width).roundToInt())
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bitmap)
                bitmap
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            renderer.close()
            fileDescriptor.close()
        }
    }

    private data class PageKey(val index: Int, val widthPx: Int)
}
