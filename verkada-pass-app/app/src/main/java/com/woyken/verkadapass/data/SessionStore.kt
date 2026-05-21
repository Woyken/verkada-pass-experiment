package com.woyken.verkadapass.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "verkada_session")

class SessionStore(private val context: Context) {

    companion object {
        private const val WIDGET_PREFS_NAME = "verkada_session_cache"
        private const val WIDGET_SESSION_KEY = "session_json"
    }

    private val sessionKey = stringPreferencesKey("session_json")

    val session: Flow<SessionData?> = context.dataStore.data.map { prefs ->
        prefs[sessionKey]?.let { json ->
            try {
                Json.decodeFromString<SessionData>(json)
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun save(session: SessionData) {
        val jsonStr = Json.encodeToString(SessionData.serializer(), session)
        context.dataStore.edit { prefs ->
            prefs[sessionKey] = jsonStr
        }
        // Also save to SharedPreferences for widget access
        context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(WIDGET_SESSION_KEY, jsonStr)
            .apply()
    }

    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(sessionKey)
        }
        context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(WIDGET_SESSION_KEY)
            .apply()
    }
}
