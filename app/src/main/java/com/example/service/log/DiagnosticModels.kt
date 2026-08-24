package com.example.service.log

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class DiagnosticLogLevel {
    NORMAL,
    DIAGNOSTIC,
    DEEP_DEBUG
}

enum class SessionLifecycleState {
    CREATED,
    DNS_RESOLVED,
    CONNECTING,
    CONNECTED,
    ACTIVE,
    CLOSING,
    CLOSED,
    FAILED
}

enum class AnomalyType {
    DNS_SLOW,
    DNS_FAILURE,
    FAKEDNS_MAPPING_MISS,
    TCP_RETRANSMISSION,
    TCP_STALL,
    DUPLICATE_ACK,
    ZERO_WINDOW,
    UNEXPECTED_RST,
    UNEXPECTED_FIN,
    UDP_TIMEOUT,
    OUTBOUND_HANDSHAKE_FAILURE,
    MTU_PROBLEM,
    CONNECT_TIMEOUT,
    TLS_TIMEOUT,
    PROXY_HANDSHAKE_TIMEOUT,
    READ_TIMEOUT,
    WRITE_TIMEOUT
}

data class AnomalyRecord(
    val type: AnomalyType,
    val sessionId: Long,
    val timestamp: Long,
    val evidence: String,
    val destination: String = "",
    val details: String = ""
)

data class OutboundTimingBreakdown(
    var dnsTimeMs: Long = 0L,
    var tcpConnectTimeMs: Long = 0L,
    var tlsHandshakeTimeMs: Long = 0L,
    var proxyHandshakeTimeMs: Long = 0L,
    var firstByteTimeMs: Long = 0L
)

class DiagnosticSession(
    val sessionId: Long,
    val protocol: String, // TCP / UDP / DNS / ICMP
    val destIp: String,
    val destPort: Int,
    val srcVirtualIp: String = "172.19.0.1",
    val srcPort: Int = 0,
    var hostname: String = "",
    var isFakeDns: Boolean = false,
    var fakeIp: String = "",
    var appPackage: String = ""
) {
    val creationTime = System.currentTimeMillis()
    var closeTime = 0L

    var state = SessionLifecycleState.CREATED
    var failureReason: String = ""
    var closeReason: String = "NORMAL"

    var outboundProtocol: String = "DIRECT"
    var outboundServer: String = ""
    var outboundPort: Int = 0
    var transport: String = "TCP"
    var tlsVersion: String = ""
    var tlsCipher: String = ""
    var sni: String = ""
    var alpn: String = ""

    val timing = OutboundTimingBreakdown()

    val bytesUploaded = AtomicLong(0L)
    val bytesDownloaded = AtomicLong(0L)
    val packetsIn = AtomicInteger(0)
    val packetsOut = AtomicInteger(0)

    val retransmissions = AtomicInteger(0)
    val duplicateAcks = AtomicInteger(0)
    val timeouts = AtomicInteger(0)
    val rstCount = AtomicInteger(0)
    val errorCount = AtomicInteger(0)

    // TCP sequence tracking for retransmission & anomaly detection
    var lastClientSeq: Long = 0L
    var lastClientAck: Long = 0L
    var lastMySeq: Long = 0L
    var lastWindowSize: Int = 65535
    var lastActiveTime = System.currentTimeMillis()

    val sentPacketsHistory = ConcurrentHashMap<Long, Long>() // Seq -> Timestamp

    fun getDurationSec(): Double {
        val end = if (closeTime > 0) closeTime else System.currentTimeMillis()
        return (end - creationTime) / 1000.0
    }
}
