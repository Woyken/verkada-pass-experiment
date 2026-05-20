package com.woyken.verkadapasstestapp.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.woyken.verkadapasstestapp.data.AppLogger
import com.woyken.verkadapasstestapp.data.BLE_AUTH_CHARACTERISTIC_UUID
import com.woyken.verkadapasstestapp.data.BLE_PHONE_ADVERTISED_SERVICE_UUID
import com.woyken.verkadapasstestapp.data.BLE_PHONE_SERVICE_UUID
import com.woyken.verkadapasstestapp.data.BLE_PUBLIC_KEY_CHARACTERISTIC_UUID
import com.woyken.verkadapasstestapp.data.BLE_READER_SERVICE_UUID
import com.woyken.verkadapasstestapp.data.BleDiscoveryResult
import com.woyken.verkadapasstestapp.data.BleKeyPair
import com.woyken.verkadapasstestapp.data.BleScanObservation
import com.woyken.verkadapasstestapp.data.CentralReader
import com.woyken.verkadapasstestapp.data.VERKADA_READER_BEACON_UUID
import com.woyken.verkadapasstestapp.data.buildUnlockPayload
import com.woyken.verkadapasstestapp.data.decodeReaderSerial
import com.woyken.verkadapasstestapp.data.decodeVerkadaBeaconDetails
import com.woyken.verkadapasstestapp.data.looksLikeVerkadaReaderSerial
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BleCentralManager(
    private val context: Context,
    private val logger: AppLogger,
) {
    suspend fun discoverReaders(timeoutMillis: Long = 10_000L): BleDiscoveryResult {
        val filteredObservations = scan(timeoutMillis, BLE_READER_SERVICE_UUID)
        val filteredResult = buildBleDiscoveryResult(filteredObservations, "FD3B callback scan")
        if (filteredResult.readers.isNotEmpty()) {
            logger.info("FD3B callback scan found ${filteredResult.readers.size} reader candidate(s).")
            return filteredResult
        }

        logger.warn("FD3B callback scan found no reader candidates. Running FD3B pending-intent scan.")
        val pendingIntentObservations = pendingIntentScan(timeoutMillis, BLE_READER_SERVICE_UUID)
        val pendingIntentResult = buildBleDiscoveryResult(pendingIntentObservations, "FD3B pending-intent scan")
        if (pendingIntentResult.readers.isNotEmpty()) {
            logger.info("FD3B pending-intent scan found ${pendingIntentResult.readers.size} reader candidate(s).")
            return pendingIntentResult
        }

        logger.warn("FD3B pending-intent scan found no reader candidates. Running unfiltered BLE diagnostics.")
        return buildBleDiscoveryResult(scan(timeoutMillis, null), "unfiltered scan")
    }

    suspend fun unlockReader(
        reader: CentralReader,
        bleKeys: BleKeyPair,
        userId: String,
    ): Int = withContext(Dispatchers.IO) {
        val adapter = requireAdapter()
        val device = adapter.getRemoteDevice(reader.address)
        val callback = ManagedGattCallback(logger)
        logger.info("Connecting to ${reader.address} for reader serial ${reader.readerSerial}.")

        @Suppress("DEPRECATION")
        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Failed to open a GATT connection to ${reader.address}.")

        try {
            withTimeout(15_000L) { callback.connected.await() }
            logger.info("Connected to ${reader.address}. Discovering services.")

            if (!gatt.discoverServices()) {
                throw IOException("Failed to start GATT service discovery for ${reader.address}.")
            }
            withTimeout(15_000L) { callback.servicesDiscovered.await() }

            val service = gatt.getService(UUID.fromString(BLE_READER_SERVICE_UUID))
                ?: throw IOException("Reader service $BLE_READER_SERVICE_UUID not found on ${reader.address}.")

            val publicKeyCharacteristic = service.getCharacteristic(UUID.fromString(BLE_PUBLIC_KEY_CHARACTERISTIC_UUID))
                ?: throw IOException("Reader public-key characteristic was not found.")
            val authCharacteristic = service.getCharacteristic(UUID.fromString(BLE_AUTH_CHARACTERISTIC_UUID))
                ?: throw IOException("Reader auth characteristic was not found.")

            val mtuResult = callback.startMtuRequest()
            if (gatt.requestMtu(185)) {
                val mtu = withTimeout(10_000L) { mtuResult.await() }
                logger.info("Negotiated BLE MTU $mtu with ${reader.address}.")
                if (mtu < 83) {
                    throw IOException("BLE MTU $mtu is too small for the 80-byte unlock payload.")
                }
            } else {
                logger.warn("Failed to request a larger MTU; writes may fail if the default MTU is too small.")
            }

            val publicKeyResult = callback.startCharacteristicRead()
            if (!gatt.readCharacteristic(publicKeyCharacteristic)) {
                throw IOException("Failed to start a read for ${publicKeyCharacteristic.uuid}.")
            }
            val readerPublicKey = withTimeout(10_000L) { publicKeyResult.await() }
            logger.info("Read ${readerPublicKey.size}-byte reader public key from ${reader.address}.")

            val payload = buildUnlockPayload(
                bleKeys = bleKeys,
                readerPublicKey = readerPublicKey,
                readerMessage = reader.readerSerial.toByteArray(Charsets.UTF_8),
                userId = userId,
            )
            logger.info("Writing ${payload.size}-byte BLE unlock payload to ${reader.address}.")

            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 33) {
                val status = gatt.writeCharacteristic(
                    authCharacteristic,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                )
                if (status != BluetoothStatusCodes.SUCCESS) {
                    throw IOException("Failed to queue the unlock payload write (status=$status).")
                }
            } else {
                authCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                authCharacteristic.value = payload
                if (!gatt.writeCharacteristic(authCharacteristic)) {
                    throw IOException("Failed to queue the unlock payload write.")
                }
            }

            delay(500L)
            logger.info("BLE unlock payload sent to ${reader.name} (${reader.address}).")
            payload.size
        } finally {
            try {
                gatt.disconnect()
            } catch (_: Throwable) {
            }
            gatt.close()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun pendingIntentScan(
        timeoutMillis: Long,
        serviceUuid: String,
    ): List<Pair<BluetoothDevice, BleScanObservation>> = suspendCancellableCoroutine { continuation ->
        val scanner = requireScanner()
        val results = linkedMapOf<String, Pair<BluetoothDevice, BleScanObservation>>()
        val handler = Handler(Looper.getMainLooper())
        val action = "${context.packageName}.action.FD3B_SCAN_RESULT.${SystemClock.elapsedRealtimeNanos()}"
        val scanIntent = Intent(action).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            scanIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        var receiverRegistered = false
        var finished = false
        lateinit var receiver: BroadcastReceiver

        fun cleanup() {
            try {
                scanner.stopScan(pendingIntent)
            } catch (_: Throwable) {
            }
            pendingIntent.cancel()
            if (receiverRegistered) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Throwable) {
                }
            }
        }

        fun finishWithError(error: Throwable) {
            if (finished) {
                return
            }
            finished = true
            cleanup()
            continuation.resumeWithException(error)
        }

        val timeoutRunnable = Runnable {
            if (finished) {
                return@Runnable
            }
            finished = true
            cleanup()
            continuation.resume(results.values.toList())
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val errorCode = intent.getIntExtra(SCAN_ERROR_CODE_EXTRA, -1)
                if (errorCode != -1) {
                    handler.removeCallbacksAndMessages(null)
                    finishWithError(IOException("Pending-intent BLE scan failed with error code $errorCode."))
                    return
                }

                val batchResults = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(SCAN_RESULT_LIST_EXTRA, ScanResult::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(SCAN_RESULT_LIST_EXTRA)
                }.orEmpty()

                for (result in batchResults) {
                    val observation = result.toObservation()
                    results[result.device.address] = result.device to observation
                    logger.debug(
                        "Pending-intent scanned device: address=${observation.address} name=${observation.name} " +
                            "localName=${observation.localName} rssi=${observation.rssi} " +
                            "services=${observation.serviceUuids} markers=${observation.markers()}",
                    )
                }
            }
        }

        fun registerReceiver() {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }

        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(serviceUuid)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        logger.info("Starting BLE pending-intent scan with service filter [$serviceUuid] (timeout=${timeoutMillis / 1000.0}s)")

        try {
            registerReceiver()
            scanner.startScan(filters, settings, pendingIntent)
        } catch (error: Throwable) {
            cleanup()
            continuation.resumeWithException(error)
            return@suspendCancellableCoroutine
        }

        handler.postDelayed(timeoutRunnable, timeoutMillis)
        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable)
            cleanup()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun scan(
        timeoutMillis: Long,
        serviceUuid: String?,
    ): List<Pair<BluetoothDevice, BleScanObservation>> = suspendCancellableCoroutine { continuation ->
        val scanner = requireScanner()
        val results = linkedMapOf<String, Pair<BluetoothDevice, BleScanObservation>>()
        val handler = Handler(Looper.getMainLooper())

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val observation = result.toObservation()
                results[result.device.address] = result.device to observation
                logger.debug(
                    "Scanned device: address=${observation.address} name=${observation.name} " +
                        "localName=${observation.localName} rssi=${observation.rssi} " +
                        "services=${observation.serviceUuids} markers=${observation.markers()}",
                )
            }

            override fun onBatchScanResults(batchResults: MutableList<ScanResult>) {
                batchResults.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                continuation.resumeWithException(IOException("BLE scan failed with error code $errorCode."))
            }
        }

        val stopScan = {
            try {
                scanner.stopScan(callback)
            } catch (_: Throwable) {
            }
        }

        val filters = serviceUuid?.let {
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(it)).build())
        }.orEmpty()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        logger.info(
            "Starting BLE scan${serviceUuid?.let { " with service filter [$it]" }.orEmpty()} " +
                "(timeout=${timeoutMillis / 1000.0}s)",
        )

        try {
            scanner.startScan(filters, settings, callback)
        } catch (error: Throwable) {
            continuation.resumeWithException(error)
            return@suspendCancellableCoroutine
        }

        val timeoutRunnable = Runnable {
            stopScan()
            continuation.resume(results.values.toList())
        }
        handler.postDelayed(timeoutRunnable, timeoutMillis)

        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeoutRunnable)
            stopScan()
        }
    }

    private fun buildBleDiscoveryResult(
        observations: List<Pair<BluetoothDevice, BleScanObservation>>,
        sourceLabel: String,
    ): BleDiscoveryResult {
        val readers = mutableListOf<CentralReader>()
        val interesting = mutableListOf<BleScanObservation>()
        var fd3bServiceCount = 0
        var fd3aServiceCount = 0
        var a4ddServiceCount = 0
        var verkadaBeaconCount = 0
        var serialCandidateCount = 0

        for ((_, observation) in observations) {
            val hasFd3b = BLE_READER_SERVICE_UUID.lowercase() in observation.serviceUuids
            val hasFd3a = BLE_PHONE_SERVICE_UUID.lowercase() in observation.serviceUuids
            val hasA4dd = BLE_PHONE_ADVERTISED_SERVICE_UUID.lowercase() in observation.serviceUuids
            val hasBeacon = observation.beaconUuid == VERKADA_READER_BEACON_UUID

            if (hasFd3b) fd3bServiceCount += 1
            if (hasFd3a) fd3aServiceCount += 1
            if (hasA4dd) a4ddServiceCount += 1
            if (hasBeacon) verkadaBeaconCount += 1
            if (!observation.readerSerial.isNullOrBlank()) serialCandidateCount += 1

            if (hasFd3b || hasFd3a || hasA4dd || hasBeacon || !observation.readerSerial.isNullOrBlank()) {
                interesting += observation
            }

            val serial = observation.readerSerial ?: continue
            if (!looksLikeVerkadaReaderSerial(serial) && !hasFd3b) {
                continue
            }
            readers += CentralReader(
                address = observation.address,
                name = observation.name ?: observation.localName ?: observation.address,
                readerSerial = serial,
                rssi = observation.rssi,
            )
        }

        return BleDiscoveryResult(
            sourceLabel = sourceLabel,
            totalEntries = observations.size,
            readers = readers,
            observations = interesting,
            fd3bServiceCount = fd3bServiceCount,
            fd3aServiceCount = fd3aServiceCount,
            a4ddServiceCount = a4ddServiceCount,
            verkadaBeaconCount = verkadaBeaconCount,
            serialCandidateCount = serialCandidateCount,
        )
    }

    private fun ScanResult.toObservation(): BleScanObservation {
        val scanRecord = scanRecord
        val manufacturerData = scanRecord.manufacturerData()
        val beaconDetails = decodeVerkadaBeaconDetails(manufacturerData)
        return BleScanObservation(
            address = device.address,
            name = device.name,
            localName = scanRecord?.deviceName,
            serviceUuids = scanRecord?.serviceUuids?.map { it.uuid.toString().lowercase() }.orEmpty(),
            rssi = rssi,
            readerSerial = decodeReaderSerial(
                manufacturerData = manufacturerData,
                rawScanRecordBytes = scanRecord?.bytes,
            ),
            beaconUuid = beaconDetails?.first,
            beaconMajor = beaconDetails?.second,
            beaconMinor = beaconDetails?.third,
        )
    }

    private fun ScanRecord?.manufacturerData(): Map<Int, ByteArray> {
        if (this == null) {
            return emptyMap()
        }
        val result = mutableMapOf<Int, ByteArray>()
        val sparseArray = manufacturerSpecificData
        for (index in 0 until sparseArray.size()) {
            result[sparseArray.keyAt(index)] = sparseArray.valueAt(index)
        }
        return result
    }

    private fun requireScanner(): BluetoothLeScanner {
        val adapter = requireAdapter()
        if (!adapter.isEnabled) {
            throw IOException("Bluetooth is disabled. Enable Bluetooth and try again.")
        }
        return adapter.bluetoothLeScanner ?: throw IOException("Bluetooth LE scanner is unavailable on this device.")
    }

    private fun requireAdapter(): BluetoothAdapter {
        val manager = ContextCompat.getSystemService(context, BluetoothManager::class.java)
            ?: throw IOException("BluetoothManager is unavailable.")
        return manager.adapter ?: throw IOException("Bluetooth is not supported on this device.")
    }

    companion object {
        private const val SCAN_RESULT_LIST_EXTRA = "android.bluetooth.le.extra.LIST_SCAN_RESULT"
        private const val SCAN_ERROR_CODE_EXTRA = "android.bluetooth.le.extra.ERROR_CODE"

        val runtimePermissions: Array<String> = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        fun hasRequiredPermissions(context: Context): Boolean =
            runtimePermissions.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
    }
}

