package com.woyken.verkadapasstestapp.data

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppLogger(context: Context) {
    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val logDirectory = (
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        ).resolve("logs")
    private val activeLogFile = logDirectory.resolve("verkada-pass-test-app.log")
    private val archivedLogFile = logDirectory.resolve("verkada-pass-test-app.previous.log")
    private val _lines = MutableStateFlow<List<String>>(emptyList())

    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    init {
        logDirectory.mkdirs()
        rotateIfNeeded()
        if (!activeLogFile.exists()) {
            activeLogFile.writeText("")
        }
        _lines.value = activeLogFile.readLines().takeLast(MAX_IN_MEMORY_LINES)
        info("Logger initialized at ${activeLogFile.absolutePath}")
    }

    fun info(message: String) = append("INFO", message)

    fun warn(message: String) = append("WARN", message)

    fun error(message: String, throwable: Throwable? = null) = append("ERROR", message, throwable)

    /** Debug messages go to Android logcat only — not written to the log file. */
    fun debug(message: String) {
        Log.d(TAG, message)
    }

    fun clear() {
        synchronized(lock) {
            if (activeLogFile.exists()) {
                activeLogFile.delete()
            }
            activeLogFile.writeText("")
            _lines.value = emptyList()
        }
        info("Log file cleared.")
    }

    fun currentLogFile(): File = activeLogFile

    private fun append(level: String, message: String, throwable: Throwable? = null) {
        val timestamp = timestampFormat.format(Date())
        val entries = buildList {
            add("$timestamp [$level] $message")
            if (throwable != null) {
                add(Log.getStackTraceString(throwable))
            }
        }
        synchronized(lock) {
            activeLogFile.appendText(entries.joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator()))
            _lines.value = (_lines.value + entries).takeLast(MAX_IN_MEMORY_LINES)
        }
    }

    private fun rotateIfNeeded() {
        if (!activeLogFile.exists() || activeLogFile.length() < MAX_LOG_BYTES) {
            return
        }
        if (archivedLogFile.exists()) {
            archivedLogFile.delete()
        }
        activeLogFile.renameTo(archivedLogFile)
    }

    private companion object {
        private const val TAG = "VerkadaPassTest"
        private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
        private const val MAX_IN_MEMORY_LINES = 300
    }
}

