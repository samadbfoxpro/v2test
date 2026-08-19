package com.example.service.proxy

import android.net.VpnService
import android.util.Log
import com.example.data.model.ServerConfig
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Embedded SOCKS5 & HTTP Inbound Proxy Server running locally on the device (ports 10808 / 10809).
 * Proxies traffic through the selected Xray outbound node.
 */
class LocalProxyServer(
    private val vpnService: VpnService?,
    private val serverConfig: ServerConfig,
    private val onDataTransferred: (uploadBytes: Long, downloadBytes: Long) -> Unit
) {
    companion object {
        const val SOCKS_PORT = 10808
        const val HTTP_PORT = 10809
        private const val TAG = "LocalProxyServer"
    }

    private val isRunning = AtomicBoolean(false)
    private var socksServerSocket: ServerSocket? = null
    private var httpServerSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val outboundClient = XrayOutboundClient(vpnService, serverConfig)

    fun start() {
        if (isRunning.getAndSet(true)) return

        executor = Executors.newCachedThreadPool()

        // SOCKS5 Server
        executor?.execute {
            try {
                socksServerSocket = ServerSocket(SOCKS_PORT, 50, InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "SOCKS5 Inbound listening on 127.0.0.1:$SOCKS_PORT")
                while (isRunning.get()) {
                    val client = socksServerSocket?.accept() ?: break
                    executor?.execute { handleSocksClient(client) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "SOCKS server error: ${e.message}")
            }
        }

        // HTTP Proxy Server
        executor?.execute {
            try {
                httpServerSocket = ServerSocket(HTTP_PORT, 50, InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "HTTP Proxy Inbound listening on 127.0.0.1:$HTTP_PORT")
                while (isRunning.get()) {
                    val client = httpServerSocket?.accept() ?: break
                    executor?.execute { handleHttpClient(client) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "HTTP server error: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try { socksServerSocket?.close() } catch (_: Exception) {}
        try { httpServerSocket?.close() } catch (_: Exception) {}
        try { executor?.shutdownNow() } catch (_: Exception) {}
        socksServerSocket = null
        httpServerSocket = null
        executor = null
    }

    private fun handleSocksClient(clientSocket: Socket) {
        var remoteTunnel: ProxyTunnel? = null
        try {
            clientSocket.soTimeout = 15000
            val inStream = clientSocket.inputStream
            val outStream = clientSocket.outputStream

            // SOCKS5 Handshake Step 1: Version & Auth negotiation
            val ver = inStream.read()
            if (ver != 0x05) {
                clientSocket.close()
                return
            }
            val nMethods = inStream.read()
            val methods = ByteArray(nMethods)
            inStream.read(methods)

            // Accept NO AUTH (0x00)
            outStream.write(byteArrayOf(0x05, 0x00))
            outStream.flush()

            // Step 2: Request command
            val reqVer = inStream.read()
            val cmd = inStream.read()
            val rsv = inStream.read()
            val atyp = inStream.read()

            if (reqVer != 0x05 || cmd != 0x01) { // 0x01 = CONNECT
                outStream.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // Command not supported
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
                    inStream.read(domainBytes)
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

            // Connect outbound to remote Xray server
            remoteTunnel = outboundClient.openTargetStream(targetHost, targetPort)
            if (remoteTunnel == null) {
                outStream.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // Host unreachable
                outStream.flush()
                clientSocket.close()
                return
            }

            // SOCKS5 success reply
            outStream.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x2A, 0x38)) // Connected
            outStream.flush()

            clientSocket.soTimeout = 0
            // Bi-directional byte transfer
            bridgeStreams(clientSocket.inputStream, clientSocket.outputStream, remoteTunnel)
        } catch (e: Exception) {
            // Clean up
        } finally {
            try { remoteTunnel?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun readLineByteByByte(inStream: InputStream): String {
        val sb = java.lang.StringBuilder()
        while (true) {
            val c = inStream.read()
            if (c == -1) break
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun handleHttpClient(clientSocket: Socket) {
        var remoteTunnel: ProxyTunnel? = null
        try {
            clientSocket.soTimeout = 15000
            val inStream = clientSocket.inputStream
            val outStream = clientSocket.outputStream

            val firstLine = readLineByteByByte(inStream)
            if (firstLine.isBlank()) return
            val parts = firstLine.split(" ")
            if (parts.size < 3) return

            val method = parts[0].uppercase()
            val uri = parts[1]
            val version = parts[2]

            if (method == "CONNECT") {
                // HTTPS CONNECT host:port
                val hostPort = uri.split(":")
                val host = hostPort[0]
                val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

                // Skip headers until empty line WITHOUT buffering ahead
                while (true) {
                    val line = readLineByteByByte(inStream)
                    if (line.isBlank()) break
                }

                remoteTunnel = outboundClient.openTargetStream(host, port)
                if (remoteTunnel == null) {
                    outStream.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    outStream.flush()
                    return
                }

                outStream.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                outStream.flush()

                clientSocket.soTimeout = 0
                bridgeStreams(inStream, outStream, remoteTunnel)
            } else {
                // Handle Standard HTTP Proxy Requests (GET, POST, etc.)
                var host = ""
                var port = 80
                var path = uri

                if (uri.startsWith("http://")) {
                    try {
                        val url = java.net.URL(uri)
                        host = url.host
                        port = if (url.port != -1) url.port else 80
                        path = if (url.file.isEmpty()) "/" else url.file
                    } catch (e: Exception) {}
                }

                val headers = mutableListOf<String>()
                while (true) {
                    val line = readLineByteByByte(inStream)
                    if (line.isBlank()) break
                    headers.add(line)
                    if (host.isEmpty() && line.lowercase().startsWith("host: ")) {
                        val hostHeader = line.substring(6).trim()
                        val hp = hostHeader.split(":")
                        host = hp[0]
                        if (hp.size > 1) {
                            port = hp[1].toIntOrNull() ?: 80
                        }
                    }
                }

                if (host.isEmpty()) return

                remoteTunnel = outboundClient.openTargetStream(host, port)
                if (remoteTunnel == null) {
                    outStream.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    outStream.flush()
                    return
                }

                // Rewrite request
                val newFirstLine = "$method $path $version\r\n"
                remoteTunnel.outputStream.write(newFirstLine.toByteArray(Charsets.US_ASCII))
                for (header in headers) {
                    if (!header.lowercase().startsWith("proxy-connection:")) {
                        remoteTunnel.outputStream.write("$header\r\n".toByteArray(Charsets.US_ASCII))
                    }
                }
                remoteTunnel.outputStream.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                remoteTunnel.outputStream.flush()
                
                clientSocket.soTimeout = 0
                bridgeStreams(inStream, outStream, remoteTunnel)
            }
        } catch (e: Exception) {
            // Error handling
        } finally {
            try { remoteTunnel?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun bridgeStreams(clientIn: InputStream, clientOut: OutputStream, remote: ProxyTunnel) {
        val upThread = Thread {
            try {
                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (clientIn.read(buffer).also { bytesRead = it } != -1) {
                    remote.outputStream.write(buffer, 0, bytesRead)
                    remote.outputStream.flush()
                    onDataTransferred(bytesRead.toLong(), 0)
                }
            } catch (_: Exception) {}
            try { remote.socket.shutdownOutput() } catch (_: Exception) {}
        }

        val downThread = Thread {
            try {
                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (remote.inputStream.read(buffer).also { bytesRead = it } != -1) {
                    clientOut.write(buffer, 0, bytesRead)
                    clientOut.flush()
                    onDataTransferred(0, bytesRead.toLong())
                }
            } catch (_: Exception) {}
            try { clientSocketClose(clientOut) } catch (_: Exception) {}
        }

        upThread.start()
        downThread.start()
        upThread.join()
        downThread.join()
    }

    private fun clientSocketClose(out: OutputStream) {
        try { out.close() } catch (_: Exception) {}
    }
}
