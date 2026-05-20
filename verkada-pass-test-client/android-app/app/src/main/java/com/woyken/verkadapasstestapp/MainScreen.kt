package com.woyken.verkadapasstestapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.woyken.verkadapasstestapp.data.CentralReader
import com.woyken.verkadapasstestapp.data.DoorRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: AppUiState,
    logLines: List<String>,
    blePermissionsGranted: Boolean,
    onEmailChanged: (String) -> Unit,
    onOrgShortNameChanged: (String) -> Unit,
    onMagicLinkChanged: (String) -> Unit,
    onUnlockMethodChanged: (String) -> Unit,
    onBleUserIdChanged: (String) -> Unit,
    onRequestMagicLink: () -> Unit,
    onRedeemMagicLink: () -> Unit,
    onLogout: () -> Unit,
    onLoadDoors: () -> Unit,
    onUnlockDoor: (DoorRecord) -> Unit,
    onRequestBlePermissions: () -> Unit,
    onScanBle: () -> Unit,
    onUnlockReader: (CentralReader) -> Unit,
    onShareLogs: () -> Unit,
    onCopyLogsToDownloads: () -> Unit,
    onClearLogs: () -> Unit,
) {
    val pageScroll = rememberScrollState()
    val logScroll = rememberScrollState()

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Verkada Pass Test App") },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(pageScroll),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                StatusCard(state)
                AuthCard(
                    state = state,
                    onEmailChanged = onEmailChanged,
                    onOrgShortNameChanged = onOrgShortNameChanged,
                    onMagicLinkChanged = onMagicLinkChanged,
                    onRequestMagicLink = onRequestMagicLink,
                    onRedeemMagicLink = onRedeemMagicLink,
                    onLogout = onLogout,
                    enabled = !state.isBusy,
                )
                RemoteCard(
                    state = state,
                    onUnlockMethodChanged = onUnlockMethodChanged,
                    onLoadDoors = onLoadDoors,
                    onUnlockDoor = onUnlockDoor,
                )
                BleCard(
                    state = state,
                    blePermissionsGranted = blePermissionsGranted,
                    onBleUserIdChanged = onBleUserIdChanged,
                    onRequestBlePermissions = onRequestBlePermissions,
                    onScanBle = onScanBle,
                    onUnlockReader = onUnlockReader,
                )
                LogsCard(
                    logPath = state.latestLogPath,
                    logLines = logLines,
                    logScroll = logScroll,
                    onShareLogs = onShareLogs,
                    onCopyLogsToDownloads = onCopyLogsToDownloads,
                    onClearLogs = onClearLogs,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: AppUiState) {
    SectionCard(title = "Status") {
        Text(state.statusMessage)
        if (state.session != null) {
            Text("Session: ${state.session.email} (${state.session.organizationId})")
        } else {
            Text("Session: not signed in")
        }
    }
}

@Composable
private fun AuthCard(
    state: AppUiState,
    onEmailChanged: (String) -> Unit,
    onOrgShortNameChanged: (String) -> Unit,
    onMagicLinkChanged: (String) -> Unit,
    onRequestMagicLink: () -> Unit,
    onRedeemMagicLink: () -> Unit,
    onLogout: () -> Unit,
    enabled: Boolean,
) {
    SectionCard(title = "Magic-link auth") {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.email,
            onValueChange = onEmailChanged,
            label = { Text("Email") },
            enabled = enabled,
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.orgShortName,
            onValueChange = onOrgShortNameChanged,
            label = { Text("Org short name (optional)") },
            enabled = enabled,
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.magicLinkUrl,
            onValueChange = onMagicLinkChanged,
            label = { Text("Magic link URL") },
            enabled = enabled,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onRequestMagicLink,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Text("Request link")
            }
            Button(
                onClick = onRedeemMagicLink,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Text("Redeem link")
            }
        }
        TextButton(
            onClick = onLogout,
            enabled = enabled && state.session != null,
        ) {
            Text("Forget saved session")
        }
    }
}

