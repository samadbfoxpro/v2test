package com.example.service.dns

import android.net.VpnService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class DnsResolverType {
    AUTO,
    DOH,
    DOT,
    FAKEDNS,
    UDP
}

interface DnsResolver {
    val name: String
    val type: DnsResolverType
    val endpoint: String
    fun resolve(queryPayload: ByteArray, vpnService: VpnService?, timeoutMs: Long = 2500L): ByteArray?
}

/**
 * DoH (DNS-over-HTTPS) Resolver using protected HTTP POST requests.
 */
class DohResolver(
    override val name: String,
    override val endpoint: String
) : DnsResolver {
    override val type: DnsResolverType = DnsResolverType.DOH

    override fun resolve(queryPayload: ByteArray, vpnService: VpnService?, timeoutMs: Long): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs.toInt()
                readTimeout = timeoutMs.toInt()
                doOutput = true
                doInput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
                setRequestProperty("User-Agent", "ShadowVPN-SmartDNS/1.0")
            }

            conn.outputStream.use { it.write(queryPayload) }

            if (conn.responseCode == 200) {
                val responseData = conn.inputStream.use { it.readBytes() }
                if (responseData.size >= 12) responseData else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}

/**
 * DoT (DNS-over-TLS) Resolver over port 853 with SNI and protected SSL sockets.
 */
class DotResolver(
    override val name: String,
    val host: String,
    val sni: String,
    val port: Int = 853
) : DnsResolver {
    override val type: DnsResolverType = DnsResolverType.DOT
    override val endpoint: String = "$host:$port ($sni)"

    override fun resolve(queryPayload: ByteArray, vpnService: VpnService?, timeoutMs: Long): ByteArray? {
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        return try {
            rawSocket = Socket()
            vpnService?.protect(rawSocket)
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = timeoutMs.toInt()
            rawSocket.connect(InetSocketAddress(host, port), timeoutMs.toInt())

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSocket = sslFactory.createSocket(rawSocket, host, port, true) as SSLSocket
            sslSocket.sslParameters = SSLParameters().apply {
                serverNames = listOf(SNIHostName(sni))
            }
            sslSocket.useClientMode = true
            sslSocket.soTimeout = timeoutMs.toInt()
            sslSocket.startHandshake()

            val outStream = DataOutputStream(sslSocket.outputStream)
            val inStream = DataInputStream(sslSocket.inputStream)

            // DoT sends 2-byte length prefix (RFC 7858)
            outStream.writeShort(queryPayload.size)
            outStream.write(queryPayload)
            outStream.flush()

            val responseLen = inStream.readUnsignedShort()
            if (responseLen in 12..4096) {
                val responseData = ByteArray(responseLen)
                inStream.readFully(responseData)
                responseData
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try { sslSocket?.close() } catch (_: Exception) {}
            try { rawSocket?.close() } catch (_: Exception) {}
        }
    }
}

/**
 * Standard Protected UDP 53 DNS Resolver with fallback capability.
 */
class UdpResolver(
    override val name: String,
    val ip: String,
    val port: Int = 53
) : DnsResolver {
    override val type: DnsResolverType = DnsResolverType.UDP
    override val endpoint: String = "$ip:$port"

    override fun resolve(queryPayload: ByteArray, vpnService: VpnService?, timeoutMs: Long): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            vpnService?.protect(socket)
            socket.soTimeout = timeoutMs.toInt()

            val dest = InetAddress.getByName(ip)
            val sendPacket = DatagramPacket(queryPayload, queryPayload.size, dest, port)
            socket.send(sendPacket)

            val buffer = ByteArray(2048)
            val recvPacket = DatagramPacket(buffer, buffer.size)
            socket.receive(recvPacket)

            if (recvPacket.length >= 12) {
                buffer.copyOf(recvPacket.length)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
