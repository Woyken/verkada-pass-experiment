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
        context.dataStore.edit { prefs ->
            prefs[sessionKey] = Json.encodeToString(SessionData.serializer(), session)
        }
    }

    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(sessionKey)
        }
    }
}
