package com.example.jabaviewer.ui.screens.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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

    LaunchedEffect(itemId) {
        showJumpDialog = false
    }

    LaunchedEffect(loadKey) {
        isPdfLoaded = false
        pdfError = null
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

    DisposableEffect(pdfViewRef) {
        val viewToDispose = pdfViewRef
        onDispose { viewToDispose?.recycle() }
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
                        pdfViewRef?.jumpTo(page, true)
                        viewModel.updateCurrentPage(page)
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
                pdfError != null -> {
                    Text(
                        text = pdfError ?: "Failed to load document",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                loadKey == null -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    AndroidView(
                        factory = { viewContext ->
                            PDFView(viewContext, null).also { pdfViewRef = it }
                        },
                        update = { pdfView ->
                            pdfViewRef = pdfView
                            val key = loadKey ?: return@AndroidView
                            if (key != lastLoadKey) {
                                val file = File(key.path)
                                if (!file.exists()) {
                                    pdfError = "Document file is missing"
                                    return@AndroidView
                                }
                                pdfView.recycle()
                                pdfView.fromFile(file)
                                    .defaultPage(state.currentPage)
                                    .enableSwipe(true)
                                    .swipeHorizontal(key.readerMode == ReaderMode.SINGLE)
                                    .pageSnap(key.readerMode == ReaderMode.SINGLE)
                                    .pageFling(key.readerMode == ReaderMode.SINGLE)
                                    .autoSpacing(key.readerMode == ReaderMode.SINGLE)
                                    .enableDoubletap(true)
                                    .nightMode(key.nightMode)
                                    .onPageChange { page, pageCount ->
                                        viewModel.updatePageCount(pageCount)
                                        viewModel.updateCurrentPage(page)
                                    }
                                    .onLoad { pageCount ->
                                        isPdfLoaded = true
                                        viewModel.updatePageCount(pageCount)
                                        val initialPage = state.currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                                        if (initialPage != 0) {
                                            pdfView.jumpTo(initialPage, false)
                                        }
                                    }
                                    .onError { error ->
                                        pdfError = error.message ?: "Failed to load document"
                                    }
                                    .load()
                                lastLoadKey = key
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (!isPdfLoaded) {
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

private const val MAX_SLIDER_STEPS = 200
