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
        val serverIpStr: String,
        val clientIpStr: String = "172.19.0.1"
    ) {
        val realHost = fakeDnsManager?.getRealHost(serverIpStr) ?: ""
        val isFakeDns = fakeDnsManager?.isFakeIp(serverIpStr) == true

        val diagnosticSession = com.example.service.log.LogRecorder.startSession(
            protocol = "TCP",
            destIp = serverIpStr,
            destPort = serverPort,
            srcVirtualIp = clientIpStr,
            srcPort = clientPort,
            hostname = realHost,
            isFakeDns = isFakeDns,
            fakeIp = if (isFakeDns) serverIpStr else ""
        )

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

        private var lastAckReceived = 0L
        private var lastSeqReceived = 0L
        private var isFirstDownstreamByte = true

        fun handlePacket(packet: IpPacket) {
            if (isClosed.get()) return

            diagnosticSession.packetsIn.incrementAndGet()

            when {
                packet.isSyn -> {
                    // SYN occupies 1 sequence number
                    clientSeq = packet.tcpSeqNum + 1
                    diagnosticSession.lastClientSeq = packet.tcpSeqNum
                    diagnosticSession.lastClientAck = packet.tcpAckNum

                    com.example.service.log.LogRecorder.logTunPacket(
                        direction = "TUN_IN",
                        ipVer = if (packet.isIpv6) "IPv6" else "IPv4",
                        protocol = "TCP",
                        srcIp = clientIpStr,
                        srcPort = clientPort,
                        dstIp = serverIpStr,
                        dstPort = serverPort,
                        packetLen = packet.totalLength,
                        payloadLen = 0,
                        tcpFlags = "SYN",
                        seq = packet.tcpSeqNum,
                        ack = packet.tcpAckNum,
                        window = packet.tcpWindowSize,
                        mss = 1360,
                        sessionId = diagnosticSession.sessionId
                    )

                    com.example.service.log.LogRecorder.logTcpState(diagnosticSession.sessionId, "CLOSED", "SYN_RECEIVED")

                    // Reply with SYN+ACK
                    sendPacket(flags = 0x12, seq = mySeq, ack = clientSeq) // SYN+ACK
                    mySeq++

                    com.example.service.log.LogRecorder.logTunPacket(
                        direction = "TUN_OUT",
                        ipVer = if (packet.isIpv6) "IPv6" else "IPv4",
                        protocol = "TCP",
                        srcIp = serverIpStr,
                        srcPort = serverPort,
                        dstIp = clientIpStr,
                        dstPort = clientPort,
                        packetLen = 40,
                        payloadLen = 0,
                        tcpFlags = "SYN+ACK",
                        seq = mySeq - 1,
                        ack = clientSeq,
                        window = 65535,
                        mss = 1360,
                        sessionId = diagnosticSession.sessionId
                    )

                    VpnLogger.logRouting(
                        target = if (realHost.isNotBlank()) "$realHost ($serverIpStr:$serverPort)" else "$serverIpStr:$serverPort",
                        protocol = "TCP",
                        action = "PROXIED_TUNNEL",
                        detail = "Session ${diagnosticSession.sessionId} ($clientPort -> $serverPort)"
                    )

                    startOutboundConnect()
                }

                packet.isRst -> {
                    diagnosticSession.rstCount.incrementAndGet()
                    com.example.service.log.LogRecorder.logTcpAnomaly(
                        type = com.example.service.log.AnomalyType.UNEXPECTED_RST,
                        sessionId = diagnosticSession.sessionId,
                        evidence = "RST packet received from client on session ${diagnosticSession.sessionId}",
                        dest = "$serverIpStr:$serverPort"
                    )
                    close(reason = "CLIENT_RST")
                }

                packet.isFin -> {
                    clientSeq = packet.tcpSeqNum + 1
                    com.example.service.log.LogRecorder.logTcpState(diagnosticSession.sessionId, "ESTABLISHED", "FIN_WAIT")
                    // Send FIN+ACK or ACK
                    sendPacket(flags = 0x11, seq = mySeq, ack = clientSeq)
                    mySeq++
                    close(reason = "CLIENT_FIN")
                }

                packet.isAck -> {
                    val payload = packet.tcpPayload
                    if (payload.isNotEmpty()) {
                        // Check for TCP Retransmission
                        if (lastSeqReceived > 0 && packet.tcpSeqNum <= lastSeqReceived) {
                            val attempt = diagnosticSession.retransmissions.incrementAndGet()
                            val elapsed = System.currentTimeMillis() - diagnosticSession.lastActiveTime
                            com.example.service.log.LogRecorder.logTcpRetransmission(
                                sessionId = diagnosticSession.sessionId,
                                seq = packet.tcpSeqNum,
                                length = payload.size,
                                attempt = attempt,
                                delayMs = elapsed
                            )
                        }
                        lastSeqReceived = packet.tcpSeqNum
                        diagnosticSession.lastActiveTime = System.currentTimeMillis()

                        clientSeq = packet.tcpSeqNum + payload.size
                        diagnosticSession.bytesUploaded.addAndGet(payload.size.toLong())

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
                    } else {
                        // Pure ACK (Keep-Alive, Window Scale, Handshake confirmation)
                        val isDup = (packet.tcpSeqNum == lastSeqReceived && packet.tcpAckNum == lastAckReceived)
                        val result = if (isDup) "DUPLICATE_ACK" else if (packet.tcpWindowSize == 0) "ZERO_WINDOW" else "ACCEPTED"

                        if (packet.tcpWindowSize == 0) {
                            com.example.service.log.LogRecorder.logTcpAnomaly(
                                type = com.example.service.log.AnomalyType.ZERO_WINDOW,
                                sessionId = diagnosticSession.sessionId,
                                evidence = "Client advertised zero receive window",
                                dest = "$serverIpStr:$serverPort"
                            )
                        }

                        com.example.service.log.LogRecorder.logPureAck(
                            sessionId = diagnosticSession.sessionId,
                            seq = packet.tcpSeqNum,
                            ack = packet.tcpAckNum,
                            window = packet.tcpWindowSize,
                            expectedAck = mySeq,
                            result = result
                        )

                        lastAckReceived = packet.tcpAckNum
                        if (packet.tcpSeqNum > clientSeq) clientSeq = packet.tcpSeqNum
                        if (packet.tcpAckNum > mySeq) mySeq = packet.tcpAckNum
                    }
                }
            }
        }

        private fun startOutboundConnect() {
            if (isConnecting.compareAndSet(false, true)) {
                executor.execute {
                    val connectStartTime = System.currentTimeMillis()
                    com.example.service.log.LogRecorder.logSessionState(diagnosticSession, com.example.service.log.SessionLifecycleState.CONNECTING)
                    try {
                        val targetHost = if (realHost.isNotBlank()) realHost else serverIpStr
                        var newTunnel = outboundClient.openTargetStream(targetHost, serverPort)

                        // If standard chat port 5222 fails (blocked by CDN or restrictive proxy), instant fallback to port 443
                        if (newTunnel == null && serverPort == 5222 && !isClosed.get()) {
                            newTunnel = outboundClient.openTargetStream(targetHost, 443)
                        }

                        val connectElapsed = System.currentTimeMillis() - connectStartTime

                        if (newTunnel != null && !isClosed.get()) {
                            tunnel = newTunnel
                            isConnected.set(true)
                            isConnecting.set(false)
                            VpnLogger.activeTcpCount.incrementAndGet()

                            diagnosticSession.timing.tcpConnectTimeMs = connectElapsed
                            com.example.service.log.LogRecorder.logOutboundStage(diagnosticSession.sessionId, "TCP_CONNECT_TIME", connectElapsed, "to=$targetHost:$serverPort")
                            com.example.service.log.LogRecorder.logSessionState(diagnosticSession, com.example.service.log.SessionLifecycleState.CONNECTED)
                            com.example.service.log.LogRecorder.logTcpState(diagnosticSession.sessionId, "SYN_RECEIVED", "ESTABLISHED")

                            // Immediately flush any payloads queued during handshake
                            drainPendingQueueDirect(newTunnel)

                            // Start background reading from remote server
                            startDownstreamReader(newTunnel)
                        } else {
                            isConnecting.set(false)
                            com.example.service.log.LogRecorder.logTimeoutOrError(
                                sessionId = diagnosticSession.sessionId,
                                errorType = com.example.service.log.AnomalyType.CONNECT_TIMEOUT,
                                message = "Outbound tunnel connection returned null after ${connectElapsed}ms",
                                destination = "$targetHost:$serverPort",
                                elapsedMs = connectElapsed
                            )
                            sendRst()
                            close(reason = "CONNECT_FAILED")
                        }
                    } catch (e: Exception) {
                        val connectElapsed = System.currentTimeMillis() - connectStartTime
                        isConnecting.set(false)
                        com.example.service.log.LogRecorder.logTimeoutOrError(
                            sessionId = diagnosticSession.sessionId,
                            errorType = com.example.service.log.AnomalyType.OUTBOUND_HANDSHAKE_FAILURE,
                            message = "Exception during outbound connect: ${e.message}",
                            destination = "$serverIpStr:$serverPort",
                            elapsedMs = connectElapsed,
                            exception = e
                        )
                        VpnLogger.logError(TAG, "Failed outbound TCP connect to $serverIpStr:$serverPort", e)
                        sendRst()
                        close(reason = "EXCEPTION: ${e.message}")
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
                    close(reason = "DRAIN_FAILED")
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

                        if (isFirstDownstreamByte) {
                            isFirstDownstreamByte = false
                            val firstByteDuration = System.currentTimeMillis() - diagnosticSession.creationTime
                            diagnosticSession.timing.firstByteTimeMs = firstByteDuration
                            com.example.service.log.LogRecorder.logOutboundStage(diagnosticSession.sessionId, "FIRST_BYTE_TIME", firstByteDuration)
                        }

                        diagnosticSession.bytesDownloaded.addAndGet(read.toLong())
                        diagnosticSession.packetsOut.incrementAndGet()
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
                        close(reason = "REMOTE_CLOSED")
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

        fun close(reason: String = "NORMAL") {
            if (isClosed.compareAndSet(false, true)) {
                sessions.remove(key)
                if (isConnected.get()) {
                    VpnLogger.activeTcpCount.decrementAndGet()
                }
                pendingUpstreamQueue.clear()
                try { tunnel?.close() } catch (_: Exception) {}

                diagnosticSession.closeReason = reason
                com.example.service.log.LogRecorder.logSessionSummary(diagnosticSession)
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
