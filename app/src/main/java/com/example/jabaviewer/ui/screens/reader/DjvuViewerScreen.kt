@file:Suppress("TooManyFunctions")

package com.example.jabaviewer.ui.screens.reader

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.jabaviewer.BuildConfig
import com.example.jabaviewer.data.settings.OrientationLock
import com.example.jabaviewer.data.settings.ReaderMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun DjvuViewerScreen(
    itemId: String,
    onBack: () -> Unit,
    viewModel: DjvuViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val view = LocalView.current
    var didScrollToInitial by remember { mutableStateOf(false) }
    var debugBucketWidthPx by remember { mutableIntStateOf(1) }
    val zoomStates = remember(itemId) { mutableStateMapOf<Int, DjvuPageZoomState>() }

    DjvuViewerEffects(
        input = DjvuViewerEffectInput(
            itemId = itemId,
            state = state,
            listState = listState,
            view = view,
            didScrollToInitial = didScrollToInitial,
            onInitialScrollHandled = { didScrollToInitial = it },
        ),
    )

    Scaffold(
        topBar = {
            DjvuViewerTopBar(
                title = state.title.ifBlank { "DjVu Viewer" },
                onBack = onBack,
                onDumpDebug = if (BuildConfig.DEBUG) {
                    {
                        viewModel.dumpDebugArtifacts(
                            pageIndex = state.currentPage,
                            bucketWidthPx = debugBucketWidthPx,
                            cacheDir = context.cacheDir,
                            includeComparison = true,
                        )
                    }
                } else {
                    null
                },
                onShareDebug = if (BuildConfig.DEBUG) {
                    {
                        viewModel.dumpDebugArtifacts(
                            pageIndex = state.currentPage,
                            bucketWidthPx = debugBucketWidthPx,
                            cacheDir = context.cacheDir,
                            includeComparison = false,
                            onComplete = { dumpedPath ->
                                shareDebugBitmap(context, dumpedPath)
                            },
                        )
                    }
                } else {
                    null
                },
            )
        },
    ) { padding ->
        DjvuViewerContent(
            padding = padding,
            state = state,
            listState = listState,
            viewModel = viewModel,
            onBucketWidthUpdated = { debugBucketWidthPx = it },
            pageZoomStates = zoomStates,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DjvuViewerTopBar(
    title: String,
    onBack: () -> Unit,
    onDumpDebug: (() -> Unit)?,
    onShareDebug: (() -> Unit)?,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            if (onDumpDebug != null) {
                IconButton(onClick = onDumpDebug) {
                    Icon(
                        imageVector = Icons.Outlined.BugReport,
                        contentDescription = "Dump debug bitmap",
                    )
                }
            }
            if (onShareDebug != null) {
                IconButton(onClick = onShareDebug) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = "Share debug bitmap",
                    )
                }
            }
        },
    )
}

@Composable
private fun DjvuViewerEffects(
    input: DjvuViewerEffectInput,
) {
    LaunchedEffect(input.itemId) {
        input.onInitialScrollHandled(false)
    }

    LaunchedEffect(input.state.pageCount, input.state.currentPage, input.didScrollToInitial) {
        if (!input.didScrollToInitial && input.state.pageCount > 0) {
            input.listState.scrollToItem(
                input.state.currentPage.coerceIn(0, input.state.pageCount - 1)
            )
            input.onInitialScrollHandled(true)
        }
    }

    KeepScreenOnEffect(view = input.view, keepOn = input.state.keepScreenOn)
    OrientationLockEffect(context = input.view.context, lock = input.state.orientationLock)
}

