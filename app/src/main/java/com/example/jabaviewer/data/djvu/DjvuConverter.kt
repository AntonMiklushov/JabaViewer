package com.example.jabaviewer.data.djvu

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import com.example.jabaviewer.core.AppConstants
import com.github.axet.djvulibre.DjvuLibre
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import kotlin.math.roundToInt

class DjvuConverter @Inject constructor() {
    fun convertToPdf(
        inputFile: File,
        outputFile: File,
        targetDpi: Int = DEFAULT_TARGET_DPI,
    ) {
        require(targetDpi > 0) { "Target DPI must be positive" }
        outputFile.parentFile?.mkdirs()
        FileInputStream(inputFile).use { stream ->
            val djvu = openDjvu(stream)
            try {
                writePdfDocument(djvu, outputFile, targetDpi)
            } finally {
                djvu.close()
            }
        }
    }

    private fun openDjvu(stream: FileInputStream): DjvuLibre {
        return try {
            DjvuLibre(stream.fd)
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("DjVu native library failed to load", error)
        }
    }

    private fun writePdfDocument(
        djvu: DjvuLibre,
        outputFile: File,
        targetDpi: Int,
    ) {
        val pageCount = djvu.getPagesCount()
        require(pageCount > 0) { "DjVu contains no pages" }
        val document = PdfDocument()
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            for (index in 0 until pageCount) {
                renderPage(djvu, document, paint, index, targetDpi)
            }
            outputFile.outputStream().use { output -> document.writeTo(output) }
        } finally {
            document.close()
        }
    }

    private fun renderPage(
        djvu: DjvuLibre,
        document: PdfDocument,
        paint: Paint,
        pageIndex: Int,
        targetDpi: Int,
    ) {
        val pageInfo = djvu.getPageInfo(pageIndex)
        val sourceDpi = pageInfo.dpi.takeIf { it > 0 } ?: targetDpi
        val widthInches = pageInfo.width.toFloat() / sourceDpi
        val heightInches = pageInfo.height.toFloat() / sourceDpi
        val pageWidthPoints = (widthInches * POINTS_PER_INCH).roundToInt().coerceAtLeast(1)
        val pageHeightPoints = (heightInches * POINTS_PER_INCH).roundToInt().coerceAtLeast(1)
        val bitmap = renderWithFallback(
            djvu = djvu,
            pageIndex = pageIndex,
            pageInfo = pageInfo,
            targetDpi = targetDpi,
        )
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(pageWidthPoints, pageHeightPoints, pageIndex + 1).create()
        )
        val dest = Rect(0, 0, pageWidthPoints, pageHeightPoints)
        page.canvas.drawBitmap(bitmap, null, dest, paint)
        document.finishPage(page)
        bitmap.recycle()
    }

    private fun renderWithFallback(
        djvu: DjvuLibre,
        pageIndex: Int,
        pageInfo: DjvuLibre.Page,
        targetDpi: Int,
    ): Bitmap {
        var dpi = targetDpi.coerceAtLeast(MIN_TARGET_DPI)
        while (true) {
            val sourceDpi = pageInfo.dpi.takeIf { it > 0 } ?: dpi
            val scale = dpi.toFloat() / sourceDpi
            val width = (pageInfo.width * scale).roundToInt().coerceAtLeast(1)
            val height = (pageInfo.height * scale).roundToInt().coerceAtLeast(1)
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                djvu.renderPage(
                    bitmap,
                    pageIndex,
                    0,
                    0,
                    pageInfo.width,
                    pageInfo.height,
                    0,
                    0,
                    width,
                    height,
                )
                return bitmap
            } catch (oom: OutOfMemoryError) {
                if (dpi <= MIN_TARGET_DPI) {
                    throw IllegalStateException("Not enough memory to render DjVu page", oom)
                }
                dpi = (dpi * DPI_FALLBACK_RATIO).roundToInt().coerceAtLeast(MIN_TARGET_DPI)
            }
        }
    }

    private companion object {
        private const val POINTS_PER_INCH = 72f
        private const val DEFAULT_TARGET_DPI = AppConstants.DEFAULT_DJVU_CONVERSION_DPI
        private const val MIN_TARGET_DPI = 120
        private const val DPI_FALLBACK_RATIO = 0.75f
    }
}
