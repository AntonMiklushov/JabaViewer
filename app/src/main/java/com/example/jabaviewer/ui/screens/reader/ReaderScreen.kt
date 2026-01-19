package com.example.jabaviewer.ui.screens.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.jabaviewer.data.settings.OrientationLock
import com.example.jabaviewer.data.settings.ReaderMode
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Pageview
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    itemId: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    var showThumbnails by remember { mutableStateOf(false) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { state.pageCount })
    val scope = rememberCoroutineScope()
    var hasJumpedToLast by remember { mutableStateOf(false) }

    LaunchedEffect(itemId) {
        hasJumpedToLast = false
    }

    DisposableEffect(state.keepScreenOn) {
        view.keepScreenOn = state.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(state.orientationLock) {
        val activity = context as? Activity
        val original = activity?.requestedOrientation
        val lock = when (state.orientationLock) {
            OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationLock.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        activity?.requestedOrientation = lock
        onDispose {
            if (original != null) {
                activity.requestedOrientation = original
            }
        }
    }

    LaunchedEffect(state.scale) {
        scale = state.scale
    }

    LaunchedEffect(state.pageCount, state.currentPage, state.readerMode) {
        if (!hasJumpedToLast && state.pageCount > 0 && state.currentPage > 0) {
            if (state.readerMode == ReaderMode.CONTINUOUS) {
                listState.scrollToItem(state.currentPage)
            } else {
                pagerState.scrollToPage(state.currentPage)
            }
            hasJumpedToLast = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title.ifBlank { "Reader" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showThumbnails = true }) {
                        Icon(Icons.Outlined.GridView, contentDescription = "Thumbnails")
                    }
                    IconButton(onClick = { showJumpDialog = true }) {
                        Icon(Icons.Outlined.Pageview, contentDescription = "Jump to page")
                    }
                }
            )
        },
        bottomBar = {
            if (state.pageCount > 0) {
                ReaderBottomBar(
                    currentPage = state.currentPage,
                    pageCount = state.pageCount,
                    onPageSelected = { page ->
                        scope.launch {
                            if (state.readerMode == ReaderMode.CONTINUOUS) {
                                listState.animateScrollToItem(page)
                            } else {
                                pagerState.animateScrollToPage(page)
                            }
                            viewModel.updateCurrentPage(page)
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            when {
                state.isLoading -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.errorMessage != null -> {
                    Text(
                        text = state.errorMessage ?: "Failed to open document",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    if (state.readerMode == ReaderMode.CONTINUOUS) {
                        ContinuousReader(
                            pageCount = state.pageCount,
                            scale = scale,
                            nightMode = state.nightMode,
                            onScaleChange = {
                                scale = it
                                viewModel.updateScale(it)
                            },
                            onPageVisible = viewModel::updateCurrentPage,
                            requestPage = viewModel::requestPage,
                            pageBitmaps = viewModel.pageBitmaps,
                            listState = listState,
                            onPan = { panOffset = it },
                            panOffset = panOffset,
                        )
                    } else {
                        SinglePageReader(
                            pageCount = state.pageCount,
                            scale = scale,
                            nightMode = state.nightMode,
                            onScaleChange = {
                                scale = it
                                viewModel.updateScale(it)
                            },
                            onPageVisible = viewModel::updateCurrentPage,
                            requestPage = viewModel::requestPage,
                            pageBitmaps = viewModel.pageBitmaps,
                            pagerState = pagerState,
                            onPan = { panOffset = it },
                            panOffset = panOffset,
                        )
                    }
                }
            }
        }
    }

    if (showThumbnails) {
        ModalBottomSheet(onDismissRequest = { showThumbnails = false }) {
            ThumbnailGrid(
                pageCount = state.pageCount,
                currentPage = state.currentPage,
                onSelect = {
                    scope.launch {
                        if (state.readerMode == ReaderMode.CONTINUOUS) {
                            listState.animateScrollToItem(it)
                        } else {
                            pagerState.animateScrollToPage(it)
                        }
                        viewModel.updateCurrentPage(it)
                    }
                    showThumbnails = false
                },
                requestThumbnail = viewModel::requestThumbnail,
                thumbnailBitmaps = viewModel.thumbnailBitmaps,
            )
        }
    }

    if (showJumpDialog) {
        JumpToPageDialog(
            pageCount = state.pageCount,
            onDismiss = { showJumpDialog = false },
            onJump = { page ->
                scope.launch {
                    if (state.readerMode == ReaderMode.CONTINUOUS) {
                        listState.animateScrollToItem(page)
                    } else {
                        pagerState.animateScrollToPage(page)
                    }
                    viewModel.updateCurrentPage(page)
                }
                showJumpDialog = false
            }
        )
    }
}

@Composable
private fun ReaderBottomBar(
    currentPage: Int,
    pageCount: Int,
    onPageSelected: (Int) -> Unit,
) {
    var sliderValue by remember(currentPage) { mutableFloatStateOf(currentPage.toFloat()) }
    val maxPage = (pageCount - 1).coerceAtLeast(0)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Page ${currentPage + 1} of $pageCount",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onPageSelected(sliderValue.roundToInt().coerceIn(0, maxPage)) },
            valueRange = 0f..maxPage.toFloat(),
            steps = (pageCount - 2).coerceAtLeast(0).coerceAtMost(MAX_SLIDER_STEPS),
        )
    }
}

@Composable
private fun ContinuousReader(
    pageCount: Int,
    scale: Float,
    nightMode: Boolean,
    onScaleChange: (Float) -> Unit,
    onPan: (Offset) -> Unit,
    panOffset: Offset,
    onPageVisible: (Int) -> Unit,
    requestPage: (Int, Int) -> Unit,
    pageBitmaps: Map<ReaderViewModel.PageRenderKey, androidx.compose.ui.graphics.ImageBitmap>,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val density = LocalDensity.current

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index -> onPageVisible(index) }
    }

    // Rendering strategy: we re-render pages at a scaled width when zoom changes to keep text sharp,
    // while still using Compose scaling for smooth pinch gestures between renders.
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 3f)
                    onScaleChange(newScale)
                    if (newScale > 1f) {
                        onPan(panOffset + pan)
                    } else {
                        onPan(Offset.Zero)
                    }
                }
            },
    ) {
        items(pageCount, key = { it }) { pageIndex ->
            BoxWithRenderedPage(
                pageIndex = pageIndex,
                scale = scale,
                nightMode = nightMode,
                requestPage = requestPage,
                pageBitmaps = pageBitmaps,
                density = density,
                panOffset = panOffset,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SinglePageReader(
    pageCount: Int,
    scale: Float,
    nightMode: Boolean,
    onScaleChange: (Float) -> Unit,
    onPan: (Offset) -> Unit,
    panOffset: Offset,
    onPageVisible: (Int) -> Unit,
    requestPage: (Int, Int) -> Unit,
    pageBitmaps: Map<ReaderViewModel.PageRenderKey, androidx.compose.ui.graphics.ImageBitmap>,
    pagerState: androidx.compose.foundation.pager.PagerState,
) {
    val density = LocalDensity.current

    LaunchedEffect(pagerState.currentPage) {
        onPageVisible(pagerState.currentPage)
    }

    HorizontalPager(
        state = pagerState,
        contentPadding = PaddingValues(16.dp),
        pageSpacing = 16.dp,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 3f)
                    onScaleChange(newScale)
                    if (newScale > 1f) {
                        onPan(panOffset + pan)
                    } else {
                        onPan(Offset.Zero)
                    }
                }
            },
    ) { pageIndex ->
        BoxWithRenderedPage(
            pageIndex = pageIndex,
            scale = scale,
            nightMode = nightMode,
            requestPage = requestPage,
            pageBitmaps = pageBitmaps,
            density = density,
            panOffset = panOffset,
        )
    }
}

