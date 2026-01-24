@file:Suppress("TooManyFunctions")

package com.example.jabaviewer.ui.screens.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.jabaviewer.data.settings.ReaderMode
import com.example.jabaviewer.data.settings.OrientationLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjvuViewerScreen(
    itemId: String,
    onBack: () -> Unit,
    viewModel: DjvuViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val view = LocalView.current
    var didScrollToInitial by remember { mutableStateOf(false) }

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
            )
        },
    ) { padding ->
        DjvuViewerContent(
            padding = padding,
            state = state,
            listState = listState,
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DjvuViewerTopBar(
    title: String,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
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
private fun DjvuViewerContent(
    padding: PaddingValues,
    state: DjvuViewerUiState,
    listState: LazyListState,
    viewModel: DjvuViewerViewModel,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(padding),
    ) {
        val density = LocalDensity.current
        val bucketWidthPx = with(density) { maxWidth.toPx().roundToInt().coerceAtLeast(1) }
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
                    )
                    ReaderMode.CONTINUOUS -> DjvuContinuousList(
                        state = state,
                        listState = listState,
                        bucketWidthPx = bucketWidthPx,
                        viewModel = viewModel,
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
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(state.pageCount, key = { it }) { pageIndex ->
            val ratio = state.pageAspectRatios.getOrNull(pageIndex) ?: DEFAULT_PAGE_RATIO
            DjvuPageItem(
                pageIndex = pageIndex,
                pageRatio = ratio,
                bucketWidthPx = bucketWidthPx,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun DjvuPageItem(
    pageIndex: Int,
    pageRatio: Float,
    bucketWidthPx: Int,
    viewModel: DjvuViewerViewModel,
) {
    val density = LocalDensity.current
    val targetWidthPx = bucketWidthPx.coerceAtLeast(1)
    val pageHeightPx = (targetWidthPx * pageRatio).roundToInt().coerceAtLeast(1)
    val pageHeightDp = with(density) { pageHeightPx.toDp() }

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
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = bitmapState.value
        if (bitmap == null) {
            CircularProgressIndicator()
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                modifier = Modifier.fillMaxSize(),
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

private const val DEFAULT_PAGE_RATIO = 1.4f
