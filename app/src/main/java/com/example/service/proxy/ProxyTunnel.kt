package com.example.service.proxy

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

data class ProxyTunnel(
    val socket: Socket,
    val inputStream: InputStream,
    val outputStream: OutputStream
) : Closeable {
    override fun close() {
        try {
            outputStream.close()
        } catch (_: Exception) {}
        try {
            inputStream.close()
        } catch (_: Exception) {}
        try {
            socket.close()
        } catch (_: Exception) {}
    }
}
