package com.woyken.verkadapass.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import com.woyken.verkadapass.R
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
        private const val TAG = "DoorTileService"
        private const val PREFS_NAME = "door_tiles"
        private const val KEY_DOOR_ID_PREFIX = "tile_door_id_"
        private const val KEY_DOOR_NAME_PREFIX = "tile_door_name_"
        private const val SESSION_PREFS_NAME = "verkada_session_cache"
        private const val SESSION_KEY = "session_json"
        private const val EXTRA_DOOR_ID_INTENT = "intent_door_id"
        private const val CHANNEL_ID = "verkada_unlock_channel"
        private const val NOTIFICATION_ID = 1002

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val doorId = intent?.getStringExtra(EXTRA_DOOR_ID_INTENT)
            ?: return super.onStartCommand(intent, flags, startId)
        createNotificationChannel()
        startForegroundCompat(NOTIFICATION_ID, createNotification())
        performUnlock(doorId, startId)
        return START_NOT_STICKY
    }

    override fun onClick() {
        super.onClick()
        val doorId = getDoorId(this, tileSlot)
        if (doorId == null) {
            // Open tile setup screen directly for this slot
            val intent = Intent(this, TileSettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(TileSettingsActivity.EXTRA_TILE_SLOT, tileSlot)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, tileSlot, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }

        // Show loading state immediately
        qsTile?.let {
            it.label = getDoorName(this, tileSlot) ?: "Door"
            it.state = Tile.STATE_ACTIVE
            it.updateTile()
        }

        // Start self as a foreground service so the network call has full access
        startForegroundService(Intent(this, this::class.java).apply {
            putExtra(EXTRA_DOOR_ID_INTENT, doorId)
        })
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val doorName = getDoorName(this, tileSlot)
        if (doorName != null) {
            tile.label = doorName
        } else {
            tile.label = "Tap to set up"
        }
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private fun performUnlock(doorId: String, startId: Int) {
        val doorName = getDoorName(this, tileSlot) ?: "Door"

        Log.d(TAG, "performUnlock: slot=$tileSlot doorId=$doorId doorName=$doorName")

        unlockJob?.cancel()
        unlockJob = CoroutineScope(Dispatchers.IO).launch {
            val resultLabel = try {
                val session = getSession()
                if (session == null) {
                    Log.w(TAG, "performUnlock: session is null, not logged in")
                    "Login required"
                } else {
                    Log.d(TAG, "performUnlock: session found for user=${session.email}, calling API")
                    val result = VerkadaApi().unlockDoor(session, doorId)
                    if (result.isSuccess) {
                        Log.d(TAG, "performUnlock: unlock success")
                        "✓ Unlocked"
                    } else {
                        Log.e(TAG, "performUnlock: unlock failed: ${result.exceptionOrNull()}")
                        "✗ Failed"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "performUnlock: exception", e)
                "✗ Error"
            }

            setTileLabel(resultLabel)

            @Suppress("DEPRECATION")
            stopForeground(true)

            delay(2000)
            refreshTile()
            stopSelf(startId)
        }
    }

    private fun setTileLabel(label: String) {
        val tile = qsTile ?: return
        tile.label = label
        tile.updateTile()
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(id, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Door Unlock",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows briefly while a door is being unlocked"
            setSound(null, null)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Verkada Pass")
            .setContentText("Opening door…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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
