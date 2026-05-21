package com.woyken.verkadapass.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.woyken.verkadapass.AppUiState
import com.woyken.verkadapass.data.DoorItem

// Inline icon paths to avoid material-icons-extended dependency
private val IconAccountCircle: ImageVector get() = ImageVector.Builder("AccountCircle", 24.dp, 24.dp, 24f, 24f).apply {
    path { moveTo(12f, 2f); arcToRelative(10f, 10f, 0f, false, false, 0f, 20f); arcToRelative(10f, 10f, 0f, false, false, 0f, -20f); close() }
    path { moveTo(12f, 6f); arcToRelative(3.5f, 3.5f, 0f, false, true, 0f, 7f); arcToRelative(3.5f, 3.5f, 0f, false, true, 0f, -7f) }
    path { moveTo(12f, 14f); curveTo(9.33f, 14f, 4f, 15.34f, 4f, 18f); verticalLineTo(20f); horizontalLineTo(20f); verticalLineTo(18f); curveTo(20f, 15.34f, 14.67f, 14f, 12f, 14f) }
}.build()

private val IconLock: ImageVector get() = ImageVector.Builder("Lock", 24.dp, 24.dp, 24f, 24f).apply {
    path { moveTo(18f, 8f); horizontalLineTo(17f); verticalLineTo(6f); curveTo(17f, 3.24f, 14.76f, 1f, 12f, 1f); curveTo(9.24f, 1f, 7f, 3.24f, 7f, 6f); verticalLineTo(8f); horizontalLineTo(6f); curveTo(4.9f, 8f, 4f, 8.9f, 4f, 10f); verticalLineTo(20f); curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f); horizontalLineTo(18f); curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f); verticalLineTo(10f); curveTo(20f, 8.9f, 19.1f, 8f, 18f, 8f); close(); moveTo(12f, 17f); curveTo(10.9f, 17f, 10f, 16.1f, 10f, 15f); curveTo(10f, 13.9f, 10.9f, 13f, 12f, 13f); curveTo(13.1f, 13f, 14f, 13.9f, 14f, 15f); curveTo(14f, 16.1f, 13.1f, 17f, 12f, 17f); close(); moveTo(15.1f, 8f); horizontalLineTo(8.9f); verticalLineTo(6f); curveTo(8.9f, 4.29f, 10.29f, 2.9f, 12f, 2.9f); curveTo(13.71f, 2.9f, 15.1f, 4.29f, 15.1f, 6f); verticalLineTo(8f) }
}.build()

private val IconLockOpen: ImageVector get() = ImageVector.Builder("LockOpen", 24.dp, 24.dp, 24f, 24f).apply {
    path { moveTo(18f, 8f); horizontalLineTo(17f); verticalLineTo(6f); curveTo(17f, 3.24f, 14.76f, 1f, 12f, 1f); curveTo(9.24f, 1f, 7f, 3.24f, 7f, 6f); horizontalLineTo(8.9f); curveTo(8.9f, 4.29f, 10.29f, 2.9f, 12f, 2.9f); curveTo(13.71f, 2.9f, 15.1f, 4.29f, 15.1f, 6f); verticalLineTo(8f); horizontalLineTo(6f); curveTo(4.9f, 8f, 4f, 8.9f, 4f, 10f); verticalLineTo(20f); curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f); horizontalLineTo(18f); curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f); verticalLineTo(10f); curveTo(20f, 8.9f, 19.1f, 8f, 18f, 8f); close(); moveTo(12f, 17f); curveTo(10.9f, 17f, 10f, 16.1f, 10f, 15f); curveTo(10f, 13.9f, 10.9f, 13f, 12f, 13f); curveTo(13.1f, 13f, 14f, 13.9f, 14f, 15f); curveTo(14f, 16.1f, 13.1f, 17f, 12f, 17f) }
}.build()

private val IconRefresh: ImageVector get() = ImageVector.Builder("Refresh", 24.dp, 24.dp, 24f, 24f).apply {
    path { moveTo(17.65f, 6.35f); curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f); curveTo(7.58f, 4f, 4.01f, 7.58f, 4.01f, 12f); curveTo(4.01f, 16.42f, 7.58f, 20f, 12f, 20f); curveTo(15.73f, 20f, 18.84f, 17.45f, 19.73f, 14f); horizontalLineTo(17.65f); curveTo(16.83f, 16.33f, 14.61f, 18f, 12f, 18f); curveTo(8.69f, 18f, 6f, 15.31f, 6f, 12f); curveTo(6f, 8.69f, 8.69f, 6f, 12f, 6f); curveTo(13.66f, 6f, 15.14f, 6.69f, 16.22f, 7.78f); lineTo(13f, 11f); horizontalLineTo(20f); verticalLineTo(4f); lineTo(17.65f, 6.35f) }
}.build()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoorListScreen(
    state: AppUiState,
    onUnlock: (DoorItem) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onDismissError: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Doors") },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(IconRefresh, contentDescription = "Refresh")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(IconAccountCircle, contentDescription = "Account")
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
                    items(state.doors, key = { it.accessPointId }) { door ->
                        DoorCard(
                            door = door,
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
private fun DoorCard(
    door: DoorItem,
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
                        imageVector = if (isUnlocked) IconLockOpen else IconLock,
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
