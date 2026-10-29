package com.woyken.verkadapass.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.woyken.verkadapass.AppUiState
import com.woyken.verkadapass.data.DoorItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoorListScreen(
    state: AppUiState,
    onUnlock: (DoorItem) -> Unit,
    onToggleFavorite: (DoorItem) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onDismissError: () -> Unit,
    onOpenTileSettings: () -> Unit = {},
    onCheckForUpdate: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onCheckForUpdate() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Doors") },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.AccountCircle, contentDescription = "Account")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            state.session?.email?.let { email ->
                                DropdownMenuItem(
                                    text = { Text(email, style = MaterialTheme.typography.bodySmall) },
                                    onClick = {},
                                    enabled = false,
                                )
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                text = { Text("Quick Settings Tiles") },
                                onClick = {
                                    showMenu = false
                                    onOpenTileSettings()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Logout") },
                                onClick = {
                                    showMenu = false
                                    onLogout()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            state.updateAvailable?.let { update ->
                UpdateBanner(
                    versionName = update.versionName,
                    onDownload = onDownloadUpdate,
                    onDismiss = onDismissUpdate,
                )
            }

            if (state.error != null) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = onDismissError) { Text("Dismiss") }
                    },
                ) {
                    Text(state.error)
                }
            }

            if (state.isLoading && state.doors.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.doors.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No doors available", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.doors.sortedByDescending { it.accessPointId in state.favoriteDoorIds }, key = { it.accessPointId }) { door ->
                        DoorCard(
                            door = door,
                            isFavorite = door.accessPointId in state.favoriteDoorIds,
                            onToggleFavorite = { onToggleFavorite(door) },
                            isUnlocking = state.unlockingDoorId == door.accessPointId,
                            isUnlocked = state.unlockedDoorId == door.accessPointId,
                            onUnlock = { onUnlock(door) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateBanner(
    versionName: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Update available", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Version $versionName is ready to download",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onDownload) { Text("Download") }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss update")
            }
        }
    }
}

@Composable
private fun DoorCard(
    door: DoorItem,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    isUnlocking: Boolean,
    isUnlocked: Boolean,
    onUnlock: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = when {
            isUnlocked -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "cardColor",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconToggleButton(checked = isFavorite, onCheckedChange = { onToggleFavorite() }) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (isFavorite) "Remove ${door.name} from favorites" else "Add ${door.name} to favorites",
                    tint = if (isFavorite) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = door.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (door.location.isNotBlank()) {
                    Text(
                        text = door.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(
                onClick = onUnlock,
                enabled = !isUnlocking,
            ) {
                if (isUnlocking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (isUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(if (isUnlocked) "Unlocked" else "Unlock")
            }
        }
    }
}
