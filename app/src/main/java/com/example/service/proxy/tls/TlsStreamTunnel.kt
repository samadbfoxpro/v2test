package com.example.service.proxy.tls

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Pure Stream-based TLS implementation using Java SSLEngine.
 * Operates purely on InputStream / OutputStream without requiring a native Socket FileDescriptor.
 * Enables 100% reliable TLS layering over multi-hop proxy chains (Relay ➔ Exit).
 */
class TlsStreamTunnel(
    private val rawIn: InputStream,
    private val rawOut: OutputStream,
    private val host: String,
    private val port: Int,
    private val sni: String = host
) {
    companion object {
        private const val TAG = "TlsStreamTunnel"

        private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
        })
    }

    private val engine: SSLEngine
    private val appInBuf: ByteBuffer
    private val appOutBuf: ByteBuffer
    private val netInBuf: ByteBuffer
    private val netOutBuf: ByteBuffer

    val inputStream: InputStream
    val outputStream: OutputStream

    init {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        engine = sslContext.createSSLEngine(host, port)
        engine.useClientMode = true

        val params = engine.sslParameters
        if (sni.isNotBlank()) {
            params.serverNames = listOf(SNIHostName(sni))
        }
        engine.sslParameters = params

        val session = engine.session
        appInBuf = ByteBuffer.allocate(session.applicationBufferSize.coerceAtLeast(32768))
        appOutBuf = ByteBuffer.allocate(session.applicationBufferSize.coerceAtLeast(32768))
        netInBuf = ByteBuffer.allocate(session.packetBufferSize.coerceAtLeast(32768))
        netOutBuf = ByteBuffer.allocate(session.packetBufferSize.coerceAtLeast(32768))

        appInBuf.flip() // Initially empty for reading

        inputStream = TlsInputStream()
        outputStream = TlsOutputStream()
    }

    /**
     * Performs the full TLS Client handshake over the raw streams.
     */
    fun performHandshake(): Boolean {
        return try {
            engine.beginHandshake()
            var hsStatus = engine.handshakeStatus

            while (hsStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
                hsStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
            ) {
                when (hsStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        netOutBuf.clear()
                        val res = engine.wrap(appOutBuf, netOutBuf)
                        hsStatus = res.handshakeStatus
                        netOutBuf.flip()
                        if (netOutBuf.hasRemaining()) {
                            val data = ByteArray(netOutBuf.remaining())
                            netOutBuf.get(data)
                            rawOut.write(data)
                            rawOut.flush()
                        }
                    }
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        if (netInBuf.position() == 0) {
                            val buffer = ByteArray(4096)
                            val read = rawIn.read(buffer)
                            if (read == -1) return false
                            netInBuf.put(buffer, 0, read)
                        }
                        netInBuf.flip()
                        val res = engine.unwrap(netInBuf, appInBuf)
                        hsStatus = res.handshakeStatus
                        netInBuf.compact()

                        if (res.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                            val buffer = ByteArray(4096)
                            val read = rawIn.read(buffer)
                            if (read == -1) return false
                            netInBuf.put(buffer, 0, read)
                        }
                    }
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        var task = engine.delegatedTask
                        while (task != null) {
                            task.run()
                            task = engine.delegatedTask
                        }
                        hsStatus = engine.handshakeStatus
                    }
                    else -> break
                }
            }
            Log.d(TAG, "TLS Handshake completed successfully with $host:$port (SNI: $sni)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "TLS Handshake failed with $host:$port: ${e.message}")
            false
        }
    }

    private inner class TlsInputStream : InputStream() {
        private val singleByteBuf = ByteArray(1)

        override fun read(): Int {
            val len = read(singleByteBuf, 0, 1)
            return if (len == -1) -1 else (singleByteBuf[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0

            // If we have decrypted app data ready, return it
            if (appInBuf.hasRemaining()) {
                val toRead = len.coerceAtMost(appInBuf.remaining())
                appInBuf.get(b, off, toRead)
                return toRead
            }

            appInBuf.clear()

            // Read encrypted TLS data from network stream and unwrap
            while (true) {
                if (netInBuf.position() > 0) {
                    netInBuf.flip()
                    val res = engine.unwrap(netInBuf, appInBuf)
                    netInBuf.compact()

                    if (appInBuf.position() > 0) {
                        appInBuf.flip()
                        val toRead = len.coerceAtMost(appInBuf.remaining())
                        appInBuf.get(b, off, toRead)
                        return toRead
                    }

                    if (res.status == SSLEngineResult.Status.CLOSED) {
                        return -1
                    }
                }

                // Need more encrypted bytes from network
                val temp = ByteArray(4096)
                val r = rawIn.read(temp)
                if (r == -1) {
                    if (appInBuf.position() > 0) {
                        appInBuf.flip()
                        val toRead = len.coerceAtMost(appInBuf.remaining())
                        appInBuf.get(b, off, toRead)
                        return toRead
                    }
                    return -1
                }
                netInBuf.put(temp, 0, r)
            }
        }

        override fun close() {
            try { rawIn.close() } catch (_: Exception) {}
        }
    }

    private inner class TlsOutputStream : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            var offset = off
            var remaining = len

            while (remaining > 0) {
                appOutBuf.clear()
                val chunk = remaining.coerceAtMost(appOutBuf.capacity())
                appOutBuf.put(b, offset, chunk)
                appOutBuf.flip()

                netOutBuf.clear()
                val res = engine.wrap(appOutBuf, netOutBuf)
                netOutBuf.flip()

                if (netOutBuf.hasRemaining()) {
                    val data = ByteArray(netOutBuf.remaining())
                    netOutBuf.get(data)
                    rawOut.write(data)
                }

                offset += chunk
                remaining -= chunk
            }
            rawOut.flush()
        }

        override fun flush() {
            rawOut.flush()
        }

        override fun close() {
            try { rawOut.close() } catch (_: Exception) {}
        }
    }
}
