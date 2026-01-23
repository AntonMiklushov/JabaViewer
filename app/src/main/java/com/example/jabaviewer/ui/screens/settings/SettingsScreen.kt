package com.example.jabaviewer.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.jabaviewer.data.settings.OrientationLock
import com.example.jabaviewer.data.settings.ReaderMode
import com.example.jabaviewer.core.AppConstants
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var readerMenu by remember { mutableStateOf(false) }
    var orientationMenu by remember { mutableStateOf(false) }
    var cacheLimit by remember(state.decryptedCacheLimitMb) {
        mutableFloatStateOf(state.decryptedCacheLimitMb.toFloat())
    }
    var djvuDpi by remember(state.djvuConversionDpi) {
        mutableFloatStateOf(state.djvuConversionDpi.toFloat())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SourceSection(
                state = state,
                onBaseUrlChange = viewModel::updateBaseUrl,
                onCatalogPathChange = viewModel::updateCatalogPath,
                onPassphraseChange = viewModel::updatePassphrase,
                onSave = viewModel::saveSettings,
            )
            ReaderSection(
                data = ReaderSectionData(
                    state = state,
                    readerMenuExpanded = readerMenu,
                    orientationMenuExpanded = orientationMenu,
                ),
                callbacks = ReaderSectionCallbacks(
                    onReaderMenuChange = { readerMenu = it },
                    onOrientationMenuChange = { orientationMenu = it },
                    onReaderModeChange = viewModel::updateReaderMode,
                    onNightModeChange = viewModel::updateNightMode,
                    onKeepScreenOnChange = viewModel::updateKeepScreenOn,
                    onOrientationChange = viewModel::updateOrientationLock,
                ),
            )
            DjvuSection(
                dpi = djvuDpi,
                onDpiChange = { djvuDpi = it },
                onDpiCommit = { viewModel.updateDjvuConversionDpi(djvuDpi.toInt()) },
            )
            CacheSection(
                cacheLimit = cacheLimit,
                onCacheLimitChange = { cacheLimit = it },
                onCacheLimitCommit = { viewModel.updateCacheLimit(cacheLimit.toInt()) },
                onClearCache = viewModel::clearDecryptedCache,
                onClearDownloads = viewModel::clearAllDownloads,
            )
            SettingsMessage(message = state.message)
        }
    }
}

@Composable
private fun SourceSection(
    state: SettingsUiState,
    onBaseUrlChange: (String) -> Unit,
    onCatalogPathChange: (String) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Text("Source", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(
        value = state.baseUrl,
        onValueChange = onBaseUrlChange,
        label = { Text("Base URL") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.catalogPath,
        onValueChange = onCatalogPathChange,
        label = { Text("Catalog path") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.passphraseInput,
        onValueChange = onPassphraseChange,
        label = { Text("New passphrase") },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
    )
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Text("Save source settings")
    }
}

@Suppress("LongMethod")
@Composable
private fun ReaderSection(
    data: ReaderSectionData,
    callbacks: ReaderSectionCallbacks,
) {
    Text("Reader", style = MaterialTheme.typography.titleLarge)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Reading mode", style = MaterialTheme.typography.bodyLarge)
        BoxWithMenu(
            expanded = data.readerMenuExpanded,
            onExpandedChange = callbacks.onReaderMenuChange,
            current = data.state.readerMode.name.lowercase(),
        ) {
            ReaderMode.values().forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.name.lowercase()) },
                    onClick = {
                        callbacks.onReaderModeChange(mode)
                        callbacks.onReaderMenuChange(false)
                    }
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Night mode", style = MaterialTheme.typography.bodyLarge)
        Switch(checked = data.state.nightMode, onCheckedChange = callbacks.onNightModeChange)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Keep screen on", style = MaterialTheme.typography.bodyLarge)
        Switch(checked = data.state.keepScreenOn, onCheckedChange = callbacks.onKeepScreenOnChange)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Orientation lock", style = MaterialTheme.typography.bodyLarge)
        BoxWithMenu(
            expanded = data.orientationMenuExpanded,
            onExpandedChange = callbacks.onOrientationMenuChange,
            current = data.state.orientationLock.name.lowercase(),
        ) {
            OrientationLock.values().forEach { lock ->
                DropdownMenuItem(
                    text = { Text(lock.name.lowercase()) },
                    onClick = {
                        callbacks.onOrientationChange(lock)
                        callbacks.onOrientationMenuChange(false)
                    }
                )
            }
        }
    }
}

@Composable
private fun DjvuSection(
    dpi: Float,
    onDpiChange: (Float) -> Unit,
    onDpiCommit: () -> Unit,
) {
    Text("DJVU", style = MaterialTheme.typography.titleLarge)
    Text(
        text = "Conversion quality: ${dpi.toInt()} DPI",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = "Higher values improve quality but increase time and size.",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = dpi,
        onValueChange = onDpiChange,
        onValueChangeFinished = onDpiCommit,
        valueRange = AppConstants.MIN_DJVU_CONVERSION_DPI.toFloat()..AppConstants.MAX_DJVU_CONVERSION_DPI.toFloat(),
        steps = DJVU_DPI_STEPS,
    )
}

@Composable
private fun CacheSection(
    cacheLimit: Float,
    onCacheLimitChange: (Float) -> Unit,
    onCacheLimitCommit: () -> Unit,
    onClearCache: () -> Unit,
    onClearDownloads: () -> Unit,
) {
    Text("Cache", style = MaterialTheme.typography.titleLarge)
    Text(
        text = "Decrypted cache limit: ${cacheLimit.toInt()} MB",
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = cacheLimit,
        onValueChange = onCacheLimitChange,
        onValueChangeFinished = onCacheLimitCommit,
        valueRange = 50f..1000f,
        steps = 18,
    )
    Button(onClick = onClearCache, modifier = Modifier.fillMaxWidth()) {
        Text("Clear decrypted cache")
    }
    Button(onClick = onClearDownloads, modifier = Modifier.fillMaxWidth()) {
        Text("Clear all downloads")
    }
}

@Composable
private fun SettingsMessage(message: String?) {
    message?.let { text ->
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = text, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun BoxWithMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    current: String,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box {
        Button(onClick = { onExpandedChange(true) }) {
            Text(current.replaceFirstChar { it.uppercase() })
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            content()
        }
    }
}

private data class ReaderSectionData(
    val state: SettingsUiState,
    val readerMenuExpanded: Boolean,
    val orientationMenuExpanded: Boolean,
)

private data class ReaderSectionCallbacks(
    val onReaderMenuChange: (Boolean) -> Unit,
    val onOrientationMenuChange: (Boolean) -> Unit,
    val onReaderModeChange: (ReaderMode) -> Unit,
    val onNightModeChange: (Boolean) -> Unit,
    val onKeepScreenOnChange: (Boolean) -> Unit,
    val onOrientationChange: (OrientationLock) -> Unit,
)

private const val DJVU_DPI_STEPS =
    (AppConstants.MAX_DJVU_CONVERSION_DPI - AppConstants.MIN_DJVU_CONVERSION_DPI) / 10 - 1
