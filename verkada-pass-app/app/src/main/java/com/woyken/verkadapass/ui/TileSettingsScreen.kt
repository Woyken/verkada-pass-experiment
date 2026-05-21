package com.woyken.verkadapass.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.woyken.verkadapass.data.DoorItem
import com.woyken.verkadapass.widget.DoorTileService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TileSettingsScreen(
    doors: List<DoorItem>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // Track assignments as state so UI updates on change
    val assignments = remember {
        mutableStateMapOf<Int, String>().also { map ->
            for (slot in 1..4) {
                val name = DoorTileService.getDoorName(context, slot)
                if (name != null) map[slot] = name
            }
        }
    }

    var showPickerForSlot by remember { mutableIntStateOf(-1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quick Settings Tiles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Assign doors to Quick Settings tiles. Add tiles via Android's Quick Settings edit mode.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            for (slot in 1..4) {
                val assignedName = assignments[slot]
                TileSlotCard(
                    slotNumber = slot,
                    assignedDoorName = assignedName,
                    onEdit = { showPickerForSlot = slot },
                    onClear = {
                        DoorTileService.clearDoorForTile(context, slot)
                        assignments.remove(slot)
                    },
                )
            }
        }
    }

    if (showPickerForSlot > 0) {
        DoorPickerDialog(
            slot = showPickerForSlot,
            doors = doors,
            onDoorSelected = { door ->
                DoorTileService.saveDoorForTile(context, showPickerForSlot, door.accessPointId, door.name)
                assignments[showPickerForSlot] = door.name
                showPickerForSlot = -1
            },
            onDismiss = { showPickerForSlot = -1 },
        )
    }
}

@Composable
private fun TileSlotCard(
    slotNumber: Int,
    assignedDoorName: String?,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Tile $slotNumber",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = assignedDoorName ?: "Not assigned",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (assignedDoorName != null)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (assignedDoorName != null) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Assign door")
            }
        }
    }
}

@Composable
private fun DoorPickerDialog(
    slot: Int,
    doors: List<DoorItem>,
    onDoorSelected: (DoorItem) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign door to Tile $slot") },
        text = {
            if (doors.isEmpty()) {
                Text("No doors available. Open the main screen first to load doors.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(doors) { door ->
                        TextButton(
                            onClick = { onDoorSelected(door) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(door.name, style = MaterialTheme.typography.bodyLarge)
                                if (door.location.isNotBlank()) {
                                    Text(
                                        door.location,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
