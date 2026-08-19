package com.example.service.tun

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.data.model.ServerConfig
import com.example.service.proxy.LocalProxyServer
import com.example.service.proxy.XrayOutboundClient
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance TUN packet processor.
 * Reads IP packets from the Android VPN TUN interface, dispatches DNS (UDP 53), ICMP,
 * and TCP connections via TunTcpHandler and Xray outbound, writing response packets back to TUN.
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
    }

    private val isRunning = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private var executor: ExecutorService? = null
    private var localProxyServer: LocalProxyServer? = null
    private var tunTcpHandler: TunTcpHandler? = null
    private val outboundClient = XrayOutboundClient(vpnService, serverConfig)

    private val totalUpload = AtomicLong(0)
    private val totalDownload = AtomicLong(0)

    fun start() {
        if (isRunning.getAndSet(true)) return

        executor = Executors.newCachedThreadPool()

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

            Log.i(TAG, "TUN packet pump started for server ${serverConfig.name}")

            try {
                while (isRunning.get()) {
                    val length = inStream.read(packetBuffer)
                    if (length <= 0) break

                    totalUpload.addAndGet(length.toLong())
                    onSpeedUpdate(length.toLong(), 0)

                    val ipPacket = try {
                        IpPacket(packetBuffer.copyOf(length), length)
                    } catch (e: Exception) {
                        continue
                    }

                    when {
                        // 1. DNS Query (UDP Port 53)
                        ipPacket.isUdp && ipPacket.udpDestPort == 53 -> {
                            executor?.execute {
                                handleDnsPacket(ipPacket, outStream)
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
                                    } catch (_: Exception) {}
                                }
                            }
                        }

                        // 3. UDP Generic (Bypass or forward to upstream)
                        ipPacket.isUdp -> {
                            executor?.execute {
                                handleGenericUdpPacket(ipPacket, outStream)
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
                    Log.e(TAG, "TUN reader error: ${e.message}")
                }
            } finally {
                try { inStream.close() } catch (_: Exception) {}
                try { outStream.close() } catch (_: Exception) {}
            }
        }.apply {
            name = "V2RayNG-TunPump"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        isRunning.set(false)
        try { tunTcpHandler?.stop() } catch (_: Exception) {}
        try { localProxyServer?.stop() } catch (_: Exception) {}
        try { readerThread?.interrupt() } catch (_: Exception) {}
        try { executor?.shutdownNow() } catch (_: Exception) {}
        tunTcpHandler = null
        localProxyServer = null
        readerThread = null
        executor = null
    }

    /**
     * Resolves DNS queries through upstream protected UDP socket.
     */
    private fun handleDnsPacket(packet: IpPacket, outStream: FileOutputStream) {
        var socket: DatagramSocket? = null
        try {
            val queryPayload = packet.udpPayload
            if (queryPayload.isEmpty()) return

            socket = DatagramSocket()
            vpnService.protect(socket)
            socket.soTimeout = 3500

            val upstreamDns = InetAddress.getByName(remoteDnsIp)
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
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Forwards non-DNS UDP packets through protected UDP socket.
     */
    private fun handleGenericUdpPacket(packet: IpPacket, outStream: FileOutputStream) {
        var socket: DatagramSocket? = null
        try {
            val payload = packet.udpPayload
            if (payload.isEmpty()) return

            socket = DatagramSocket()
            vpnService.protect(socket)
            socket.soTimeout = 4000

            val targetIp = InetAddress.getByAddress(packet.destIp)
            val sendPacket = DatagramPacket(payload, payload.size, targetIp, packet.udpDestPort)
            socket.send(sendPacket)

            val recvBuffer = ByteArray(4096)
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
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