@Composable
private fun RemoteCard(
    state: AppUiState,
    onUnlockMethodChanged: (String) -> Unit,
    onLoadDoors: () -> Unit,
    onUnlockDoor: (DoorRecord) -> Unit,
) {
    SectionCard(title = "Remote unlock") {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.unlockMethod,
            onValueChange = onUnlockMethodChanged,
            label = { Text("Unlock method") },
            singleLine = true,
        )
        Button(
            onClick = onLoadDoors,
            enabled = !state.isBusy && state.session != null,
        ) {
            Text("Load doors")
        }
        if (state.doors.isEmpty()) {
            Text("No doors loaded yet.")
        } else {
            state.doors.forEach { door ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(door.name, style = MaterialTheme.typography.titleMedium)
                        Text("Access point: ${door.accessPointId}")
                        door.accessControllerId?.let { Text("Controller: $it") }
                        door.floorId?.let { Text("Floor: $it") }
                        if (door.schedule != null) {
                            Text("Schedule states: ${door.schedule.distinctStates().joinToString("/")}")
                        }
                        if (door.readerSerials.isNotEmpty()) {
                            Text("Reader serials: ${door.readerSerials.joinToString(", ")}")
                        }
                        Button(
                            onClick = { onUnlockDoor(door) },
                            enabled = !state.isBusy && state.session != null,
                        ) {
                            Text("Unlock door")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BleCard(
    state: AppUiState,
    blePermissionsGranted: Boolean,
    onBleUserIdChanged: (String) -> Unit,
    onRequestBlePermissions: () -> Unit,
    onScanBle: () -> Unit,
    onUnlockReader: (CentralReader) -> Unit,
) {
    SectionCard(title = "BLE diagnostics + central unlock") {
        Text("Permissions (Bluetooth + location): ${if (blePermissionsGranted) "granted" else "missing"}")
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.bleUserId,
            onValueChange = onBleUserIdChanged,
            label = { Text("BLE user ID override (optional)") },
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onRequestBlePermissions,
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text("Grant BLE + location")
            }
            Button(
                onClick = onScanBle,
                enabled = !state.isBusy && blePermissionsGranted,
                modifier = Modifier.weight(1f),
            ) {
                Text("Scan readers")
            }
        }
        if (state.bleDiscoverySummary.isNotEmpty()) {
            Text(state.bleDiscoverySummary.joinToString("\n"))
        }
        if (state.bleReaders.isEmpty()) {
            Text("No BLE readers discovered yet.")
        } else {
            state.bleReaders.forEach { reader ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(reader.name, style = MaterialTheme.typography.titleMedium)
                        Text("Address: ${reader.address}")
                        Text("Serial: ${reader.readerSerial}")
                        reader.rssi?.let { Text("RSSI: $it") }
                        Button(
                            onClick = { onUnlockReader(reader) },
                            enabled = !state.isBusy && state.session != null && blePermissionsGranted,
                        ) {
                            Text("Send BLE unlock")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsCard(
    logPath: String,
    logLines: List<String>,
    logScroll: androidx.compose.foundation.ScrollState,
    onShareLogs: () -> Unit,
    onCopyLogsToDownloads: () -> Unit,
    onClearLogs: () -> Unit,
) {
    SectionCard(title = "Logs") {
        Text("Current file: $logPath")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onShareLogs, modifier = Modifier.weight(1f)) {
                Text("Share log")
            }
            Button(onClick = onCopyLogsToDownloads, modifier = Modifier.weight(1f)) {
                Text("Copy to Downloads")
            }
        }
        TextButton(onClick = onClearLogs) {
            Text("Clear log file")
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            SelectionContainer {
                Text(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(logScroll),
                    text = if (logLines.isEmpty()) "No log lines yet." else logLines.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            HorizontalDivider()
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}
