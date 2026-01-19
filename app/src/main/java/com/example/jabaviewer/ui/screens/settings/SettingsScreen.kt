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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
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
            Text("Source", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::updateBaseUrl,
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.catalogPath,
                onValueChange = viewModel::updateCatalogPath,
                label = { Text("Catalog path") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.passphraseInput,
                onValueChange = viewModel::updatePassphrase,
                label = { Text("New passphrase") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(onClick = viewModel::saveSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Save source settings")
            }

            Text("Reader", style = MaterialTheme.typography.titleLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Reading mode", style = MaterialTheme.typography.bodyLarge)
                BoxWithMenu(
                    expanded = readerMenu,
                    onExpandedChange = { readerMenu = it },
                    current = state.readerMode.name.lowercase(),
                ) {
                    ReaderMode.values().forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.name.lowercase()) },
                            onClick = {
                                viewModel.updateReaderMode(mode)
                                readerMenu = false
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
                Switch(checked = state.nightMode, onCheckedChange = viewModel::updateNightMode)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Keep screen on", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.keepScreenOn, onCheckedChange = viewModel::updateKeepScreenOn)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Orientation lock", style = MaterialTheme.typography.bodyLarge)
                BoxWithMenu(
                    expanded = orientationMenu,
                    onExpandedChange = { orientationMenu = it },
                    current = state.orientationLock.name.lowercase(),
                ) {
                    OrientationLock.values().forEach { lock ->
                        DropdownMenuItem(
                            text = { Text(lock.name.lowercase()) },
                            onClick = {
                                viewModel.updateOrientationLock(lock)
                                orientationMenu = false
                            }
                        )
                    }
                }
            }

            Text("Cache", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Decrypted cache limit: ${cacheLimit.toInt()} MB",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = cacheLimit,
                onValueChange = { cacheLimit = it },
                onValueChangeFinished = { viewModel.updateCacheLimit(cacheLimit.toInt()) },
                valueRange = 50f..1000f,
                steps = 18,
            )
            Button(onClick = viewModel::clearDecryptedCache, modifier = Modifier.fillMaxWidth()) {
                Text("Clear decrypted cache")
            }
            Button(onClick = viewModel::clearAllDownloads, modifier = Modifier.fillMaxWidth()) {
                Text("Clear all downloads")
            }

            state.message?.let { message ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = message, color = MaterialTheme.colorScheme.tertiary)
            }
        }
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
