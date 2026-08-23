package com.example.service.tun

import com.example.service.log.VpnLogger
import com.example.service.proxy.ProxyTunnel
import com.example.service.proxy.XrayOutboundClient
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles TCP IP packets from TUN interface, manages local TCP state machine,
 * buffers upstream payloads during tunnel establishment, and bridges bidirectional
 * traffic with XrayOutboundClient tunnel.
 */
class TunTcpHandler(
    @Volatile private var outboundClient: XrayOutboundClient,
    private val tunOutputStream: FileOutputStream,
    private val fakeDnsManager: com.example.service.dns.FakeDnsManager? = null,
    private val onDataTransferred: (upload: Long, download: Long) -> Unit
) {
    companion object {
        private const val TAG = "TunTcpHandler"
        private const val MAX_CONCURRENT_SESSIONS = 512
        private const val MSS_CHUNK_SIZE = 1360
    }

    fun updateOutboundClient(newClient: XrayOutboundClient) {
        outboundClient = newClient
        for (session in sessions.values) {
            session.close()
        }
        sessions.clear()
    }

    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val isRunning = AtomicBoolean(true)

    inner class TcpSession(
        val key: String,
        val clientIp: ByteArray,
        val clientPort: Int,
        val serverIp: ByteArray,
        val serverPort: Int,
        val serverIpStr: String
    ) {
        @Volatile
        var clientSeq: Long = 0

        @Volatile
        var mySeq: Long = (System.currentTimeMillis() and 0x7FFFFFFF) % 1000000 + 1000

        val isConnecting = AtomicBoolean(false)
        val isConnected = AtomicBoolean(false)
        val isClosed = AtomicBoolean(false)

        @Volatile
        var tunnel: ProxyTunnel? = null

        // Buffer payloads that arrive before the remote outbound handshake completes
        private val pendingUpstreamQueue = ConcurrentLinkedQueue<ByteArray>()
        private val isDrainingQueue = AtomicBoolean(false)

        fun handlePacket(packet: IpPacket) {
            if (isClosed.get()) return

            when {
                packet.isSyn -> {
                    // SYN occupies 1 sequence number
                    clientSeq = packet.tcpSeqNum + 1

                    // Reply with SYN+ACK
                    sendPacket(flags = 0x12, seq = mySeq, ack = clientSeq)
                    mySeq++

                    VpnLogger.logRouting(
                        target = "$serverIpStr:$serverPort",
                        protocol = "TCP",
                        action = "PROXIED_TUNNEL",
                        detail = "Session $clientPort -> $serverPort"
                    )

                    startOutboundConnect()
                }

                packet.isRst -> {
                    close()
                }

                packet.isFin -> {
                    clientSeq = packet.tcpSeqNum + 1
                    // Send FIN+ACK or ACK
                    sendPacket(flags = 0x11, seq = mySeq, ack = clientSeq)
                    mySeq++
                    close()
                }

                packet.isAck -> {
                    val payload = packet.tcpPayload
                    if (payload.isNotEmpty()) {
                        clientSeq = packet.tcpSeqNum + payload.size
                        // Send TCP ACK back to the phone TUN
                        sendPacket(flags = 0x10, seq = mySeq, ack = clientSeq)
                        onDataTransferred(payload.size.toLong(), 0)

                        // Enqueue payload to ensure zero data loss even during connection setup
                        pendingUpstreamQueue.add(payload)

                        if (isConnected.get() && tunnel != null) {
                            drainPendingQueueAsync()
                        } else if (!isConnecting.get()) {
                            startOutboundConnect()
                        }
                    }
                }
            }
        }

        private fun startOutboundConnect() {
            if (isConnecting.compareAndSet(false, true)) {
                executor.execute {
                    try {
                        val targetHost = fakeDnsManager?.getRealHost(serverIpStr) ?: serverIpStr
                        val newTunnel = outboundClient.openTargetStream(targetHost, serverPort)
                        if (newTunnel != null && !isClosed.get()) {
                            tunnel = newTunnel
                            isConnected.set(true)
                            isConnecting.set(false)
                            VpnLogger.activeTcpCount.incrementAndGet()

                            // Immediately flush any payloads queued during handshake
                            drainPendingQueueDirect(newTunnel)

                            // Start background reading from remote server
                            startDownstreamReader(newTunnel)
                        } else {
                            isConnecting.set(false)
                            sendRst()
                            close()
                        }
                    } catch (e: Exception) {
                        isConnecting.set(false)
                        VpnLogger.logError(TAG, "Failed outbound TCP connect to $serverIpStr:$serverPort", e)
                        sendRst()
                        close()
                    }
                }
            }
        }

        private fun drainPendingQueueAsync() {
            executor.execute {
                tunnel?.let { drainPendingQueueDirect(it) }
            }
        }

        private fun drainPendingQueueDirect(activeTunnel: ProxyTunnel) {
            if (isDrainingQueue.compareAndSet(false, true)) {
                try {
                    val out = activeTunnel.outputStream
                    while (true) {
                        val chunk = pendingUpstreamQueue.poll() ?: break
                        out.write(chunk)
                    }
                    out.flush()
                } catch (e: Exception) {
                    close()
                } finally {
                    isDrainingQueue.set(false)
                    // If more items were added concurrently, try to drain once more
                    if (!pendingUpstreamQueue.isEmpty() && !isClosed.get()) {
                        drainPendingQueueDirect(activeTunnel)
                    }
                }
            }
        }

        private fun startDownstreamReader(activeTunnel: ProxyTunnel) {
            executor.execute {
                val buf = ByteArray(16384)
                val inStream = activeTunnel.inputStream
                try {
                    while (isRunning.get() && !isClosed.get()) {
                        val read = inStream.read(buf)
                        if (read == -1) break

                        onDataTransferred(0, read.toLong())

                        // Chunk into MTU/MSS safe segments (1360 bytes)
                        var off = 0
                        while (off < read && !isClosed.get()) {
                            val chunkLen = minOf(read - off, MSS_CHUNK_SIZE)
                            val chunk = buf.copyOfRange(off, off + chunkLen)
                            sendPacket(flags = 0x18, seq = mySeq, ack = clientSeq, payload = chunk) // PSH+ACK
                            mySeq += chunkLen
                            off += chunkLen
                        }
                    }
                } catch (e: Exception) {
                    VpnLogger.totalPacketsDroppedCount.incrementAndGet()
                } finally {
                    if (!isClosed.get()) {
                        sendPacket(flags = 0x11, seq = mySeq, ack = clientSeq) // FIN+ACK
                        mySeq++
                        close()
                    }
                }
            }
        }

        fun sendPacket(flags: Int, seq: Long, ack: Long, payload: ByteArray = ByteArray(0)) {
            try {
                val raw = IpPacket.buildTcpPacket(
                    srcIp = serverIp,
                    dstIp = clientIp,
                    srcPort = serverPort,
                    dstPort = clientPort,
                    seqNum = seq,
                    ackNum = ack,
                    flags = flags,
                    payload = payload
                )
                synchronized(tunOutputStream) {
                    tunOutputStream.write(raw)
                    tunOutputStream.flush()
                }
                VpnLogger.totalPacketsProcessedCount.incrementAndGet()
            } catch (_: Exception) {
                VpnLogger.totalPacketsDroppedCount.incrementAndGet()
            }
        }

        fun sendRst() {
            try {
                val raw = IpPacket.buildTcpPacket(
                    srcIp = serverIp,
                    dstIp = clientIp,
                    srcPort = serverPort,
                    dstPort = clientPort,
                    seqNum = mySeq,
                    ackNum = clientSeq,
                    flags = 0x14 // RST+ACK
                )
                synchronized(tunOutputStream) {
                    tunOutputStream.write(raw)
                    tunOutputStream.flush()
                }
            } catch (_: Exception) {}
        }

        fun close() {
            if (isClosed.compareAndSet(false, true)) {
                sessions.remove(key)
                if (isConnected.get()) {
                    VpnLogger.activeTcpCount.decrementAndGet()
                }
                pendingUpstreamQueue.clear()
                try { tunnel?.close() } catch (_: Exception) {}
            }
        }
    }

    fun handleTcpPacket(packet: IpPacket) {
        val key = "${packet.sourceIpStr}:${packet.tcpSourcePort}->${packet.destIpStr}:${packet.tcpDestPort}"
        var session = sessions[key]

        if (session == null) {
            if (packet.isSyn || packet.tcpPayload.isNotEmpty()) {
                if (sessions.size >= MAX_CONCURRENT_SESSIONS) {
                    val oldestKey = sessions.keys().nextElement()
                    sessions.remove(oldestKey)?.close()
                }

                session = TcpSession(
                    key = key,
                    clientIp = packet.sourceIp,
                    clientPort = packet.tcpSourcePort,
                    serverIp = packet.destIp,
                    serverPort = packet.tcpDestPort,
                    serverIpStr = packet.destIpStr
                )
                sessions[key] = session
                session.handlePacket(packet)
            } else if (packet.isFin || packet.isRst) {
                // Ignore stale teardown packets for already closed sessions
            }
        } else {
            session.handlePacket(packet)
        }
    }

    fun stop() {
        isRunning.set(false)
        for (session in sessions.values) {
            session.close()
        }
        sessions.clear()
        try { executor.shutdownNow() } catch (_: Exception) {}
    }
}
