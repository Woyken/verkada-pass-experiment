package com.woyken.verkadapasstestapp

import com.woyken.verkadapasstestapp.data.BleKeyPair
import com.woyken.verkadapasstestapp.data.MagicLinkContext
import com.woyken.verkadapasstestapp.data.buildUnlockPayload
import com.woyken.verkadapasstestapp.data.decodeReaderSerial
import com.woyken.verkadapasstestapp.data.decodeReaderSerialFromScanRecord
import com.woyken.verkadapasstestapp.data.decodeVerkadaBeaconDetails
import com.woyken.verkadapasstestapp.data.deriveBeaconPairFromReaderSerial
import com.woyken.verkadapasstestapp.data.hexToByteArray
import com.woyken.verkadapasstestapp.data.toHex
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolCryptoTest {
    @Test
    fun `magic link parsing reads expected parameters`() {
        val link = MagicLinkContext.fromUrl(
            "https://example.test/login?magicToken=abc123&userEmail=user%40example.com&orgShortName=verkada&entityId=42&shard=va1",
        )

        assertEquals("abc123", link.magicToken)
        assertEquals("user@example.com", link.userEmail)
        assertEquals("verkada", link.orgShortName)
        assertEquals("42", link.entityId)
        assertEquals("va1", link.shardName)
    }

    @Test
    fun `decode reader serial recovers APL identifiers`() {
        val manufacturerData = mapOf(0x5041 to "L123456".encodeToByteArray())
        assertEquals("APL123456", decodeReaderSerial(manufacturerData))
    }

    @Test
    fun `decode reader serial recovers dml serial from raw scan record bytes`() {
        val rawScanRecord = ByteArray(9) + "DMLD-HT99-NT7H".encodeToByteArray()
        assertEquals("DMLD-HT99-NT7H", decodeReaderSerialFromScanRecord(rawScanRecord))
        assertEquals("DMLD-HT99-NT7H", decodeReaderSerial(emptyMap(), rawScanRecord))
    }

    @Test
    fun `decode iBeacon payload recovers Verkada beacon details`() {
        val manufacturerData = mapOf(
            0x004C to "0215ac3ef23c70d8477397adb9a566a0fb4000010002c5".hexToByteArray(),
        )

        assertEquals(
            Triple("ac3ef23c-70d8-4773-97ad-b9a566a0fb40", 1, 2),
            decodeVerkadaBeaconDetails(manufacturerData),
        )
    }

    @Test
    fun `reader serial hashes match known beacon pairs`() {
        assertEquals(17620 to 26582, deriveBeaconPairFromReaderSerial("DMLD-HT99-NT7H"))
        assertEquals(49886 to 41906, deriveBeaconPairFromReaderSerial("DMLX-PHHJ-EX9L"))
    }

    @Test
    fun `unlock payload matches python fixture`() {
        val bleKeys = BleKeyPair(
            publicKey = "0e0216223f147143d32615a91189c288c1728cba3cc5f9f621b1026e03d83129".hexToByteArray(),
            privateKey = "cb2f5160fc1f7e05a55ef49d340b48da2e5a78099d53393351cd579dd42503d6".hexToByteArray(),
        )
        val payload = buildUnlockPayload(
            bleKeys = bleKeys,
            readerPublicKey = "99f4674ecc87c0b8e712f192b8f49e7442a9376b4875967ababa28471019a93e".hexToByteArray(),
            readerMessage = "APL123456".encodeToByteArray(),
            userId = "12345678-1234-5678-1234-567812345678",
        )

        assertEquals(
            "0e0216223f147143d32615a91189c288c1728cba3cc5f9f621b1026e03d8312965f194e8d33b7b901205bc17ed29e84e44e0633479f402ed44b0f06fcd8ce25e12345678123456781234567812345678",
            payload.toHex(),
        )
    }
}