@Composable
@Suppress("LongMethod", "LongParameterList")
private fun DjvuViewerContent(
    padding: PaddingValues,
    state: DjvuViewerUiState,
    listState: LazyListState,
    viewModel: DjvuViewerViewModel,
    onBucketWidthUpdated: (Int) -> Unit,
    pageZoomStates: MutableMap<Int, DjvuPageZoomState>,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(padding),
    ) {
        val density = LocalDensity.current
        val horizontalPaddingPx = with(density) { (LIST_HORIZONTAL_PADDING * 2).roundToPx() }
        val bucketWidthPx = (
            with(density) { maxWidth.toPx().roundToInt() } - horizontalPaddingPx
            ).coerceAtLeast(1)
        LaunchedEffect(bucketWidthPx) {
            onBucketWidthUpdated(bucketWidthPx)
        }
        LaunchedEffect(listState, bucketWidthPx, state.pageCount) {
            if (state.pageCount <= 0) return@LaunchedEffect
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
                .map { items -> items.filter { it in 0 until state.pageCount } }
                .filter { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect { indices ->
                    viewModel.updateVisiblePages(indices, bucketWidthPx)
                }
        }

        when {
            state.errorMessage != null -> {
                ErrorText(message = state.errorMessage ?: "Failed to open DjVu file.")
            }
            state.isLoading || state.pageCount <= 0 -> {
                LoadingIndicator()
            }
            else -> {
                when (state.readerMode) {
                    ReaderMode.SINGLE -> DjvuContinuousList(
                        state = state,
                        listState = listState,
                        bucketWidthPx = bucketWidthPx,
                        viewModel = viewModel,
                        pageZoomStates = pageZoomStates,
                    )
                    ReaderMode.CONTINUOUS -> DjvuContinuousList(
                        state = state,
                        listState = listState,
                        bucketWidthPx = bucketWidthPx,
                        viewModel = viewModel,
                        pageZoomStates = pageZoomStates,
                    )
                }
            }
        }
    }
}

private data class DjvuViewerEffectInput(
    val itemId: String,
    val state: DjvuViewerUiState,
    val listState: LazyListState,
    val view: android.view.View,
    val didScrollToInitial: Boolean,
    val onInitialScrollHandled: (Boolean) -> Unit,
)

