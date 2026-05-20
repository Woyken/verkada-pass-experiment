package com.woyken.verkadapasstestapp.data

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class AppConfigData(
    val email: String = "",
    val orgShortName: String = "",
    val unlockMethod: String = "mobile",
    val bleUserId: String = "",
)

data class MagicLinkContext(
    val sourceUrl: String,
    val magicToken: String,
    val userEmail: String,
    val orgShortName: String? = null,
    val entityId: String? = null,
    val shardName: String? = null,
) {
    companion object {
        fun fromUrl(url: String): MagicLinkContext {
            val trimmedUrl = url.trim()
            val uri = URI(trimmedUrl)
            val query = parseQuery(uri.rawQuery ?: "")

            val magicToken = query["magicToken"].orEmpty()
            val userEmail = query["userEmail"].orEmpty()
            require(magicToken.isNotBlank()) { "The magic link URL is missing the magicToken query parameter." }
            require(userEmail.isNotBlank()) { "The magic link URL is missing the userEmail query parameter." }

            return MagicLinkContext(
                sourceUrl = trimmedUrl,
                magicToken = magicToken,
                userEmail = userEmail,
                orgShortName = query["orgShortName"],
                entityId = query["entityId"],
                shardName = query["shard"],
            )
        }

        private fun parseQuery(query: String): Map<String, String> =
            query.split("&")
                .filter { it.isNotBlank() }
                .mapNotNull { part ->
                    val pieces = part.split("=", limit = 2)
                    if (pieces.isEmpty() || pieces[0].isBlank()) {
                        null
                    } else {
                        val key = URLDecoder.decode(pieces[0], StandardCharsets.UTF_8)
                        val value = URLDecoder.decode(pieces.getOrElse(1) { "" }, StandardCharsets.UTF_8)
                        key to value
                    }
                }
                .toMap()
    }
}

data class SessionState(
    val organizationId: String,
    val userId: String,
    val userToken: String,
    val email: String,
    val shardDomain: String? = null,
    val shardName: String? = null,
    val orgShortName: String? = null,
) {
    fun authHeaders(): Map<String, String> =
        mapOf(
            "X-Verkada-Organization-Id" to organizationId,
            "X-Verkada-User-Id" to userId,
            "X-Verkada-Auth" to userToken,
        )
}

data class DoorScheduleEvent(
    val doorPermissionState: String,
    val startDateTime: String,
    val endDateTime: String,
)

data class DoorSchedule(
    val doorId: String,
    val startDateTime: String,
    val endDateTime: String,
    val events: List<DoorScheduleEvent>,
) {
    fun distinctStates(): List<String> = events.map { it.doorPermissionState }.distinct()
}

data class DoorRecord(
    val accessPointId: String,
    val name: String,
    val accessControllerId: String? = null,
    val floorId: String? = null,
    val schedule: DoorSchedule? = null,
    val readerSerials: List<String> = emptyList(),
)

data class UnlockResult(
    val duration: Double? = null,
)

data class RegisteredAuthKey(
    val fingerprint: String,
    val keyType: String,
)

data class BleKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
) {
    val publicKeyHex: String
        get() = publicKey.toHex()

    val privateKeyHex: String
        get() = privateKey.toHex()

    val fingerprint: String
        get() {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(publicKeyHex.toByteArray(StandardCharsets.UTF_8))
            return digest.toHex()
        }
}

data class CentralReader(
    val address: String,
    val name: String,
    val readerSerial: String,
    val rssi: Int? = null,
)

data class BleScanObservation(
    val address: String,
    val name: String? = null,
    val localName: String? = null,
    val serviceUuids: List<String> = emptyList(),
    val rssi: Int? = null,
    val readerSerial: String? = null,
    val beaconUuid: String? = null,
    val beaconMajor: Int? = null,
    val beaconMinor: Int? = null,
) {
    fun markers(): List<String> {
        val labels = mutableListOf<String>()
        if (BLE_READER_SERVICE_UUID.lowercase() in serviceUuids) {
            labels += "FD3B"
        }
        if (BLE_PHONE_SERVICE_UUID.lowercase() in serviceUuids) {
            labels += "FD3A"
        }
        if (BLE_PHONE_ADVERTISED_SERVICE_UUID.lowercase() in serviceUuids) {
            labels += "A4DD"
        }
        if (beaconUuid == VERKADA_READER_BEACON_UUID) {
            labels += if (beaconMajor != null && beaconMinor != null) {
                "BEACON=$beaconMajor/$beaconMinor"
            } else {
                "BEACON"
            }
        }
        if (!readerSerial.isNullOrBlank()) {
            labels += "serial=$readerSerial"
        }
        return labels
    }

    fun summary(): String {
        val displayName = name ?: localName ?: "<unnamed>"
        val rssiLabel = rssi?.let { ", rssi=$it" }.orEmpty()
        val details = markers().takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " [", postfix = "]").orEmpty()
        return "- $displayName @ $address$rssiLabel$details"
    }
}

data class BleDiscoveryResult(
    val sourceLabel: String,
    val totalEntries: Int,
    val readers: List<CentralReader>,
    val observations: List<BleScanObservation>,
    val fd3bServiceCount: Int,
    val fd3aServiceCount: Int,
    val a4ddServiceCount: Int,
    val verkadaBeaconCount: Int,
    val serialCandidateCount: Int,
) {
    fun renderSummaryLines(): List<String> {
        val lines = mutableListOf(
            "Scan source: $sourceLabel",
            "Total advertisements seen: $totalEntries",
            "FD3B reader-service advertisers: $fd3bServiceCount",
            "FD3A phone-service advertisers: $fd3aServiceCount",
            "A4DD phone companion advertisers: $a4ddServiceCount",
            "Verkada reader beacon hits ($VERKADA_READER_BEACON_UUID): $verkadaBeaconCount",
            "Decodable serial candidates: $serialCandidateCount",
            "Reader candidates usable by this client: ${readers.size}",
        )
        val interesting = observations.take(8).map { it.summary() }
        if (interesting.isNotEmpty()) {
            lines += "Interesting advertisements:"
            lines += interesting
        }
        return lines
    }
}
