package com.example.service.log

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DiagnosticAnalyzer {

    val sessions = ConcurrentHashMap<Long, DiagnosticSession>()
    val anomalies = ConcurrentLinkedQueue<AnomalyRecord>()

    val totalDnsQueries = AtomicInteger(0)
    val failedDnsQueries = AtomicInteger(0)
    val totalDnsLatencyMs = AtomicLong(0L)

    val fakeDnsMisses = AtomicInteger(0)
    val udpTimeouts = AtomicInteger(0)

    fun registerSession(session: DiagnosticSession) {
        sessions[session.sessionId] = session
    }

    fun getSession(sessionId: Long): DiagnosticSession? = sessions[sessionId]

    fun recordAnomaly(type: AnomalyType, sessionId: Long, evidence: String, destination: String = "", details: String = "") {
        val record = AnomalyRecord(
            type = type,
            sessionId = sessionId,
            timestamp = System.currentTimeMillis(),
            evidence = evidence,
            destination = destination,
            details = details
        )
        anomalies.add(record)
        sessions[sessionId]?.errorCount?.incrementAndGet()
    }

    fun generateAnalysisReport(): String = buildString {
        val allSessions = sessions.values.toList()
        val totalSessions = allSessions.size
        val failedSessions = allSessions.count { it.state == SessionLifecycleState.FAILED }
        val timeoutCount = allSessions.sumOf { it.timeouts.get() }
        val retransmissionCount = allSessions.sumOf { it.retransmissions.get() }
        val rstCount = allSessions.sumOf { it.rstCount.get() }
        val dnsFailures = failedDnsQueries.get()
        val fakeDnsMissCount = fakeDnsMisses.get()
        val udpTimeoutCount = udpTimeouts.get()

        val dnsQueriesCount = totalDnsQueries.get().coerceAtLeast(1)
        val avgDnsLatency = totalDnsLatencyMs.get() / dnsQueriesCount

        val connectedSessions = allSessions.filter { it.timing.tcpConnectTimeMs > 0 }
        val avgConnectLatency = if (connectedSessions.isNotEmpty()) connectedSessions.map { it.timing.tcpConnectTimeMs }.average() else 0.0

        val tlsSessions = allSessions.filter { it.timing.tlsHandshakeTimeMs > 0 }
        val avgTlsLatency = if (tlsSessions.isNotEmpty()) tlsSessions.map { it.timing.tlsHandshakeTimeMs }.average() else 0.0

        val firstByteSessions = allSessions.filter { it.timing.firstByteTimeMs > 0 }
        val avgFirstByteLatency = if (firstByteSessions.isNotEmpty()) firstByteSessions.map { it.timing.firstByteTimeMs }.average() else 0.0

        appendLine()
        appendLine("================================================================================")
        appendLine("                         === DIAGNOSTIC SUMMARY ===")
        appendLine("================================================================================")
        appendLine("• Total Sessions Tracked       : $totalSessions")
        appendLine("• Failed Sessions              : $failedSessions")
        appendLine("• Timeout Count                : $timeoutCount")
        appendLine("• TCP Retransmission Count     : $retransmissionCount")
        appendLine("• RST Packet Count             : $rstCount")
        appendLine("• DNS Resolution Failures      : $dnsFailures")
        appendLine("• FakeDNS Mapping Misses       : $fakeDnsMissCount")
        appendLine("• UDP Flow Timeouts            : $udpTimeoutCount")
        appendLine("• Average DNS Latency          : ${avgDnsLatency}ms")
        appendLine("• Average TCP Connect Latency  : ${String.format("%.1f", avgConnectLatency)}ms")
        appendLine("• Average TLS Handshake Latency: ${String.format("%.1f", avgTlsLatency)}ms")
        appendLine("• Average First-Byte Latency   : ${String.format("%.1f", avgFirstByteLatency)}ms")

        // Top Destination Errors
        val destinationErrors = anomalies.groupBy { it.destination.ifBlank { "Unknown" } }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }.take(5)

        if (destinationErrors.isNotEmpty()) {
            appendLine()
            appendLine("• Top Error Destinations:")
            destinationErrors.forEach { (dest, count) ->
                appendLine("   - $dest : $count error events")
            }
        }

        // Top Anomaly Types
        val anomalyTypeCounts = anomalies.groupBy { it.type }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }.take(5)

        if (anomalyTypeCounts.isNotEmpty()) {
            appendLine()
            appendLine("• Top Anomaly Types:")
            anomalyTypeCounts.forEach { (type, count) ->
                appendLine("   - ${type.name} : $count occurrences")
            }
        }

        // Suspicious Sessions
        appendLine()
        appendLine("================================================================================")
        appendLine("                   === MOST SUSPICIOUS SESSIONS (TOP 10) ===")
        appendLine("================================================================================")

        val suspicious = allSessions.filter {
            it.state == SessionLifecycleState.FAILED ||
            it.retransmissions.get() > 0 ||
            it.timeouts.get() > 0 ||
            it.rstCount.get() > 0 ||
            it.duplicateAcks.get() > 3
        }.sortedByDescending {
            it.retransmissions.get() * 3 + it.timeouts.get() * 4 + it.rstCount.get() * 2 + (if (it.state == SessionLifecycleState.FAILED) 5 else 0)
        }.take(10)

        if (suspicious.isEmpty()) {
            appendLine("هیچ نشست مشکوک یا خطای بحرانی در طول این ضبط شناسایی نشد (ترافیک پایدار).")
        } else {
            suspicious.forEachIndexed { idx, s ->
                val target = s.hostname.ifBlank { "${s.destIp}:${s.destPort}" }
                val reasons = mutableListOf<String>()
                if (s.state == SessionLifecycleState.FAILED) reasons.add("FAILED (${s.failureReason})")
                if (s.retransmissions.get() > 0) reasons.add("${s.retransmissions.get()} Retransmissions")
                if (s.timeouts.get() > 0) reasons.add("${s.timeouts.get()} Timeouts")
                if (s.rstCount.get() > 0) reasons.add("${s.rstCount.get()} RST packets")
                if (s.duplicateAcks.get() > 2) reasons.add("${s.duplicateAcks.get()} Dup-ACKs")

                appendLine("[#${idx + 1}] Session ${s.sessionId} -> $target (${s.protocol})")
                appendLine("    • دلایل: ${reasons.joinToString(", ")}")
                appendLine("    • جزئیات: Duration: ${String.format("%.2f", s.getDurationSec())}s | Up: ${s.bytesUploaded.get()}B, Down: ${s.bytesDownloaded.get()}B | Outbound: ${s.outboundProtocol}/${s.transport}")
            }
        }

        // AI Diagnostic Hints
        appendLine()
        appendLine("================================================================================")
        appendLine("                      === AI DIAGNOSTIC HINTS ===")
        appendLine("================================================================================")

        val hints = mutableListOf<String>()

        if (fakeDnsMissCount > 0) {
            hints.add("⚠️ Evidence: تعداد $fakeDnsMissCount عدم تطابق FakeDNS ثبت شد. این نشانه ارسال پکت به IPهای فرضی ۱۹۸.۱۸ قبل از رزولوشن است.")
        }
        if (retransmissionCount > 5) {
            hints.add("⚠️ Evidence: تعداد $retransmissionCount بسته TCP Retransmission در لاگ وجود دارد که نشان‌دهنده پکت‌لاس در مسیر سرور یا فیلترینگ است.")
        }
        if (rstCount > 3) {
            hints.add("⚠️ Evidence: دریافت $rstCount بسته RST نشان‌دهنده ریست شدن ارتباط توسط فایروال یا عدم پذیرش پورت مقصد است.")
        }
        if (dnsFailures > 0) {
            hints.add("⚠️ Evidence: تعداد $dnsFailures شکست در DNS ثبت شد. ریزالور فعال پاسخ نداده و Failover انجام شده است.")
        }
        if (avgConnectLatency > 600) {
            hints.add("⚠️ Evidence: میانگین زمان اتصال TCP معادل ${String.format("%.0f", avgConnectLatency)}ms است که نشان‌دهنده پینگ بالای سرور پروکسی است.")
        }
        if (avgTlsLatency > 800) {
            hints.add("⚠️ Evidence: زمان هندشیک TLS معادل ${String.format("%.0f", avgTlsLatency)}ms است؛ در صورت وجود فیلترینگ SNI، فعال‌سازی Fragment پیشنهاد می‌شود.")
        }

        if (hints.isEmpty()) {
            hints.add("✅ بر اساس شواهد موجود، پارامترهای پکت، هندشیک و تاخیرهای ثبت‌شده در محدوده نرمال قرار دارند.")
        }

        hints.forEach { hint ->
            appendLine("• $hint")
        }
        appendLine("================================================================================")
    }

    fun clear() {
        sessions.clear()
        anomalies.clear()
        totalDnsQueries.set(0)
        failedDnsQueries.set(0)
        totalDnsLatencyMs.set(0L)
        fakeDnsMisses.set(0)
        udpTimeouts.set(0)
    }
}
