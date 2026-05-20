package com.woyken.verkadapasstestapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.woyken.verkadapasstestapp.data.AppLogger
import com.woyken.verkadapasstestapp.data.BLE_AUTH_CHARACTERISTIC_UUID
import com.woyken.verkadapasstestapp.data.BLE_PHONE_ADVERTISED_SERVICE_UUID
import com.woyken.verkadapasstestapp.data.BLE_PHONE_SERVICE_UUID
import com.woyken.verkadapasstestapp.data.BLE_PUBLIC_KEY_CHARACTERISTIC_UUID
import com.woyken.verkadapasstestapp.data.BleKeyPair
import com.woyken.verkadapasstestapp.data.buildUnlockPayload
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/**
 * Phone-side BLE peripheral that advertises FD3A + A4DD and serves a GATT FD3A service so the
 * Verkada reader can connect to the phone, write its public key + serial to characteristic 1001,
 * and read the computed 80-byte unlock payload from characteristic 2000.
 *
 * Characteristic layout (from APK op/e.java line 133-134):
 *   char 2000: PROPERTY_READ  (0x02), PERMISSION_READ  (0x01) — phone payload, reader reads this
 *   char 1001: PROPERTY_WRITE_NO_RESPONSE (0x04), PERMISSION_WRITE (0x10) — reader writes pubkey+serial
 *   Service: FD3A; order: 2000 added first, then 1001 (matches APK addCharacteristic order)
 *
 * Protocol (reader-to-phone direction):
 *   reader → WRITE_NO_RESPONSE to 1001: [reader public key 32 bytes][reader serial UTF-8]
 *   reader → READ from 2000: [phone public key 32][auth tag 32][user UUID 16]
 *
 * Race-condition mitigation:
 *   The reader connects within ~10ms of openGattServer() being called (it caches our BT address).
 *   The FD3A service is not registered until ~16ms later (onServiceAdded callback). During that
 *   window the reader does GATT discovery and finds an empty database.
 *
 *   Fix: we add a Generic Attribute service (0x1801) with a Service Changed characteristic
 *   (0x2A05) BEFORE the FD3A service. When the reader connects, it discovers Generic Attribute
 *   and may subscribe to Service Changed. When FD3A is then added, we send a Service Changed
 *   indication so the reader re-discovers and finds FD3A. We also buffer any write from the
 *   reader that arrives before keys are configured (e.g., when the server is pre-opened at
 *   ViewModel startup) and process them once keys are set.
 */
