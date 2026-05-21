package com.woyken.verkadapass.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.woyken.verkadapass.data.DoorItem
import com.woyken.verkadapass.data.SessionData
import com.woyken.verkadapass.data.VerkadaApi
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class TileSettingsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TILE_SLOT = "tile_slot"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val slot = intent.getIntExtra(EXTRA_TILE_SLOT, -1)

        setContent {
            MaterialTheme {
                TileSetupScreen(
                    slot = slot,
                    onDoorSelected = { door ->
                        if (slot > 0) {
                            DoorTileService.saveDoorForTile(this, slot, door.accessPointId, door.name)
                        }
                        finish()
                    },
                    onBack = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TileSetupScreen(
    slot: Int,
    onDoorSelected: (DoorItem) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var doors by remember { mutableStateOf<List<DoorItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        scope.launch {
            val session = loadSession(context)
            if (session == null) {
                error = "Not logged in. Open the Verkada Pass app and log in first."
                isLoading = false
                return@launch
            }
            val api = VerkadaApi()
            api.listDoors(session)
                .onSuccess { list ->
                    doors = list
                    isLoading = false
                }
                .onFailure { e ->
                    error = e.message ?: "Failed to load doors"
                    isLoading = false
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (slot > 0) "Set up Tile $slot" else "Quick Settings Tiles")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            error != null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(error!!, style = MaterialTheme.typography.bodyLarge)
            }

            else -> {
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    Text(
                        text = "Select the door this tile should unlock:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(doors) { door ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onDoorSelected(door) },
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
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
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun loadSession(context: android.content.Context): SessionData? {
    val json = context.getSharedPreferences("verkada_session_cache", android.content.Context.MODE_PRIVATE)
        .getString("session_json", null) ?: return null
    return try {
        Json.decodeFromString<SessionData>(json)
    } catch (_: Exception) {
        null
    }
}
