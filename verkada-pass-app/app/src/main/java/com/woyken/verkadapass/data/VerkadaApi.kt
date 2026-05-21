package com.woyken.verkadapass.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class VerkadaApi {

    private val json = Json { ignoreUnknownKeys = true }

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
        val uri = Uri.parse(url)
        return MagicLinkParams(
            magicToken = uri.getQueryParameter("magicToken") ?: throw IllegalArgumentException("Missing magicToken"),
            entityId = uri.getQueryParameter("entityId") ?: throw IllegalArgumentException("Missing entityId"),
            userEmail = URLDecoder.decode(uri.getQueryParameter("userEmail") ?: "", "UTF-8"),
            orgShortName = uri.getQueryParameter("orgShortName") ?: "",
        )
    }

    private fun post(urlString: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        val responseBody = if (code in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
            throw RuntimeException("HTTP $code: $err")
        }
        conn.disconnect()
        return responseBody
    }

    suspend fun requestMagicLink(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            post(MAGIC_LINK_URL, """{"email":"$email","tokenScopeTypes":["PASS_APP"]}""")
            Unit
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
            val responseBody = post(LOGIN_URL, body)
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
            val headers = mapOf(
                "x-verkada-token" to session.userToken,
                "x-verkada-organization-id" to session.organizationId,
            )
            val responseBody = post(ACCESS_POINTS_URL, body, headers)
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
            val headers = mapOf(
                "x-verkada-token" to session.userToken,
                "x-verkada-organization-id" to session.organizationId,
            )
            val responseBody = post(url, body, headers)
            val obj = json.parseToJsonElement(responseBody).jsonObject
            UnlockResult(
                success = true,
                duration = obj["duration"]?.jsonPrimitive?.double,
            )
        }
    }
}

