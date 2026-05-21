package com.woyken.verkadapass.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class VerkadaApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val LOGIN_URL = "https://vprovision.command.verkada.com/user/login"
        private const val MAGIC_LINK_URL = "https://vauth.command.verkada.com/auth/magic"
        private const val ACCESS_POINTS_URL = "https://vcerberus.command.verkada.com/access/v2/user/pass/unlockables/1"
        private const val UNLOCK_URL_TEMPLATE = "https://vcerberus.command.verkada.com/access/v2/user/virtual_device/%s/unlock"
    }

    data class MagicLinkParams(
        val magicToken: String,
        val entityId: String,
        val userEmail: String,
        val orgShortName: String,
    )

    fun parseMagicLinkUrl(url: String): MagicLinkParams {
        val uri = android.net.Uri.parse(url)
        return MagicLinkParams(
            magicToken = uri.getQueryParameter("magicToken") ?: throw IllegalArgumentException("Missing magicToken"),
            entityId = uri.getQueryParameter("entityId") ?: throw IllegalArgumentException("Missing entityId"),
            userEmail = URLDecoder.decode(uri.getQueryParameter("userEmail") ?: "", "UTF-8"),
            orgShortName = uri.getQueryParameter("orgShortName") ?: "",
        )
    }

    suspend fun requestMagicLink(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = """{"email":"$email","tokenScopeTypes":["PASS_APP"]}"""
            val request = Request.Builder()
                .url(MAGIC_LINK_URL)
                .post(body.toRequestBody(jsonMediaType))
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw RuntimeException("Magic link request failed: ${response.code} ${response.body?.string()}")
            }
        }
    }

    suspend fun redeemMagicLink(params: MagicLinkParams): Result<SessionData> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildString {
                append("{")
                append("\"email\":\"${params.userEmail}\",")
                append("\"magicToken\":\"${params.magicToken}\",")
                append("\"tokenScopeTypes\":[\"PASS_APP\"],")
                append("\"entityId\":\"${params.entityId}\",")
                append("\"orgShortName\":\"${params.orgShortName}\",")
                append("\"appNotificationToken\":null,")
                append("\"notificationPermissions\":null")
                append("}")
            }
            val request = Request.Builder()
                .url(LOGIN_URL)
                .post(body.toRequestBody(jsonMediaType))
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw RuntimeException("Empty response")
            if (!response.isSuccessful) {
                throw RuntimeException("Login failed: ${response.code} $responseBody")
            }
            val obj = json.parseToJsonElement(responseBody).jsonObject
            SessionData(
                userToken = obj["userToken"]?.jsonPrimitive?.content ?: throw RuntimeException("No userToken"),
                organizationId = obj["organizationId"]?.jsonPrimitive?.content ?: throw RuntimeException("No organizationId"),
                userId = obj["userId"]?.jsonPrimitive?.content ?: params.entityId,
                email = params.userEmail,
                orgShortName = params.orgShortName,
            )
        }
    }

    suspend fun listDoors(session: SessionData): Result<List<DoorItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = """{"organizationId":"${session.organizationId}"}"""
            val request = Request.Builder()
                .url(ACCESS_POINTS_URL)
                .post(body.toRequestBody(jsonMediaType))
                .addHeader("x-verkada-token", session.userToken)
                .addHeader("x-verkada-organization-id", session.organizationId)
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw RuntimeException("Empty response")
            if (!response.isSuccessful) {
                throw RuntimeException("List doors failed: ${response.code} $responseBody")
            }
            val obj = json.parseToJsonElement(responseBody).jsonObject
            val unlockables = obj["unlockables"]?.jsonObject ?: obj["accessPoints"]?.jsonObject
                ?: throw RuntimeException("No unlockables in response")
            val doors = unlockables["doors"]?.jsonArray ?: throw RuntimeException("No doors array")
            doors.map { element ->
                val door = element.jsonObject
                DoorItem(
                    accessPointId = (door["doorId"] ?: door["accessPointId"] ?: door["id"])
                        ?.jsonPrimitive?.content ?: "",
                    name = door["name"]?.jsonPrimitive?.content ?: "Unknown Door",
                    location = door["location"]?.jsonPrimitive?.content ?: "",
                )
            }
        }
    }

    suspend fun unlockDoor(session: SessionData, accessPointId: String): Result<UnlockResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = String.format(UNLOCK_URL_TEMPLATE, accessPointId)
            val body = """{"unlockMethod":"nearby"}"""
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(jsonMediaType))
                .addHeader("x-verkada-token", session.userToken)
                .addHeader("x-verkada-organization-id", session.organizationId)
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw RuntimeException("Empty response")
            if (!response.isSuccessful) {
                throw RuntimeException("Unlock failed: ${response.code} $responseBody")
            }
            val obj = json.parseToJsonElement(responseBody).jsonObject
            UnlockResult(
                success = true,
                duration = obj["duration"]?.jsonPrimitive?.double,
            )
        }
    }
}
