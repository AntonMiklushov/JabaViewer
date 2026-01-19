package com.example.jabaviewer.ui.screens.details

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.jabaviewer.data.local.entities.DownloadState
import com.example.jabaviewer.ui.util.formatBytes
import com.example.jabaviewer.ui.util.formatDate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailsScreen(
    itemId: String,
    onBack: () -> Unit,
    onOpenReader: () -> Unit,
    viewModel: ItemDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val item = state.item
    val context = LocalContext.current
    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            viewModel.saveDecryptedCopy(context.contentResolver, uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.title ?: "Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.background,
                        ),
                    )
                )
                .padding(padding)
                .padding(20.dp)
        ) {
            if (item == null) {
                Text("Loading...", style = MaterialTheme.typography.bodyLarge)
                return@Box
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item.tags.forEach { tag ->
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                        Text(
                            text = "Size: ${formatBytes(item.size)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Last opened: ${formatDate(item.lastOpenedAt)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Object key: ${item.objectKey}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                when (item.downloadState) {
                    DownloadState.DOWNLOADED -> {
                        Button(onClick = onOpenReader, modifier = Modifier.fillMaxWidth()) {
                            Text("Open")
                        }
                        OutlinedButton(
                            onClick = { createDocumentLauncher.launch(buildPdfFileName(item.title)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isSaving,
                        ) {
                            Text(if (state.isSaving) "Saving..." else "Decrypt save")
                        }
                        OutlinedButton(
                            onClick = { viewModel.removeDownload(onBack) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isRemoving,
                        ) {
                            Text("Remove download")
                        }
                    }
                    DownloadState.DOWNLOADING -> {
                        Text(
                            text = "Downloading ${item.downloadProgress}%",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        OutlinedButton(
                            onClick = viewModel::cancelDownload,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Cancel")
                        }
                    }
                    else -> {
                        Button(onClick = viewModel::download, modifier = Modifier.fillMaxWidth()) {
                            Text("Download")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Downloads stay encrypted on disk. PDFs are decrypted only when opened.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.errorMessage?.let { message ->
                    Text(text = message, color = MaterialTheme.colorScheme.error)
                }
                state.message?.let { message ->
                    Text(text = message, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

private fun buildPdfFileName(title: String): String {
    val base = title.trim().ifBlank { "document" }
    val sanitized = base.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    return if (sanitized.lowercase().endsWith(".pdf")) sanitized else "$sanitized.pdf"
}
