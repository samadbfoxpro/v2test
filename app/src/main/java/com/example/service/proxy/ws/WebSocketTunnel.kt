package com.example.service.proxy.ws

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Encapsulates bidirectional WebSocket framing (RFC 6455) for VLESS / VMess over WebSocket (ws).
 */
class WebSocketTunnel(
    rawIn: InputStream,
    rawOut: OutputStream
) {
    val inputStream: InputStream = WsInputStream(rawIn, rawOut)
    val outputStream: OutputStream = WsOutputStream(rawOut)

    companion object {
        private const val TAG = "WebSocketTunnel"

        fun performHandshake(
            rawIn: InputStream,
            rawOut: OutputStream,
            path: String,
            host: String
        ): Boolean {
            val cleanPath = if (path.startsWith("/")) path else "/$path"
            val req = "GET $cleanPath HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"

            rawOut.write(req.toByteArray(Charsets.US_ASCII))
            rawOut.flush()

            // Read HTTP response status line
            val statusLine = readLine(rawIn)
            if (!statusLine.contains("101")) {
                Log.e(TAG, "WS Handshake failed, received: $statusLine")
                return false
            }

            // Skip headers until empty line
            while (true) {
                val line = readLine(rawIn)
                if (line.isEmpty()) break
            }
            return true
        }

        private fun readLine(inStream: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val c = inStream.read()
                if (c == -1) break
                if (c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
            }
            return sb.toString()
        }
    }

    private class WsOutputStream(private val out: OutputStream) : OutputStream() {
        private val random = SecureRandom()

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            var remaining = len
            var currentOff = off

            while (remaining > 0) {
                val frameLen = minOf(remaining, 16384)
                writeFrame(b, currentOff, frameLen)
                currentOff += frameLen
                remaining -= frameLen
            }
            out.flush()
        }

        private fun writeFrame(data: ByteArray, off: Int, len: Int) {
            // Client-to-server frame MUST be masked
            val mask = ByteArray(4)
            random.nextBytes(mask)

            val header: ByteArray
            if (len < 126) {
                header = ByteArray(6)
                header[0] = 0x82.toByte() // FIN | Opcode 2 (Binary)
                header[1] = (0x80 or len).toByte() // Masked | Length
                System.arraycopy(mask, 0, header, 2, 4)
            } else if (len <= 65535) {
                header = ByteArray(8)
                header[0] = 0x82.toByte()
                header[1] = (0x80 or 126).toByte()
                header[2] = (len ushr 8).toByte()
                header[3] = (len and 0xFF).toByte()
                System.arraycopy(mask, 0, header, 4, 4)
            } else {
                header = ByteArray(14)
                header[0] = 0x82.toByte()
                header[1] = (0x80 or 127).toByte()
                val bb = ByteBuffer.wrap(header, 2, 8)
                bb.putLong(len.toLong())
                System.arraycopy(mask, 0, header, 10, 4)
            }

            out.write(header)

            val masked = ByteArray(len)
            for (i in 0 until len) {
                masked[i] = (data[off + i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            out.write(masked)
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            out.close()
        }
    }

    private class WsInputStream(
        private val inStream: InputStream,
        private val outStream: OutputStream
    ) : InputStream() {
        private var buffer = ByteArray(0)
        private var bufferPos = 0

        override fun read(): Int {
            val single = ByteArray(1)
            val count = read(single, 0, 1)
            return if (count == -1) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0

            while (bufferPos >= buffer.size) {
                if (!readNextFrame()) {
                    return -1 // EOF
                }
            }

            val available = buffer.size - bufferPos
            val toCopy = minOf(len, available)
            System.arraycopy(buffer, bufferPos, b, off, toCopy)
            bufferPos += toCopy
            return toCopy
        }

        private fun readNextFrame(): Boolean {
            val b0 = inStream.read()
            if (b0 == -1) return false

            val opcode = b0 and 0x0F
            val b1 = inStream.read()
            if (b1 == -1) return false

            val isMasked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()

            if (payloadLen == 126L) {
                val b2 = inStream.read()
                val b3 = inStream.read()
                if (b2 == -1 || b3 == -1) return false
                payloadLen = ((b2 shl 8) or b3).toLong()
            } else if (payloadLen == 127L) {
                val lenBytes = ByteArray(8)
                if (readFully(lenBytes) != 8) return false
                payloadLen = ByteBuffer.wrap(lenBytes).long
            }

            val maskKey = if (isMasked) {
                val key = ByteArray(4)
                if (readFully(key) != 4) return false
                key
            } else null

            val payload = ByteArray(payloadLen.toInt())
            if (readFully(payload) != payload.size) return false

            if (isMasked && maskKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }

            return when (opcode) {
                0x01, 0x02 -> { // Text or Binary Frame
                    buffer = payload
                    bufferPos = 0
                    true
                }
                0x08 -> { // Close Frame
                    false
                }
                0x09 -> { // Ping Frame -> Send Pong
                    sendPong(payload)
                    readNextFrame()
                }
                0x0A -> { // Pong Frame -> Ignore
                    readNextFrame()
                }
                else -> {
                    buffer = payload
                    bufferPos = 0
                    true
                }
            }
        }

        private fun sendPong(payload: ByteArray) {
            try {
                val header = byteArrayOf(0x8A.toByte(), (0x80 or payload.size).toByte(), 0, 0, 0, 0)
                outStream.write(header)
                outStream.write(payload)
                outStream.flush()
            } catch (_: Exception) {}
        }

        private fun readFully(target: ByteArray): Int {
            var totalRead = 0
            while (totalRead < target.size) {
                val r = inStream.read(target, totalRead, target.size - totalRead)
                if (r == -1) break
                totalRead += r
            }
            return totalRead
        }

        override fun close() {
            inStream.close()
        }
    }
}
