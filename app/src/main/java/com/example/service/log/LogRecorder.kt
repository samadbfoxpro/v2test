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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Enterprise-grade Flight Recorder & Deep Diagnostic Log Engine for Shadow VPN.
 * Non-blocking, Channel-buffered stream logging with millisecond precision,
 * TCP state machine tracking, Retransmission detection, DNS lifecycle,
 * and AI-ready anomaly reports.
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

    private val _logLevel = MutableStateFlow(DiagnosticLogLevel.DIAGNOSTIC)
    val logLevel: StateFlow<DiagnosticLogLevel> = _logLevel.asStateFlow()

    fun setLogLevel(level: DiagnosticLogLevel) {
        _logLevel.value = level
    }

    val analyzer = DiagnosticAnalyzer()

    private val logChannel = Channel<String>(capacity = 10000)
    private val lineCounter = AtomicInteger(0)
    private val sessionCounter = AtomicLong(10000L)

    private var recordingStartTime = 0L
    private var timerJob: Job? = null
    private var writerJob: Job? = null
    private var tempFile: File? = null
    private var bufferedWriter: BufferedWriter? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    fun generateSessionId(): Long = sessionCounter.incrementAndGet()

    @Synchronized
    fun startRecording(context: Context) {
        if (_isRecording.value) return

        try {
            analyzer.clear()
            lineCounter.set(0)
            _recordedCount.value = 0
            _recordingDurationSec.value = 0L
            _lastExportedFilePath.value = null
            recordingStartTime = System.currentTimeMillis()

            tempFile = File(context.cacheDir, TEMP_LOG_NAME)
            if (tempFile?.exists() == true) {
                tempFile?.delete()
            }
            bufferedWriter = BufferedWriter(FileWriter(tempFile, true), 32768)

            _isRecording.value = true

            // Start background writer worker
            writerJob?.cancel()
            writerJob = coroutineScope.launch {
                for (line in logChannel) {
                    try {
                        bufferedWriter?.write(line)
                        bufferedWriter?.newLine()
                    } catch (_: Exception) {}
                }
            }

            // Start duration timer
            timerJob?.cancel()
            timerJob = coroutineScope.launch {
                while (isActive && _isRecording.value) {
                    delay(1000)
                    _recordingDurationSec.value = (System.currentTimeMillis() - recordingStartTime) / 1000
                    try {
                        bufferedWriter?.flush()
                    } catch (_: Exception) {}
                }
            }

            // Header
            val header = buildString {
                appendLine("================================================================================")
                appendLine("                 🚀 SHADOW VPN - FLIGHT RECORDER & DIAGNOSTIC LOG               ")
                appendLine("================================================================================")
                appendLine("📅 تاریخ و زمان شروع: ${dateFormat.format(Date(recordingStartTime))}")
                appendLine("📱 مدل دستگاه: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                appendLine("🤖 نسخه اندروید: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("🔒 موتور امنیتی TLS: Conscrypt BoringSSL TLS 1.3")
                appendLine("⚡ مشخصات شبکه: MTU 1400, TCP MSS Clamping 1360, Pure ACK Tracking Active")
                appendLine("🔍 سطح لاگ: ${_logLevel.value.name}")
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

        // Append End-of-Run Analysis Report
        val analysisReport = analyzer.generateAnalysisReport()
        writeRaw(analysisReport)

        val footer = buildString {
            appendLine("--------------------------------------------------------------------------------")
            appendLine("⏹️ زمان پایان ضبط: ${dateFormat.format(Date(stopTime))} (مدت زمان: ${durationSec} ثانیه)")
            appendLine("📊 مجموع کل رویدادهای ثبت‌شده: ${lineCounter.get()}")
            appendLine("================================================================================")
        }
        writeRaw(footer)

        try {
            bufferedWriter?.flush()
            bufferedWriter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing log writer: ${e.message}", e)
        }
        bufferedWriter = null

        return getLogsAsString()
    }

    private fun writeRaw(text: String) {
        logChannel.trySend(text)
    }

    fun recordEvent(tag: String, message: String, detail: String = "", level: String = "INFO") {
        if (!_isRecording.value) return

        val timestamp = dateFormat.format(Date())
        val formattedDetail = if (detail.isNotBlank()) " | $detail" else ""
        val line = String.format(Locale.US, "%-24s [%-5s] [%-10s] %s%s", timestamp, level, tag, message, formattedDetail)

        lineCounter.incrementAndGet()
        _recordedCount.value = lineCounter.get()
        logChannel.trySend(line)
    }

    // === 1. Session Lifecycle Logging ===

    fun startSession(
        protocol: String,
        destIp: String,
        destPort: Int,
        srcVirtualIp: String = "172.19.0.1",
        srcPort: Int = 0,
        hostname: String = "",
        isFakeDns: Boolean = false,
        fakeIp: String = "",
        appPackage: String = ""
    ): DiagnosticSession {
        val sessionId = generateSessionId()
        val session = DiagnosticSession(
            sessionId = sessionId,
            protocol = protocol,
            destIp = destIp,
            destPort = destPort,
            srcVirtualIp = srcVirtualIp,
            srcPort = srcPort,
            hostname = hostname,
            isFakeDns = isFakeDns,
            fakeIp = fakeIp,
            appPackage = appPackage
        )
        analyzer.registerSession(session)

        val target = if (hostname.isNotBlank()) "$hostname ($destIp:$destPort)" else "$destIp:$destPort"
        val fakeTag = if (isFakeDns) " [FakeDNS: $fakeIp]" else ""
        val appTag = if (appPackage.isNotBlank()) " [App: $appPackage]" else ""
        recordEvent("SESSION", "SESSION_CREATED session=$sessionId proto=$protocol -> $target$fakeTag$appTag", level = "DEBUG")
        return session
    }

    fun logSessionState(session: DiagnosticSession, newState: SessionLifecycleState, detail: String = "") {
        if (!_isRecording.value) return
        val oldState = session.state
        session.state = newState
        val detailStr = if (detail.isNotBlank()) " ($detail)" else ""
        recordEvent("SESSION", "SESSION_STATE session=${session.sessionId} $oldState -> $newState$detailStr", level = "DEBUG")
    }

    fun logSessionSummary(session: DiagnosticSession) {
        if (!_isRecording.value) return
        session.closeTime = System.currentTimeMillis()
        val target = session.hostname.ifBlank { "${session.destIp}:${session.destPort}" }

        val summary = buildString {
            appendLine("--- SESSION SUMMARY ---")
            appendLine("sessionId=${session.sessionId}")
            appendLine("protocol=${session.protocol}")
            appendLine("hostname=${session.hostname}")
            appendLine("destination=${session.destIp}:${session.destPort}")
            if (session.isFakeDns) appendLine("fakeIp=${session.fakeIp}")
            if (session.appPackage.isNotBlank()) appendLine("appPackage=${session.appPackage}")
            appendLine("outbound=${session.outboundProtocol}")
            appendLine("transport=${session.transport}")
            appendLine("duration=${String.format("%.2f", session.getDurationSec())}s")
            appendLine("uploadBytes=${session.bytesUploaded.get()}")
            appendLine("downloadBytes=${session.bytesDownloaded.get()}")
            appendLine("packetsIn=${session.packetsIn.get()}")
            appendLine("packetsOut=${session.packetsOut.get()}")
            appendLine("retransmissions=${session.retransmissions.get()}")
            appendLine("duplicateAcks=${session.duplicateAcks.get()}")
            appendLine("timeouts=${session.timeouts.get()}")
            appendLine("rst=${session.rstCount.get()}")
            if (session.timing.dnsTimeMs > 0) appendLine("dnsLatency=${session.timing.dnsTimeMs}ms")
            if (session.timing.tcpConnectTimeMs > 0) appendLine("connectLatency=${session.timing.tcpConnectTimeMs}ms")
            if (session.timing.tlsHandshakeTimeMs > 0) appendLine("tlsLatency=${session.timing.tlsHandshakeTimeMs}ms")
            if (session.timing.firstByteTimeMs > 0) appendLine("firstByteLatency=${session.timing.firstByteTimeMs}ms")
            appendLine("status=${session.state}")
            appendLine("reason=${if (session.state == SessionLifecycleState.FAILED) session.failureReason else session.closeReason}")
            append("-----------------------")
        }
        recordEvent("SUMMARY", summary, level = "INFO")
    }

    // === 2. DNS Logging ===

    fun logDnsQuery(hostname: String, resolver: String, protocol: String = "UDP", queryId: Int = 0) {
        analyzer.totalDnsQueries.incrementAndGet()
        recordEvent("DNS", "DNS_QUERY hostname=$hostname resolver=$resolver protocol=$protocol id=$queryId", level = "INFO")
    }

    fun logDnsResponse(
        hostname: String,
        rcode: String,
        latencyMs: Long,
        answerIp: String = "",
        isFakeDns: Boolean = false,
        fakeIp: String = "",
        ttl: Int = 0,
        resolver: String = "Auto"
    ) {
        analyzer.totalDnsLatencyMs.addAndGet(latencyMs)
        if (rcode != "NOERROR") {
            analyzer.failedDnsQueries.incrementAndGet()
            analyzer.recordAnomaly(AnomalyType.DNS_FAILURE, 0L, "DNS query for $hostname failed with rcode=$rcode", hostname)
        } else if (latencyMs > 400) {
            analyzer.recordAnomaly(AnomalyType.DNS_SLOW, 0L, "High DNS latency of ${latencyMs}ms for $hostname", hostname)
        }

        val fakeStr = if (isFakeDns) " [FakeIP=$fakeIp]" else ""
        val ansStr = if (answerIp.isNotBlank()) " ip=$answerIp" else ""
        recordEvent("DNS", "DNS_RESPONSE hostname=$hostname rcode=$rcode latency=${latencyMs}ms resolver=$resolver$ansStr$fakeStr ttl=${ttl}s", level = "INFO")
    }

    fun logFakeDnsAlloc(hostname: String, fakeIp: String) {
        recordEvent("FAKEDNS", "FAKEDNS_ALLOC hostname=$hostname fakeIp=$fakeIp", level = "INFO")
    }

    fun logFakeDnsMiss(fakeIp: String, port: Int = 0) {
        analyzer.fakeDnsMisses.incrementAndGet()
        analyzer.recordAnomaly(AnomalyType.FAKEDNS_MAPPING_MISS, 0L, "No hostname found in mapping for FakeIP $fakeIp:$port", fakeIp)
        recordEvent("FAKEDNS", "FAKEDNS_MAPPING_MISS fakeIp=$fakeIp port=$port", level = "WARN")
    }

    // === 3. TUN Packet Logging ===

    fun logTunPacket(
        direction: String, // TUN_IN / TUN_OUT
        ipVer: String, // IPv4 / IPv6
        protocol: String, // TCP / UDP / ICMP
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        packetLen: Int,
        payloadLen: Int,
        tcpFlags: String = "",
        seq: Long = 0L,
        ack: Long = 0L,
        window: Int = 0,
        mss: Int = 0,
        sessionId: Long = 0L
    ) {
        if (_logLevel.value != DiagnosticLogLevel.DEEP_DEBUG) return

        val flagsStr = if (tcpFlags.isNotBlank()) " flags=$tcpFlags" else ""
        val seqStr = if (protocol == "TCP") " seq=$seq ack=$ack win=$window mss=$mss" else ""
        val sId = if (sessionId > 0) " session=$sessionId" else ""

        recordEvent("TUN", "$direction $ipVer $protocol $srcIp:$srcPort -> $dstIp:$dstPort len=$packetLen payload=$payloadLen$flagsStr$seqStr$sId", level = "DEBUG")
    }

    // === 4. TCP State Machine & Anomaly Logging ===

    fun logTcpState(sessionId: Long, fromState: String, toState: String) {
        recordEvent("TCP", "TCP_STATE session=$sessionId $fromState -> $toState", level = "DEBUG")
    }

    fun logPureAck(sessionId: Long, seq: Long, ack: Long, window: Int, expectedAck: Long, result: String) {
        if (_logLevel.value == DiagnosticLogLevel.NORMAL) return
        val session = analyzer.getSession(sessionId)
        if (result == "DUPLICATE_ACK") {
            session?.duplicateAcks?.incrementAndGet()
            if ((session?.duplicateAcks?.get() ?: 0) > 3) {
                analyzer.recordAnomaly(AnomalyType.DUPLICATE_ACK, sessionId, "Excessive duplicate ACKs on session $sessionId")
            }
        }
        recordEvent("TCP", "PURE_ACK session=$sessionId seq=$seq ack=$ack win=$window expectedAck=$expectedAck result=$result", level = "DEBUG")
    }

    fun logTcpRetransmission(sessionId: Long, seq: Long, length: Int, attempt: Int, delayMs: Long, rtt: Long = 0L) {
        val session = analyzer.getSession(sessionId)
        session?.retransmissions?.incrementAndGet()
        analyzer.recordAnomaly(AnomalyType.TCP_RETRANSMISSION, sessionId, "Retransmission attempt=$attempt delay=${delayMs}ms seq=$seq")

        recordEvent("TCP", "TCP_RETRANSMISSION session=$sessionId seq=$seq len=$length attempt=$attempt delay=${delayMs}ms rtt=${rtt}ms", level = "WARN")
        if (attempt >= 3) {
            recordEvent("TCP", "TCP_WARNING RETRANSMISSION_THRESHOLD_EXCEEDED session=$sessionId attempt=$attempt", level = "WARN")
        }
    }

    fun logTcpAnomaly(type: AnomalyType, sessionId: Long, evidence: String, dest: String = "", details: String = "") {
        analyzer.recordAnomaly(type, sessionId, evidence, dest, details)
        val sId = if (sessionId > 0) " session=$sessionId" else ""
        val detailStr = if (details.isNotBlank()) " | $details" else ""
        recordEvent("ANOMALY", "ANOMALY ${type.name}$sId evidence=\"$evidence\"$detailStr", level = "WARN")
    }

    // === 5. UDP Flow Logging ===

    fun logUdpFlow(
        sessionId: Long,
        src: String,
        dst: String,
        port: Int,
        packetCount: Int,
        byteCount: Long,
        latencyMs: Long = 0L,
        isDropped: Boolean = false,
        dropReason: String = ""
    ) {
        if (isDropped) {
            recordEvent("UDP", "UDP_DROP session=$sessionId $src -> $dst:$port packets=$packetCount bytes=$byteCount reason=\"$dropReason\"", level = "WARN")
        } else {
            if (_logLevel.value != DiagnosticLogLevel.NORMAL) {
                recordEvent("UDP", "UDP_FLOW session=$sessionId $src -> $dst:$port packets=$packetCount bytes=$byteCount latency=${latencyMs}ms", level = "DEBUG")
            }
        }
    }

    fun logFakeDnsUdpDestination(
        fakeIp: String,
        port: Int,
        realHost: String?,
        isDropped: Boolean,
        dropReason: String = ""
    ) {
        if (realHost != null) {
            recordEvent("FAKEDNS", "FAKEDNS_UDP_DESTINATION fakeIp=$fakeIp:$port -> mappedHost=$realHost status=TRANSLATED_AND_ROUTED", level = "INFO")
        } else {
            analyzer.fakeDnsMisses.incrementAndGet()
            analyzer.recordAnomaly(AnomalyType.FAKEDNS_MAPPING_MISS, 0L, "UDP Packet sent to FakeIP $fakeIp:$port with no mapping found", fakeIp)
            recordEvent("FAKEDNS", "FAKEDNS_UDP_DESTINATION fakeIp=$fakeIp:$port status=MAPPING_MISS dropped=$isDropped reason=\"$dropReason\"", level = "WARN")
        }
    }

    // === 6. Outbound Stage & Timing Logging ===

    fun logOutboundStage(sessionId: Long, stageName: String, durationMs: Long, details: String = "") {
        val session = analyzer.getSession(sessionId)
        when (stageName) {
            "DNS_TIME" -> session?.timing?.dnsTimeMs = durationMs
            "TCP_CONNECT_TIME" -> session?.timing?.tcpConnectTimeMs = durationMs
            "TLS_HANDSHAKE_TIME" -> session?.timing?.tlsHandshakeTimeMs = durationMs
            "PROXY_HANDSHAKE_TIME" -> session?.timing?.proxyHandshakeTimeMs = durationMs
            "FIRST_BYTE_TIME" -> session?.timing?.firstByteTimeMs = durationMs
        }
        val detailStr = if (details.isNotBlank()) " | $details" else ""
        recordEvent("OUTBOUND", "OUTBOUND_STAGE session=$sessionId stage=$stageName duration=${durationMs}ms$detailStr", level = "DEBUG")
    }

    // === 7. Granular Timeout & Error Logging ===

    fun logTimeoutOrError(
        sessionId: Long,
        errorType: AnomalyType,
        message: String,
        destination: String = "",
        elapsedMs: Long = 0L,
        exception: Throwable? = null
    ) {
        val session = analyzer.getSession(sessionId)
        session?.timeouts?.incrementAndGet()
        session?.state = SessionLifecycleState.FAILED
        session?.failureReason = errorType.name

        analyzer.recordAnomaly(errorType, sessionId, message, destination, exception?.message ?: "")

        val excStr = if (exception != null) " exception=${exception.javaClass.simpleName}: ${exception.message}" else ""
        val elapsedStr = if (elapsedMs > 0) " elapsed=${elapsedMs}ms" else ""
        val destStr = if (destination.isNotBlank()) " dest=$destination" else ""
        recordEvent("ERROR", "ERROR ${errorType.name} session=$sessionId$destStr$elapsedStr message=\"$message\"$excStr", level = "ERROR")
    }

    // === 8. MTU / MSS Diagnostics ===

    fun logMtuEvent(type: String, size: Int, mtu: Int = 1400, mss: Int = 1360, sessionId: Long = 0L, reason: String = "") {
        val sId = if (sessionId > 0) " session=$sessionId" else ""
        val reasonStr = if (reason.isNotBlank()) " reason=\"$reason\"" else ""
        recordEvent("MTU", "$type$sId packetSize=$size MTU=$mtu MSS=$mss$reasonStr", level = if (type.contains("DROP") || type.contains("WARNING")) "WARN" else "DEBUG")
    }

    // === 9. Export & File Sharing ===

    fun getLogsAsString(): String {
        return try {
            if (tempFile?.exists() == true) {
                tempFile?.readText() ?: ""
            } else ""
        } catch (_: Exception) {
            ""
        }
    }

    fun saveLogToDownloads(context: Context): Result<String> {
        val content = getLogsAsString()
        if (content.isBlank()) {
            return Result.failure(Exception("هیچ لاگی برای ذخیره وجود ندارد"))
        }

        val filename = "shadow_vpn_flight_log_${fileTimestampFormat.format(Date())}.txt"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return Result.failure(Exception("خطا در ایجاد فایل در پوشه Downloads"))

                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                _lastExportedFilePath.value = "Downloads/$filename"
                Result.success("فایل با موفقیت در Downloads/$filename ذخیره شد 📥")
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val targetFile = File(downloadsDir, filename)
                targetFile.writeText(content, Charsets.UTF_8)
                _lastExportedFilePath.value = targetFile.absolutePath
                Result.success("فایل با موفقیت در ${targetFile.absolutePath} ذخیره شد 📥")
            }
        } catch (e: Exception) {
            Result.failure(Exception("خطا در ذخیره فایل لاگ: ${e.message}"))
        }
    }

    fun shareLogFile(context: Context): Result<Unit> {
        return try {
            val content = getLogsAsString()
            if (content.isBlank()) {
                return Result.failure(Exception("هیچ لاگی برای اشتراک‌گذاری وجود ندارد"))
            }

            val shareFile = File(context.cacheDir, "shadow_vpn_diagnostic_log.txt")
            shareFile.writeText(content, Charsets.UTF_8)

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                shareFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Shadow VPN Flight Recorder Diagnostic Log")
                putExtra(Intent.EXTRA_TEXT, "لاگ ثبت‌شده از نشست‌های شبکه Shadow VPN برای عیب‌یابی با هوش مصنوعی.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "اشتراک‌گذاری لاگ شبکه با:").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("خطا در باز کردن منوی اشتراک‌گذاری: ${e.message}"))
        }
    }
}
