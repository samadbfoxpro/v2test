package com.example.service.log

import android.util.Log
import com.example.data.model.LiveNetworkMetrics
import com.example.data.model.LogCategory
import com.example.data.model.LogLevel
import com.example.data.model.VpnLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object VpnLogger {
    private const val MAX_LOGS = 600
    private const val TAG = "VpnLogger"

    private val logDeque = ConcurrentLinkedDeque<VpnLogEntry>()
    private val _logsFlow = MutableStateFlow<List<VpnLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<VpnLogEntry>> = _logsFlow.asStateFlow()

    // Real-time metric counters
    val activeTcpCount = AtomicInteger(0)
    val activeUdpCount = AtomicInteger(0)
    val totalDnsQueriesCount = AtomicLong(0)
    val totalDnsErrorsCount = AtomicLong(0)
    val totalPacketsProcessedCount = AtomicLong(0)
    val totalPacketsDroppedCount = AtomicLong(0)
    val currentLatencyMs = AtomicLong(-1)

    private val _liveMetricsFlow = MutableStateFlow(LiveNetworkMetrics())
    val liveMetricsFlow: StateFlow<LiveNetworkMetrics> = _liveMetricsFlow.asStateFlow()

    fun log(
        category: LogCategory,
        level: LogLevel,
        tag: String,
        message: String,
        detail: String = ""
    ) {
        val entry = VpnLogEntry(
            category = category,
            level = level,
            tag = tag,
            message = message,
            detail = detail
        )

        // Also output to Android logcat for adb/terminal debugging
        when (level) {
            LogLevel.INFO -> Log.i(tag, "[${category.name}] $message $detail")
            LogLevel.WARN -> Log.w(tag, "[${category.name}] $message $detail")
            LogLevel.ERROR -> Log.e(tag, "[${category.name}] $message $detail")
            LogLevel.DEBUG -> Log.d(tag, "[${category.name}] $message $detail")
        }

        logDeque.addFirst(entry)
        while (logDeque.size > MAX_LOGS) {
            logDeque.pollLast()
        }

        _logsFlow.value = logDeque.toList()
        updateMetricsSnapshot()

        // Stream into active flight recorder session if enabled
        LogRecorder.recordEvent(tag, message, detail, level.name)
    }

    fun logConnection(tag: String, message: String, detail: String = "", level: LogLevel = LogLevel.INFO) {
        log(LogCategory.CONNECTION, level, tag, message, detail)
    }

    fun logDns(domain: String, upstreamIp: String, latencyMs: Long, isSuccess: Boolean, errorMsg: String = "") {
        totalDnsQueriesCount.incrementAndGet()
        if (!isSuccess) {
            totalDnsErrorsCount.incrementAndGet()
            log(
                category = LogCategory.DNS,
                level = LogLevel.WARN,
                tag = "DNS",
                message = "خطا در حل دامنه: $domain",
                detail = "سرور: $upstreamIp | خطا: $errorMsg"
            )
        } else {
            log(
                category = LogCategory.DNS,
                level = LogLevel.INFO,
                tag = "DNS",
                message = "حل دامنه $domain",
                detail = "سرور: $upstreamIp | تاخیر: ${latencyMs}ms"
            )
        }
    }

    fun logRouting(target: String, protocol: String, action: String, detail: String = "") {
        log(
            category = LogCategory.ROUTING,
            level = LogLevel.DEBUG,
            tag = "Routing",
            message = "مسیر $protocol $target -> $action",
            detail = detail
        )
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val detail = throwable?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: ""
        log(LogCategory.ERROR, LogLevel.ERROR, tag, message, detail)
    }

    fun logMetric(tag: String, message: String, detail: String = "") {
        log(LogCategory.METRICS, LogLevel.INFO, tag, message, detail)
    }

    fun updateLatency(latency: Long) {
        currentLatencyMs.set(latency)
        updateMetricsSnapshot()
    }

    fun clearLogs() {
        logDeque.clear()
        _logsFlow.value = emptyList()
        updateMetricsSnapshot()
    }

    private fun updateMetricsSnapshot() {
        val processed = totalPacketsProcessedCount.get()
        val dropped = totalPacketsDroppedCount.get()
        val lossPercent = if (processed + dropped > 0) {
            (dropped.toFloat() / (processed + dropped).toFloat()) * 100f
        } else 0f

        _liveMetricsFlow.value = LiveNetworkMetrics(
            latencyMs = currentLatencyMs.get(),
            packetLossPercent = lossPercent.coerceIn(0f, 100f),
            activeTcpConnections = activeTcpCount.get().coerceAtLeast(0),
            activeUdpSessions = activeUdpCount.get().coerceAtLeast(0),
            totalDnsQueries = totalDnsQueriesCount.get(),
            totalDnsErrors = totalDnsErrorsCount.get(),
            totalPacketsProcessed = processed,
            totalPacketsDropped = dropped
        )
    }

    fun getExportableLogs(): String {
        val sb = StringBuilder()
        sb.append("==== v2rayNG VPN Diagnostics & Network Logs ====\n")
        sb.append("تعداد لاگ‌ها: ${logDeque.size}\n")
        sb.append("زمان استخراج: ${java.util.Date()}\n")
        sb.append("------------------------------------------------\n")
        for (entry in logDeque.toList().reversed()) {
            sb.append(entry.toFormattedString()).append("\n")
        }
        return sb.toString()
    }
}
