package com.example.jabaviewer.data.djvu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import com.example.jabaviewer.core.AppConstants
import com.github.axet.djvulibre.DjvuLibre
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

@Suppress("TooManyFunctions")
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

    fun openDocument(inputFile: File): DjvuDocument {
        val stream = FileInputStream(inputFile)
        return runCatching { DjvuLibre(stream.fd) }
            .map { djvu -> DjvuDocument(djvu, stream) }
            .getOrElse { error ->
                stream.close()
                throw error
            }
    }

    fun getPageCount(document: DjvuDocument): Int = synchronized(document.renderLock) {
        document.djvu.getPagesCount()
    }

    fun getPageInfo(document: DjvuDocument, pageIndex: Int): DjvuPageInfo {
        val info = synchronized(document.renderLock) {
            document.djvu.getPageInfo(pageIndex)
        }
        return DjvuPageInfo(
            width = info.width,
            height = info.height,
            dpi = info.dpi,
        )
    }

    fun renderPage(
        document: DjvuDocument,
        pageIndex: Int,
        targetWidthPx: Int,
    ): Bitmap {
        return renderPageWithDebug(document, pageIndex, targetWidthPx).bitmap
    }

    fun renderPageWithDebug(
        document: DjvuDocument,
        pageIndex: Int,
        targetWidthPx: Int,
    ): DjvuRenderOutput {
        val requestedWidthPx = targetWidthPx.coerceAtLeast(1)
        val pageInfo = synchronized(document.renderLock) {
            document.djvu.getPageInfo(pageIndex)
        }
        requireValidPageInfo(pageInfo)
        var lastError: OutOfMemoryError? = null
        for (nativeScaleFloor in VIEWER_NATIVE_SCALE_FLOORS) {
            try {
                val plan = buildViewerRenderPlan(
                    pageWidthPx = pageInfo.width,
                    pageHeightPx = pageInfo.height,
                    requestedWidthPx = requestedWidthPx,
                    minNativeScale = nativeScaleFloor,
                )
                val bitmap = renderSafeViewerBitmap(
                    djvu = document.djvu,
                    renderLock = document.renderLock,
                    pageIndex = pageIndex,
                    plan = plan,
                )
                return DjvuRenderOutput(
                    bitmap = bitmap,
                    debugInfo = plan.toDebugInfo(
                        pageIndex = pageIndex,
                        pageInfo = pageInfo,
                        renderMode = DjvuRenderMode.SAFE_INTERMEDIATE,
                    ),
                )
            } catch (oom: OutOfMemoryError) {
                lastError = oom
            }
        }
        throw IllegalStateException("Not enough memory to render DjVu page", lastError)
    }

    fun renderComparisonWithDebug(
        document: DjvuDocument,
        pageIndex: Int,
        targetWidthPx: Int,
    ): DjvuRenderComparisonOutput {
        val pageInfo = synchronized(document.renderLock) {
            document.djvu.getPageInfo(pageIndex)
        }
        requireValidPageInfo(pageInfo)
        val directPlan = buildDirectSmallRenderPlan(
            pageWidthPx = pageInfo.width,
            pageHeightPx = pageInfo.height,
            requestedWidthPx = targetWidthPx,
        )
        val directBitmap = renderDirectBitmap(
            djvu = document.djvu,
            renderLock = document.renderLock,
            pageIndex = pageIndex,
            plan = directPlan,
        )
        val oracleBitmap = renderOracleDownscaledBitmap(
            djvu = document.djvu,
            renderLock = document.renderLock,
            pageIndex = pageIndex,
            pageInfo = pageInfo,
            targetRect = directPlan.targetRect,
        )
        return DjvuRenderComparisonOutput(
            directSmall = DjvuRenderOutput(
                bitmap = directBitmap,
                debugInfo = directPlan.toDebugInfo(
                    pageIndex = pageIndex,
                    pageInfo = pageInfo,
                    renderMode = DjvuRenderMode.DIRECT_SMALL,
                ),
            ),
            oracleDownscaled = DjvuRenderOutput(
                bitmap = oracleBitmap,
                debugInfo = directPlan.toDebugInfo(
                    pageIndex = pageIndex,
                    pageInfo = pageInfo,
                    renderMode = DjvuRenderMode.ORACLE_DOWNSCALED,
                    nativeDestRect = DjvuRect(
                        left = 0,
                        top = 0,
                        width = pageInfo.width,
                        height = pageInfo.height,
                    ),
                    nativeScale = 1f,
                ),
            ),
        )
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
        requireValidPageInfo(pageInfo)
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
        requireValidPageInfo(pageInfo)
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
        private val VIEWER_NATIVE_SCALE_FLOORS = floatArrayOf(
            MIN_NATIVE_SCALE,
            0.85f,
            0.75f,
            0.66f,
            LOWEST_NATIVE_SCALE_FLOOR,
        )
    }
}

