package com.example.service.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.model.ServerConfig
import com.example.service.XrayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance, low-latency LAN Sharing Service for HTTP (port 8881) and SOCKS5 (port 10808) proxies.
 * Shares the active VPN connection & proxy bypass with any device on the local Wi-Fi / Hotspot / LAN.
 */
object LanSharingManager {

    private const val TAG = "LanSharingManager"

    private val isRunning = AtomicBoolean(false)
    private var httpServerSocket: ServerSocket? = null
    private var socksServerSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    @Volatile
    private var currentOutboundConfig: ServerConfig? = null

    private val _isLanSharingActive = MutableStateFlow(false)
    val isLanSharingActive: StateFlow<Boolean> = _isLanSharingActive.asStateFlow()

    private val _isHttpRunning = MutableStateFlow(false)
    val isHttpRunning: StateFlow<Boolean> = _isHttpRunning.asStateFlow()

    private val _isSocksRunning = MutableStateFlow(false)
    val isSocksRunning: StateFlow<Boolean> = _isSocksRunning.asStateFlow()

    private val _currentLanIp = MutableStateFlow<String?>(null)
    val currentLanIp: StateFlow<String?> = _currentLanIp.asStateFlow()

    private val _httpPort = MutableStateFlow(8881)
    val httpPort: StateFlow<Int> = _httpPort.asStateFlow()

