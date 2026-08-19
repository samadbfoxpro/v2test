package com.example.service.tun

import com.example.service.log.VpnLogger
import com.example.service.proxy.ProxyTunnel
import com.example.service.proxy.XrayOutboundClient
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles TCP IP packets from TUN interface, manages local TCP state machine,
 * and bridges data with XrayOutboundClient tunnel.
 */
class TunTcpHandler(
    private val outboundClient: XrayOutboundClient,
    private val tunOutputStream: FileOutputStream,
    private val onDataTransferred: (upload: Long, download: Long) -> Unit
) {
    companion object {
        private const val TAG = "TunTcpHandler"
        private const val MAX_CONCURRENT_SESSIONS = 256
        private const val MSS_CHUNK_SIZE = 1360
    }

    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private val executor: ExecutorService = Executors.newFixedThreadPool(32)
    private val isRunning = AtomicBoolean(true)

    inner class TcpSession(
        val key: String,
        val clientIp: ByteArray,
        val clientPort: Int,
        val serverIp: ByteArray,
        val serverPort: Int,
        val serverIpStr: String
    ) {
        var clientSeq: Long = 0
        var mySeq: Long = 1000
        var isConnected = false
        var tunnel: ProxyTunnel? = null
        var isClosed = false

        fun handlePacket(packet: IpPacket) {
            if (isClosed) return

            when {
                packet.isSyn -> {
                    clientSeq = packet.tcpSeqNum
                    // Reply with SYN+ACK
                    sendPacket(flags = 0x12, seq = mySeq, ack = clientSeq + 1)
                    mySeq++

                    VpnLogger.logRouting(
                        target = "$serverIpStr:$serverPort",
                        protocol = "TCP",
                        action = "PROXIED_TUNNEL",
                        detail = "Session $clientPort -> $serverPort"
                    )

                    // Connect outbound to remote server via Xray
                    executor.execute {
                        try {
                            tunnel = outboundClient.openTargetStream(serverIpStr, serverPort)
                            if (tunnel != null && !isClosed) {
                                isConnected = true
                                VpnLogger.activeTcpCount.incrementAndGet()
                                startDownstreamReader()
                            } else {
                                sendRst()
                                close()
                            }
                        } catch (e: Exception) {
                            VpnLogger.logError(TAG, "Failed outbound TCP connect to $serverIpStr:$serverPort", e)
                            sendRst()
                            close()
                        }
                    }
                }
                packet.isFin -> {
                    clientSeq = packet.tcpSeqNum
                    sendPacket(flags = 0x11, seq = mySeq, ack = clientSeq + 1) // FIN+ACK
                    close()
                }
                packet.isRst -> {
                    close()
                }
                packet.isAck -> {
                    val payload = packet.tcpPayload
                    if (payload.isNotEmpty()) {
                        clientSeq = packet.tcpSeqNum + payload.size
                        // Send ACK back to TUN
                        sendPacket(flags = 0x10, seq = mySeq, ack = clientSeq)
                        onDataTransferred(payload.size.toLong(), 0)

                        // Forward payload to remote outbound socket
                        executor.execute {
                            try {
                                val out = tunnel?.outputStream
                                if (out != null) {
                                    out.write(payload)
                                    out.flush()
                                }
                            } catch (e: Exception) {
                                close()
                            }
                        }
                    }
                }
            }
        }

        private fun startDownstreamReader() {
            executor.execute {
                val buf = ByteArray(16384)
                val inStream = tunnel?.inputStream
                try {
                    while (isRunning.get() && !isClosed && inStream != null) {
                        val read = inStream.read(buf)
                        if (read == -1) break

                        onDataTransferred(0, read.toLong())

                        // Chunk into MTU/MSS safe segments (1360 bytes)
                        var off = 0
                        while (off < read && !isClosed) {
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
                    if (!isClosed) {
                        sendPacket(flags = 0x11, seq = mySeq, ack = clientSeq + 1) // FIN+ACK
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
                    ackNum = clientSeq + 1,
                    flags = 0x14 // RST+ACK
                )
                synchronized(tunOutputStream) {
                    tunOutputStream.write(raw)
                    tunOutputStream.flush()
                }
            } catch (_: Exception) {}
        }

        fun close() {
            if (isClosed) return
            isClosed = true
            sessions.remove(key)
            if (isConnected) {
                VpnLogger.activeTcpCount.decrementAndGet()
            }
            try { tunnel?.close() } catch (_: Exception) {}
        }
    }

    fun handleTcpPacket(packet: IpPacket) {
        val key = "${packet.sourceIpStr}:${packet.tcpSourcePort}->${packet.destIpStr}:${packet.tcpDestPort}"
        var session = sessions[key]

        if (session == null) {
            if (packet.isSyn) {
                if (sessions.size >= MAX_CONCURRENT_SESSIONS) {
                    // Evict or reject to protect RAM
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
            } else {
                // Send RST for unknown connection
                val rst = IpPacket.buildTcpPacket(
                    srcIp = packet.destIp,
                    dstIp = packet.sourceIp,
                    srcPort = packet.tcpDestPort,
                    dstPort = packet.tcpSourcePort,
                    seqNum = 0,
                    ackNum = packet.tcpSeqNum + 1,
                    flags = 0x14
                )
                synchronized(tunOutputStream) {
                    try {
                        tunOutputStream.write(rst)
                        tunOutputStream.flush()
                    } catch (_: Exception) {}
                }
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