private fun requireValidPageInfo(pageInfo: DjvuLibre.Page) {
    require(pageInfo.width > 0 && pageInfo.height > 0) { "Invalid DjVu page size" }
}

private fun renderSafeViewerBitmap(
    djvu: DjvuLibre,
    renderLock: Any,
    pageIndex: Int,
    plan: DjvuRenderPlan,
): Bitmap {
    val intermediate = Bitmap.createBitmap(
        plan.destRect.width,
        plan.destRect.height,
        Bitmap.Config.ARGB_8888,
    )
    intermediate.eraseColor(Color.WHITE)
    synchronized(renderLock) {
        djvu.renderPage(
            intermediate,
            pageIndex,
            plan.sourceRect.left,
            plan.sourceRect.top,
            plan.sourceRect.width,
            plan.sourceRect.height,
            plan.destRect.left,
            plan.destRect.top,
            plan.destRect.width,
            plan.destRect.height,
        )
    }
    if (
        plan.destRect.width == plan.targetRect.width &&
        plan.destRect.height == plan.targetRect.height
    ) {
        return intermediate
    }
    return try {
        val finalBitmap = Bitmap.createBitmap(
            plan.targetRect.width,
            plan.targetRect.height,
            Bitmap.Config.ARGB_8888,
        )
        finalBitmap.eraseColor(Color.WHITE)
        Canvas(finalBitmap).drawBitmap(
            intermediate,
            null,
            Rect(0, 0, plan.targetRect.width, plan.targetRect.height),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        finalBitmap
    } finally {
        intermediate.recycle()
    }
}

private fun renderDirectBitmap(
    djvu: DjvuLibre,
    renderLock: Any,
    pageIndex: Int,
    plan: DjvuRenderPlan,
): Bitmap {
    val bitmap = Bitmap.createBitmap(
        plan.targetRect.width,
        plan.targetRect.height,
        Bitmap.Config.ARGB_8888,
    )
    bitmap.eraseColor(Color.WHITE)
    synchronized(renderLock) {
        djvu.renderPage(
            bitmap,
            pageIndex,
            plan.sourceRect.left,
            plan.sourceRect.top,
            plan.sourceRect.width,
            plan.sourceRect.height,
            plan.targetRect.left,
            plan.targetRect.top,
            plan.targetRect.width,
            plan.targetRect.height,
        )
    }
    return bitmap
}

private fun renderOracleDownscaledBitmap(
    djvu: DjvuLibre,
    renderLock: Any,
    pageIndex: Int,
    pageInfo: DjvuLibre.Page,
    targetRect: DjvuRect,
): Bitmap {
    val fullSizeBitmap = Bitmap.createBitmap(
        pageInfo.width,
        pageInfo.height,
        Bitmap.Config.ARGB_8888,
    )
    fullSizeBitmap.eraseColor(Color.WHITE)
    synchronized(renderLock) {
        djvu.renderPage(
            fullSizeBitmap,
            pageIndex,
            0,
            0,
            pageInfo.width,
            pageInfo.height,
            0,
            0,
            pageInfo.width,
            pageInfo.height,
        )
    }
    if (
        targetRect.width == pageInfo.width &&
        targetRect.height == pageInfo.height
    ) {
        return fullSizeBitmap
    }
    return try {
        val downscaled = Bitmap.createBitmap(
            targetRect.width,
            targetRect.height,
            Bitmap.Config.ARGB_8888,
        )
        downscaled.eraseColor(Color.WHITE)
        Canvas(downscaled).drawBitmap(
            fullSizeBitmap,
            null,
            Rect(0, 0, targetRect.width, targetRect.height),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        downscaled
    } finally {
        fullSizeBitmap.recycle()
    }
}

internal fun buildViewerRenderPlan(
    pageWidthPx: Int,
    pageHeightPx: Int,
    requestedWidthPx: Int,
    minNativeScale: Float = MIN_NATIVE_SCALE,
): DjvuRenderPlan {
    val sourceWidthPx = pageWidthPx.coerceAtLeast(1)
    val sourceHeightPx = pageHeightPx.coerceAtLeast(1)
    val targetWidthPx = requestedWidthPx.coerceIn(1, sourceWidthPx)
    val targetHeightPx = computeAspectHeight(
        sourceWidthPx = sourceWidthPx,
        sourceHeightPx = sourceHeightPx,
        targetWidthPx = targetWidthPx,
    )
    val desiredScale = targetWidthPx.toFloat() / sourceWidthPx.toFloat()
    val nativeScale = max(desiredScale, minNativeScale).coerceAtMost(1f)
    val nativeWidthPx = (sourceWidthPx * nativeScale)
        .roundToInt()
        .coerceIn(targetWidthPx, sourceWidthPx)
    val nativeHeightPx = computeAspectHeight(
        sourceWidthPx = sourceWidthPx,
        sourceHeightPx = sourceHeightPx,
        targetWidthPx = nativeWidthPx,
    )
    return DjvuRenderPlan(
        sourceRect = DjvuRect(
            left = 0,
            top = 0,
            width = sourceWidthPx,
            height = sourceHeightPx,
        ),
        destRect = DjvuRect(
            left = 0,
            top = 0,
            width = nativeWidthPx,
            height = nativeHeightPx,
        ),
        targetRect = DjvuRect(
            left = 0,
            top = 0,
            width = targetWidthPx,
            height = targetHeightPx,
        ),
        outputWidthPx = targetWidthPx,
        outputHeightPx = targetHeightPx,
        desiredScale = desiredScale,
        nativeScale = nativeScale,
    )
}

private fun buildDirectSmallRenderPlan(
    pageWidthPx: Int,
    pageHeightPx: Int,
    requestedWidthPx: Int,
): DjvuRenderPlan {
    val sourceWidthPx = pageWidthPx.coerceAtLeast(1)
    val sourceHeightPx = pageHeightPx.coerceAtLeast(1)
    val targetWidthPx = requestedWidthPx.coerceIn(1, sourceWidthPx)
    val targetHeightPx = computeAspectHeight(
        sourceWidthPx = sourceWidthPx,
        sourceHeightPx = sourceHeightPx,
        targetWidthPx = targetWidthPx,
    )
    val desiredScale = targetWidthPx.toFloat() / sourceWidthPx.toFloat()
    val targetRect = DjvuRect(
        left = 0,
        top = 0,
        width = targetWidthPx,
        height = targetHeightPx,
    )
    return DjvuRenderPlan(
        sourceRect = DjvuRect(
            left = 0,
            top = 0,
            width = sourceWidthPx,
            height = sourceHeightPx,
        ),
        destRect = targetRect,
        targetRect = targetRect,
        outputWidthPx = targetWidthPx,
        outputHeightPx = targetHeightPx,
        desiredScale = desiredScale,
        nativeScale = desiredScale,
    )
}

internal fun computeAspectHeight(
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    targetWidthPx: Int,
): Int {
    val safeSourceWidthPx = sourceWidthPx.coerceAtLeast(1)
    val safeSourceHeightPx = sourceHeightPx.coerceAtLeast(1)
    val safeTargetWidthPx = targetWidthPx.coerceAtLeast(1)
    return (safeTargetWidthPx.toFloat() * safeSourceHeightPx.toFloat() / safeSourceWidthPx.toFloat())
        .roundToInt()
        .coerceAtLeast(1)
}

private fun DjvuRenderPlan.toDebugInfo(
    pageIndex: Int,
    pageInfo: DjvuLibre.Page,
    renderMode: DjvuRenderMode,
    nativeDestRect: DjvuRect = destRect,
    nativeScale: Float = this.nativeScale,
): DjvuRenderDebugInfo {
    return DjvuRenderDebugInfo(
        pageIndex = pageIndex,
        pageWidthPx = pageInfo.width,
        pageHeightPx = pageInfo.height,
        sourceDpi = pageInfo.dpi,
        rotationDegrees = 0,
        sourceRect = sourceRect,
        destRect = nativeDestRect,
        targetRect = targetRect,
        outputWidthPx = outputWidthPx,
        outputHeightPx = outputHeightPx,
        desiredScale = desiredScale,
        nativeScale = nativeScale,
        renderMode = renderMode,
    )
}

data class DjvuRenderOutput(
    val bitmap: Bitmap,
    val debugInfo: DjvuRenderDebugInfo,
)

data class DjvuRenderComparisonOutput(
    val directSmall: DjvuRenderOutput,
    val oracleDownscaled: DjvuRenderOutput,
)

enum class DjvuRenderMode {
    SAFE_INTERMEDIATE,
    DIRECT_SMALL,
    ORACLE_DOWNSCALED,
}

data class DjvuRenderDebugInfo(
    val pageIndex: Int,
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val sourceDpi: Int,
    val rotationDegrees: Int,
    val sourceRect: DjvuRect,
    val destRect: DjvuRect,
    val targetRect: DjvuRect,
    val outputWidthPx: Int,
    val outputHeightPx: Int,
    val desiredScale: Float,
    val nativeScale: Float,
    val renderMode: DjvuRenderMode,
)

data class DjvuRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal data class DjvuRenderPlan(
    val sourceRect: DjvuRect,
    val destRect: DjvuRect,
    val targetRect: DjvuRect,
    val outputWidthPx: Int,
    val outputHeightPx: Int,
    val desiredScale: Float,
    val nativeScale: Float,
)

data class DjvuDocument internal constructor(
    internal val djvu: DjvuLibre,
    private val stream: FileInputStream,
) : AutoCloseable {
    internal val renderLock: Any = Any()

    override fun close() {
        djvu.close()
        stream.close()
    }
}

data class DjvuPageInfo(
    val width: Int,
    val height: Int,
    val dpi: Int,
)

internal const val MIN_NATIVE_SCALE: Float = 1f
internal const val LOWEST_NATIVE_SCALE_FLOOR: Float = 0.5f
