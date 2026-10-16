package com.woyken.verkadapass.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.woyken.verkadapass.data.DoorItem
import com.woyken.verkadapass.data.SessionData
import com.woyken.verkadapass.data.VerkadaApi
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set result to CANCELED in case user backs out
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                WidgetConfigScreen(
                    onDoorSelected = { door -> onDoorChosen(door) },
                )
            }
        }
    }

    private fun onDoorChosen(door: DoorItem) {
        // Save door config for this widget
        DoorWidgetProvider.saveDoorForWidget(this, appWidgetId, door.accessPointId, door.name)

        // Update the widget
        val appWidgetManager = AppWidgetManager.getInstance(this)
        DoorWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)

        // Return success
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }
}

@Composable
private fun WidgetConfigScreen(onDoorSelected: (DoorItem) -> Unit) {
    val scope = rememberCoroutineScope()
    var doors by remember { mutableStateOf<List<DoorItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val favoriteDoorIds = remember {
        context.getSharedPreferences("door_favorites", android.content.Context.MODE_PRIVATE)
            .getStringSet("door_ids", emptySet())?.toSet() ?: emptySet()
    }

    LaunchedEffect(Unit) {
        scope.launch {
            val session = getSessionFromPrefs(context)
            if (session == null) {
                error = "Not logged in. Open the app and log in first."
                isLoading = false
                return@launch
            }
            val api = VerkadaApi()
            val result = api.listDoors(session)
            result.onSuccess { list ->
                doors = list
                isLoading = false
            }
            result.onFailure { e ->
                error = e.message ?: "Failed to load doors"
                isLoading = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SmallTopAppBar(
                title = { Text("Select a Door") },
            )

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(error!!, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(doors.sortedByDescending { it.accessPointId in favoriteDoorIds }) { door ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onDoorSelected(door) },
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (door.accessPointId in favoriteDoorIds) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = "Favorite",
                                            tint = Color(0xFFFFC107),
                                        )
                                        Spacer(Modifier.width(12.dp))
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
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmallTopAppBar(title: @Composable () -> Unit) {
    TopAppBar(title = title)
}

private fun getSessionFromPrefs(context: android.content.Context): SessionData? {
    val prefs = context.getSharedPreferences("verkada_session_cache", android.content.Context.MODE_PRIVATE)
    val json = prefs.getString("session_json", null) ?: return null
    return try {
        Json.decodeFromString<SessionData>(json)
    } catch (_: Exception) {
        null
    }
}
