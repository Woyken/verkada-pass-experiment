package com.woyken.verkadapass.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

data class RemoteBuild(
    val versionCode: Int,
    val versionName: String,
    val pageUrl: String,
)

/**
 * Checks the newest `android-v*` GitHub release. Each build publishes its own
 * release, so the version is read from the release notes (`versionCode: N`)
 * and every build stays visible in the repository's release history.
 */
class UpdateChecker(context: Context) {

    companion object {
        private const val RELEASES_API_URL =
            "https://api.github.com/repos/Woyken/verkada-pass-experiment/releases?per_page=20"
        private const val TAG_PREFIX = "android-v"
        private const val RELEASES_PAGE_URL =
            "https://github.com/Woyken/verkada-pass-experiment/releases"

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
            val conn = URL(RELEASES_API_URL).openConnection() as HttpURLConnection
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
        fun build(release: JsonObject): RemoteBuild? {
            if (release.string("tag_name")?.startsWith(TAG_PREFIX) != true) return null
            val notes = release.string("body").orEmpty()
            val versionCode = VERSION_CODE_PATTERN.find(notes)?.groupValues?.get(1)?.toIntOrNull()
                ?: return null
            val versionName = VERSION_NAME_PATTERN.find(notes)?.groupValues?.get(1)
                ?: versionCode.toString()
            return RemoteBuild(
                versionCode = versionCode,
                versionName = versionName,
                pageUrl = release.string("html_url") ?: RELEASES_PAGE_URL,
            )
        }

        // A replayed old build can be published after a newer one, so pick the
        // highest versionCode rather than trusting the response order.
        return Json.parseToJsonElement(responseBody).jsonArray
            .mapNotNull { build(it.jsonObject) }
            .maxByOrNull { it.versionCode }
            ?: throw IllegalStateException("No $TAG_PREFIX release published")
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