@Composable
private fun BoxWithRenderedPage(
    pageIndex: Int,
    scale: Float,
    nightMode: Boolean,
    requestPage: (Int, Int) -> Unit,
    pageBitmaps: Map<ReaderViewModel.PageRenderKey, androidx.compose.ui.graphics.ImageBitmap>,
    density: Density,
    panOffset: Offset,
) {
    var renderWidthPx by remember { mutableStateOf(0) }
    val renderScale = remember(scale) { (scale * 10).roundToInt() / 10f }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth()
        ) {
            renderWidthPx = with(density) { (maxWidth * renderScale).roundToPx() }
            requestPage(pageIndex, renderWidthPx)
            val key = ReaderViewModel.PageRenderKey(pageIndex, renderWidthPx)
            val bitmap = pageBitmaps[key]
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = ContentScale.FillWidth,
                    colorFilter = if (nightMode) ColorFilter.colorMatrix(invertMatrix()) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = panOffset.x,
                            translationY = panOffset.y,
                        ),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ThumbnailGrid(
    pageCount: Int,
    currentPage: Int,
    onSelect: (Int) -> Unit,
    requestThumbnail: (Int, Int) -> Unit,
    thumbnailBitmaps: Map<Int, androidx.compose.ui.graphics.ImageBitmap>,
) {
    val density = LocalDensity.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(pageCount, key = { it }) { pageIndex ->
            val widthPx = with(density) { 120.dp.roundToPx() }
            requestThumbnail(pageIndex, widthPx)
            val bitmap = thumbnailBitmaps[pageIndex]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (pageIndex == currentPage) MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(8.dp)
            ) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = "Thumbnail ${pageIndex + 1}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.height(120.dp).fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = "${pageIndex + 1}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Button(onClick = { onSelect(pageIndex) }) {
                    Text("Go")
                }
            }
        }
    }
}

@Composable
private fun JumpToPageDialog(
    pageCount: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val maxPage = pageCount.coerceAtLeast(1)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jump to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Page number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            Button(onClick = {
                val page = text.text.toIntOrNull()?.coerceIn(1, maxPage)
                if (page != null) {
                    onJump(page - 1)
                }
            }) {
                Text("Go")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun invertMatrix(): ColorMatrix = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )
)

private const val MAX_SLIDER_STEPS = 200
