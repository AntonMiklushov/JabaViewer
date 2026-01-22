@file:Suppress("TooManyFunctions")

package com.example.jabaviewer.ui.screens.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Pageview
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.jabaviewer.data.settings.OrientationLock
import com.example.jabaviewer.data.settings.ReaderMode
import com.github.barteksc.pdfviewer.PDFView
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun ReaderScreen(
    itemId: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    var showJumpDialog by remember { mutableStateOf(false) }
    var pdfViewRef by remember { mutableStateOf<PDFView?>(null) }
    var isPdfLoaded by remember { mutableStateOf(false) }
    var pdfError by remember { mutableStateOf<String?>(null) }
    var lastLoadKey by remember { mutableStateOf<PdfLoadKey?>(null) }

    val loadKey = remember(state.decryptedFilePath, state.readerMode, state.nightMode) {
        state.decryptedFilePath?.let { PdfLoadKey(it, state.readerMode, state.nightMode) }
    }

    ReaderScreenEffects(
        itemId = itemId,
        loadKey = loadKey,
        view = view,
        context = context,
        keepScreenOn = state.keepScreenOn,
        orientationLock = state.orientationLock,
        pdfViewRef = pdfViewRef,
        onResetJump = { showJumpDialog = false },
        onResetLoadState = {
            isPdfLoaded = false
            pdfError = null
        },
    )

    Scaffold(
        topBar = {
            ReaderTopBar(
                title = state.title.ifBlank { "Reader" },
                onBack = onBack,
                onJump = { showJumpDialog = true },
            )
        },
        bottomBar = {
            if (state.pageCount > 0) {
                ReaderBottomBar(
                    currentPage = state.currentPage,
                    pageCount = state.pageCount,
                    onPageSelected = { page ->
                        pdfViewRef?.jumpTo(page, true)
                        viewModel.updateCurrentPage(page)
                    },
                )
            }
        },
    ) { padding ->
        val contentModel = ReaderContentModel(
            state = state,
            loadKey = loadKey,
            isPdfLoaded = isPdfLoaded,
            pdfError = pdfError,
            lastLoadKey = lastLoadKey,
        )
        val callbacks = ReaderContentCallbacks(
            onPdfViewReady = { pdfViewRef = it },
            onUpdateLastKey = { lastLoadKey = it },
            onPdfLoaded = { isPdfLoaded = true },
            onPdfError = { pdfError = it },
            onPageChange = { page, count ->
                viewModel.updatePageCount(count)
                viewModel.updateCurrentPage(page)
            },
        )
        ReaderContent(
            padding = padding,
            model = contentModel,
            callbacks = callbacks,
        )
    }

    if (showJumpDialog) {
        JumpToPageDialog(
            pageCount = state.pageCount,
            onDismiss = { showJumpDialog = false },
            onJump = { page ->
                pdfViewRef?.jumpTo(page, true)
                viewModel.updateCurrentPage(page)
                showJumpDialog = false
            }
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun ReaderScreenEffects(
    itemId: String,
    loadKey: PdfLoadKey?,
    view: android.view.View,
    context: android.content.Context,
    keepScreenOn: Boolean,
    orientationLock: OrientationLock,
    pdfViewRef: PDFView?,
    onResetJump: () -> Unit,
    onResetLoadState: () -> Unit,
) {
    LaunchedEffect(itemId) {
        onResetJump()
    }

    LaunchedEffect(loadKey) {
        onResetLoadState()
    }

    KeepScreenOnEffect(view = view, keepOn = keepScreenOn)
    OrientationLockEffect(context = context, lock = orientationLock)
    PdfViewRecycleEffect(pdfView = pdfViewRef)
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

@Composable
private fun PdfViewRecycleEffect(pdfView: PDFView?) {
    DisposableEffect(pdfView) {
        val viewToDispose = pdfView
        onDispose { viewToDispose?.recycle() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    onJump: () -> Unit,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onJump) {
                Icon(Icons.Outlined.Pageview, contentDescription = "Jump to page")
            }
        }
    )
}

@Composable
private fun ReaderContent(
    padding: PaddingValues,
    model: ReaderContentModel,
    callbacks: ReaderContentCallbacks,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(padding)
    ) {
        when {
            model.state.isLoading -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            model.state.errorMessage != null -> {
                ErrorText(message = model.state.errorMessage ?: "Failed to open document")
            }
            model.pdfError != null -> {
                ErrorText(message = model.pdfError.ifBlank { "Failed to load document" })
            }
            model.loadKey == null -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            else -> {
                PdfViewContainer(
                    config = PdfViewConfig(
                        loadKey = model.loadKey,
                        currentPage = model.state.currentPage,
                        lastLoadKey = model.lastLoadKey,
                    ),
                    callbacks = callbacks,
                )
                if (!model.isPdfLoaded) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfViewContainer(
    config: PdfViewConfig,
    callbacks: ReaderContentCallbacks,
) {
    AndroidView(
        factory = { viewContext ->
            PDFView(viewContext, null).also(callbacks.onPdfViewReady)
        },
        update = { pdfView ->
            callbacks.onPdfViewReady(pdfView)
            if (config.loadKey == config.lastLoadKey) return@AndroidView
            val file = File(config.loadKey.path)
            if (!file.exists()) {
                callbacks.onPdfError("Document file is missing")
                return@AndroidView
            }
            pdfView.recycle()
            val singlePage = config.loadKey.readerMode == ReaderMode.SINGLE
            pdfView.fromFile(file)
                .defaultPage(config.currentPage)
                .enableSwipe(true)
                .swipeHorizontal(singlePage)
                .pageSnap(singlePage)
                .pageFling(singlePage)
                .autoSpacing(singlePage)
                .enableDoubletap(true)
                .nightMode(config.loadKey.nightMode)
                .onPageChange { page, pageCount ->
                    callbacks.onPageChange(page, pageCount)
                }
                .onLoad { pageCount ->
                    callbacks.onPdfLoaded()
                    callbacks.onPageChange(config.currentPage.coerceAtLeast(0), pageCount)
                    val initialPage = config.currentPage.coerceIn(
                        0,
                        (pageCount - 1).coerceAtLeast(0),
                    )
                    if (initialPage != 0) {
                        pdfView.jumpTo(initialPage, false)
                    }
                }
                .onError { error ->
                    callbacks.onPdfError(error.message ?: "Failed to load document")
                }
                .load()
            callbacks.onUpdateLastKey(config.loadKey)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun BoxScope.ErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.align(Alignment.Center),
    )
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

private data class PdfLoadKey(
    val path: String,
    val readerMode: ReaderMode,
    val nightMode: Boolean,
)

private data class ReaderContentModel(
    val state: ReaderUiState,
    val loadKey: PdfLoadKey?,
    val isPdfLoaded: Boolean,
    val pdfError: String?,
    val lastLoadKey: PdfLoadKey?,
)

private data class ReaderContentCallbacks(
    val onPdfViewReady: (PDFView) -> Unit,
    val onUpdateLastKey: (PdfLoadKey) -> Unit,
    val onPdfLoaded: () -> Unit,
    val onPdfError: (String) -> Unit,
    val onPageChange: (Int, Int) -> Unit,
)

private data class PdfViewConfig(
    val loadKey: PdfLoadKey,
    val currentPage: Int,
    val lastLoadKey: PdfLoadKey?,
)

private const val MAX_SLIDER_STEPS = 200
