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
import com.example.data.model.RoutingMode
import com.example.data.model.ServerConfig
import com.example.data.model.SpeedStats
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

    private val uploadedBytesCounter = AtomicLong(0)
    private val downloadedBytesCounter = AtomicLong(0)

    companion object {
        const val ACTION_CONNECT = "com.example.v2rayng.CONNECT"
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

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "v2rayng_vpn_channel"
        private const val TAG = "XrayVpnService"

        private val _isVpnRunning = MutableStateFlow(false)
        val isVpnRunning: StateFlow<Boolean> = _isVpnRunning.asStateFlow()

        private val _activeServerName = MutableStateFlow("")
        val activeServerName: StateFlow<String> = _activeServerName.asStateFlow()

        private val _liveSpeedStats = MutableStateFlow(SpeedStats())
        val liveSpeedStats: StateFlow<SpeedStats> = _liveSpeedStats.asStateFlow()

        @Volatile
        private var lastConfig: ServerConfig? = null

        fun startVpn(context: Context, server: ServerConfig, routingMode: RoutingMode) {
            lastConfig = server
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_CONNECT
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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_CONNECT -> {
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

                val serverConfig = lastConfig ?: ServerConfig(
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
                    transportType = transport
                )

                val notification = createNotification("متصل به $serverName", "هسته v2rayNG در حال تونل کردن ترافیک کل گوشی است")
                startForeground(NOTIFICATION_ID, notification)

                establishVpnTunnel(serverConfig, routingMode)
            }
            ACTION_DISCONNECT -> {
                closeVpnTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    /**
     * Creates and configures the Android System TUN interface and initializes the packet forwarder pump.
     */
    private fun establishVpnTunnel(serverConfig: ServerConfig, routingMode: RoutingMode) {
        serviceScope.launch {
            try {
                // Stop previous tunnel & pump
                closeVpnTunnel()

                val builder = Builder().apply {
                    setSession("v2rayNG: ${serverConfig.name}")
                    setMtu(1500)
                    // Virtual IP inside tunnel
                    addAddress("172.19.0.1", 30)
                    addDnsServer("1.1.1.1")
                    addDnsServer("8.8.8.8")

                    // Route all IPv4 traffic through the VPN TUN
                    addRoute("0.0.0.0", 0)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            setHttpProxy(android.net.ProxyInfo.buildDirectProxy("127.0.0.1", com.example.service.proxy.LocalProxyServer.HTTP_PORT))
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not set HTTP proxy: ${e.message}")
                        }
                    }

                    try {
                        addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set disallowed app: ${e.message}")
                    }

                    setBlocking(false)
                }

                vpnInterface = builder.establish()

                if (vpnInterface != null) {
                    _isVpnRunning.value = true
                    _activeServerName.value = serverConfig.name
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

                    startSpeedMonitoring()
                    Log.i(TAG, "VPN TUN and Packet Pump successfully active for ${serverConfig.name}")
                } else {
                    _isVpnRunning.value = false
                    Log.e(TAG, "Failed to establish VPN interface")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error establishing VPN: ${e.message}", e)
                _isVpnRunning.value = false
            }
        }
    }

    private fun startSpeedMonitoring() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var prevUp = 0L
            var prevDown = 0L
            var seconds = 0L

            while (isActive && _isVpnRunning.value) {
                delay(1000)
                seconds++
                val curUp = uploadedBytesCounter.get()
                val curDown = downloadedBytesCounter.get()

                val upSpeed = (curUp - prevUp).coerceAtLeast(0)
                val downSpeed = (curDown - prevDown).coerceAtLeast(0)

                prevUp = curUp
                prevDown = curDown

                _liveSpeedStats.value = SpeedStats(
                    downloadBps = downSpeed,
                    uploadBps = upSpeed,
                    totalDownloadedBytes = curDown,
                    totalUploadedBytes = curUp,
                    connectedDurationSeconds = seconds
                )
            }
        }
    }

    private fun closeVpnTunnel() {
        statsJob?.cancel()
        statsJob = null
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
            _liveSpeedStats.value = SpeedStats()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
                "سرویس فعال v2rayNG VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نمایش وضعیت اتصال و مصرف دیتای v2rayNG"
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
