package com.woyken.verkadapass.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

data class RemoteBuild(
    val versionCode: Int,
    val versionName: String,
)

/**
 * Checks the rolling `android-latest` GitHub release. The release is recreated on
 * every push to main, so the version a build reports lives in its release notes
 * (`versionCode: N`) rather than in the (reused) tag name.
 */
class UpdateChecker(context: Context) {

    companion object {
        private const val RELEASE_API_URL =
            "https://api.github.com/repos/Woyken/verkada-pass-experiment/releases/tags/android-latest"
        const val RELEASE_PAGE_URL =
            "https://github.com/Woyken/verkada-pass-experiment/releases/tag/android-latest"

        private const val PREFS_NAME = "update_check"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_DISMISSED_UNTIL = "dismissed_until"

        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val DISMISS_INTERVAL_MS = 3 * 24L * 60 * 60 * 1000
        private const val TIMEOUT_MS = 5_000

        private val VERSION_CODE_PATTERN = Regex("(?m)^versionCode:\\s*(\\d+)")
        private val VERSION_NAME_PATTERN = Regex("(?m)^versionName:\\s*(\\S+)")
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isCheckDue(now: Long = System.currentTimeMillis()): Boolean =
        now - prefs.getLong(KEY_LAST_CHECK, 0L) >= CHECK_INTERVAL_MS

    fun markChecked(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
    }

    fun isDismissed(now: Long = System.currentTimeMillis()): Boolean =
        now < prefs.getLong(KEY_DISMISSED_UNTIL, 0L)

    fun dismiss(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_DISMISSED_UNTIL, now + DISMISS_INTERVAL_MS).apply()
    }

    suspend fun latestBuild(): Result<RemoteBuild> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(RELEASE_API_URL).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "verkada-pass-app")
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                val code = conn.responseCode
                if (code !in 200..299) throw RuntimeException("HTTP $code")
                parse(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun parse(responseBody: String): RemoteBuild {
        val notes = Json.parseToJsonElement(responseBody).jsonObject["body"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Release notes are missing")
        val versionCode = VERSION_CODE_PATTERN.find(notes)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IllegalStateException("Release notes do not declare a versionCode")
        val versionName = VERSION_NAME_PATTERN.find(notes)?.groupValues?.get(1)
            ?: versionCode.toString()
        return RemoteBuild(versionCode = versionCode, versionName = versionName)
    }
}
