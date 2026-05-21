package com.woyken.verkadapass.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.woyken.verkadapass.R
import com.woyken.verkadapass.data.SessionData
import com.woyken.verkadapass.data.VerkadaApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class DoorWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_UNLOCK = "com.woyken.verkadapass.ACTION_UNLOCK"
        const val EXTRA_WIDGET_ID = "widget_id"
        private const val PREFS_NAME = "door_widgets"
        private const val KEY_DOOR_ID_PREFIX = "door_id_"
        private const val KEY_DOOR_NAME_PREFIX = "door_name_"

        fun saveDoorForWidget(context: Context, widgetId: Int, doorId: String, doorName: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("${KEY_DOOR_ID_PREFIX}$widgetId", doorId)
                .putString("${KEY_DOOR_NAME_PREFIX}$widgetId", doorName)
                .apply()
        }

        fun getDoorId(context: Context, widgetId: Int): String? {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("${KEY_DOOR_ID_PREFIX}$widgetId", null)
        }

        fun getDoorName(context: Context, widgetId: Int): String? {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("${KEY_DOOR_NAME_PREFIX}$widgetId", null)
        }

        fun deleteDoorForWidget(context: Context, widgetId: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove("${KEY_DOOR_ID_PREFIX}$widgetId")
                .remove("${KEY_DOOR_NAME_PREFIX}$widgetId")
                .apply()
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val doorName = getDoorName(context, widgetId) ?: "No Door"
            val views = RemoteViews(context.packageName, R.layout.widget_door)
            views.setTextViewText(R.id.widget_door_name, doorName)
            views.setViewVisibility(R.id.widget_status, android.view.View.GONE)
            views.setImageViewResource(R.id.widget_unlock_icon, android.R.drawable.ic_lock_idle_lock)

            val intent = Intent(context, DoorWidgetProvider::class.java).apply {
                action = ACTION_UNLOCK
                putExtra(EXTRA_WIDGET_ID, widgetId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            deleteDoorForWidget(context, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UNLOCK) {
            val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
            performUnlock(context, widgetId)
        }
    }

    private fun performUnlock(context: Context, widgetId: Int) {
        val doorId = getDoorId(context, widgetId) ?: return
        val doorName = getDoorName(context, widgetId) ?: "Door"

        // Show "Unlocking..." state
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val views = RemoteViews(context.packageName, R.layout.widget_door)
        views.setTextViewText(R.id.widget_door_name, doorName)
        views.setTextViewText(R.id.widget_status, "…")
        views.setViewVisibility(R.id.widget_status, android.view.View.VISIBLE)
        views.setImageViewResource(R.id.widget_unlock_icon, android.R.drawable.ic_lock_idle_lock)
        appWidgetManager.updateAppWidget(widgetId, views)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val session = getSession(context)
                if (session == null) {
                    setWidgetState(context, widgetId, doorName, "Login!", unlocked = false)
                    return@launch
                }
                val api = VerkadaApi()
                val result = api.unlockDoor(session, doorId)
                if (result.isSuccess) {
                    setWidgetState(context, widgetId, doorName, "✓", unlocked = true)
                } else {
                    setWidgetState(context, widgetId, doorName, "✗", unlocked = false)
                }
            } catch (e: Exception) {
                setWidgetState(context, widgetId, doorName, "✗", unlocked = false)
            }

            // Reset after 3 seconds
            kotlinx.coroutines.delay(3000)
            updateWidget(context, AppWidgetManager.getInstance(context), widgetId)
        }
    }

    private fun setWidgetState(context: Context, widgetId: Int, doorName: String, status: String, unlocked: Boolean) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val views = RemoteViews(context.packageName, R.layout.widget_door)
        views.setTextViewText(R.id.widget_door_name, doorName)
        views.setTextViewText(R.id.widget_status, status)
        views.setViewVisibility(R.id.widget_status, android.view.View.VISIBLE)
        views.setImageViewResource(
            R.id.widget_unlock_icon,
            if (unlocked) android.R.drawable.ic_lock_idle_low_battery
            else android.R.drawable.ic_lock_idle_lock
        )

        // Re-attach click handler
        val intent = Intent(context, DoorWidgetProvider::class.java).apply {
            action = ACTION_UNLOCK
            putExtra(EXTRA_WIDGET_ID, widgetId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, widgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
        appWidgetManager.updateAppWidget(widgetId, views)
    }

    private fun getSession(context: Context): SessionData? {
        val prefs = context.getSharedPreferences("verkada_session_cache", Context.MODE_PRIVATE)
        val json = prefs.getString("session_json", null) ?: return null
        return try {
            Json.decodeFromString<SessionData>(json)
        } catch (_: Exception) {
            null
        }
    }
}