private class ManagedGattCallback(
    private val logger: AppLogger,
) : BluetoothGattCallback() {
    val connected = CompletableDeferred<Unit>()
    val servicesDiscovered = CompletableDeferred<Unit>()
    private var mtuRequest = CompletableDeferred<Int>()
    private var characteristicRead = CompletableDeferred<ByteArray>()

    fun startMtuRequest(): CompletableDeferred<Int> {
        mtuRequest = CompletableDeferred()
        return mtuRequest
    }

    fun startCharacteristicRead(): CompletableDeferred<ByteArray> {
        characteristicRead = CompletableDeferred()
        return characteristicRead
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        logger.info("onConnectionStateChange address=${gatt.device.address} status=$status newState=$newState")
        if (status != BluetoothGatt.GATT_SUCCESS) {
            val error = IOException("GATT connection failed with status=$status.")
            completeExceptionally(error)
            return
        }
        if (newState == BluetoothProfile.STATE_CONNECTED && !connected.isCompleted) {
            connected.complete(Unit)
            return
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED && !connected.isCompleted) {
            completeExceptionally(IOException("Disconnected before BLE setup completed."))
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        logger.info("onServicesDiscovered address=${gatt.device.address} status=$status services=${gatt.services.size}")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            servicesDiscovered.complete(Unit)
        } else {
            completeExceptionally(IOException("Service discovery failed with status=$status."))
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        logger.info("onMtuChanged address=${gatt.device.address} mtu=$mtu status=$status")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            mtuRequest.complete(mtu)
        } else if (!mtuRequest.isCompleted) {
            mtuRequest.completeExceptionally(IOException("MTU request failed with status=$status."))
        }
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        logger.info(
            "onCharacteristicRead address=${gatt.device.address} characteristic=${characteristic.uuid} " +
                "status=$status bytes=${value.size}",
        )
        if (status == BluetoothGatt.GATT_SUCCESS) {
            characteristicRead.complete(value)
        } else if (!characteristicRead.isCompleted) {
            characteristicRead.completeExceptionally(IOException("Characteristic read failed with status=$status."))
        }
    }

    @Deprecated("Deprecated in API 33")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (Build.VERSION.SDK_INT >= 33) {
            return
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            characteristicRead.complete(characteristic.value ?: ByteArray(0))
        } else if (!characteristicRead.isCompleted) {
            characteristicRead.completeExceptionally(IOException("Characteristic read failed with status=$status."))
        }
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            logger.warn("Descriptor write failed for ${descriptor.uuid} with status=$status.")
        }
    }

    private fun completeExceptionally(error: Throwable) {
        if (!connected.isCompleted) connected.completeExceptionally(error)
        if (!servicesDiscovered.isCompleted) servicesDiscovered.completeExceptionally(error)
        if (!mtuRequest.isCompleted) mtuRequest.completeExceptionally(error)
        if (!characteristicRead.isCompleted) characteristicRead.completeExceptionally(error)
    }
}
