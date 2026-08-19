package com.example.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogCategory(val displayName: String) {
    ALL("همه"),
    CONNECTION("اتصال"),
    DNS("DNS"),
    ROUTING("مسیریابی"),
    ERROR("خطاها"),
    METRICS("عملکرد")
}

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    DEBUG
}

data class VpnLogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val category: LogCategory,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val detail: String = ""
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    fun toFormattedString(): String {
        val levelStr = when (level) {
            LogLevel.INFO -> "[INFO]"
            LogLevel.WARN -> "[WARN]"
            LogLevel.ERROR -> "[ERROR]"
            LogLevel.DEBUG -> "[DEBUG]"
        }
        val catStr = "[${category.name}]"
        return "$formattedTime $levelStr $catStr $tag: $message ${if (detail.isNotBlank()) "($detail)" else ""}"
    }
}

data class LiveNetworkMetrics(
    val latencyMs: Long = -1,
    val packetLossPercent: Float = 0f,
    val activeTcpConnections: Int = 0,
    val activeUdpSessions: Int = 0,
    val totalDnsQueries: Long = 0,
    val totalDnsErrors: Long = 0,
    val totalPacketsProcessed: Long = 0,
    val totalPacketsDropped: Long = 0
)
