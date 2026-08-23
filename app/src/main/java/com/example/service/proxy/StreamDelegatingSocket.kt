package com.example.service.proxy

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * A Socket wrapper that delegates all I/O to the provided streams
 * instead of using the underlying socket's raw streams.
 *
 * This is critical for Proxy Chain (2-hop): after Hop 1, we have
 * protocol-wrapped streams (VLESS/WS framed). When we layer TLS
 * for Hop 2, SSLSocketFactory.createSocket(socket, ...) reads/writes
 * from socket.getInputStream()/getOutputStream() directly. Without
 * this wrapper, TLS bytes would bypass the VLESS/WS framing and go
 * directly to the raw TCP socket → breaking the chain.
 *
 * With this wrapper, TLS bytes flow through the correct path:
 * TLS → VLESS/WS framed stream → raw TCP → Relay Server
 */
class StreamDelegatingSocket(
    private val delegate: Socket,
    private val delegateIn: InputStream,
    private val delegateOut: OutputStream,
    private val remoteHost: String,
    private val remotePort: Int
) : Socket() {

    override fun getInputStream(): InputStream = delegateIn
    override fun getOutputStream(): OutputStream = delegateOut

    override fun isConnected(): Boolean = delegate.isConnected
    override fun isBound(): Boolean = delegate.isBound
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun getSoLinger(): Int = delegate.soLinger
    override fun getSendBufferSize(): Int = delegate.sendBufferSize
    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
    override fun getReuseAddress(): Boolean = delegate.reuseAddress
    override fun getOOBInline(): Boolean = delegate.oobInline
    override fun getTrafficClass(): Int = delegate.trafficClass
    override fun getPort(): Int = remotePort
    override fun getLocalPort(): Int = delegate.localPort
    override fun getInetAddress(): InetAddress? = try { InetAddress.getByName(remoteHost) } catch (_: Exception) { null }
    override fun getLocalAddress(): InetAddress? = delegate.localAddress
    override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(remoteHost, remotePort)
    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress

    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun setSoLinger(on: Boolean, linger: Int) { delegate.setSoLinger(on, linger) }
    override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
    override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
    override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
    override fun setOOBInline(on: Boolean) { delegate.oobInline = on }
    override fun setTrafficClass(tc: Int) { delegate.trafficClass = tc }

    override fun close() {
        try { delegateOut.close() } catch (_: Exception) {}
        try { delegateIn.close() } catch (_: Exception) {}
        try { delegate.close() } catch (_: Exception) {}
    }

    override fun shutdownInput() { delegate.shutdownInput() }
    override fun shutdownOutput() { delegate.shutdownOutput() }
}
