package com.woyken.verkadapasstestapp.data

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.math.ec.rfc7748.X25519
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val BLE_READER_SERVICE_UUID = "0000FD3B-0000-1000-8000-00805F9B34FB"
const val BLE_PHONE_SERVICE_UUID = "0000FD3A-0000-1000-8000-00805F9B34FB"
const val BLE_PHONE_ADVERTISED_SERVICE_UUID = "0000A4DD-0000-1000-8000-00805F9B34FB"
const val BLE_PUBLIC_KEY_CHARACTERISTIC_UUID = "00001001-0000-1000-8000-00805F9B34FB"
const val BLE_AUTH_CHARACTERISTIC_UUID = "00002000-0000-1000-8000-00805F9B34FB"
const val BLE_KEY_TYPE = "BLE_UNLOCK_PUBLIC_KEY_ED25519"
const val VERKADA_READER_BEACON_UUID = "ac3ef23c-70d8-4773-97ad-b9a566a0fb40"

private const val APPLE_COMPANY_ID = 0x004C
private val IBEACON_PREFIX = byteArrayOf(0x02, 0x15)

data class BleRegistrationInfo(
    val platform: String,
    val version: String,
    val make: String,
    val model: String,
    val name: String,
)

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex input must have an even number of characters." }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

fun generateBleKeyPair(random: SecureRandom = SecureRandom()): BleKeyPair {
    val privateKey = ByteArray(32)
    val publicKey = ByteArray(32)
    X25519.generatePrivateKey(random, privateKey)
    X25519.generatePublicKey(privateKey, 0, publicKey, 0)
    return BleKeyPair(publicKey = publicKey, privateKey = privateKey)
}

fun computeBleAuthTag(message: ByteArray, sessionTxKey: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA512")
    mac.init(SecretKeySpec(sessionTxKey, "HmacSHA512"))
    return mac.doFinal(message).copyOfRange(0, 32)
}

fun buildUnlockPayload(
    bleKeys: BleKeyPair,
    readerPublicKey: ByteArray,
    readerMessage: ByteArray,
    userId: String,
): ByteArray {
    require(readerPublicKey.size == 32) {
        "Reader public key must be 32 bytes, got ${readerPublicKey.size}."
    }

    val (_, txKey) = deriveClientSessionKeys(bleKeys, readerPublicKey)
    val authTag = computeBleAuthTag(readerMessage, txKey)
    return bleKeys.publicKey + authTag + uuidToBytes(UUID.fromString(userId))
}

fun buildBleRegistrationRequest(
    bleKeys: BleKeyPair,
    registrationInfo: BleRegistrationInfo,
): JSONObject = JSONObject()
    .put("publicKey", bleKeys.publicKeyHex)
    .put("platform", registrationInfo.platform)
    .put("version", registrationInfo.version)
    .put("make", registrationInfo.make)
    .put("model", registrationInfo.model)
    .put("name", registrationInfo.name)
    .put("keyType", BLE_KEY_TYPE)

fun looksLikeVerkadaReaderSerial(value: String): Boolean {
    if (value.startsWith("APL")) {
        return true
    }
    return value.length == 14 &&
        value.count { it == '-' } == 2 &&
        value.all { it == '-' || it in 'A'..'Z' || it in '0'..'9' }
}

fun decodeReaderSerialFromScanRecord(rawScanRecordBytes: ByteArray?): String? {
    if (rawScanRecordBytes == null || rawScanRecordBytes.size < 23) {
        return null
    }
    val decoded = rawScanRecordBytes.copyOfRange(9, 23)
        .toString(Charsets.UTF_8)
        .trim('\u0000', ' ', '\n', '\r', '\t')
    return decoded.takeIf(::looksLikeVerkadaReaderSerial)
}

fun decodeReaderSerial(
    manufacturerData: Map<Int, ByteArray>,
    rawScanRecordBytes: ByteArray? = null,
): String? {
    decodeReaderSerialFromScanRecord(rawScanRecordBytes)?.let { return it }

    var fallback: String? = null
    for ((companyId, payload) in manufacturerData) {
        val prefix = byteArrayOf((companyId and 0xff).toByte(), ((companyId shr 8) and 0xff).toByte())
        val serialBytes = prefix + payload
        val decoded = serialBytes.toString(Charsets.UTF_8).trim('\u0000', ' ', '\n', '\r', '\t')
        if (decoded.isBlank()) {
            continue
        }
        if (looksLikeVerkadaReaderSerial(decoded)) {
            return decoded
        }
        if (fallback == null) {
            fallback = decoded
        }
    }
    return fallback
}

fun decodeVerkadaBeaconDetails(manufacturerData: Map<Int, ByteArray>): Triple<String, Int, Int>? {
    for ((companyId, payload) in manufacturerData) {
        if (companyId != APPLE_COMPANY_ID || payload.size < 18 || !payload.copyOfRange(0, 2).contentEquals(IBEACON_PREFIX)) {
            continue
        }
        val uuid = bytesToUuid(payload.copyOfRange(2, 18)).lowercase()
        if (payload.size < 22) {
            return Triple(uuid, 0, 0)
        }
        val major = ((payload[18].toInt() and 0xff) shl 8) or (payload[19].toInt() and 0xff)
        val minor = ((payload[20].toInt() and 0xff) shl 8) or (payload[21].toInt() and 0xff)
        return Triple(uuid, major, minor)
    }
    return null
}

fun deriveBeaconPairFromReaderSerial(readerSerial: String): Pair<Int, Int> {
    val digest = MessageDigest.getInstance("SHA-256").digest(readerSerial.toByteArray()).toHex()
    return digest.substring(0, 4).toInt(16) to digest.substring(4, 8).toInt(16)
}

private fun deriveClientSessionKeys(
    bleKeys: BleKeyPair,
    readerPublicKey: ByteArray,
): Pair<ByteArray, ByteArray> {
    val sharedSecret = ByteArray(32)
    X25519.scalarMult(bleKeys.privateKey, 0, readerPublicKey, 0, sharedSecret, 0)

    val digest = Blake2bDigest(512)
    digest.update(sharedSecret, 0, sharedSecret.size)
    digest.update(bleKeys.publicKey, 0, bleKeys.publicKey.size)
    digest.update(readerPublicKey, 0, readerPublicKey.size)

    val output = ByteArray(64)
    digest.doFinal(output, 0)
    return output.copyOfRange(0, 32) to output.copyOfRange(32, 64)
}

private fun uuidToBytes(uuid: UUID): ByteArray =
    ByteBuffer.allocate(16)
        .putLong(uuid.mostSignificantBits)
        .putLong(uuid.leastSignificantBits)
        .array()

private fun bytesToUuid(bytes: ByteArray): String {
    val buffer = ByteBuffer.wrap(bytes)
    val most = buffer.long
    val least = buffer.long
    return UUID(most, least).toString()
}
