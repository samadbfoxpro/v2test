package com.example.service.proxy

import android.content.Context
import android.net.VpnService
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.model.ServerConfig
import com.example.service.proxy.shadowsocks.ShadowsocksAeadTunnel
import com.example.service.proxy.ws.WebSocketTunnel
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Handles outbound connections for VLESS, Trojan, VMess, Shadowsocks protocols,
 * and 2-Hop Proxy Chains (Relay ➔ Exit Node).
 */
class XrayOutboundClient(
    private val vpnService: VpnService?,
    private val serverConfig: ServerConfig,
    private val relayConfigOverride: ServerConfig? = null,
    private val exitConfigOverride: ServerConfig? = null,
    private val appContext: Context? = null
) {
    companion object {
        private const val TAG = "XrayOutboundClient"
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val READ_TIMEOUT_MS = 30000

        private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
        })
    }

    /**
     * Establishes an outbound connection to the target host and port.
     * If the server is a Proxy Chain, routes through Relay ➔ Exit ➔ Target.
     */
    fun openTargetStream(targetHost: String, targetPort: Int): ProxyTunnel? {
        if (serverConfig.isProxyChain || serverConfig.protocol.equals("CHAIN", ignoreCase = true)) {
            return openProxyChainStream(targetHost, targetPort)
        }
        return openDirectTargetStream(serverConfig, targetHost, targetPort)
    }

    /**
     * Standard 1-hop outbound connection to a single server.
     */
    private fun openDirectTargetStream(config: ServerConfig, targetHost: String, targetPort: Int): ProxyTunnel? {
        return try {
            val rawSocket = Socket()
            vpnService?.protect(rawSocket)

            rawSocket.tcpNoDelay = true
            rawSocket.keepAlive = true
            rawSocket.receiveBufferSize = 65536
            rawSocket.sendBufferSize = 65536
            rawSocket.soTimeout = 0 // Keep persistent idle sockets alive (WhatsApp/Telegram/Push)
            rawSocket.connect(
                InetSocketAddress(config.address, config.port),
                CONNECT_TIMEOUT_MS
            )

            // Step 1: Establish TLS if required
            val (tunnelSocket, tlsInStream, tlsOutStream) = if (isTlsSecurity(config)) {
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, SecureRandom())
                val sslFactory = sslContext.socketFactory

                val sni = config.sni.ifBlank { config.host.ifBlank { config.address } }
                val sslSocket = sslFactory.createSocket(
                    rawSocket,
                    config.address,
                    config.port,
                    true
                ) as SSLSocket

                val sslParams = SSLParameters()
                sslParams.serverNames = listOf(SNIHostName(sni))
                sslSocket.sslParameters = sslParams
                sslSocket.useClientMode = true
                sslSocket.startHandshake()
                Triple(sslSocket, sslSocket.inputStream, sslSocket.outputStream)
            } else {
                Triple(rawSocket, rawSocket.inputStream, rawSocket.outputStream)
            }

            // Step 2: Establish WebSocket transport if required
            val (wsInStream, wsOutStream) = if (config.transportType.equals("ws", ignoreCase = true)) {
                val wsHost = config.host.ifBlank { config.sni.ifBlank { config.address } }
                val wsPath = config.path.ifBlank { "/" }
                val success = WebSocketTunnel.performHandshake(tlsInStream, tlsOutStream, wsPath, wsHost)
                if (!success) {
                    tunnelSocket.close()
                    return null
                }
                val ws = WebSocketTunnel(tlsInStream, tlsOutStream)
                Pair(ws.inputStream, ws.outputStream)
            } else {
                Pair(tlsInStream, tlsOutStream)
            }

            // Step 3: Perform Protocol Handshake
            val (finalInStream, finalOutStream) = when (config.protocol.uppercase()) {
                "VLESS" -> {
                    performVlessHandshake(wsOutStream, config, targetHost, targetPort)
                    Pair(VlessInputStream(wsInStream), wsOutStream)
                }
                "TROJAN" -> {
                    performTrojanHandshake(wsOutStream, config, targetHost, targetPort)
                    Pair(wsInStream, wsOutStream)
                }
                "SHADOWSOCKS", "SS" -> {
                    val method = config.encryption.ifBlank { "aes-128-gcm" }
                    val password = config.uuid.ifBlank { "shadowsocks" }
                    val ss = ShadowsocksAeadTunnel(wsInStream, wsOutStream, method, password)
                    ss.performHandshake(targetHost, targetPort)
                    Pair(ss.inputStream, ss.outputStream)
                }
                else -> {
                    performVlessHandshake(wsOutStream, config, targetHost, targetPort)
                    Pair(VlessInputStream(wsInStream), wsOutStream)
                }
            }

            ProxyTunnel(tunnelSocket, finalInStream, finalOutStream)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to connect to ${config.address}:${config.port} for $targetHost:$targetPort - ${e.message}")
            null
        }
    }

    /**
     * 2-Hop Proxy Chain connection:
     * Phone ➔ Relay Server (Node 1) ➔ Exit Server (Node 2) ➔ Target (Host, Port)
     *
     * CRITICAL ARCHITECTURE NOTE:
     * After Hop 1, we get a ProxyTunnel whose inputStream/outputStream are
     * protocol-wrapped (VLESS header stripped, WebSocket framed, etc.).
     * The raw `socket` field is the physical TCP socket to the Relay.
     *
     * For Hop 2, when we need to layer TLS to the Exit node over this tunnel,
     * SSLSocketFactory.createSocket(socket, ...) reads/writes from
     * socket.getInputStream()/getOutputStream() DIRECTLY, which would bypass
     * the VLESS/WS framing. We MUST use StreamDelegatingSocket to redirect
     * SSL I/O through the tunnel's wrapped streams.
     */
    private fun openProxyChainStream(targetHost: String, targetPort: Int): ProxyTunnel? {
        val relay = relayConfigOverride ?: resolveServer(serverConfig.chainRelayId)
        val exit = exitConfigOverride ?: resolveServer(serverConfig.chainExitId)

        if (relay == null || exit == null) {
            Log.e(TAG, "Proxy Chain missing relay ($relay) or exit ($exit) node configuration!")
            return null
        }

        return try {
            // Hop 1: Connect to Relay Node and ask it to open TCP tunnel to Exit Node (exit.address:exit.port)
            val relayClient = XrayOutboundClient(vpnService, relay, appContext = appContext)
            val tunnelToExit = relayClient.openTargetStream(exit.address, exit.port)
            if (tunnelToExit == null) {
                Log.w(TAG, "Proxy Chain: Failed to establish Hop 1 tunnel to Exit Node via Relay Node ${relay.name}")
                return null
            }

            Log.d(TAG, "Proxy Chain Hop 1 OK: connected to ${relay.name}, tunnel to ${exit.address}:${exit.port}")

            // Hop 2: Layer Exit Node's Security (TLS) over the tunnel stream if Exit uses TLS
            val (exitTlsIn, exitTlsOut) = if (isTlsSecurity(exit)) {
                val sni = exit.sni.ifBlank { exit.host.ifBlank { exit.address } }
                val tlsTunnel = com.example.service.proxy.tls.TlsStreamTunnel(
                    rawIn = tunnelToExit.inputStream,
                    rawOut = tunnelToExit.outputStream,
                    host = exit.address,
                    port = exit.port,
                    sni = sni
                )
                val success = tlsTunnel.performHandshake()
                if (!success) {
                    Log.w(TAG, "Proxy Chain Hop 2 TLS handshake failed to ${exit.address}")
                    tunnelToExit.close()
                    return null
                }
                Log.d(TAG, "Proxy Chain Hop 2 TLS OK: Pure stream TLS established to ${exit.address}")
                Pair(tlsTunnel.inputStream, tlsTunnel.outputStream)
            } else {
                Pair(tunnelToExit.inputStream, tunnelToExit.outputStream)
            }

            // Hop 2.1: Layer Exit Node's WebSocket Transport if required
            val (exitWsIn, exitWsOut) = if (exit.transportType.equals("ws", ignoreCase = true)) {
                val wsHost = exit.host.ifBlank { exit.sni.ifBlank { exit.address } }
                val wsPath = exit.path.ifBlank { "/" }
                val success = WebSocketTunnel.performHandshake(exitTlsIn, exitTlsOut, wsPath, wsHost)
                if (!success) {
                    Log.w(TAG, "Proxy Chain Hop 2 WS handshake failed to ${exit.address}")
                    tunnelToExit.close()
                    return null
                }
                val ws = WebSocketTunnel(exitTlsIn, exitTlsOut)
                Log.d(TAG, "Proxy Chain Hop 2 WS OK: WebSocket upgraded to ${exit.address}")
                Pair(ws.inputStream, ws.outputStream)
            } else {
                Pair(exitTlsIn, exitTlsOut)
            }

            // Hop 2.2: Perform Exit Node Protocol Handshake targeting final destination (targetHost:targetPort)
            val (finalInStream, finalOutStream) = when (exit.protocol.uppercase()) {
                "VLESS" -> {
                    performVlessHandshake(exitWsOut, exit, targetHost, targetPort)
                    Pair(VlessInputStream(exitWsIn), exitWsOut)
                }
                "TROJAN" -> {
                    performTrojanHandshake(exitWsOut, exit, targetHost, targetPort)
                    Pair(exitWsIn, exitWsOut)
                }
                "SHADOWSOCKS", "SS" -> {
                    val method = exit.encryption.ifBlank { "aes-128-gcm" }
                    val password = exit.uuid.ifBlank { "shadowsocks" }
                    val ss = ShadowsocksAeadTunnel(exitWsIn, exitWsOut, method, password)
                    ss.performHandshake(targetHost, targetPort)
                    Pair(ss.inputStream, ss.outputStream)
                }
                else -> {
                    performVlessHandshake(exitWsOut, exit, targetHost, targetPort)
                    Pair(VlessInputStream(exitWsIn), exitWsOut)
                }
            }

            Log.i(TAG, "Proxy Chain fully established: ${relay.name} ➔ ${exit.name} ➔ $targetHost:$targetPort")
            ProxyTunnel(tunnelToExit.socket, finalInStream, finalOutStream)
        } catch (e: Exception) {
            Log.e(TAG, "Failed Proxy Chain tunnel: ${e.message}", e)
            null
        }
    }

    private fun resolveServer(id: Long): ServerConfig? {
        if (id <= 0L) return null
        val context = vpnService?.applicationContext ?: appContext
        if (context == null) {
            Log.e(TAG, "resolveServer: No context available to query database for id=$id")
            return null
        }
        return try {
            runBlocking {
                AppDatabase.getInstance(context).serverDao().getServerById(id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveServer: Failed to query server id=$id: ${e.message}")
            null
        }
    }

    private fun isTlsSecurity(config: ServerConfig): Boolean {
        val sec = config.security.lowercase()
        return sec == "tls" || sec == "reality" || config.port == 2096 || config.port == 2083 || config.port == 2087 || config.port == 2053 || config.port == 8443 || (config.port == 443 && !config.protocol.equals("SHADOWSOCKS", ignoreCase = true))
    }

    /**
     * VLESS Request Header Format:
     * 1 byte: Version (0)
     * 16 bytes: UUID
     * 1 byte: Addon Proto length (0)
     * 1 byte: Command (1 = TCP)
     * 2 bytes: Port (Big-Endian)
     * 1 byte: Address Type (1 = IPv4, 2 = Domain, 3 = IPv6)
     * N bytes: Address
     */
    private fun performVlessHandshake(outStream: OutputStream, config: ServerConfig, targetHost: String, targetPort: Int) {
        val buffer = ByteBuffer.allocate(512)

        buffer.put(0.toByte()) // Version = 0

        val uuidBytes = parseUuid(config.uuid)
        buffer.put(uuidBytes) // 16 bytes UUID

        buffer.put(0.toByte()) // Addons length = 0
        buffer.put(1.toByte()) // Command = 1 (TCP)
        buffer.putShort(targetPort.toShort()) // Port (2 bytes)

        packTargetAddress(buffer, targetHost, isTrojan = false)

        buffer.flip()
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        outStream.write(data)
        outStream.flush()
    }

    /**
     * Trojan Request Header Format:
     * 56 bytes: Hex(SHA224(password))
     * 2 bytes: CRLF (\r\n)
     * 1 byte: Command (1 = CONNECT TCP)
     * 1 byte: Address Type (1 = IPv4, 3 = Domain, 4 = IPv6)
     * N bytes: Address
     * 2 bytes: Port
     * 2 bytes: CRLF (\r\n)
     */
    private fun performTrojanHandshake(outStream: OutputStream, config: ServerConfig, targetHost: String, targetPort: Int) {
        val hexHash = sha224Hex(config.uuid)
        val buffer = ByteBuffer.allocate(512)

        buffer.put(hexHash.toByteArray(Charsets.US_ASCII))
        buffer.put(0x0D.toByte())
        buffer.put(0x0A.toByte())

        buffer.put(1.toByte()) // Command = CONNECT TCP

        packTargetAddress(buffer, targetHost, isTrojan = true)

        buffer.putShort(targetPort.toShort())
        buffer.put(0x0D.toByte())
        buffer.put(0x0A.toByte())

        buffer.flip()
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        outStream.write(data)
        outStream.flush()
    }

    private fun packTargetAddress(buffer: ByteBuffer, targetHost: String, isTrojan: Boolean) {
        if (targetHost.contains(":")) {
            // IPv6
            val ipv6Type = if (isTrojan) 4.toByte() else 3.toByte()
            buffer.put(ipv6Type)
            val ipBytes = InetAddress.getByName(targetHost).address
            buffer.put(ipBytes)
        } else {
            val isIpv4 = targetHost.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))
            if (isIpv4) {
                buffer.put(1.toByte()) // Type 1 = IPv4
                val ipBytes = InetAddress.getByName(targetHost).address
                buffer.put(ipBytes)
            } else {
                val domainType = if (isTrojan) 3.toByte() else 2.toByte()
                buffer.put(domainType) // Domain
                val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
                buffer.put(hostBytes.size.toByte())
                buffer.put(hostBytes)
            }
        }
    }

    private fun parseUuid(uuidStr: String): ByteArray {
        return try {
            val uuid = UUID.fromString(uuidStr.trim())
            val bb = ByteBuffer.wrap(ByteArray(16))
            bb.putLong(uuid.mostSignificantBits)
            bb.putLong(uuid.leastSignificantBits)
            bb.array()
        } catch (_: Exception) {
            val md5 = MessageDigest.getInstance("MD5")
            md5.digest(uuidStr.toByteArray(Charsets.UTF_8))
        }
    }

    private fun sha224Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-224")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Strips the VLESS response header (Version + Addons length) on the first read.
 */
class VlessInputStream(private val rawIn: InputStream) : InputStream() {
    private var headerStripped = false

    override fun read(): Int {
        ensureHeaderStripped()
        return rawIn.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        ensureHeaderStripped()
        return rawIn.read(b, off, len)
    }

    @Synchronized
    private fun ensureHeaderStripped() {
        while (!headerStripped) {
            val version = rawIn.read()
            if (version == -1) {
                break
            }
            val addonLen = rawIn.read()
            if (addonLen > 0) {
                var totalRead = 0
                val addonBytes = ByteArray(addonLen)
                while (totalRead < addonLen) {
                    val r = rawIn.read(addonBytes, totalRead, addonLen - totalRead)
                    if (r == -1) break
                    totalRead += r
                }
            }
            headerStripped = true
        }
    }

    override fun close() = rawIn.close()
    override fun available(): Int = rawIn.available()
}
