package com.woyken.verkadapasstestapp

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.woyken.verkadapasstestapp.ble.BleCentralManager
import com.woyken.verkadapasstestapp.ble.BlePeripheralManager
import com.woyken.verkadapasstestapp.data.AppConfigData
import com.woyken.verkadapasstestapp.data.AppLogger
import com.woyken.verkadapasstestapp.data.AppStorage
import com.woyken.verkadapasstestapp.data.BLE_KEY_TYPE
import com.woyken.verkadapasstestapp.data.BleDiscoveryResult
import com.woyken.verkadapasstestapp.data.BleKeyPair
import com.woyken.verkadapasstestapp.data.BleRegistrationInfo
import com.woyken.verkadapasstestapp.data.CentralReader
import com.woyken.verkadapasstestapp.data.DoorRecord
import com.woyken.verkadapasstestapp.data.MagicLinkContext
import com.woyken.verkadapasstestapp.data.SessionState
import com.woyken.verkadapasstestapp.data.VerkadaApi
import com.woyken.verkadapasstestapp.data.buildBleRegistrationRequest
import com.woyken.verkadapasstestapp.data.deriveBeaconPairFromReaderSerial
import com.woyken.verkadapasstestapp.data.generateBleKeyPair
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class AppUiState(
    val email: String = "",
    val orgShortName: String = "",
    val magicLinkUrl: String = "",
    val unlockMethod: String = "mobile",
    val bleUserId: String = "",
    val session: SessionState? = null,
    val doors: List<DoorRecord> = emptyList(),
    val bleReaders: List<CentralReader> = emptyList(),
    val bleDiscoverySummary: List<String> = emptyList(),
    val statusMessage: String = "Ready.",
    val isBusy: Boolean = false,
    val latestLogPath: String = "",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = AppStorage(application)
    private val logger = AppLogger(application)
    private val api = VerkadaApi(logger)
    private val bleManager = BleCentralManager(application, logger)
    private val blePeripheral = BlePeripheralManager(application, logger)
    private val _uiState = MutableStateFlow(AppUiState())

    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    val logLines = logger.lines

    init {
        val config = storage.loadConfig()
        val session = storage.loadSession()
        if (session != null) {
            logger.info("Loaded saved session for ${session.email}.")
        }
        _uiState.value = AppUiState(
            email = config.email,
            orgShortName = config.orgShortName,
            unlockMethod = config.unlockMethod,
            bleUserId = config.bleUserId,
            session = session,
            statusMessage = if (session == null) {
                "Paste a magic link or request one by email."
            } else {
                "Loaded saved session for ${session.email}."
            },
            latestLogPath = logger.currentLogFile().absolutePath,
        )

        // Pre-open the GATT server immediately at startup so the FD3A service is registered
        // long before any BLE unlock attempt. The Verkada reader caches our BT address and
        // connects within ~10ms of openGattServer() — earlier the server is opened, the higher
        // the chance the service is ready before the reader does GATT discovery.
        viewModelScope.launch {
            blePeripheral.prepareGattServer()
        }
    }

    fun updateEmail(value: String) = updateConfigState { copy(email = value) }

    fun updateOrgShortName(value: String) = updateConfigState { copy(orgShortName = value) }

    fun updateMagicLinkUrl(value: String) {
        _uiState.value = _uiState.value.copy(magicLinkUrl = value)
    }

    fun updateUnlockMethod(value: String) = updateConfigState { copy(unlockMethod = value) }

    fun updateBleUserId(value: String) = updateConfigState { copy(bleUserId = value) }

    fun requestMagicLink() = launchTask("Requesting a magic link") {
        val state = _uiState.value
        require(state.email.isNotBlank()) { "Enter an email first." }
        api.requestMagicLink(state.email.trim(), state.orgShortName.trim().ifBlank { null })
        updateStatus("Magic link requested. Paste the full URL from your email into the field above.")
    }

    fun redeemMagicLink() = launchTask("Redeeming a magic link") {
        val link = MagicLinkContext.fromUrl(_uiState.value.magicLinkUrl)
        val session = api.redeemMagicLink(link)
        storage.saveSession(session)
        logger.info("Saved session for ${session.email}.")
        val doors = api.listDoors(session)
        _uiState.value = _uiState.value.copy(
            magicLinkUrl = "",
            session = session,
            doors = doors,
            statusMessage = "Signed in as ${session.email}. Loaded ${doors.size} door(s).",
            latestLogPath = logger.currentLogFile().absolutePath,
        )
    }

    fun logout() = launchTask("Clearing the saved session") {
        storage.clearSession()
        _uiState.value = _uiState.value.copy(
            session = null,
            doors = emptyList(),
            bleReaders = emptyList(),
            bleDiscoverySummary = emptyList(),
            statusMessage = "Saved session removed.",
            latestLogPath = logger.currentLogFile().absolutePath,
        )
        logger.info("Saved session removed.")
    }

    fun loadDoors() = launchTask("Loading doors") {
        val session = requireSession()
        val doors = api.listDoors(session)
        _uiState.value = _uiState.value.copy(
            doors = doors,
            statusMessage = "Loaded ${doors.size} door(s).",
            latestLogPath = logger.currentLogFile().absolutePath,
        )
    }

    fun unlockDoor(door: DoorRecord) = launchTask("Unlocking ${door.name}") {
        val session = requireSession()
        val unlockMethod = _uiState.value.unlockMethod.ifBlank { "mobile" }
        val result = api.unlockDoor(session, door.accessPointId, unlockMethod)
        updateStatus(
            buildString {
                append("Unlocked ${door.name} with method $unlockMethod")
                result.duration?.let { append(" (${String.format("%.2f", it)}s)") }
            },
        )
    }

    fun scanBleReaders() = launchTask("Scanning nearby BLE readers") {
        blePeripheral.startAdvertising()
        delay(2_000L)
        val result = try {
            bleManager.discoverReaders()
        } finally {
            blePeripheral.stopAdvertising()
        }
        applyDiscovery(result)
    }

    fun unlockReader(reader: CentralReader) = launchTask("Sending BLE unlock to ${reader.name}") {
        val session = requireSession()
        val (bleKeys, createdNewKey) = loadOrCreateBleKeys()
        val registeredKey = ensureBleKeyRegistration(session, bleKeys)
        val userId = _uiState.value.bleUserId.trim().ifBlank { session.userId }
        val payloadSize = bleManager.unlockReader(reader, bleKeys, userId)
        updateStatus(
            "BLE unlock sent to ${reader.name}. payload=$payloadSize bytes, " +
                "createdKey=${if (createdNewKey) "yes" else "no"}, registeredKey=${if (registeredKey) "yes" else "no"}",
        )
    }

    fun autoBleUnlockKnownDoors() = launchTask("Auto BLE unlocking known doors") {
        val session = requireSession()
        val (bleKeys, createdNewKey) = loadOrCreateBleKeys()
        val userId = _uiState.value.bleUserId.trim().ifBlank { session.userId }

        // Configure GATT server with keys (server was pre-opened in init).
        // Keys must be set before advertising so any early reader write can be processed
        // immediately (buffered write path handles the remaining race, but having keys ready
        // earlier reduces ambiguity in logs).
        val peripheralUnlock = CompletableDeferred<String>()
        blePeripheral.onPayloadComputed = { _, serial ->
            logger.info("Peripheral unlock payload delivered for serial='$serial' — awaiting reader READ on char 2000.")
            peripheralUnlock.complete(serial)
        }
        blePeripheral.configure(bleKeys, userId)

        // Fetch door list and register the BLE key with the backend BEFORE advertising.
        // The official app registers keys at login time (before BleService starts), so the
        // reader's backend-synced approved-key list is up to date when it first encounters our
        // advertisement. Advertising after registration reduces the chance of the reader
        // connecting but then rejecting our auth tag because our key was not yet pushed to it.
        val doors = api.listDoors(session)
        _uiState.value = _uiState.value.copy(
            doors = doors,
            latestLogPath = logger.currentLogFile().absolutePath,
        )

        val targetSerials = doors.flatMap { it.readerSerials }.distinct()
        require(targetSerials.isNotEmpty()) { "No reader serials were returned for the current doors." }
        logger.info("Auto BLE unlock target serials: $targetSerials")

        val registeredKey = ensureBleKeyRegistration(session, bleKeys)

        // Start advertising only after key is registered in the backend.
        blePeripheral.startAdvertising()

        // Wait up to 60 s for the reader to connect, write its pubkey+serial to char 1001,
        // and receive the computed payload. If the reader already wrote during HTTP (pre-opened
        // server + quick service discovery), peripheralUnlock is already completed and
        // await() returns immediately.
        val peripheralResult = withTimeoutOrNull(60_000L) { peripheralUnlock.await() }
        blePeripheral.stopAdvertising()

        if (peripheralResult != null) {
            // Reader received the payload — give it 2 s to read char 2000 and unlock,
            // then clean up the GATT server.
            logger.info("Peripheral payload delivered for '$peripheralResult'. Waiting 2 s for reader to read and unlock.")
            delay(2_000L)
            blePeripheral.closeGattServer()
            val doorName = doors.firstOrNull { peripheralResult in it.readerSerials }?.name ?: peripheralResult
            updateStatus(
                "Peripheral BLE unlock sent to '$doorName'. " +
                    "createdKey=${if (createdNewKey) "yes" else "no"}, registeredKey=${if (registeredKey) "yes" else "no"}",
            )
            return@launchTask
        }

        blePeripheral.closeGattServer()
        logger.warn("Peripheral mode: no reader write received within 60 s. Trying FD3B central scan.")

        // Phase 2 (fallback): central FD3B scan — in case reader uses central mode on this site.
        blePeripheral.startAdvertising()
        delay(3_000L)
        val discovery = try {
            bleManager.discoverReaders(15_000L)
        } finally {
            blePeripheral.stopAdvertising()
            blePeripheral.closeGattServer()
        }
        applyDiscovery(discovery)

        val matchedReaders = discovery.readers
            .filter { it.readerSerial in targetSerials }
            .distinctBy { it.address }
        val nearbyBeaconHints = buildBeaconMatchedReaders(discovery, targetSerials)
            .map { it.readerSerial }
            .distinct()
        if (matchedReaders.isEmpty() && nearbyBeaconHints.isNotEmpty()) {
            logger.warn(
                "Nearby Verkada beacons matched known readers $nearbyBeaconHints, " +
                    "but the APK BLE central path connects only real FD3B scan-result devices. " +
                    "Skipping beacon-address connection attempts.",
            )
        }
        logger.info(
            "Auto BLE matched readers: ${matchedReaders.map { "${it.readerSerial}@${it.address}" }}",
        )
        require(matchedReaders.isNotEmpty()) {
            buildString {
                append("No FD3B reader candidates matched the known door serials.")
                if (nearbyBeaconHints.isNotEmpty()) {
                    append(" Nearby beacon hints: ${nearbyBeaconHints.joinToString(", ")}.")
                }
            }
        }

        val unlockedDoors = mutableListOf<String>()
        val failedReaders = mutableListOf<String>()
        for (reader in matchedReaders) {
            val doorNames = doors.filter { reader.readerSerial in it.readerSerials }.map { it.name }
            val label = if (doorNames.isEmpty()) reader.readerSerial else doorNames.joinToString("/")
            try {
                val payloadSize = bleManager.unlockReader(reader, bleKeys, userId)
                unlockedDoors += label
                logger.info(
                    "Auto BLE unlock sent to ${reader.readerSerial} (${reader.address}) for $label with payload=$payloadSize bytes.",
                )
            } catch (error: Throwable) {
                failedReaders += label
                logger.error("Auto BLE unlock failed for ${reader.readerSerial} (${reader.address}) / $label.", error)
            }
        }

        require(unlockedDoors.isNotEmpty()) {
            buildString {
                append("Auto BLE unlock did not reach any door reader.")
                if (failedReaders.isNotEmpty()) {
                    append(" Failed readers: ${failedReaders.joinToString(", ")}.")
                }
            }
        }

        updateStatus(
            "Auto BLE unlock sent to ${unlockedDoors.joinToString(", ")}. " +
                failedReaders.takeIf { it.isNotEmpty() }?.let { "failed=${it.joinToString("/")}, " }.orEmpty() +
                "createdKey=${if (createdNewKey) "yes" else "no"}, registeredKey=${if (registeredKey) "yes" else "no"}",
        )
    }

    fun notePermissions(granted: Boolean) {
        if (granted) {
            updateStatus("Bluetooth and location permissions granted.")
        } else {
            updateError("Bluetooth and location permissions are required for scanning and connecting to readers.")
        }
    }

    fun noteInfo(message: String) {
        updateStatus(message)
    }

    fun noteError(message: String, throwable: Throwable? = null) {
        updateError(message, throwable)
    }

    fun clearLogs() {
        logger.clear()
        _uiState.value = _uiState.value.copy(
            latestLogPath = logger.currentLogFile().absolutePath,
            statusMessage = "Log file cleared.",
        )
    }

    fun currentLogFile(): File = logger.currentLogFile()

    override fun onCleared() {
        api.close()
        super.onCleared()
    }

    private fun applyDiscovery(result: BleDiscoveryResult) {
        _uiState.value = _uiState.value.copy(
            bleReaders = result.readers,
            bleDiscoverySummary = result.renderSummaryLines(),
            statusMessage = "BLE scan completed. Found ${result.readers.size} candidate reader(s).",
            latestLogPath = logger.currentLogFile().absolutePath,
        )
    }

    private fun loadOrCreateBleKeys(): Pair<BleKeyPair, Boolean> {
        storage.loadBleKeys()?.let {
            logger.info("Loaded BLE key pair ${it.fingerprint}.")
            return it to false
        }

        val newKeys = generateBleKeyPair()
        storage.saveBleKeys(newKeys)
        logger.info("Generated new BLE key pair ${newKeys.fingerprint}.")
        return newKeys to true
    }

    private suspend fun ensureBleKeyRegistration(session: SessionState, bleKeys: BleKeyPair): Boolean {
        val registeredKeys = api.getRegisteredAuthKeys(session)
        if (registeredKeys.any { it.fingerprint == bleKeys.fingerprint && it.keyType == BLE_KEY_TYPE }) {
            logger.info("BLE key ${bleKeys.fingerprint} is already registered.")
            return false
        }

        api.registerPublicBleKey(
            session = session,
            request = buildBleRegistrationRequest(
                bleKeys = bleKeys,
                registrationInfo = BleRegistrationInfo(
                    platform = "ANDROID",
                    version = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
                    make = Build.MANUFACTURER ?: "Android",
                    model = Build.MODEL ?: "Android",
                    name = listOf(Build.MANUFACTURER, Build.MODEL)
                        .filter { !it.isNullOrBlank() }
                        .joinToString(" ")
                        .ifBlank { "Android Test Device" },
                ),
            ),
        )
        logger.info("Registered BLE key ${bleKeys.fingerprint} with the backend.")
        return true
    }

    private fun requireSession(): SessionState =
        _uiState.value.session ?: error("Sign in first.")

    private fun updateConfigState(transform: AppUiState.() -> AppUiState) {
        val updated = _uiState.value.transform()
        _uiState.value = updated
        storage.saveConfig(
            AppConfigData(
                email = updated.email.trim(),
                orgShortName = updated.orgShortName.trim(),
                unlockMethod = updated.unlockMethod.trim().ifBlank { "mobile" },
                bleUserId = updated.bleUserId.trim(),
            ),
        )
    }

    private fun launchTask(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, statusMessage = label, latestLogPath = logger.currentLogFile().absolutePath)
            try {
                logger.info(label)
                block()
            } catch (error: Throwable) {
                updateError(error.message ?: label, error)
            } finally {
                _uiState.value = _uiState.value.copy(isBusy = false, latestLogPath = logger.currentLogFile().absolutePath)
            }
        }
    }

    private fun updateStatus(message: String) {
        logger.info(message)
        _uiState.value = _uiState.value.copy(statusMessage = message, latestLogPath = logger.currentLogFile().absolutePath)
    }

    private fun updateError(message: String, throwable: Throwable? = null) {
        logger.error(message, throwable)
        _uiState.value = _uiState.value.copy(statusMessage = message, latestLogPath = logger.currentLogFile().absolutePath)
    }

    private fun buildBeaconMatchedReaders(
        discovery: BleDiscoveryResult,
        targetSerials: List<String>,
    ): List<CentralReader> {
        val serialByPair = targetSerials.associateBy { deriveBeaconPairFromReaderSerial(it) }
        return discovery.observations.mapNotNull { observation ->
            val major = observation.beaconMajor ?: return@mapNotNull null
            val minor = observation.beaconMinor ?: return@mapNotNull null
            val serial = serialByPair[major to minor] ?: return@mapNotNull null
            CentralReader(
                address = observation.address,
                name = observation.name ?: observation.localName ?: observation.address,
                readerSerial = serial,
                rssi = observation.rssi,
            )
        }
    }
}