@Composable
private fun DjvuContinuousList(
    state: DjvuViewerUiState,
    listState: LazyListState,
    bucketWidthPx: Int,
    viewModel: DjvuViewerViewModel,
    pageZoomStates: MutableMap<Int, DjvuPageZoomState>,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = LIST_HORIZONTAL_PADDING, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(state.pageCount, key = { it }) { pageIndex ->
            val ratio = state.pageAspectRatios.getOrNull(pageIndex) ?: DEFAULT_PAGE_RATIO
            DjvuPageItem(
                pageIndex = pageIndex,
                pageRatio = ratio,
                bucketWidthPx = bucketWidthPx,
                viewModel = viewModel,
                zoomState = pageZoomStates[pageIndex] ?: DjvuPageZoomState.DEFAULT,
                onZoomStateChanged = { next ->
                    if (next.isDefault()) {
                        pageZoomStates.remove(pageIndex)
                    } else {
                        pageZoomStates[pageIndex] = next
                    }
                },
            )
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
private fun DjvuPageItem(
    pageIndex: Int,
    pageRatio: Float,
    bucketWidthPx: Int,
    viewModel: DjvuViewerViewModel,
    zoomState: DjvuPageZoomState,
    onZoomStateChanged: (DjvuPageZoomState) -> Unit,
) {
    val density = LocalDensity.current
    val targetWidthPx = bucketWidthPx.coerceAtLeast(1)
    val pageHeightPx = (targetWidthPx * pageRatio).roundToInt().coerceAtLeast(1)
    val pageHeightDp = with(density) { pageHeightPx.toDp() }
    var containerSize by remember(pageIndex) { mutableStateOf(IntSize.Zero) }

    val bitmapState = produceState<Bitmap?>(initialValue = null, pageIndex, targetWidthPx) {
        value = null
        val result = runCatching { viewModel.renderPage(pageIndex, targetWidthPx) }
        val bitmap = result.getOrNull()
        if (bitmap != null) {
            value = bitmap
        } else {
            val error = result.exceptionOrNull()
            if (error is CancellationException) {
                throw error
            }
            if (error != null) {
                viewModel.onRenderFailure(error)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(pageHeightDp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { size -> containerSize = size },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = bitmapState.value
        if (bitmap == null) {
            CircularProgressIndicator()
        } else {
            val presentation = computePresentationDebug(
                containerSize = containerSize,
                bitmap = bitmap,
            )
            val clampedZoomState = clampZoomState(
                state = zoomState,
                containerSize = containerSize,
                presentation = presentation,
            )
            if (clampedZoomState != zoomState) {
                onZoomStateChanged(clampedZoomState)
            }
            val currentZoomState by rememberUpdatedState(clampedZoomState)
            val currentContainerSize by rememberUpdatedState(containerSize)
            val currentPresentation by rememberUpdatedState(presentation)
            val zoomStateCallback by rememberUpdatedState(onZoomStateChanged)
            val panModifier = if (clampedZoomState.scale > DEFAULT_ZOOM_SCALE + ZOOM_EPSILON) {
                Modifier.pointerInput(pageIndex) {
                    detectDragGestures { change, dragAmount ->
                        val baseState = currentZoomState
                        val next = clampZoomState(
                            state = baseState.copy(
                                offsetX = baseState.offsetX + dragAmount.x,
                                offsetY = baseState.offsetY + dragAmount.y,
                            ),
                            containerSize = currentContainerSize,
                            presentation = currentPresentation,
                        )
                        change.consume()
                        zoomStateCallback(next)
                    }
                }
            } else {
                Modifier
            }
            val zoomModifier = Modifier
                .fillMaxSize()
                .pointerInput(pageIndex) {
                    awaitEachGesture {
                        var stillPressed: Boolean
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) {
                                val baseState = currentZoomState
                                val next = clampZoomState(
                                    state = baseState.copy(
                                        scale = baseState.scale * event.calculateZoom(),
                                        offsetX = baseState.offsetX + event.calculatePan().x,
                                        offsetY = baseState.offsetY + event.calculatePan().y,
                                    ),
                                    containerSize = currentContainerSize,
                                    presentation = currentPresentation,
                                )
                                event.changes.forEach { pointer ->
                                    if (pointer.positionChanged()) {
                                        pointer.consume()
                                    }
                                }
                                zoomStateCallback(next)
                            }
                            stillPressed = event.changes.any { it.pressed }
                        } while (stillPressed)
                    }
                }
                .then(panModifier)
                .pointerInput(pageIndex) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            val baseState = currentZoomState
                            val next = if (baseState.scale > DEFAULT_ZOOM_SCALE + ZOOM_EPSILON) {
                                DjvuPageZoomState.DEFAULT
                            } else {
                                val centerX = currentContainerSize.width / 2f
                                val centerY = currentContainerSize.height / 2f
                                clampZoomState(
                                    state = DjvuPageZoomState(
                                        scale = DOUBLE_TAP_ZOOM_SCALE,
                                        offsetX = centerX - tapOffset.x,
                                        offsetY = centerY - tapOffset.y,
                                    ),
                                    containerSize = currentContainerSize,
                                    presentation = currentPresentation,
                                )
                            }
                            zoomStateCallback(next)
                        },
                    )
                }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
                modifier = zoomModifier.graphicsLayer(
                    scaleX = clampedZoomState.scale,
                    scaleY = clampedZoomState.scale,
                    translationX = clampedZoomState.offsetX,
                    translationY = clampedZoomState.offsetY,
                ),
            )
        }
    }
}

