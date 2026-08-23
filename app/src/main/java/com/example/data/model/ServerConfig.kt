package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerConfig(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val protocol: String, // VLESS, VMESS, TROJAN, SHADOWSOCKS, HYSTERIA2, SOCKS, CHAIN
    val address: String,
    val port: Int,
    val uuid: String = "", // UUID or Password
    val encryption: String = "none",
    val flow: String = "", // e.g. xtls-rprx-vision
    val transportType: String = "tcp", // tcp, ws, grpc, h2, quic
    val path: String = "",
    val host: String = "",
    val security: String = "tls", // none, tls, reality
    val sni: String = "",
    val publicKey: String = "", // Reality public key
    val shortId: String = "", // Reality short ID
    val fingerprint: String = "chrome",
    val rawUri: String = "",
    val latencyMs: Long = -1, // -1: untested, -2: timeout, >0: milliseconds
    val lastTested: Long = 0,
    val subscriptionId: Long = 1L, // Linked subscription group ID
    val group: String = "پیش‌فرض",
    val countryCode: String = "US",
    val isSelected: Boolean = false,
    val isProxyChain: Boolean = false, // True if this is a 2-hop proxy chain
    val chainRelayId: Long = 0L, // ID of Middle/Relay Node
    val chainExitId: Long = 0L, // ID of Final Exit Node
    val chainRelayName: String = "",
    val chainExitName: String = "",
    val addedAt: Long = System.currentTimeMillis()
) {
    fun getDisplayCountry(): String {
        if (isProxyChain || protocol.equals("CHAIN", ignoreCase = true)) {
            return "🔗 زنجیره پروکسی (۲ مرحله‌ای)"
        }
        return when (countryCode.uppercase()) {
            "DE" -> "🇩🇪 Germany"
            "US" -> "🇺🇸 United States"
            "GB", "UK" -> "🇬🇧 United Kingdom"
            "NL" -> "🇳🇱 Netherlands"
            "FR" -> "🇫🇷 France"
            "TR" -> "🇹🇷 Turkey"
            "IR" -> "🇮🇷 Iran"
            "SG" -> "🇸🇬 Singapore"
            "JP" -> "🇯🇵 Japan"
            "CA" -> "🇨🇦 Canada"
            "FI" -> "🇫🇮 Finland"
            "SE" -> "🇸🇪 Sweden"
            else -> "🌐 Global"
        }
    }

    fun getCountryFlag(): String {
        if (isProxyChain || protocol.equals("CHAIN", ignoreCase = true)) {
            return "🔗"
        }
        return when (countryCode.uppercase()) {
            "DE" -> "🇩🇪"
            "US" -> "🇺🇸"
            "GB", "UK" -> "🇬🇧"
            "NL" -> "🇳🇱"
            "FR" -> "🇫🇷"
            "TR" -> "🇹🇷"
            "IR" -> "🇮🇷"
            "SG" -> "🇸🇬"
            "JP" -> "🇯🇵"
            "CA" -> "🇨🇦"
            "FI" -> "🇫🇮"
            "SE" -> "🇸🇪"
            else -> "⚡"
        }
    }

    val isReality: Boolean
        get() = security.equals("reality", ignoreCase = true) || publicKey.isNotBlank()

    val isTls: Boolean
        get() = security.equals("tls", ignoreCase = true) || isReality
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SWITCHING,
    TESTING_CONNECTION,
    FAILED,
    DISCONNECTING
}

enum class RoutingMode(val title: String, val description: String) {
    BYPASS_LAN_AND_IRAN("دور زدن سایت‌های ایرانی و شبکه محلی", "سایت‌های داخلی و بانکی مستقیم، سایت‌های تحریم و فیلتر از پروکسی"),
    GLOBAL("پروکسی سراسری (کل گوشی)", "تمام ترافیک و نرم‌افزارهای دستگاه از تونل وی‌پی‌ان عبور می‌کنند"),
    DIRECT("اتصال مستقیم (بدون پروکسی)", "عبور بدون فیلترشکن برای تمام ترافیک دستگاه")
}

data class SpeedStats(
    val downloadBps: Long = 0,
    val uploadBps: Long = 0,
    val totalDownloadedBytes: Long = 0,
    val totalUploadedBytes: Long = 0,
    val connectedDurationSeconds: Long = 0
) {
    fun formatDownloadSpeed(): String = formatSpeed(downloadBps)
    fun formatUploadSpeed(): String = formatSpeed(uploadBps)
    fun formatTotalDownloaded(): String = formatBytes(totalDownloadedBytes)
    fun formatTotalUploaded(): String = formatBytes(totalUploadedBytes)

    fun formatDuration(): String {
        val hours = connectedDurationSeconds / 3600
        val minutes = (connectedDurationSeconds % 3600) / 60
        val seconds = connectedDurationSeconds % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
