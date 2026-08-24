package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.ConnectionStatus
import com.example.data.model.RoutingMode
import com.example.data.model.ServerConfig
import com.example.data.model.SpeedStats
import com.example.data.ping.PingManager
import com.example.service.log.VpnLogger
import com.example.service.tun.TunPacketPump
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class XrayVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetPump: TunPacketPump? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var statsJob: Job? = null
    private var latencyJob: Job? = null

    private val uploadedBytesCounter = AtomicLong(0)
    private val downloadedBytesCounter = AtomicLong(0)

    companion object {
        const val ACTION_CONNECT = "com.example.v2rayng.CONNECT"
        const val ACTION_SWITCH = "com.example.v2rayng.SWITCH"
        const val ACTION_DISCONNECT = "com.example.v2rayng.DISCONNECT"
        const val EXTRA_SERVER_NAME = "extra_server_name"
        const val EXTRA_SERVER_ADDRESS = "extra_server_address"
        const val EXTRA_SERVER_PORT = "extra_server_port"
        const val EXTRA_SERVER_PROTOCOL = "extra_server_protocol"
        const val EXTRA_SERVER_UUID = "extra_server_uuid"
        const val EXTRA_SERVER_SECURITY = "extra_server_security"
        const val EXTRA_SERVER_SNI = "extra_server_sni"
        const val EXTRA_SERVER_PUBLIC_KEY = "extra_server_public_key"
        const val EXTRA_SERVER_SHORT_ID = "extra_server_short_id"
        const val EXTRA_SERVER_FLOW = "extra_server_flow"
        const val EXTRA_SERVER_TRANSPORT = "extra_server_transport"
        const val EXTRA_ROUTING_MODE = "extra_routing_mode"

        const val EXTRA_IS_PROXY_CHAIN = "extra_is_proxy_chain"
        const val EXTRA_CHAIN_RELAY_ID = "extra_chain_relay_id"
        const val EXTRA_CHAIN_EXIT_ID = "extra_chain_exit_id"
        const val EXTRA_SERVER_ID = "extra_server_id"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "v2rayng_vpn_channel"
        private const val TAG = "XrayVpnService"

        private val _isVpnRunning = MutableStateFlow(false)
        val isVpnRunning: StateFlow<Boolean> = _isVpnRunning.asStateFlow()

        private val _activeServerName = MutableStateFlow("")
        val activeServerName: StateFlow<String> = _activeServerName.asStateFlow()

        private val _activeServerConfig = MutableStateFlow<ServerConfig?>(null)
        val activeServerConfig: StateFlow<ServerConfig?> = _activeServerConfig.asStateFlow()

        private val _vpnConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
        val vpnConnectionStatus: StateFlow<ConnectionStatus> = _vpnConnectionStatus.asStateFlow()

        private val _liveSpeedStats = MutableStateFlow(SpeedStats())
        val liveSpeedStats: StateFlow<SpeedStats> = _liveSpeedStats.asStateFlow()

        val globalSmartDnsEngine = com.example.service.dns.SmartDnsEngine(null)

        @Volatile
        var instance: XrayVpnService? = null
            private set

        @Volatile
        private var lastConfig: ServerConfig? = null

        fun startVpn(context: Context, server: ServerConfig, routingMode: RoutingMode) {
            lastConfig = server
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_SERVER_ID, server.id)
                putExtra(EXTRA_SERVER_NAME, server.name)
                putExtra(EXTRA_SERVER_ADDRESS, server.address)
                putExtra(EXTRA_SERVER_PORT, server.port)
                putExtra(EXTRA_SERVER_PROTOCOL, server.protocol)
                putExtra(EXTRA_SERVER_UUID, server.uuid)
                putExtra(EXTRA_SERVER_SECURITY, server.security)
                putExtra(EXTRA_SERVER_SNI, server.sni)
                putExtra(EXTRA_SERVER_PUBLIC_KEY, server.publicKey)
                putExtra(EXTRA_SERVER_SHORT_ID, server.shortId)
                putExtra(EXTRA_SERVER_FLOW, server.flow)
                putExtra(EXTRA_SERVER_TRANSPORT, server.transportType)
                putExtra(EXTRA_IS_PROXY_CHAIN, server.isProxyChain)
                putExtra(EXTRA_CHAIN_RELAY_ID, server.chainRelayId)
                putExtra(EXTRA_CHAIN_EXIT_ID, server.chainExitId)
                putExtra(EXTRA_ROUTING_MODE, routingMode.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun switchServer(context: Context, server: ServerConfig, routingMode: RoutingMode) {
            lastConfig = server
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_SWITCH
                putExtra(EXTRA_SERVER_ID, server.id)
                putExtra(EXTRA_SERVER_NAME, server.name)
                putExtra(EXTRA_SERVER_ADDRESS, server.address)
                putExtra(EXTRA_SERVER_PORT, server.port)
                putExtra(EXTRA_SERVER_PROTOCOL, server.protocol)
                putExtra(EXTRA_SERVER_UUID, server.uuid)
                putExtra(EXTRA_SERVER_SECURITY, server.security)
                putExtra(EXTRA_SERVER_SNI, server.sni)
                putExtra(EXTRA_SERVER_PUBLIC_KEY, server.publicKey)
                putExtra(EXTRA_SERVER_SHORT_ID, server.shortId)
                putExtra(EXTRA_SERVER_FLOW, server.flow)
                putExtra(EXTRA_SERVER_TRANSPORT, server.transportType)
                putExtra(EXTRA_IS_PROXY_CHAIN, server.isProxyChain)
                putExtra(EXTRA_CHAIN_RELAY_ID, server.chainRelayId)
                putExtra(EXTRA_CHAIN_EXIT_ID, server.chainExitId)
                putExtra(EXTRA_ROUTING_MODE, routingMode.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopVpn(context: Context) {
            lastConfig = null
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.example.data.ping.PingManager.init(applicationContext)
        com.example.service.proxy.LanSharingManager.init(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "Xray Server"
        val serverAddress = intent.getStringExtra(EXTRA_SERVER_ADDRESS) ?: ""
        val serverPort = intent.getIntExtra(EXTRA_SERVER_PORT, 443)
        val protocol = intent.getStringExtra(EXTRA_SERVER_PROTOCOL) ?: "VLESS"
        val uuid = intent.getStringExtra(EXTRA_SERVER_UUID) ?: ""
        val security = intent.getStringExtra(EXTRA_SERVER_SECURITY) ?: "tls"
        val sni = intent.getStringExtra(EXTRA_SERVER_SNI) ?: ""
        val publicKey = intent.getStringExtra(EXTRA_SERVER_PUBLIC_KEY) ?: ""
        val shortId = intent.getStringExtra(EXTRA_SERVER_SHORT_ID) ?: ""
        val flow = intent.getStringExtra(EXTRA_SERVER_FLOW) ?: ""
        val transport = intent.getStringExtra(EXTRA_SERVER_TRANSPORT) ?: "tcp"
        val routingModeName = intent.getStringExtra(EXTRA_ROUTING_MODE) ?: RoutingMode.BYPASS_LAN_AND_IRAN.name
        val routingMode = try { RoutingMode.valueOf(routingModeName) } catch (_: Exception) { RoutingMode.BYPASS_LAN_AND_IRAN }

        val isProxyChain = intent.getBooleanExtra(EXTRA_IS_PROXY_CHAIN, false)
        val chainRelayId = intent.getLongExtra(EXTRA_CHAIN_RELAY_ID, 0L)
        val chainExitId = intent.getLongExtra(EXTRA_CHAIN_EXIT_ID, 0L)
        val serverId = intent.getLongExtra(EXTRA_SERVER_ID, 0L)

        val serverConfig = lastConfig ?: ServerConfig(
            id = serverId,
            name = serverName,
            protocol = protocol,
            address = serverAddress,
            port = serverPort,
            uuid = uuid,
            security = security,
            sni = sni,
            publicKey = publicKey,
            shortId = shortId,
            flow = flow,
            transportType = transport,
            isProxyChain = isProxyChain,
            chainRelayId = chainRelayId,
            chainExitId = chainExitId
        )

        when (action) {
            ACTION_CONNECT -> {
                VpnLogger.logConnection(TAG, "شروع فرآیند اتصال به سرور: $serverName (${serverConfig.address}:${serverConfig.port})")
                val notification = createNotification("متصل به $serverName", "سرویس Shadow VPN در حال برقراری تونل ترافیک امن است")
                startForeground(NOTIFICATION_ID, notification)
                establishVpnTunnel(serverConfig, routingMode)
            }
            ACTION_SWITCH -> {
                handleSwitchServer(serverConfig, routingMode)
            }
            ACTION_DISCONNECT -> {
                VpnLogger.logConnection(TAG, "درخواست قطع اتصال وی‌پی‌ان دریافت شد")
                closeVpnTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun handleSwitchServer(serverConfig: ServerConfig, routingMode: RoutingMode) {
        if (_isVpnRunning.value && vpnInterface != null && packetPump != null) {
            _vpnConnectionStatus.value = ConnectionStatus.SWITCHING
            _activeServerConfig.value = serverConfig
            _activeServerName.value = serverConfig.name
            lastConfig = serverConfig

            // Hot-reload packet pump with new outbound config without dropping VPN TUN
            packetPump?.updateServerConfig(serverConfig)

            // Reset traffic stats and restart timer from zero for this specific server
            uploadedBytesCounter.set(0)
            downloadedBytesCounter.set(0)
            startSpeedMonitoring(serverConfig.name)
            startRealtimeLatencyMonitoring(serverConfig)

            val notification = createNotification("متصل به ${serverConfig.name}", "سرویس Shadow VPN به سرور جدید متصل شد")
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, notification)

            _vpnConnectionStatus.value = ConnectionStatus.CONNECTED
            VpnLogger.logConnection(TAG, "سوئیچ آنی بدون قطعی به سرور ${serverConfig.name} با موفقیت انجام شد")
        } else {
            val notification = createNotification("متصل به ${serverConfig.name}", "سرویس Shadow VPN در حال برقراری تونل ترافیک امن است")
            startForeground(NOTIFICATION_ID, notification)
            establishVpnTunnel(serverConfig, routingMode)
        }
    }

    /**
     * Creates and configures the Android System TUN interface with IPv4 + IPv6 and starts the packet pump.
     */
    private fun establishVpnTunnel(serverConfig: ServerConfig, routingMode: RoutingMode) {
        serviceScope.launch {
            try {
                // Stop previous tunnel & pump
                closeVpnTunnel()

                val builder = Builder().apply {
                    setSession("Shadow VPN: ${serverConfig.name}")
                    setMtu(1400) // 1400 avoids MTU fragmentation on mobile networks (MSS clamp 1360)

                    // Virtual IPv4 inside tunnel - Clean standard DNS
                    addAddress("172.19.0.1", 30)
                    addDnsServer("1.1.1.1")
                    addDnsServer("8.8.8.8")
                    addRoute("0.0.0.0", 0)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            setHttpProxy(android.net.ProxyInfo.buildDirectProxy("127.0.0.1", com.example.service.proxy.LocalProxyServer.HTTP_PORT))
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not set HTTP proxy: ${e.message}")
                        }
                    }

                    // Disallow current package to prevent VPN routing loop
                    try {
                        addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set disallowed app: ${e.message}")
                    }

                    // Apply Per-App Bypass (Split Tunneling)
                    if (com.example.data.local.AppBypassManager.isBypassEnabled(this@XrayVpnService)) {
                        val bypassedPackages = com.example.data.local.AppBypassManager.getBypassedPackages(this@XrayVpnService)
                        for (pkg in bypassedPackages) {
                            try {
                                if (pkg != packageName) {
                                    addDisallowedApplication(pkg)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not disallow package $pkg: ${e.message}")
                            }
                        }
                    }

                    // Blocking mode is crucial to prevent CPU 100% busy-loop on read()
                    setBlocking(true)
                }

                vpnInterface = builder.establish()

                if (vpnInterface != null) {
                    _isVpnRunning.value = true
                    _activeServerName.value = serverConfig.name
                    _activeServerConfig.value = serverConfig
                    _vpnConnectionStatus.value = ConnectionStatus.CONNECTED
                    com.example.service.proxy.LanSharingManager.updateOutboundConfig(serverConfig)
                    uploadedBytesCounter.set(0)
                    downloadedBytesCounter.set(0)

                    // Start TUN packet forwarder pump
                    packetPump = TunPacketPump(
                        vpnService = this@XrayVpnService,
                        vpnInterface = vpnInterface!!,
                        serverConfig = serverConfig,
                        remoteDnsIp = "1.1.1.1",
                        onSpeedUpdate = { up, down ->
                            if (up > 0) uploadedBytesCounter.addAndGet(up)
                            if (down > 0) downloadedBytesCounter.addAndGet(down)
                        }
                    ).apply {
                        start()
                    }

                    startSpeedMonitoring(serverConfig.name)
                    startRealtimeLatencyMonitoring(serverConfig)
                    VpnLogger.logConnection(TAG, "رابط TUN با موفقیت راه‌اندازی شد (MTU 1400, IPv4/IPv6 فعال)")
                } else {
                    _isVpnRunning.value = false
                    _activeServerConfig.value = null
                    _vpnConnectionStatus.value = ConnectionStatus.FAILED
                    VpnLogger.logError(TAG, "خطا در establish() رابط TUN - دسترسی داده نشد")
                }
            } catch (e: Exception) {
                VpnLogger.logError(TAG, "خطای استقرار تونل وی‌پی‌ان: ${e.message}", e)
                _isVpnRunning.value = false
                _activeServerConfig.value = null
                _vpnConnectionStatus.value = ConnectionStatus.FAILED
            }
        }
    }

    private fun startSpeedMonitoring(serverName: String) {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var prevUp = 0L
            var prevDown = 0L
            var seconds = 0L

            val notificationManager = getSystemService(NotificationManager::class.java)

            while (isActive && _isVpnRunning.value) {
                delay(1000)
                seconds++
                val curUp = uploadedBytesCounter.get()
                val curDown = downloadedBytesCounter.get()

                val upSpeed = (curUp - prevUp).coerceAtLeast(0)
                val downSpeed = (curDown - prevDown).coerceAtLeast(0)

                prevUp = curUp
                prevDown = curDown

                val currentStats = SpeedStats(
                    downloadBps = downSpeed,
                    uploadBps = upSpeed,
                    totalDownloadedBytes = curDown,
                    totalUploadedBytes = curUp,
                    connectedDurationSeconds = seconds
                )
                _liveSpeedStats.value = currentStats

                // Update notification text every 3 seconds
                if (seconds % 3 == 0L) {
                    val notifText = "⬇ ${currentStats.formatDownloadSpeed()}  ⬆ ${currentStats.formatUploadSpeed()}  ⏱ ${currentStats.formatDuration()}"
                    val updatedNotification = createNotification("متصل به $serverName", notifText)
                    notificationManager?.notify(NOTIFICATION_ID, updatedNotification)
                }
            }
        }
    }

    private fun startRealtimeLatencyMonitoring(serverConfig: ServerConfig) {
        latencyJob?.cancel()
        latencyJob = serviceScope.launch {
            while (isActive && _isVpnRunning.value) {
                val latency = PingManager.measureTcpLatency(serverConfig.address, serverConfig.port, timeoutMs = 3000)
                if (latency > 0) {
                    VpnLogger.updateLatency(latency)
                }
                delay(4000)
            }
        }
    }

    private fun closeVpnTunnel() {
        statsJob?.cancel()
        statsJob = null
        latencyJob?.cancel()
        latencyJob = null
        try {
            packetPump?.stop()
            packetPump = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping packet pump: ${e.message}")
        }
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface: ${e.message}")
        } finally {
            _isVpnRunning.value = false
            _activeServerName.value = ""
            _activeServerConfig.value = null
            _vpnConnectionStatus.value = ConnectionStatus.DISCONNECTED
            _liveSpeedStats.value = SpeedStats()
            VpnLogger.updateLatency(-1)
            VpnLogger.logConnection(TAG, "تونل وی‌پی‌ان متوقف شد و منابع آزاد شدند")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        closeVpnTunnel()
    }

    override fun onRevoke() {
        super.onRevoke()
        closeVpnTunnel()
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "سرویس فعال Shadow VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نمایش وضعیت اتصال و مصرف دیتای Shadow VPN"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = Intent(this, XrayVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "قطع اتصال",
                disconnectPendingIntent
            )
            .build()
    }
}