    private val _socksPort = MutableStateFlow(10808)
    val socksPort: StateFlow<Int> = _socksPort.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    private var appContext: Context? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        refreshLanIp(context)
        registerNetworkCallback(context)
    }

    /**
     * Resolves the device's actual routable IPv4 address on the active LAN (Wi-Fi, Hotspot, or Ethernet).
     * Excludes 127.0.0.1, localhost, loopbacks, and VPN interfaces (tun, ppp).
     */
    fun getLanIpAddress(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && cm != null) {
                val activeNetwork = cm.activeNetwork
                val linkProperties = cm.getLinkProperties(activeNetwork)
                if (linkProperties != null) {
                    for (linkAddress in linkProperties.linkAddresses) {
                        val address = linkAddress.address
                        if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                            val hostAddress = address.hostAddress
                            if (!hostAddress.isNullOrBlank() && hostAddress != "127.0.0.1") {
                                return hostAddress
                            }
                        }
                    }
                }
            }

            // Fallback: Scan network interfaces for valid LAN address (192.168.x.x, 10.x.x.x, 172.16-31.x.x, rndis, wlan, ap)
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("p2p") || intf.isLoopback || !intf.isUp) {
                    continue
                }
                val addresses = intf.inetAddresses
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip != "127.0.0.1" && !ip.startsWith("169.254")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving LAN IP: ${e.message}")
        }
        return null
    }

    fun refreshLanIp(context: Context) {
        val ip = getLanIpAddress(context)
        _currentLanIp.value = ip
    }

    private fun registerNetworkCallback(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshLanIp(context)
                }

                override fun onLost(network: Network) {
                    refreshLanIp(context)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    refreshLanIp(context)
                }
            }
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback: ${e.message}")
        }
    }

    fun updateOutboundConfig(server: ServerConfig?) {
        currentOutboundConfig = server
    }

    private fun getEffectiveConfig(): ServerConfig? {
        return currentOutboundConfig
            ?: XrayVpnService.activeServerConfig.value
            ?: appContext?.let { ctx ->
                try {
                    runBlocking(Dispatchers.IO) {
                        AppDatabase.getInstance(ctx).serverDao().getSelectedServerSync()
                    }
                } catch (_: Exception) {
                    null
                }
            }
    }

    /**
     * Starts LAN Sharing with specified HTTP / SOCKS ports.
     */
    @Synchronized
    fun start(
        context: Context,
        enableHttp: Boolean,
        httpPort: Int,
        enableSocks: Boolean,
        socksPort: Int,
        activeServer: ServerConfig?
    ): Boolean {
        stop()

        _lastErrorMessage.value = null
        currentOutboundConfig = activeServer ?: XrayVpnService.activeServerConfig.value
        _httpPort.value = httpPort
        _socksPort.value = socksPort
        refreshLanIp(context)

        if (!enableHttp && !enableSocks) {
            _lastErrorMessage.value = "حداقل یکی از پروتکل‌های HTTP یا SOCKS باید فعال باشد"
            return false
        }

        if (enableHttp && enableSocks && httpPort == socksPort) {
            _lastErrorMessage.value = "پورت‌های HTTP و SOCKS نمی‌توانند یکسان باشند ($httpPort)"
            return false
        }

        if (enableHttp && (httpPort <= 0 || httpPort > 65535)) {
            _lastErrorMessage.value = "پورت HTTP نامعتبر است ($httpPort)"
            return false
        }

        if (enableSocks && (socksPort <= 0 || socksPort > 65535)) {
            _lastErrorMessage.value = "پورت SOCKS نامعتبر است ($socksPort)"
            return false
        }

        isRunning.set(true)
        executor = Executors.newCachedThreadPool()

        var httpStarted = false
        var socksStarted = false

        // Start HTTP Listener on 0.0.0.0:httpPort
        if (enableHttp) {
            try {
                httpServerSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", httpPort), 100)
                }
                httpStarted = true
                _isHttpRunning.value = true
                Log.i(TAG, "LAN Sharing: HTTP Proxy listening on 0.0.0.0:$httpPort")

                executor?.execute {
                    while (isRunning.get()) {
                        try {
                            val client = httpServerSocket?.accept() ?: break
                            executor?.execute { handleHttpClient(client) }
                        } catch (e: Exception) {
                            if (isRunning.get()) {
                                Log.w(TAG, "HTTP accept error: ${e.message}")
                            }
                        }
                    }
                    _isHttpRunning.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed starting HTTP listener on port $httpPort: ${e.message}")
                _lastErrorMessage.value = "خطا در پورت HTTP ($httpPort): پورت در حال استفاده است یا دسترسی مسدود است"
            }
        }

        // Start SOCKS5 Listener on 0.0.0.0:socksPort
        if (enableSocks) {
            try {
                socksServerSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", socksPort), 100)
                }
                socksStarted = true
                _isSocksRunning.value = true
                Log.i(TAG, "LAN Sharing: SOCKS5 Proxy listening on 0.0.0.0:$socksPort")

                executor?.execute {
                    while (isRunning.get()) {
                        try {
                            val client = socksServerSocket?.accept() ?: break
                            executor?.execute { handleSocksClient(client) }
                        } catch (e: Exception) {
                            if (isRunning.get()) {
                                Log.w(TAG, "SOCKS accept error: ${e.message}")
                            }
                        }
                    }
                    _isSocksRunning.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed starting SOCKS listener on port $socksPort: ${e.message}")
                val prev = _lastErrorMessage.value
                _lastErrorMessage.value = if (prev != null) "$prev | پورت SOCKS ($socksPort) در حال استفاده است" else "خطا در پورت SOCKS ($socksPort): پورت در حال استفاده است"
            }
        }

        val success = httpStarted || socksStarted
        _isLanSharingActive.value = success
        if (!success) {
            stop()
        }
        return success
    }

    /**
     * Completely stops all LAN Sharing listeners and closes sockets.
     */
    @Synchronized
    fun stop() {
        isRunning.set(false)
        _isLanSharingActive.value = false
        _isHttpRunning.value = false
        _isSocksRunning.value = false

        try { httpServerSocket?.close() } catch (_: Exception) {}
        try { socksServerSocket?.close() } catch (_: Exception) {}
        try { executor?.shutdownNow() } catch (_: Exception) {}

        httpServerSocket = null
        socksServerSocket = null
        executor = null
    }

    /**
     * Opens an outbound stream to the target host and port through the active VPN / Proxy pipeline.
     */
    private fun openOutboundTunnel(targetHost: String, targetPort: Int): ProxyTunnel? {
        val activeConfig = getEffectiveConfig()

        // Strategy 1: If VPN is running and LocalProxyServer is listening on 127.0.0.1:10808, route through it
        if (XrayVpnService.isVpnRunning.value) {
            try {
                val socksSocket = Socket()
                socksSocket.tcpNoDelay = true
                socksSocket.soTimeout = 8000
                socksSocket.connect(InetSocketAddress("127.0.0.1", LocalProxyServer.SOCKS_PORT), 4000)

                val sIn = socksSocket.inputStream
                val sOut = socksSocket.outputStream

                // SOCKS5 Handshake: NO AUTH (0x05, 0x01, 0x00)
                sOut.write(byteArrayOf(0x05, 0x01, 0x00))
                sOut.flush()

                val ver = sIn.read()
                val auth = sIn.read()
                if (ver == 0x05 && auth == 0x00) {
                    // SOCKS5 CONNECT
                    val req = java.io.ByteArrayOutputStream()
                    req.write(byteArrayOf(0x05, 0x01, 0x00)) // VER, CMD=CONNECT, RSV
                    val isIpv4 = targetHost.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))
                    if (isIpv4) {
                        req.write(0x01) // IPv4
                        req.write(InetAddress.getByName(targetHost).address)
                    } else {
                        req.write(0x03) // Domain
                        val domainBytes = targetHost.toByteArray(Charsets.UTF_8)
                        req.write(domainBytes.size)
                        req.write(domainBytes)
                    }
                    req.write((targetPort ushr 8) and 0xFF)
                    req.write(targetPort and 0xFF)
                    sOut.write(req.toByteArray())
                    sOut.flush()

                    val respVer = sIn.read()
                    val respStatus = sIn.read()
                    sIn.read() // RSV
                    val respAtyp = sIn.read()
                    when (respAtyp) {
                        0x01 -> sIn.skip(4 + 2) // IPv4 + port
                        0x03 -> {
                            val dlen = sIn.read()
                            if (dlen > 0) sIn.skip(dlen.toLong() + 2)
                        }
                        0x04 -> sIn.skip(16 + 2) // IPv6 + port
                    }

                    if (respVer == 0x05 && respStatus == 0x00) {
                        socksSocket.soTimeout = 0
                        return ProxyTunnel(socksSocket, sIn, sOut)
                    }
                }
                try { socksSocket.close() } catch (_: Exception) {}
            } catch (_: Exception) {
                // LocalProxyServer might not be ready, fall back to Strategy 2
            }
        }

        // Strategy 2: Direct XrayOutboundClient with VpnService protection
        if (activeConfig != null) {
            try {
                val outboundClient = XrayOutboundClient(
                    vpnService = XrayVpnService.instance,
                    serverConfig = activeConfig,
                    appContext = appContext
                )
                val tunnel = outboundClient.openTargetStream(targetHost, targetPort)
                if (tunnel != null) return tunnel
            } catch (e: Exception) {
                Log.w(TAG, "Direct outbound client error for $targetHost:$targetPort - ${e.message}")
            }
        }

        // Strategy 3: Direct outbound connection (fallback)
        return try {
            val directSocket = Socket()
            XrayVpnService.instance?.protect(directSocket)
            directSocket.tcpNoDelay = true
            directSocket.connect(InetSocketAddress(targetHost, targetPort), 7000)
            directSocket.soTimeout = 0
            ProxyTunnel(directSocket, directSocket.inputStream, directSocket.outputStream)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Handles SOCKS5 client connection from LAN.
     */
    private fun handleSocksClient(clientSocket: Socket) {
        var remoteTunnel: ProxyTunnel? = null
        try {
            clientSocket.tcpNoDelay = true
            clientSocket.soTimeout = 15000
            val inStream = clientSocket.inputStream
            val outStream = clientSocket.outputStream

            // SOCKS5 Step 1: Version & Auth
            val ver = inStream.read()
            if (ver != 0x05) {
                clientSocket.close()
                return
            }
            val nMethods = inStream.read()
            if (nMethods <= 0) {
                clientSocket.close()
                return
            }
            val methods = ByteArray(nMethods)
            var readMethods = 0
            while (readMethods < nMethods) {
                val r = inStream.read(methods, readMethods, nMethods - readMethods)
                if (r == -1) break
                readMethods += r
            }

            // Respond: NO AUTH (0x05, 0x00)
            outStream.write(byteArrayOf(0x05, 0x00))
            outStream.flush()

            // Step 2: SOCKS5 Request
            val reqVer = inStream.read()
            val cmd = inStream.read()
            val rsv = inStream.read()
            val atyp = inStream.read()

            if (reqVer != 0x05 || cmd != 0x01) { // 0x01 = CONNECT
                outStream.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                outStream.flush()
                clientSocket.close()
                return
            }

            val targetHost: String
            when (atyp) {
                0x01 -> { // IPv4
                    val ip = ByteArray(4)
                    inStream.read(ip)
                    targetHost = InetAddress.getByAddress(ip).hostAddress ?: ""
                }
                0x03 -> { // Domain name
                    val len = inStream.read()
                    val domainBytes = ByteArray(len)
                    var readDomain = 0
                    while (readDomain < len) {
                        val r = inStream.read(domainBytes, readDomain, len - readDomain)
                        if (r == -1) break
                        readDomain += r
                    }
                    targetHost = String(domainBytes, Charsets.UTF_8)
                }
                0x04 -> { // IPv6
                    val ip6 = ByteArray(16)
                    inStream.read(ip6)
                    targetHost = InetAddress.getByAddress(ip6).hostAddress ?: ""
                }
                else -> {
                    clientSocket.close()
                    return
                }
            }

            val portBytes = ByteArray(2)
            inStream.read(portBytes)
            val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            remoteTunnel = openOutboundTunnel(targetHost, targetPort)
            if (remoteTunnel == null) {
                outStream.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // Host unreachable
                outStream.flush()
                clientSocket.close()
                return
            }

            // SOCKS5 success response: 0x00 = succeeded
            outStream.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            outStream.flush()

            clientSocket.soTimeout = 0

            pipeBidirectional(
                clientSocket.inputStream, clientSocket.outputStream,
                remoteTunnel.inputStream, remoteTunnel.outputStream,
                clientSocket, remoteTunnel
            )
        } catch (e: Exception) {
            try { clientSocket.close() } catch (_: Exception) {}
            try { remoteTunnel?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Handles HTTP proxy client connection from LAN (HTTP CONNECT tunneling & standard HTTP).
     */
    private fun handleHttpClient(clientSocket: Socket) {
        var remoteTunnel: ProxyTunnel? = null
        try {
            clientSocket.tcpNoDelay = true
            clientSocket.soTimeout = 15000
            val inStream = clientSocket.inputStream
            val outStream = clientSocket.outputStream

            val requestLine = readLine(inStream) ?: run {
                clientSocket.close()
                return
            }

            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) {
                clientSocket.close()
                return
            }

            val method = parts[0].uppercase()
            val uriStr = parts[1]

            if (method == "CONNECT") {
                // HTTPS / TLS tunneling over HTTP CONNECT
                val hostPort = uriStr.split(":")
                val targetHost = hostPort[0]
                val targetPort = if (hostPort.size > 1) hostPort[1].toIntOrNull() ?: 443 else 443

                // Drain remaining HTTP request headers until blank line
                while (true) {
                    val line = readLine(inStream) ?: break
                    if (line.isBlank() || line == "\r") break
                }

                remoteTunnel = openOutboundTunnel(targetHost, targetPort)
                if (remoteTunnel == null) {
                    outStream.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    outStream.flush()
                    clientSocket.close()
                    return
                }

                // 200 Connection Established
                outStream.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: V2RayNG-LAN/1.0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                outStream.flush()

                clientSocket.soTimeout = 0

                pipeBidirectional(
                    clientSocket.inputStream, clientSocket.outputStream,
                    remoteTunnel.inputStream, remoteTunnel.outputStream,
                    clientSocket, remoteTunnel
                )
            } else {
                // Plain HTTP forward
                val url = if (uriStr.startsWith("http://", ignoreCase = true)) {
                    uriStr.substring(7)
                } else {
                    uriStr
                }
                val hostPart = url.substringBefore("/").substringBefore(":")
                val portPart = if (url.substringBefore("/").contains(":")) {
                    url.substringBefore("/").substringAfter(":").toIntOrNull() ?: 80
                } else 80
                val path = "/" + url.substringAfter("/", "")

                remoteTunnel = openOutboundTunnel(hostPart, portPart)
                if (remoteTunnel == null) {
                    outStream.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    outStream.flush()
                    clientSocket.close()
                    return
                }

                // Forward modified HTTP request line
                val newRequestLine = "$method $path ${parts.getOrElse(2) { "HTTP/1.1" }}\r\n"
                remoteTunnel.outputStream.write(newRequestLine.toByteArray(Charsets.UTF_8))
                remoteTunnel.outputStream.flush()

                clientSocket.soTimeout = 0

                pipeBidirectional(
                    clientSocket.inputStream, clientSocket.outputStream,
                    remoteTunnel.inputStream, remoteTunnel.outputStream,
                    clientSocket, remoteTunnel
                )
            }
        } catch (e: Exception) {
            try { clientSocket.close() } catch (_: Exception) {}
            try { remoteTunnel?.close() } catch (_: Exception) {}
        }
    }

    private fun readLine(inStream: InputStream): String? {
        val sb = StringBuilder()
        var b: Int
        while (inStream.read().also { b = it } != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) break
        }
        return if (sb.isEmpty() && b == -1) null else sb.toString()
    }

    private fun pipeBidirectional(
        clientIn: InputStream, clientOut: OutputStream,
        remoteIn: InputStream, remoteOut: OutputStream,
        clientSocket: Socket, remoteTunnel: ProxyTunnel
    ) {
        val forwardJob = Thread {
            val buffer = ByteArray(32768)
            try {
                var len: Int
                while (clientIn.read(buffer).also { len = it } > 0) {
                    remoteOut.write(buffer, 0, len)
                    remoteOut.flush()
                }
            } catch (_: Exception) {}
            finally {
                try { clientSocket.close() } catch (_: Exception) {}
                try { remoteTunnel.close() } catch (_: Exception) {}
            }
        }

        val reverseJob = Thread {
            val buffer = ByteArray(32768)
            try {
                var len: Int
                while (remoteIn.read(buffer).also { len = it } > 0) {
                    clientOut.write(buffer, 0, len)
                    clientOut.flush()
                }
            } catch (_: Exception) {}
            finally {
                try { clientSocket.close() } catch (_: Exception) {}
                try { remoteTunnel.close() } catch (_: Exception) {}
            }
        }

        forwardJob.isDaemon = true
        reverseJob.isDaemon = true
        forwardJob.start()
        reverseJob.start()
    }
}
