package com.woyken.verkadapasstestapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class VerkadaApi(
    private val logger: AppLogger,
    private val timeoutSeconds: Long = 30,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    suspend fun requestMagicLink(email: String, orgShortName: String?): JSONObject {
        val payload = JSONObject()
            .put("email", email)
            .put("tokenScopeTypes", JSONArray().put("PASS_APP"))
        if (!orgShortName.isNullOrBlank()) {
            payload.put("organizationShortName", orgShortName)
        }
        return postJson(
            url = EMAIL_MAGIC_LINK_URL,
            label = "Requesting a magic link",
            body = payload,
        )
    }

    suspend fun redeemMagicLink(link: MagicLinkContext): SessionState {
        val payload = JSONObject()
            .put("appNotificationToken", JSONObject.NULL)
            .put("notificationPermissions", JSONObject.NULL)
            .put("email", link.userEmail)
            .put("magicToken", link.magicToken)
            .put("tokenScopeTypes", JSONArray().put("PASS_APP"))
        link.orgShortName?.let { payload.put("orgShortName", it) }
        link.entityId?.let { payload.put("entityId", it) }

        val json = postJson(
            url = LOGIN_URL,
            label = "Redeeming the magic link",
            body = payload,
        )

        val userShard = json.optJSONObject("userShard") ?: json.optJSONObject("shard")
        val hostMetadata = userShard?.optJSONObject("host_metadata") ?: userShard?.optJSONObject("hostMetadata")

        return SessionState(
            organizationId = json.requireString("organizationId"),
            userId = json.requireString("userId"),
            userToken = json.requireString("userToken"),
            email = json.requireString("email"),
            shardDomain = hostMetadata.optStringOrNull("domain") ?: userShard.optStringOrNull("domain"),
            shardName = userShard.optStringOrNull("name") ?: userShard.optStringOrNull("shardName"),
            orgShortName = link.orgShortName,
        )
    }

    suspend fun listDoors(session: SessionState): List<DoorRecord> {
        val json = postJson(
            url = rewriteCommandHost(ACCESS_POINTS_URL, session.shardDomain),
            label = "Fetching doors",
            headers = session.authHeaders(),
            body = JSONObject().put("organizationId", session.organizationId),
        )

        val unlockables = json.optJSONObject("unlockables") ?: json.optJSONObject("accessPoints")
            ?: throw IOException("The doors response did not include an unlockables object.")
        val doors = unlockables.optJSONArray("doors")
            ?: throw IOException("The doors response did not include a doors array.")
        val schedules = buildScheduleMap(json)

        return buildList {
            for (index in 0 until doors.length()) {
                val item = doors.optJSONObject(index) ?: continue
                val accessPointId = item.optString("doorId")
                    .ifBlank { item.optString("accessPointId") }
                    .ifBlank { item.optString("id") }
                if (accessPointId.isBlank()) {
                    continue
                }

                add(
                    DoorRecord(
                        accessPointId = accessPointId,
                        name = item.optString("name").ifBlank { accessPointId },
                        accessControllerId = item.optString("accessControllerId").ifBlank { null },
                        floorId = item.optString("floorId").ifBlank { null },
                        schedule = schedules[accessPointId],
                        readerSerials = parseReaderSerials(item),
                    ),
                )
            }
        }
    }

    suspend fun unlockDoor(
        session: SessionState,
        accessPointId: String,
        unlockMethod: String,
    ): UnlockResult {
        val json = postJson(
            url = rewriteCommandHost(
                REMOTE_UNLOCK_URL_TEMPLATE.replace("{accessPointId}", accessPointId),
                session.shardDomain,
            ),
            label = "Unlocking the door",
            headers = session.authHeaders(),
            body = JSONObject().put("unlockMethod", unlockMethod),
        )
        val duration = json.optDouble("duration", Double.NaN)
        return UnlockResult(duration = duration.takeUnless { it.isNaN() })
    }

    suspend fun getRegisteredAuthKeys(session: SessionState): List<RegisteredAuthKey> {
        val json = getJson(
            url = rewriteCommandHost(
                AUTH_KEYS_URL_TEMPLATE.replace("{organizationId}", session.organizationId),
                session.shardDomain,
            ),
            label = "Fetching registered auth keys",
            headers = session.authHeaders(),
        )
        val keys = json.optJSONArray("keys") ?: return emptyList()
        return buildList {
            for (index in 0 until keys.length()) {
                val item = keys.optJSONObject(index) ?: continue
                add(
                    RegisteredAuthKey(
                        fingerprint = item.optString("fingerprint"),
                        keyType = item.optString("keyType"),
                    ),
                )
            }
        }
    }

    suspend fun registerPublicBleKey(
        session: SessionState,
        request: JSONObject,
    ) {
        postJson(
            url = rewriteCommandHost(
                AUTH_KEYS_URL_TEMPLATE.replace("{organizationId}", session.organizationId),
                session.shardDomain,
            ),
            label = "Registering the BLE public key",
            headers = session.authHeaders(),
            body = request,
        )
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private suspend fun postJson(
        url: String,
        label: String,
        headers: Map<String, String> = emptyMap(),
        body: JSONObject,
    ): JSONObject = withContext(Dispatchers.IO) {
        val rawBody = body.toString()
        val request = Request.Builder()
            .url(url)
            .headers(headers.toOkHttpHeaders())
            .post(rawBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeJsonRequest(request, label, rawBody)
    }

    private suspend fun getJson(
        url: String,
        label: String,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .headers(headers.toOkHttpHeaders())
            .get()
            .build()
        executeJsonRequest(request, label, null)
    }

    private fun executeJsonRequest(
        request: Request,
        label: String,
        requestBody: String?,
    ): JSONObject {
        logger.info("HTTP ${request.method} ${request.url}")
        logger.debug(
            "HTTP ${request.method} ${request.url} headers=${redactHeaders(request.headers.toMap())}" +
                requestBody?.let { " body=${redactSensitive(it)}" }.orEmpty(),
        )

        client.newCall(request)
            .execute()
            .use { response ->
                val responseBody = response.body?.string().orEmpty()
                logger.info("HTTP ${request.method} ${request.url} -> ${response.code} ${response.message}")
                logger.debug(
                    "HTTP ${request.method} ${request.url} -> ${response.code} body=${redactSensitive(responseBody)}",
                )

                if (!response.isSuccessful) {
                    throw IOException(
                        "$label failed with HTTP ${response.code} ${response.message}." +
                            responseBody.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty(),
                    )
                }

                if (responseBody.isBlank()) {
                    return JSONObject()
                }
                return JSONObject(responseBody)
            }
    }

    private fun buildScheduleMap(json: JSONObject): Map<String, DoorSchedule> {
        val schedules = json.optJSONArray("userSchedules") ?: return emptyMap()
        val result = mutableMapOf<String, DoorSchedule>()
        for (index in 0 until schedules.length()) {
            val item = schedules.optJSONObject(index) ?: continue
            val events = item.optJSONArray("events") ?: continue
            val doorId = item.optString("doorId")
            if (doorId.isBlank()) {
                continue
            }
            val parsedEvents = buildList {
                for (eventIndex in 0 until events.length()) {
                    val event = events.optJSONObject(eventIndex) ?: continue
                    add(
                        DoorScheduleEvent(
                            doorPermissionState = event.requireString("doorPermissionState"),
                            startDateTime = event.requireString("startDateTime"),
                            endDateTime = event.requireString("endDateTime"),
                        ),
                    )
                }
            }
            result[doorId] = DoorSchedule(
                doorId = doorId,
                startDateTime = item.requireString("startDateTime"),
                endDateTime = item.requireString("endDateTime"),
                events = parsedEvents,
            )
        }
        return result
    }

    private fun parseReaderSerials(door: JSONObject): List<String> {
        val readerPeripherals = door.optJSONArray("readerPeripherals") ?: return emptyList()
        val result = mutableListOf<String>()
        for (index in 0 until readerPeripherals.length()) {
            val item = readerPeripherals.optJSONObject(index) ?: continue
            val serial = item.optString("serialNumber").trim()
            if (serial.isNotBlank() && serial !in result) {
                result += serial
            }
        }
        return result
    }

    private fun rewriteCommandHost(url: String, shardDomain: String?): String {
        if (shardDomain.isNullOrBlank() || "command.verkada.com" !in url) {
            return url
        }
        return url.replace("command.verkada.com", shardDomain)
    }

    private fun JSONObject.requireString(key: String): String =
        optString(key).ifBlank { throw IOException("Response is missing required field '$key'.") }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val EMAIL_MAGIC_LINK_URL = "https://vauth.command.verkada.com/auth/magic"
        const val LOGIN_URL = "https://vprovision.command.verkada.com/user/login"
        const val ACCESS_POINTS_URL = "https://vcerberus.command.verkada.com/access/v2/user/pass/unlockables/1"
        const val REMOTE_UNLOCK_URL_TEMPLATE =
            "https://vcerberus.command.verkada.com/access/v2/user/virtual_device/{accessPointId}/unlock"
        const val AUTH_KEYS_URL_TEMPLATE = "https://vcerberus.command.verkada.com/{organizationId}/keys"
    }
}

private fun JSONObject?.optStringOrNull(key: String): String? =
    this?.optString(key)?.ifBlank { null }

private fun Map<String, String>.toOkHttpHeaders(): okhttp3.Headers = okhttp3.Headers.Builder().apply {
    for ((name, value) in this@toOkHttpHeaders) {
        add(name, value)
    }
}.build()

private fun okhttp3.Headers.toMap(): Map<String, String> =
    names().associateWith { name -> values(name).joinToString(", ") }
