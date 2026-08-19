package com.example.service.tun

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.data.model.ServerConfig
import com.example.service.log.VpnLogger
import com.example.service.proxy.LocalProxyServer
import com.example.service.proxy.XrayOutboundClient
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance TUN packet processor.
 * Reads IP packets from the Android VPN TUN interface, dispatches DNS (UDP 53), ICMP,
 * and TCP/UDP connections via TunTcpHandler and Xray outbound, writing response packets back to TUN.
 */
class TunPacketPump(
    private val vpnService: VpnService,
    private val vpnInterface: ParcelFileDescriptor,
    private val serverConfig: ServerConfig,
    private val remoteDnsIp: String = "1.1.1.1",
    private val onSpeedUpdate: (uploadBytes: Long, downloadBytes: Long) -> Unit
) {
    companion object {
        private const val TAG = "TunPacketPump"
        private const val BUFFER_SIZE = 32768
        private const val UDP_IDLE_TIMEOUT_MS = 60000L
        private val FALLBACK_DNS_LIST = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "1.0.0.1")
    }

    private val isRunning = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private var executor: ExecutorService? = null
    private var reaperExecutor: ScheduledExecutorService? = null
    private var localProxyServer: LocalProxyServer? = null
    private var tunTcpHandler: TunTcpHandler? = null
    private val outboundClient = XrayOutboundClient(vpnService, serverConfig)

    private val totalUpload = AtomicLong(0)
    private val totalDownload = AtomicLong(0)

    // Reusable UDP session map: "clientPort->destIp:destPort" -> UdpSession
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()

    inner class UdpSession(
        val socket: DatagramSocket,
        val clientIp: ByteArray,
        val clientPort: Int,
        val destIp: ByteArray,
        val destPort: Int
    ) {
        @Volatile
        var lastActiveTime = System.currentTimeMillis()

        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun start() {
        if (isRunning.getAndSet(true)) return

        executor = Executors.newFixedThreadPool(24)
        reaperExecutor = Executors.newSingleThreadScheduledExecutor()

        // Start reaper task for stale UDP sessions to conserve memory & battery
        reaperExecutor?.scheduleWithFixedDelay({
            val now = System.currentTimeMillis()
            val it = udpSessions.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (now - entry.value.lastActiveTime > UDP_IDLE_TIMEOUT_MS) {
                    entry.value.close()
                    it.remove()
                    VpnLogger.activeUdpCount.decrementAndGet()
                }
            }
        }, 30, 30, TimeUnit.SECONDS)

        // Start embedded SOCKS5 & HTTP proxy on 127.0.0.1:10808 / 10809
        localProxyServer = LocalProxyServer(
            vpnService = vpnService,
            serverConfig = serverConfig,
            onDataTransferred = { up, down ->
                totalUpload.addAndGet(up)
                totalDownload.addAndGet(down)
                onSpeedUpdate(up, down)
            }
        )
        localProxyServer?.start()

        // Read thread for TUN interface
        readerThread = Thread {
            val inStream = FileInputStream(vpnInterface.fileDescriptor)
            val outStream = FileOutputStream(vpnInterface.fileDescriptor)
            val packetBuffer = ByteArray(BUFFER_SIZE)

            tunTcpHandler = TunTcpHandler(
                outboundClient = outboundClient,
                tunOutputStream = outStream,
                onDataTransferred = { up, down ->
                    totalUpload.addAndGet(up)
                    totalDownload.addAndGet(down)
                    onSpeedUpdate(up, down)
                }
            )

            VpnLogger.logConnection(TAG, "TUN packet pump started for server ${serverConfig.name}")

            try {
                while (isRunning.get()) {
                    val length = inStream.read(packetBuffer)
                    if (length <= 0) break

                    totalUpload.addAndGet(length.toLong())
                    onSpeedUpdate(length.toLong(), 0)
                    VpnLogger.totalPacketsProcessedCount.incrementAndGet()

                    val ipPacket = try {
                        IpPacket(packetBuffer, length)
                    } catch (e: Exception) {
                        VpnLogger.totalPacketsDroppedCount.incrementAndGet()
                        continue
                    }

                    when {
                        // 1. DNS Query (UDP Port 53)
                        ipPacket.isUdp && ipPacket.udpDestPort == 53 -> {
                            val packetCopy = IpPacket(packetBuffer.copyOf(length), length)
                            executor?.execute {
                                handleDnsPacket(packetCopy, outStream)
                            }
                        }

                        // 2. ICMP Echo (Ping inside tunnel)
                        ipPacket.isIcmp -> {
                            val echoReply = IpPacket.buildIcmpEchoReply(ipPacket)
                            if (echoReply != null) {
                                synchronized(outStream) {
                                    try {
                                        outStream.write(echoReply)
                                        outStream.flush()
                                        totalDownload.addAndGet(echoReply.size.toLong())
                                        onSpeedUpdate(0, echoReply.size.toLong())
                                    } catch (_: Exception) {
                                        VpnLogger.totalPacketsDroppedCount.incrementAndGet()
                                    }
                                }
                            }
                        }

                        // 3. UDP Generic (Audio, Video, WebRTC, Gaming)
                        ipPacket.isUdp -> {
                            val packetCopy = IpPacket(packetBuffer.copyOf(length), length)
                            executor?.execute {
                                handleGenericUdpPacket(packetCopy, outStream)
                            }
                        }

                        // 4. TCP Packet handling via TunTcpHandler
                        ipPacket.isTcp -> {
                            tunTcpHandler?.handleTcpPacket(ipPacket)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    VpnLogger.logError(TAG, "TUN reader loop closed: ${e.message}", e)
                }
            } finally {
                try { inStream.close() } catch (_: Exception) {}
                try { outStream.close() } catch (_: Exception) {}
            }
        }.apply {
            name = "v2rayNG-TunPump"
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
    }

    fun stop() {
        isRunning.set(false)
        try { tunTcpHandler?.stop() } catch (_: Exception) {}
        try { localProxyServer?.stop() } catch (_: Exception) {}
        try { readerThread?.interrupt() } catch (_: Exception) {}
        try { reaperExecutor?.shutdownNow() } catch (_: Exception) {}
        try { executor?.shutdownNow() } catch (_: Exception) {}

        for (session in udpSessions.values) {
            session.close()
        }
        udpSessions.clear()
        VpnLogger.activeUdpCount.set(0)

        tunTcpHandler = null
        localProxyServer = null
        readerThread = null
        reaperExecutor = null
        executor = null
    }

    /**
     * Resolves DNS queries through upstream protected UDP socket with failover and logging.
     */
    private fun handleDnsPacket(packet: IpPacket, outStream: FileOutputStream) {
        val queryPayload = packet.udpPayload
        if (queryPayload.isEmpty()) return

        val domain = packet.extractDnsQueryDomain().ifBlank { "unknown.query" }
        val startTime = System.currentTimeMillis()

        var success = false
        val dnsServers = listOf(remoteDnsIp) + FALLBACK_DNS_LIST.filter { it != remoteDnsIp }

        for (dnsIp in dnsServers) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                vpnService.protect(socket)
                socket.soTimeout = 2500

                val upstreamDns = InetAddress.getByName(dnsIp)
                val sendPacket = DatagramPacket(queryPayload, queryPayload.size, upstreamDns, 53)
                socket.send(sendPacket)

                val recvBuffer = ByteArray(2048)
                val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
                socket.receive(recvPacket)

                val responseData = recvBuffer.copyOf(recvPacket.length)
                val replyIpPacket = IpPacket.buildUdpPacket(
                    srcIp = packet.destIp,
                    dstIp = packet.sourceIp,
                    srcPort = packet.udpDestPort,
                    dstPort = packet.udpSourcePort,
                    payload = responseData
                )

                synchronized(outStream) {
                    outStream.write(replyIpPacket)
                    outStream.flush()
                    totalDownload.addAndGet(replyIpPacket.size.toLong())
                    onSpeedUpdate(0, replyIpPacket.size.toLong())
                }

                val latency = System.currentTimeMillis() - startTime
                VpnLogger.logDns(domain, dnsIp, latency, isSuccess = true)
                success = true
                break
            } catch (e: Exception) {
                // Try next DNS in list
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }

        if (!success) {
            val totalLatency = System.currentTimeMillis() - startTime
            VpnLogger.logDns(domain, remoteDnsIp, totalLatency, isSuccess = false, errorMsg = "DNS Timeout on all upstreams")
            VpnLogger.totalPacketsDroppedCount.incrementAndGet()
        }
    }

    /**
     * Forwards non-DNS UDP packets through reusable session sockets.
     */
    private fun handleGenericUdpPacket(packet: IpPacket, outStream: FileOutputStream) {
        val payload = packet.udpPayload
        if (payload.isEmpty()) return

        val key = "${packet.udpSourcePort}->${packet.destIpStr}:${packet.udpDestPort}"
        var session = udpSessions[key]

        if (session == null || session.socket.isClosed) {
            try {
                val socket = DatagramSocket()
                vpnService.protect(socket)
                socket.soTimeout = 5000

                val newSession = UdpSession(
                    socket = socket,
                    clientIp = packet.sourceIp,
                    clientPort = packet.udpSourcePort,
                    destIp = packet.destIp,
                    destPort = packet.udpDestPort
                )
                udpSessions[key] = newSession
                VpnLogger.activeUdpCount.incrementAndGet()
                session = newSession

                // Start receiver loop for this UDP session
                executor?.execute {
                    val recvBuffer = ByteArray(4096)
                    val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
                    try {
                        while (isRunning.get() && !socket.isClosed) {
                            socket.receive(recvPacket)
                            newSession.lastActiveTime = System.currentTimeMillis()
                            val responseData = recvBuffer.copyOf(recvPacket.length)

                            val replyIpPacket = IpPacket.buildUdpPacket(
                                srcIp = newSession.destIp,
                                dstIp = newSession.clientIp,
                                srcPort = newSession.destPort,
                                dstPort = newSession.clientPort,
                                payload = responseData
                            )

                            synchronized(outStream) {
                                outStream.write(replyIpPacket)
                                outStream.flush()
                                totalDownload.addAndGet(replyIpPacket.size.toLong())
                                onSpeedUpdate(0, replyIpPacket.size.toLong())
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        newSession.close()
                        udpSessions.remove(key)
                        VpnLogger.activeUdpCount.decrementAndGet()
                    }
                }
            } catch (e: Exception) {
                VpnLogger.logError(TAG, "Failed creating UDP socket for $key: ${e.message}", e)
                return
            }
        }

        try {
            session.lastActiveTime = System.currentTimeMillis()
            val targetIp = InetAddress.getByAddress(packet.destIp)
            val sendPacket = DatagramPacket(payload, payload.size, targetIp, packet.udpDestPort)
            session.socket.send(sendPacket)
        } catch (e: Exception) {
            VpnLogger.totalPacketsDroppedCount.incrementAndGet()
        }
    }
}
