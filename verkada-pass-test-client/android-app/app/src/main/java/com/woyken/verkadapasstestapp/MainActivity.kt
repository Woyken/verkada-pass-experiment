package com.woyken.verkadapasstestapp

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.woyken.verkadapasstestapp.ble.BleCentralManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            var blePermissionsGranted by remember {
                mutableStateOf(BleCentralManager.hasRequiredPermissions(this@MainActivity))
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
            ) { grants ->
                blePermissionsGranted = grants.values.all { it }
                viewModel.notePermissions(blePermissionsGranted)
            }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val logLines by viewModel.logLines.collectAsStateWithLifecycle()
            var launchedAutomation by remember { mutableStateOf(false) }

            if (intent.getBooleanExtra(EXTRA_AUTO_BLE_UNLOCK, false) && !launchedAutomation) {
                LaunchedEffect(blePermissionsGranted, state.session) {
                    if (!blePermissionsGranted) {
                        launchedAutomation = true
                        permissionLauncher.launch(BleCentralManager.runtimePermissions)
                    } else if (state.session != null) {
                        launchedAutomation = true
                        viewModel.autoBleUnlockKnownDoors()
                    }
                }
            }

            MainScreen(
                state = state,
                logLines = logLines,
                blePermissionsGranted = blePermissionsGranted,
                onEmailChanged = viewModel::updateEmail,
                onOrgShortNameChanged = viewModel::updateOrgShortName,
                onMagicLinkChanged = viewModel::updateMagicLinkUrl,
                onUnlockMethodChanged = viewModel::updateUnlockMethod,
                onBleUserIdChanged = viewModel::updateBleUserId,
                onRequestMagicLink = viewModel::requestMagicLink,
                onRedeemMagicLink = viewModel::redeemMagicLink,
                onLogout = viewModel::logout,
                onLoadDoors = viewModel::loadDoors,
                onUnlockDoor = viewModel::unlockDoor,
                onRequestBlePermissions = {
                    permissionLauncher.launch(BleCentralManager.runtimePermissions)
                },
                onScanBle = viewModel::scanBleReaders,
                onUnlockReader = viewModel::unlockReader,
                onShareLogs = {
                    try {
                        shareLogFile(viewModel.currentLogFile())
                        viewModel.noteInfo("Shared the current log file.")
                    } catch (error: Throwable) {
                        viewModel.noteError("Failed to share the current log file.", error)
                    }
                },
                onCopyLogsToDownloads = {
                    try {
                        val name = copyLogToDownloads(viewModel.currentLogFile())
                        viewModel.noteInfo("Copied logs to Downloads\\VerkadaPassTest\\$name")
                    } catch (error: Throwable) {
                        viewModel.noteError("Failed to copy logs to Downloads.", error)
                    }
                },
                onClearLogs = viewModel::clearLogs,
            )
        }
    }

    private fun shareLogFile(logFile: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            logFile,
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(logFile.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share log file"))
    }

    private fun copyLogToDownloads(logFile: File): String {
        val displayName = "verkada-pass-test-${TIMESTAMP_FORMAT.format(Date())}.log"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + "VerkadaPassTest",
            )
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create a Downloads entry for the exported log file.")

        contentResolver.openOutputStream(uri)?.use { output ->
            logFile.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Could not open the Downloads destination for writing.")

        return displayName
    }

    private companion object {
        const val EXTRA_AUTO_BLE_UNLOCK = "com.woyken.verkadapasstestapp.AUTO_BLE_UNLOCK"
        val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
