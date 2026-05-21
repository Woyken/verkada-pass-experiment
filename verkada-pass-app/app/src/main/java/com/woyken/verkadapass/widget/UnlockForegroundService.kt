package com.woyken.verkadapass.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.woyken.verkadapass.data.SessionData
import com.woyken.verkadapass.data.VerkadaApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class UnlockForegroundService : Service() {

    companion object {
        private const val TAG = "UnlockForegroundSvc"
        private const val CHANNEL_ID = "verkada_unlock_channel"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_DOOR_ID = "door_id"
        private const val EXTRA_DOOR_NAME = "door_name"
        private const val EXTRA_WIDGET_ID = "widget_id"

        fun startForWidget(context: Context, widgetId: Int, doorId: String, doorName: String) {
            val intent = Intent(context, UnlockForegroundService::class.java).apply {
                putExtra(EXTRA_WIDGET_ID, widgetId)
                putExtra(EXTRA_DOOR_ID, doorId)
                putExtra(EXTRA_DOOR_NAME, doorName)
            }
            context.startForegroundService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val doorId = intent?.getStringExtra(EXTRA_DOOR_ID) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val doorName = intent.getStringExtra(EXTRA_DOOR_NAME) ?: "Door"
        val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)

        createNotificationChannel()
        startForegroundCompat(NOTIFICATION_ID, createNotification("Opening $doorName…"))

        CoroutineScope(Dispatchers.IO).launch {
            var success = false
            var status = "✗"

            try {
                val session = getSession()
                if (session == null) {
                    Log.w(TAG, "No session found")
                    status = "Login!"
                } else {
                    Log.d(TAG, "Unlocking $doorName ($doorId) for ${session.email}")
                    val result = VerkadaApi().unlockDoor(session, doorId)
                    if (result.isSuccess) {
                        Log.d(TAG, "Unlock success")
                        success = true
                        status = "✓"
                    } else {
                        Log.e(TAG, "Unlock failed: ${result.exceptionOrNull()}")
                        status = "✗"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during unlock", e)
                status = "✗"
            }

            if (widgetId != -1) {
                DoorWidgetProvider.setWidgetResult(this@UnlockForegroundService, widgetId, doorName, status, success)
            }

            @Suppress("DEPRECATION")
            stopForeground(true)

            delay(3000)

            if (widgetId != -1) {
                DoorWidgetProvider.updateWidget(
                    this@UnlockForegroundService,
                    AppWidgetManager.getInstance(this@UnlockForegroundService),
                    widgetId
                )
            }

            stopSelf(startId)
        }

        return START_NOT_STICKY
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

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Verkada Pass")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getSession(): SessionData? {
        val json = getSharedPreferences("verkada_session_cache", MODE_PRIVATE)
            .getString("session_json", null) ?: return null
        return try {
            Json.decodeFromString<SessionData>(json)
        } catch (_: Exception) {
            null
        }
    }
}