@Composable
private fun LoadingIndicator() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorText(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun KeepScreenOnEffect(view: android.view.View, keepOn: Boolean) {
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun OrientationLockEffect(
    context: android.content.Context,
    lock: OrientationLock,
) {
    DisposableEffect(lock) {
        val activity = context as? Activity
        val original = activity?.requestedOrientation
        val lockValue = when (lock) {
            OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationLock.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        activity?.requestedOrientation = lockValue
        onDispose {
            if (original != null) {
                activity.requestedOrientation = original
            }
        }
    }
}

private data class PresentationDebug(
    val scale: Float,
    val translateXPx: Float,
    val translateYPx: Float,
    val imageWidthPx: Float,
    val imageHeightPx: Float,
)

private data class DjvuPageZoomState(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    fun isDefault(): Boolean {
        return scale <= DEFAULT_ZOOM_SCALE + ZOOM_EPSILON &&
            kotlin.math.abs(offsetX) <= ZOOM_EPSILON &&
            kotlin.math.abs(offsetY) <= ZOOM_EPSILON
    }

    companion object {
        val DEFAULT = DjvuPageZoomState(
            scale = DEFAULT_ZOOM_SCALE,
            offsetX = 0f,
            offsetY = 0f,
        )
    }
}

@Suppress("ReturnCount")
private fun computePresentationDebug(
    containerSize: IntSize,
    bitmap: Bitmap,
): PresentationDebug? {
    if (containerSize.width <= 0 || containerSize.height <= 0) return null
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val scaleX = containerSize.width.toFloat() / bitmap.width.toFloat()
    val scaleY = containerSize.height.toFloat() / bitmap.height.toFloat()
    val scale = min(scaleX, scaleY)
    val imageWidthPx = bitmap.width.toFloat() * scale
    val imageHeightPx = bitmap.height.toFloat() * scale
    val translateXPx = (containerSize.width - imageWidthPx) / 2f
    val translateYPx = (containerSize.height - imageHeightPx) / 2f
    return PresentationDebug(
        scale = scale,
        translateXPx = translateXPx,
        translateYPx = translateYPx,
        imageWidthPx = imageWidthPx,
        imageHeightPx = imageHeightPx,
    )
}

@Suppress("ReturnCount")
private fun clampZoomState(
    state: DjvuPageZoomState,
    containerSize: IntSize,
    presentation: PresentationDebug?,
): DjvuPageZoomState {
    if (containerSize.width <= 0 || containerSize.height <= 0 || presentation == null) {
        return DjvuPageZoomState.DEFAULT
    }
    val clampedScale = state.scale.coerceIn(DEFAULT_ZOOM_SCALE, MAX_ZOOM_SCALE)
    if (clampedScale <= DEFAULT_ZOOM_SCALE + ZOOM_EPSILON) {
        return DjvuPageZoomState.DEFAULT
    }
    val scaledWidthPx = presentation.imageWidthPx * clampedScale
    val scaledHeightPx = presentation.imageHeightPx * clampedScale
    val maxOffsetX = ((scaledWidthPx - containerSize.width) / 2f).coerceAtLeast(0f)
    val maxOffsetY = ((scaledHeightPx - containerSize.height) / 2f).coerceAtLeast(0f)
    return DjvuPageZoomState(
        scale = clampedScale,
        offsetX = state.offsetX.coerceIn(-maxOffsetX, maxOffsetX),
        offsetY = state.offsetY.coerceIn(-maxOffsetY, maxOffsetY),
    )
}

private fun shareDebugBitmap(
    context: android.content.Context,
    filePath: String?,
) {
    if (filePath.isNullOrBlank()) return
    val file = File(filePath)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newUri(context.contentResolver, "debug_bitmap", uri)
    context.startActivity(
        Intent.createChooser(intent, "Share debug bitmap")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private val LIST_HORIZONTAL_PADDING = 16.dp
private const val DEFAULT_PAGE_RATIO = 1.4f
private const val DEFAULT_ZOOM_SCALE = 1f
private const val DOUBLE_TAP_ZOOM_SCALE = 2f
private const val MAX_ZOOM_SCALE = 5f
private const val ZOOM_EPSILON = 0.01f
