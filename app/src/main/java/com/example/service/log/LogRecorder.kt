package com.example.service.log

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-precision Flight Recorder for VPN Network Events.
 * Captures real-time TCP state transitions, DNS resolutions, timeouts, packet drops,
 * and connection drops to produce comprehensive log files for AI diagnosis.
 */
object LogRecorder {
    private const val TAG = "LogRecorder"
    private const val TEMP_LOG_NAME = "vpn_flight_recording.tmp"

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordedCount = MutableStateFlow(0)
    val recordedCount: StateFlow<Int> = _recordedCount.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0L)
    val recordingDurationSec: StateFlow<Long> = _recordingDurationSec.asStateFlow()

    private val _lastExportedFilePath = MutableStateFlow<String?>(null)
    val lastExportedFilePath: StateFlow<String?> = _lastExportedFilePath.asStateFlow()

    private val inMemoryLogs = ConcurrentLinkedQueue<String>()
    private val lineCounter = AtomicInteger(0)
    private var recordingStartTime = 0L
    private var timerJob: Job? = null
    private var logWriter: PrintWriter? = null
    private var tempFile: File? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    @Synchronized
    fun startRecording(context: Context) {
        if (_isRecording.value) return

        try {
            inMemoryLogs.clear()
            lineCounter.set(0)
            _recordedCount.value = 0
            _recordingDurationSec.value = 0L
            _lastExportedFilePath.value = null
            recordingStartTime = System.currentTimeMillis()

            tempFile = File(context.cacheDir, TEMP_LOG_NAME)
            if (tempFile?.exists() == true) {
                tempFile?.delete()
            }
            logWriter = PrintWriter(FileWriter(tempFile, true), true)

            _isRecording.value = true

            // Start duration timer
            timerJob?.cancel()
            timerJob = coroutineScope.launch {
                while (isActive && _isRecording.value) {
                    delay(1000)
                    _recordingDurationSec.value = (System.currentTimeMillis() - recordingStartTime) / 1000
                }
            }

            // Write initial header with device context
            val header = buildString {
                appendLine("================================================================================")
                appendLine("                 🚀 SHADOW VPN - FLIGHT RECORDER & DIAGNOSTIC LOG               ")
                appendLine("================================================================================")
                appendLine("📅 تاریخ و زمان شروع: ${dateFormat.format(Date(recordingStartTime))}")
                appendLine("📱 مدل دستگاه: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                appendLine("🤖 نسخه اندروید: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("🔒 موتور امنیتی TLS: Conscrypt BoringSSL Engine")
                appendLine("⚡ مشخصات شبکه: MTU 1400, TCP MSS Clamping 1360, Pure ACK Tracking Active")
                appendLine("--------------------------------------------------------------------------------")
                appendLine("[TIME]                   [LEVEL] [TAG]        [MESSAGE / DETAIL]")
                appendLine("--------------------------------------------------------------------------------")
            }
            writeRaw(header)
            recordEvent("RECORDER", "شروع ضبط رویدادهای زنده وی‌پی‌ان...")

        } catch (e: Exception) {
            Log.e(TAG, "Failed starting log recording: ${e.message}", e)
        }
    }

    @Synchronized
    fun stopRecording(context: Context): String {
        if (!_isRecording.value) return getLogsAsString()

        _isRecording.value = false
        timerJob?.cancel()
        timerJob = null

        val stopTime = System.currentTimeMillis()
        val durationSec = (stopTime - recordingStartTime) / 1000

        val footer = buildString {
            appendLine("--------------------------------------------------------------------------------")
            appendLine("⏹️ زمان پایان ضبط: ${dateFormat.format(Date(stopTime))} (مدت زمان: ${durationSec} ثانیه)")
            appendLine("📊 مجموع کل رویدادهای ثبت‌شده: ${lineCounter.get()}")
            appendLine("================================================================================")
        }
        writeRaw(footer)

        try {
            logWriter?.flush()
            logWriter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing log writer: ${e.message}", e)
        }
        logWriter = null

        return getLogsAsString()
    }

    fun recordEvent(tag: String, message: String, detail: String = "", level: String = "INFO") {
        if (!_isRecording.value) return

        val timestamp = dateFormat.format(Date())
        val formattedDetail = if (detail.isNotBlank()) " | $detail" else ""
        val line = String.format(Locale.US, "%-24s [%-5s] [%-10s] %s%s", timestamp, level, tag, message, formattedDetail)

        writeRaw(line)
        val count = lineCounter.incrementAndGet()
        _recordedCount.value = count
    }

    private fun writeRaw(text: String) {
        inMemoryLogs.add(text)
        // Keep in-memory buffer capped at 3000 to avoid OOM
        while (inMemoryLogs.size > 3000) {
            inMemoryLogs.poll()
        }
        coroutineScope.launch {
            try {
                logWriter?.println(text)
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing to log file: ${e.message}")
            }
        }
    }

    fun getLogsAsString(): String {
        return if (tempFile?.exists() == true) {
            try {
                tempFile?.readText() ?: inMemoryLogs.joinToString("\n")
            } catch (e: Exception) {
                inMemoryLogs.joinToString("\n")
            }
        } else {
            inMemoryLogs.joinToString("\n")
        }
    }

    /**
     * Saves the recorded log directly into the public Downloads directory.
     * Uses MediaStore for modern Android versions (10+) and direct file I/O for legacy versions.
     */
    fun saveLogToDownloads(context: Context): Result<String> {
        return try {
            val content = getLogsAsString()
            if (content.isBlank()) {
                return Result.failure(Exception("هیچ لاگی برای ذخیره وجود ندارد"))
            }

            val fileName = "vpn_flight_log_${fileTimestampFormat.format(Date())}.txt"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return Result.failure(Exception("خطا در ایجاد فایل در Downloads"))

                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                }

                val savedPath = "Downloads/$fileName"
                _lastExportedFilePath.value = savedPath
                Result.success(savedPath)
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val targetFile = File(downloadsDir, fileName)
                targetFile.writeText(content, Charsets.UTF_8)

                val savedPath = targetFile.absolutePath
                _lastExportedFilePath.value = savedPath
                Result.success(savedPath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving log to downloads: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Opens Android Share Sheet so the user can easily send the log file to Telegram,
     * Google Drive, Gmail, or copy it directly.
     */
    fun shareLogFile(context: Context): Boolean {
        return try {
            val content = getLogsAsString()
            if (content.isBlank()) return false

            // Save to app's cache directory first
            val shareFile = File(context.cacheDir, "vpn_diagnostic_log_${fileTimestampFormat.format(Date())}.txt")
            shareFile.writeText(content, Charsets.UTF_8)

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                shareFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "گزارش عیب‌یابی لاگ‌های وی‌پی‌ان (Flight Log)")
                putExtra(Intent.EXTRA_TEXT, "فایل لاگ ضبط‌شده اتصال وی‌پی‌ان برای تحلیل و عیب‌یابی.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "اشتراک‌گذاری فایل لاگ با...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing log file: ${e.message}", e)
            false
        }
    }
}
