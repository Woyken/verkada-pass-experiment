package com.woyken.verkadapass.widget

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.woyken.verkadapass.data.SessionData
import com.woyken.verkadapass.data.VerkadaApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

abstract class DoorTileService : TileService() {

    abstract val tileSlot: Int

    companion object {
        private const val PREFS_NAME = "door_tiles"
        private const val KEY_DOOR_ID_PREFIX = "tile_door_id_"
        private const val KEY_DOOR_NAME_PREFIX = "tile_door_name_"
        private const val SESSION_PREFS_NAME = "verkada_session_cache"
        private const val SESSION_KEY = "session_json"

        fun saveDoorForTile(context: android.content.Context, slot: Int, doorId: String, doorName: String) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString("${KEY_DOOR_ID_PREFIX}$slot", doorId)
                .putString("${KEY_DOOR_NAME_PREFIX}$slot", doorName)
                .apply()
        }

        fun getDoorId(context: android.content.Context, slot: Int): String? =
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString("${KEY_DOOR_ID_PREFIX}$slot", null)

        fun getDoorName(context: android.content.Context, slot: Int): String? =
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString("${KEY_DOOR_NAME_PREFIX}$slot", null)

        fun clearDoorForTile(context: android.content.Context, slot: Int) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove("${KEY_DOOR_ID_PREFIX}$slot")
                .remove("${KEY_DOOR_NAME_PREFIX}$slot")
                .apply()
        }
    }

    private var unlockJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        unlockJob?.cancel()
    }

    override fun onClick() {
        super.onClick()
        val doorId = getDoorId(this, tileSlot)
        if (doorId == null) {
            // Open main app to configure
            val intent = Intent(this, com.woyken.verkadapass.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }
        performUnlock(doorId)
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val doorName = getDoorName(this, tileSlot)
        if (doorName != null) {
            tile.label = doorName
            tile.state = Tile.STATE_INACTIVE
        } else {
            tile.label = "Set up tile"
            tile.state = Tile.STATE_UNAVAILABLE
        }
        tile.updateTile()
    }

    private fun performUnlock(doorId: String) {
        val doorName = getDoorName(this, tileSlot) ?: "Door"
        val tile = qsTile ?: return

        tile.label = doorName
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()

        unlockJob?.cancel()
        unlockJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val session = getSession() ?: run {
                    setTileLabel("Login required")
                    delay(2000)
                    refreshTile()
                    return@launch
                }
                val api = VerkadaApi()
                val result = api.unlockDoor(session, doorId)
                if (result.isSuccess) {
                    setTileLabel("✓ Unlocked")
                } else {
                    setTileLabel("✗ Failed")
                }
            } catch (_: Exception) {
                setTileLabel("✗ Error")
            }
            delay(2000)
            refreshTile()
        }
    }

    private fun setTileLabel(label: String) {
        val tile = qsTile ?: return
        tile.label = label
        tile.updateTile()
    }

    private fun getSession(): SessionData? {
        val json = getSharedPreferences(SESSION_PREFS_NAME, MODE_PRIVATE)
            .getString(SESSION_KEY, null) ?: return null
        return try {
            Json.decodeFromString<SessionData>(json)
        } catch (_: Exception) {
            null
        }
    }
}
