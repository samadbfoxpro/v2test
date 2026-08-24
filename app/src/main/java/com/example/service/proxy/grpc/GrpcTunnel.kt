package com.example.service.proxy.grpc

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Implements gRPC Gun framing for VLESS and Trojan over TLS transports.
 * Encapsulates payloads into 5-byte length-prefixed gRPC frames:
 * [1-byte Compression Flag (0x00)] [4-byte Big-Endian Length] [Data Payload]
 */
class GrpcTunnel(
    private val rawIn: InputStream,
    private val rawOut: OutputStream
) {
    val inputStream: InputStream = GrpcInputStream(rawIn)
    val outputStream: OutputStream = GrpcOutputStream(rawOut)

    class GrpcOutputStream(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val frameHeader = ByteBuffer.allocate(5)
            frameHeader.put(0.toByte()) // uncompressed
            frameHeader.putInt(len)
            synchronized(out) {
                out.write(frameHeader.array())
                out.write(b, off, len)
                out.flush()
            }
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            out.close()
        }
    }

    class GrpcInputStream(private val inStream: InputStream) : InputStream() {
        private var currentFrameRemaining = 0
        private val headerBuf = ByteArray(5)

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n > 0) b[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0

            if (currentFrameRemaining <= 0) {
                // Read next 5-byte gRPC header
                var headerRead = 0
                while (headerRead < 5) {
                    val count = inStream.read(headerBuf, headerRead, 5 - headerRead)
                    if (count < 0) return if (headerRead == 0) -1 else headerRead
                    headerRead += count
                }
                val buffer = ByteBuffer.wrap(headerBuf)
                buffer.get() // skip flag
                currentFrameRemaining = buffer.int.coerceAtLeast(0)
                if (currentFrameRemaining == 0) return 0
            }

            val toRead = minOf(len, currentFrameRemaining)
            val bytesRead = inStream.read(b, off, toRead)
            if (bytesRead > 0) {
                currentFrameRemaining -= bytesRead
            }
            return bytesRead
        }

        override fun close() {
            inStream.close()
        }
    }
}
