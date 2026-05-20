package com.woyken.verkadapasstestapp.data

import android.content.Context
import org.json.JSONObject
import java.io.File

class AppStorage(context: Context) {
    private val appContext = context.applicationContext
    private val configFile = File(appContext.filesDir, "app-config.json")
    private val sessionFile = File(appContext.filesDir, "session.json")
    private val bleKeysFile = File(appContext.filesDir, "ble-keys.json")

    fun loadConfig(): AppConfigData {
        if (!configFile.exists()) {
            return AppConfigData()
        }
        val json = JSONObject(configFile.readText())
        return AppConfigData(
            email = json.optString("email"),
            orgShortName = json.optString("orgShortName"),
            unlockMethod = json.optString("unlockMethod").ifBlank { "mobile" },
            bleUserId = json.optString("bleUserId"),
        )
    }

    fun saveConfig(config: AppConfigData) {
        writeJson(
            configFile,
            JSONObject()
                .put("email", config.email)
                .put("orgShortName", config.orgShortName)
                .put("unlockMethod", config.unlockMethod)
                .put("bleUserId", config.bleUserId),
        )
    }

    fun loadSession(): SessionState? {
        if (!sessionFile.exists()) {
            return null
        }
        val json = JSONObject(sessionFile.readText())
        return SessionState(
            organizationId = json.getString("organizationId"),
            userId = json.getString("userId"),
            userToken = json.getString("userToken"),
            email = json.getString("email"),
            shardDomain = json.optString("shardDomain").ifBlank { null },
            shardName = json.optString("shardName").ifBlank { null },
            orgShortName = json.optString("orgShortName").ifBlank { null },
        )
    }

    fun saveSession(session: SessionState) {
        writeJson(
            sessionFile,
            JSONObject()
                .put("organizationId", session.organizationId)
                .put("userId", session.userId)
                .put("userToken", session.userToken)
                .put("email", session.email)
                .put("shardDomain", session.shardDomain ?: "")
                .put("shardName", session.shardName ?: "")
                .put("orgShortName", session.orgShortName ?: ""),
        )
    }

    fun clearSession() {
        if (sessionFile.exists()) {
            sessionFile.delete()
        }
    }

    fun loadBleKeys(): BleKeyPair? {
        if (!bleKeysFile.exists()) {
            return null
        }
        val json = JSONObject(bleKeysFile.readText())
        return BleKeyPair(
            publicKey = json.getString("publicKeyHex").hexToByteArray(),
            privateKey = json.getString("privateKeyHex").hexToByteArray(),
        )
    }

    fun saveBleKeys(bleKeys: BleKeyPair) {
        writeJson(
            bleKeysFile,
            JSONObject()
                .put("publicKeyHex", bleKeys.publicKeyHex)
                .put("privateKeyHex", bleKeys.privateKeyHex),
        )
    }

    private fun writeJson(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        file.writeText(json.toString(2))
    }
}

