@file:Suppress("TooManyFunctions")

package com.example.jabaviewer.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.jabaviewer.data.local.entities.DownloadState
import com.example.jabaviewer.data.settings.LibraryLayout
import com.example.jabaviewer.data.settings.SortOrder
import com.example.jabaviewer.domain.model.LibraryItem
import com.example.jabaviewer.ui.util.formatBytes
import com.example.jabaviewer.ui.util.formatDate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun LibraryScreen(
    onOpenDetails: (String) -> Unit,
    onOpenReader: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val refreshState = rememberPullRefreshState(
        refreshing = state.isRefreshing,
        onRefresh = viewModel::refreshCatalog,
    )

    ScaffoldWithBackground(
        topBar = {
            LibraryTopBar(
                isList = state.layout == LibraryLayout.LIST,
                onToggleLayout = viewModel::toggleLayout,
                onOpenSettings = onOpenSettings,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pullRefresh(refreshState)
        ) {
            val contentCallbacks = rememberLibraryContentCallbacks(
                onOpenDetails = onOpenDetails,
                onOpenReader = onOpenReader,
                onDownload = viewModel::downloadItem,
                onCancel = viewModel::cancelDownload,
                onSearch = viewModel::updateSearch,
                onToggleTag = viewModel::toggleTag,
                onClearTags = viewModel::clearTags,
                onSortChange = viewModel::updateSort,
            )
            LibraryContent(
                state = state,
                callbacks = contentCallbacks,
            )

            PullRefreshIndicator(
                refreshing = state.isRefreshing,
                state = refreshState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    isList: Boolean,
    onToggleLayout: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = { Text("Library") },
        actions = {
            IconButton(onClick = onToggleLayout) {
                val icon = if (isList) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.List
                Icon(
                    imageVector = icon,
                    contentDescription = "Toggle layout",
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(imageVector = Icons.Outlined.Settings, contentDescription = "Settings")
            }
        },
    )
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    callbacks: LibraryContentCallbacks,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = callbacks.onSearch,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text("Search titles or tags") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        TagRow(
            tags = state.availableTags,
            selectedTags = state.selectedTags,
            onToggle = callbacks.onToggleTag,
            onClear = callbacks.onClearTags,
        )
        SortRow(
            sortOrder = state.sortOrder,
            onSortChange = callbacks.onSortChange,
        )
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when (state.layout) {
            LibraryLayout.LIST -> LibraryList(
                items = state.items,
                onItemClick = callbacks.onOpenDetails,
                onOpenReader = callbacks.onOpenReader,
                onDownload = callbacks.onDownload,
                onCancel = callbacks.onCancel,
            )
            LibraryLayout.GRID -> LibraryGrid(
                items = state.items,
                onItemClick = callbacks.onOpenDetails,
                onOpenReader = callbacks.onOpenReader,
                onDownload = callbacks.onDownload,
                onCancel = callbacks.onCancel,
            )
        }
        if (state.items.isEmpty() && !state.isRefreshing) {
            Text(
                text = "No documents yet. Pull to refresh to load your catalog.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private data class LibraryContentCallbacks(
    val onOpenDetails: (String) -> Unit,
    val onOpenReader: (String) -> Unit,
    val onDownload: (String) -> Unit,
    val onCancel: (String) -> Unit,
    val onSearch: (String) -> Unit,
    val onToggleTag: (String) -> Unit,
    val onClearTags: () -> Unit,
    val onSortChange: (SortOrder) -> Unit,
)

@Composable
private fun rememberLibraryContentCallbacks(
    onOpenDetails: (String) -> Unit,
    onOpenReader: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onSearch: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onClearTags: () -> Unit,
    onSortChange: (SortOrder) -> Unit,
): LibraryContentCallbacks {
    return remember(
        onOpenDetails,
        onOpenReader,
        onDownload,
        onCancel,
        onSearch,
        onToggleTag,
        onClearTags,
        onSortChange,
    ) {
        LibraryContentCallbacks(
            onOpenDetails = onOpenDetails,
            onOpenReader = onOpenReader,
            onDownload = onDownload,
            onCancel = onCancel,
            onSearch = onSearch,
            onToggleTag = onToggleTag,
            onClearTags = onClearTags,
            onSortChange = onSortChange,
        )
    }
}

@Composable
private fun TagRow(
    tags: List<String>,
    selectedTags: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (tags.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = selectedTags.isEmpty(),
                onClick = onClear,
                label = { Text("All") },
            )
        }
        items(tags, key = { it }) { tag ->
            FilterChip(
                selected = selectedTags.contains(tag),
                onClick = { onToggle(tag) },
                label = { Text(tag) },
            )
        }
    }
}

@Composable
private fun SortRow(
    sortOrder: SortOrder,
    onSortChange: (SortOrder) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Sort: ${sortOrder.name.lowercase().replaceFirstChar { it.uppercase() }}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                SortOrder.values().forEach { order ->
                    DropdownMenuItem(
                        text = { Text(order.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        onClick = {
                            onSortChange(order)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryList(
    items: List<LibraryItem>,
    onItemClick: (String) -> Unit,
    onOpenReader: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id }) { item ->
            LibraryItemCard(
                item = item,
                onClick = { onItemClick(item.id) },
                onOpenReader = { onOpenReader(item.id) },
                onDownload = { onDownload(item.id) },
                onCancel = { onCancel(item.id) },
            )
        }
    }
}

@Composable
private fun LibraryGrid(
    items: List<LibraryItem>,
    onItemClick: (String) -> Unit,
    onOpenReader: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id }) { item ->
            LibraryItemCard(
                item = item,
                onClick = { onItemClick(item.id) },
                onOpenReader = { onOpenReader(item.id) },
                onDownload = { onDownload(item.id) },
                onCancel = { onCancel(item.id) },
            )
        }
    }
}

@Composable
private fun LibraryItemCard(
    item: LibraryItem,
    onClick: () -> Unit,
    onOpenReader: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LibraryItemTags(tags = item.tags)
            Text(
                text = "${formatBytes(item.size)} \u2022 ${formatDate(item.lastOpenedAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LibraryItemActions(
                downloadState = item.downloadState,
                progress = item.downloadProgress,
                onOpenReader = onOpenReader,
                onCancel = onCancel,
                onDownload = onDownload,
            )
        }
    }
}

@Composable
private fun LibraryItemTags(tags: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.take(3).forEach { tag ->
            Text(
                text = tag,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun LibraryItemActions(
    downloadState: DownloadState,
    progress: Int,
    onOpenReader: () -> Unit,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
) {
    when (downloadState) {
        DownloadState.DOWNLOADED -> {
            Button(onClick = onOpenReader, modifier = Modifier.fillMaxWidth()) {
                Text("Open")
            }
        }
        DownloadState.DOWNLOADING -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Downloading $progress%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Button(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
        else -> {
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Download")
            }
        }
    }
}

@Composable
private fun ScaffoldWithBackground(
    topBar: @Composable () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    androidx.compose.material3.Scaffold(
        topBar = topBar,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.background,
                        ),
                    )
                )
        ) {
            content(padding)
        }
    }
}