@SuppressLint("MissingPermission")
class BlePeripheralManager(
    private val context: Context,
    private val logger: AppLogger,
) {
    // BLE UUIDs for Generic Attribute service and Service Changed characteristic
    private val GENERIC_ATTR_SERVICE_UUID = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
    private val SERVICE_CHANGED_CHAR_UUID = UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CHAR_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter

    private var activeAdvertiseCallback: AdvertiseCallback? = null
    private var activeGattServer: BluetoothGattServer? = null
    private var authCharacteristic: BluetoothGattCharacteristic? = null
    private var serviceChangedCharacteristic: BluetoothGattCharacteristic? = null

    // Set to true only after the FD3A service (not just Generic Attribute) is registered.
    private var fd3aServiceReady = false

    // Keyed from the ViewModel before starting so we can compute payloads on demand.
    private var bleKeys: BleKeyPair? = null
    private var userId: String? = null

    // Per-device pending payloads — computed once 1001 write arrives, returned on 2000 read.
    private val pendingPayloads = mutableMapOf<String, ByteArray>()

    // Writes buffered when they arrive before keys are configured (e.g., pre-opened server).
    private val pendingRawWrites = mutableMapOf<String, ByteArray>()

    // Accumulates prepared-write chunks (ATT PREPARE_WRITE_REQ) until onExecuteWrite.
    // Matches APK op/d.java f13500a LinkedHashMap.
    private val preparedWriteBuffers = mutableMapOf<String, ByteArray>()

    // Devices that connected before FD3A service was registered — need Service Changed indication.
    private val earlyConnectedDevices = mutableSetOf<String>()

    // Devices that have subscribed to Service Changed (wrote CCCD 0x0002).
    private val serviceChangedSubscribers = mutableSetOf<String>()

    // Fires when the reader has written its pubkey+serial and the payload is computed and ready.
    var onPayloadComputed: ((readerAddress: String, readerSerial: String) -> Unit)? = null

    /**
     * Pre-open the GATT server (Generic Attribute + FD3A services registered) without setting
     * BLE keys. Call this at ViewModel startup to ensure the server is ready long before any
     * connection attempt. Keys must be set later via configure() or setKeys().
     */
    suspend fun prepareGattServer() {
        if (activeGattServer != null) {
            logger.info("BLE peripheral GATT server already running.")
            return
        }
        openGattServer()
    }

    /**
     * Set BLE keys and userId, then ensure the GATT server is open.
     * Also processes any writes buffered while keys were not yet set.
     */
    suspend fun configure(bleKeys: BleKeyPair, userId: String) {
        this.bleKeys = bleKeys
        this.userId = userId
        logger.info("BLE peripheral configured with key ${bleKeys.fingerprint} for userId $userId.")
        openGattServer()
        // Process any writes that arrived while keys were not configured (pre-opened server case).
        processBufferedWrites()
    }

    fun startAdvertising() {
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            logger.warn("BLE LE advertising is not supported on this adapter.")
            return
        }
        if (activeAdvertiseCallback != null) {
            logger.info("BLE peripheral advertising is already running.")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid.fromString(BLE_PHONE_SERVICE_UUID))
            .addServiceUuid(ParcelUuid.fromString(BLE_PHONE_ADVERTISED_SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(true)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                logger.info("BLE peripheral advertising started (FD3A + A4DD, connectable).")
            }

            override fun onStartFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                    ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                    else -> "errorCode=$errorCode"
                }
                logger.warn("BLE peripheral advertising failed to start: $reason.")
                activeAdvertiseCallback = null
            }
        }
        activeAdvertiseCallback = callback
        advertiser.startAdvertising(settings, advertiseData, callback)
    }

    fun stopAdvertising() {
        val cb = activeAdvertiseCallback ?: return
        try {
            adapter.bluetoothLeAdvertiser?.stopAdvertising(cb)
            logger.info("BLE peripheral advertising stopped.")
        } catch (e: Throwable) {
            logger.warn("Error stopping BLE advertising: ${e.message}")
        }
        activeAdvertiseCallback = null
    }

    fun closeGattServer() {
        activeGattServer?.let { server ->
            try {
                server.close()
                logger.info("BLE peripheral GATT server closed.")
            } catch (e: Throwable) {
                logger.warn("Error closing GATT server: ${e.message}")
            }
            activeGattServer = null
            authCharacteristic = null
            serviceChangedCharacteristic = null
            fd3aServiceReady = false
            pendingPayloads.clear()
            preparedWriteBuffers.clear()
            earlyConnectedDevices.clear()
            serviceChangedSubscribers.clear()
        }
    }

    val isAdvertising: Boolean get() = activeAdvertiseCallback != null

    /**
     * Opens the GATT server with two phases:
     *   Phase A: Generic Attribute service (0x1801) with Service Changed (0x2A05) — gives the
     *            reader something to discover immediately and lets it subscribe to Service Changed.
     *   Phase B: FD3A service with chars 2000 + 1001 — the actual unlock service.
     *
     * After Phase B completes, any already-connected reader that has subscribed to Service Changed
     * receives an indication so it re-discovers and finds the FD3A service. If the reader has not
     * yet subscribed, the indication is sent as soon as the CCCD write arrives.
     */
    private suspend fun openGattServer() {
        if (activeGattServer != null) {
            logger.info("BLE peripheral GATT server already running.")
            return
        }

        val phaseADeferred = CompletableDeferred<Boolean>()
        val phaseBDeferred = CompletableDeferred<Boolean>()

        val gattCallback = object : BluetoothGattServerCallback() {

            override fun onConnectionStateChange(
                device: android.bluetooth.BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                val stateStr = when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                    BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                    else -> "state=$newState"
                }
                logger.info(
                    "BLE peripheral GATT: device ${device.address} $stateStr " +
                        "(status=$status, fd3aReady=$fd3aServiceReady).",
                )
                if (newState == BluetoothProfile.STATE_CONNECTED && !fd3aServiceReady) {
                    earlyConnectedDevices.add(device.address)
                    logger.warn(
                        "BLE peripheral: ${device.address} connected before FD3A service ready — " +
                            "will send Service Changed indication after FD3A is registered.",
                    )
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    earlyConnectedDevices.remove(device.address)
                    serviceChangedSubscribers.remove(device.address)
                }
            }

            override fun onCharacteristicReadRequest(
                device: android.bluetooth.BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (offset == 0) {
                    logger.info(
                        "BLE peripheral GATT: READ from ${device.address} char=${characteristic.uuid} offset=$offset.",
                    )
                } else {
                    logger.debug(
                        "BLE peripheral GATT: READ from ${device.address} char=${characteristic.uuid} offset=$offset.",
                    )
                }
                val payload = pendingPayloads[device.address]
                if (payload == null) {
                    // Return 1-byte 0x00 sentinel so the reader knows the payload is not ready yet.
                    // The reader should retry the read (same pattern as APK op/d.java).
                    logger.warn("BLE peripheral: no payload for ${device.address} — returning 1-byte not-ready sentinel.")
                    activeGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(0x00))
                    return
                }
                val slice = if (offset < payload.size) payload.copyOfRange(offset, payload.size) else ByteArray(0)
                activeGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            }

            override fun onCharacteristicWriteRequest(
                device: android.bluetooth.BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                val hex = value?.joinToString("") { "%02x".format(it.toInt() and 0xff) } ?: ""
                logger.debug(
                    "BLE peripheral GATT: WRITE from ${device.address} char=${characteristic.uuid} " +
                        "len=${value?.size} prepared=$preparedWrite responseNeeded=$responseNeeded offset=$offset hex=$hex.",
                )
                logger.info(
                    "BLE peripheral GATT: WRITE from ${device.address} char=${characteristic.uuid} " +
                        "len=${value?.size} prepared=$preparedWrite responseNeeded=$responseNeeded offset=$offset.",
                )

                if (characteristic.uuid == UUID.fromString(BLE_PUBLIC_KEY_CHARACTERISTIC_UUID) && value != null) {
                    // Accumulate all writes into preparedWriteBuffers — matches APK op/d.java f13500a map.
                    val existing = preparedWriteBuffers[device.address] ?: ByteArray(0)
                    preparedWriteBuffers[device.address] = existing + value

                    if (!preparedWrite) {
                        // Single WRITE_NO_RESPONSE: current value is the complete payload.
                        // APK op/d.java line 188-190: calls a(device, bArr) with current chunk.
                        if (value.size >= 32) {
                            handleOrBufferReaderWrite(device.address, value)
                        } else {
                            logger.warn(
                                "BLE peripheral: direct write too short (${value.size} < 32) from ${device.address} — ignoring.",
                            )
                        }
                    }
                    // For preparedWrite=true: accumulate only — processing happens in onExecuteWrite.
                }

                if (responseNeeded) {
                    // APK op/d.java line 195: echo offset + value back for prepared writes.
                    activeGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }

            override fun onDescriptorWriteRequest(
                device: android.bluetooth.BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                val hex = value?.joinToString("") { "%02x".format(it.toInt() and 0xff) } ?: ""
                logger.info(
                    "BLE peripheral GATT: DESCRIPTOR WRITE from ${device.address} " +
                        "char=${descriptor.characteristic?.uuid} desc=${descriptor.uuid} hex=$hex.",
                )
                // Detect when reader subscribes to Service Changed (CCCD = 0x0002 = ENABLE_INDICATION).
                if (descriptor.uuid == CLIENT_CHAR_CONFIG_UUID &&
                    descriptor.characteristic?.uuid == SERVICE_CHANGED_CHAR_UUID
                ) {
                    val isIndication = value != null && value.size >= 2 && value[0] == 0x02.toByte() && value[1] == 0x00.toByte()
                    val isDisabled = value != null && value.size >= 2 && value[0] == 0x00.toByte() && value[1] == 0x00.toByte()
                    when {
                        isIndication -> {
                            serviceChangedSubscribers.add(device.address)
                            logger.info("BLE peripheral: ${device.address} subscribed to Service Changed.")
                            // If FD3A was already added while this device was connecting,
                            // send Service Changed now that the device has subscribed.
                            if (fd3aServiceReady && device.address in earlyConnectedDevices) {
                                logger.info("BLE peripheral: sending Service Changed indication to ${device.address} (late subscribe).")
                                sendServiceChangedIndication(device)
                            }
                        }
                        isDisabled -> {
                            serviceChangedSubscribers.remove(device.address)
                            logger.info("BLE peripheral: ${device.address} unsubscribed from Service Changed.")
                        }
                        else -> logger.warn("BLE peripheral: unknown CCCD value from ${device.address}: $hex")
                    }
                }

                if (responseNeeded) {
                    activeGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }

            override fun onExecuteWrite(
                device: android.bluetooth.BluetoothDevice,
                requestId: Int,
                execute: Boolean,
            ) {
                logger.info("BLE peripheral GATT: EXECUTE WRITE from ${device.address} execute=$execute.")
                // APK op/d.java onExecuteWrite: process accumulated prepared-write buffer when execute=true.
                val accumulated = preparedWriteBuffers.remove(device.address)
                if (execute && accumulated != null && accumulated.size >= 32) {
                    logger.info(
                        "BLE peripheral: processing prepared-write buffer " +
                            "(${accumulated.size} bytes) from ${device.address}.",
                    )
                    handleOrBufferReaderWrite(device.address, accumulated)
                }
                // APK op/d.java line 235: send response echoing the accumulated buffer.
                activeGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, accumulated)
            }

            override fun onMtuChanged(device: android.bluetooth.BluetoothDevice, mtu: Int) {
                logger.info("BLE peripheral GATT: MTU changed for ${device.address} → $mtu bytes.")
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                logger.info("BLE peripheral GATT: onServiceAdded status=$status uuid=${service?.uuid}.")
                when (service?.uuid) {
                    GENERIC_ATTR_SERVICE_UUID -> phaseADeferred.complete(status == 0)
                    UUID.fromString(BLE_PHONE_SERVICE_UUID) -> {
                        fd3aServiceReady = (status == 0)
                        phaseBDeferred.complete(status == 0)
                        // Notify any early-connected devices that are already subscribed.
                        if (fd3aServiceReady) {
                            for (addr in earlyConnectedDevices.toSet()) {
                                if (addr in serviceChangedSubscribers) {
                                    logger.info("BLE peripheral: sending Service Changed indication to $addr (already subscribed).")
                                    try {
                                        sendServiceChangedIndication(adapter.getRemoteDevice(addr))
                                    } catch (e: Throwable) {
                                        logger.warn("BLE peripheral: Service Changed indication failed for $addr: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                    else -> logger.warn("BLE peripheral GATT: unexpected service UUID ${service?.uuid} in onServiceAdded.")
                }
            }
        }

        val server = bluetoothManager.openGattServer(context, gattCallback) ?: run {
            logger.warn("Failed to open BLE GATT server.")
            return
        }
        activeGattServer = server

        // Phase A: Generic Attribute service (0x1801) with Service Changed (0x2A05).
        // This service must be registered BEFORE FD3A. The reader will discover it on first
        // connection and (if BLE-compliant) subscribe to Service Changed.
        val genericAttrService = BluetoothGattService(
            GENERIC_ATTR_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val svcChangedChar = BluetoothGattCharacteristic(
            SERVICE_CHANGED_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_INDICATE, // 0x20
            0, // no direct permission — access via CCCD
        )
        val cccdDescriptor = BluetoothGattDescriptor(
            CLIENT_CHAR_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        svcChangedChar.addDescriptor(cccdDescriptor)
        genericAttrService.addCharacteristic(svcChangedChar)
        server.addService(genericAttrService)

        val phaseA = phaseADeferred.await()
        logger.info("BLE peripheral GATT: Generic Attribute service registered (ok=$phaseA).")
        serviceChangedCharacteristic = svcChangedChar

        // Phase B: FD3A service — matches APK op/e.java l() exactly.
        val fd3aService = BluetoothGattService(
            UUID.fromString(BLE_PHONE_SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val readChar = BluetoothGattCharacteristic(
            UUID.fromString(BLE_AUTH_CHARACTERISTIC_UUID),
            BluetoothGattCharacteristic.PROPERTY_READ,            // 2
            BluetoothGattCharacteristic.PERMISSION_READ,          // 1
        )
        val writeChar = BluetoothGattCharacteristic(
            UUID.fromString(BLE_PUBLIC_KEY_CHARACTERISTIC_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, // 4
            BluetoothGattCharacteristic.PERMISSION_WRITE,           // 16
        )
        fd3aService.addCharacteristic(readChar)  // 2000 first — matches APK
        fd3aService.addCharacteristic(writeChar) // 1001 second — matches APK
        server.addService(fd3aService)
        authCharacteristic = readChar

        val phaseB = phaseBDeferred.await()
        logger.info(
            "BLE peripheral GATT server fully ready (FD3A ok=$phaseB, key=${bleKeys?.fingerprint}).",
        )
    }

    /** Send a Service Changed indication covering the full handle range to a specific device. */
    @Suppress("DEPRECATION")
    private fun sendServiceChangedIndication(device: android.bluetooth.BluetoothDevice) {
        val char = serviceChangedCharacteristic ?: return
        val server = activeGattServer ?: return
        // Service Changed value = start handle (2 bytes LE) + end handle (2 bytes LE).
        // Use 0x0001–0xFFFF to signal the entire GATT database has changed.
        val value = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, char, true, value)
        } else {
            char.value = value
            server.notifyCharacteristicChanged(device, char, true)
        }
    }

    /** Handle a reader write, or buffer it if keys are not yet configured. */
    private fun handleOrBufferReaderWrite(readerAddress: String, data: ByteArray) {
        if (bleKeys == null || userId == null) {
            logger.info("BLE peripheral: keys not set yet — buffering write from $readerAddress (${data.size} bytes).")
            pendingRawWrites[readerAddress] = data
        } else {
            handleReaderWrite(readerAddress, data)
        }
    }

    /** Process writes buffered before keys were set. Called from configure(). */
    private fun processBufferedWrites() {
        val buffered = pendingRawWrites.toMap()
        pendingRawWrites.clear()
        for ((addr, data) in buffered) {
            logger.info("BLE peripheral: processing buffered write from $addr (${data.size} bytes).")
            handleReaderWrite(addr, data)
        }
    }

    /**
     * Reader wrote its public key + serial to characteristic 1001.
     * APK op/d.java a(): splits data at byte 32 → [reader pubkey][reader serial UTF-8],
     * computes auth tag, concatenates [phone pubkey][auth tag][userId UUID big-endian].
     */
    private fun handleReaderWrite(readerAddress: String, data: ByteArray) {
        val keys = bleKeys
        val uid = userId
        if (keys == null || uid == null) {
            logger.warn("BLE peripheral: received reader write but keys/userId not configured.")
            return
        }

        val readerPublicKey = data.copyOfRange(0, 32)
        // APK op/d.java: raw bytes from position 32 onward are the serial, passed as-is to crypto_auth.
        val readerSerialRaw = if (data.size > 32) data.copyOfRange(32, data.size) else ByteArray(0)
        val readerSerial = readerSerialRaw.toString(Charsets.UTF_8).trimEnd('\u0000', ' ')
        logger.info(
            "BLE peripheral: reader $readerAddress sent pubkey=${
                readerPublicKey.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            } serial='$readerSerial' (${readerSerialRaw.size} raw bytes).",
        )

        try {
            val payload = buildUnlockPayload(
                bleKeys = keys,
                readerPublicKey = readerPublicKey,
                readerMessage = readerSerialRaw, // pass raw bytes to match APK crypto_auth exactly
                userId = uid,
            )
            pendingPayloads[readerAddress] = payload
            logger.info(
                "BLE peripheral: computed and stored ${payload.size}-byte unlock payload for $readerAddress " +
                    "(serial='$readerSerial').",
            )
            onPayloadComputed?.invoke(readerAddress, readerSerial)
        } catch (e: Throwable) {
            logger.error("BLE peripheral: failed to build unlock payload.", e)
        }
    }
}
