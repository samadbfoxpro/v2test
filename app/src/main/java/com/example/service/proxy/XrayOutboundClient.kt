package com.example.service.proxy

import android.net.VpnService
import android.util.Log
import com.example.data.model.ServerConfig
import com.example.service.proxy.ws.WebSocketTunnel
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
 * Handles outbound connections for VLESS, Trojan, VMess, Shadowsocks protocols.
 * Supports TLS, WebSocket (ws) transport, and socket protection to bypass the VPN tunnel.
 */
class XrayOutboundClient(
    private val vpnService: VpnService?,
    private val serverConfig: ServerConfig
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
     * Establishes an outbound connection to the remote server and performs the protocol handshake
     * targeting [targetHost] and [targetPort].
     * Returns a [ProxyTunnel] containing the open input/output streams and socket.
     */
    fun openTargetStream(targetHost: String, targetPort: Int): ProxyTunnel? {
        return try {
            val rawSocket = Socket()
            // CRITICAL: Protect socket from entering VPN loop!
            vpnService?.protect(rawSocket)

            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = READ_TIMEOUT_MS
            rawSocket.connect(
                InetSocketAddress(serverConfig.address, serverConfig.port),
                CONNECT_TIMEOUT_MS
            )

            // Step 1: Establish TLS if required
            val (tunnelSocket, tlsInStream, tlsOutStream) = if (isTlsSecurity()) {
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, SecureRandom())
                val sslFactory = sslContext.socketFactory

                val sni = serverConfig.sni.ifBlank { serverConfig.host.ifBlank { serverConfig.address } }
                val sslSocket = sslFactory.createSocket(
                    rawSocket,
                    serverConfig.address,
                    serverConfig.port,
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
            val (wsInStream, wsOutStream) = if (serverConfig.transportType.equals("ws", ignoreCase = true)) {
                val wsHost = serverConfig.host.ifBlank { serverConfig.sni.ifBlank { serverConfig.address } }
                val wsPath = serverConfig.path.ifBlank { "/" }
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
            val finalInStream: InputStream = when (serverConfig.protocol.uppercase()) {
                "VLESS" -> {
                    performVlessHandshake(wsOutStream, targetHost, targetPort)
                    VlessInputStream(wsInStream)
                }
                "TROJAN" -> {
                    performTrojanHandshake(wsOutStream, targetHost, targetPort)
                    wsInStream
                }
                "SHADOWSOCKS", "SS" -> {
                    performShadowsocksHandshake(wsOutStream, targetHost, targetPort)
                    wsInStream
                }
                else -> {
                    performVlessHandshake(wsOutStream, targetHost, targetPort)
                    VlessInputStream(wsInStream)
                }
            }

            ProxyTunnel(tunnelSocket, finalInStream, wsOutStream)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to connect to ${serverConfig.address}:${serverConfig.port} for $targetHost:$targetPort - ${e.message}")
            null
        }
    }

    private fun isTlsSecurity(): Boolean {
        val sec = serverConfig.security.lowercase()
        return sec == "tls" || sec == "reality" || serverConfig.port == 443 || serverConfig.port == 2096 || serverConfig.port == 2083 || serverConfig.port == 2087 || serverConfig.port == 2053 || serverConfig.port == 8443
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
    private fun performVlessHandshake(outStream: OutputStream, targetHost: String, targetPort: Int) {
        val uuidBytes = parseUuidToBytes(serverConfig.uuid)
        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
        val isIpv4 = targetHost.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))

        val totalLen = 1 + 16 + 1 + 1 + 2 + 1 + (if (isIpv4) 4 else (1 + hostBytes.size))
        val buffer = ByteBuffer.allocate(totalLen)

        buffer.put(0x00.toByte()) // Version 0
        buffer.put(uuidBytes) // 16 bytes UUID
        buffer.put(0x00.toByte()) // Addons length 0
        buffer.put(0x01.toByte()) // Command TCP
        buffer.putShort(targetPort.toShort()) // Port

        if (isIpv4) {
            buffer.put(0x01.toByte()) // IPv4
            val ipParts = targetHost.split(".").map { it.toInt().toByte() }
            for (b in ipParts) buffer.put(b)
        } else {
            buffer.put(0x02.toByte()) // Domain name
            buffer.put(hostBytes.size.toByte())
            buffer.put(hostBytes)
        }

        outStream.write(buffer.array())
        outStream.flush()
    }

    /**
     * Trojan Request Header
     */
    private fun performTrojanHandshake(outStream: OutputStream, targetHost: String, targetPort: Int) {
        val hash = sha224Hex(serverConfig.uuid)
        val isIpv4 = targetHost.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))
        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)

        outStream.write(hash.toByteArray(Charsets.US_ASCII))
        outStream.write(byteArrayOf(0x0D, 0x0A)) // \r\n
        outStream.write(0x01) // Command TCP

        if (isIpv4) {
            outStream.write(0x01) // IPv4
            val ipBytes = InetAddress.getByName(targetHost).address
            outStream.write(ipBytes)
        } else {
            outStream.write(0x03) // Domain
            outStream.write(hostBytes.size)
            outStream.write(hostBytes)
        }

        val portBuf = ByteBuffer.allocate(2).putShort(targetPort.toShort()).array()
        outStream.write(portBuf)
        outStream.write(byteArrayOf(0x0D, 0x0A)) // \r\n
        outStream.flush()
    }

    private fun performShadowsocksHandshake(outStream: OutputStream, targetHost: String, targetPort: Int) {
        val isIpv4 = targetHost.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))
        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)

        if (isIpv4) {
            outStream.write(0x01)
            outStream.write(InetAddress.getByName(targetHost).address)
        } else {
            outStream.write(0x03)
            outStream.write(hostBytes.size)
            outStream.write(hostBytes)
        }
        val portBuf = ByteBuffer.allocate(2).putShort(targetPort.toShort()).array()
        outStream.write(portBuf)
        outStream.flush()
    }

    private fun parseUuidToBytes(uuidStr: String): ByteArray {
        return try {
            val clean = uuidStr.trim().replace("-", "")
            if (clean.length == 32) {
                val bytes = ByteArray(16)
                for (i in 0 until 16) {
                    bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                bytes
            } else {
                val u = UUID.fromString(uuidStr.trim())
                val bb = ByteBuffer.wrap(ByteArray(16))
                bb.putLong(u.mostSignificantBits)
                bb.putLong(u.leastSignificantBits)
                bb.array()
            }
        } catch (_: Exception) {
            ByteArray(16)
        }
    }

    private fun sha224Hex(input: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-224")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            input.padEnd(56, '0').take(56)
        }
    }
}

/**
 * Strips the 2-byte VLESS server response header (Version + AddonLength) on the first read
 * so the incoming stream represents the pure target server response.
 */
class VlessInputStream(private val underlying: InputStream) : InputStream() {
    private var headerStripped = false

    private fun ensureHeaderStripped() {
        if (!headerStripped) {
            headerStripped = true
            try {
                val version = underlying.read()
                if (version != -1) {
                    val addonLen = underlying.read()
                    if (addonLen > 0) {
                        var skipped = 0
                        while (skipped < addonLen) {
                            val r = underlying.read()
                            if (r == -1) break
                            skipped++
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("VlessInputStream", "Failed to strip VLESS response header: ${e.message}")
            }
        }
    }

    override fun read(): Int {
        ensureHeaderStripped()
        return underlying.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        ensureHeaderStripped()
        return underlying.read(b, off, len)
    }

    override fun close() {
        underlying.close()
    }
}

class ProxyTunnel(
    val socket: Socket,
    val inputStream: InputStream,
    val outputStream: OutputStream
) : AutoCloseable {
    override fun close() {
        try { inputStream.close() } catch (_: Exception) {}
        try { outputStream.close() } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }
}
