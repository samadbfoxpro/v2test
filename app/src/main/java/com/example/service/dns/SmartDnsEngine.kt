package com.example.service.dns

import android.net.VpnService
import com.example.service.log.VpnLogger
import com.example.service.tun.IpPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Enterprise Smart DNS Engine providing automatic resolver selection, DoH, DoT, FakeDNS,
 * intelligent caching with real TTL, zero-downtime failover, and live diagnostics.
 */
class SmartDnsEngine(
    private val vpnService: VpnService?
) {
    companion object {
        private const val TAG = "SmartDnsEngine"

        val AVAILABLE_RESOLVERS: List<DnsResolver> = listOf(
            UdpResolver("Cloudflare (UDP)", "1.1.1.1"),
            UdpResolver("Google (UDP)", "8.8.8.8"),
            DohResolver("Cloudflare (DoH)", "https://1.1.1.1/dns-query"),
            DohResolver("Google (DoH)", "https://dns.google/dns-query"),
            DohResolver("Quad9 (DoH)", "https://dns.quad9.net/dns-query"),
            DohResolver("AdGuard (DoH)", "https://dns.adguard-dns.com/dns-query"),
            DotResolver("Cloudflare (DoT)", "1.1.1.1", "cloudflare-dns.com"),
            DotResolver("Google (DoT)", "8.8.8.8", "dns.google"),
            DotResolver("Quad9 (DoT)", "9.9.9.9", "dns.quad9.net")
        )
    }

    val cacheManager = DnsCacheManager()
    val fakeDnsManager = FakeDnsManager()
    val diagnosticsManager = DnsDiagnosticsManager()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeResolverIndex = AtomicInteger(0)
    private val consecutiveFailures = AtomicInteger(0)

    @Volatile
    var currentMode: String = "Automatic"
        private set

    @Volatile
    var manualResolver: DnsResolver? = null

    fun setMode(mode: String, fakeDnsEnabled: Boolean = false) {
        currentMode = mode
        fakeDnsManager.isEnabled = fakeDnsEnabled || mode.equals("FAKEDNS", ignoreCase = true)

        manualResolver = when (mode.uppercase()) {
            "UDP_CLOUDFLARE" -> AVAILABLE_RESOLVERS.find { it.name.contains("Cloudflare (UDP)") }
            "UDP_GOOGLE" -> AVAILABLE_RESOLVERS.find { it.name.contains("Google (UDP)") }
            "DOH_CLOUDFLARE" -> AVAILABLE_RESOLVERS.find { it.name.contains("Cloudflare (DoH)") }
            "DOH_GOOGLE" -> AVAILABLE_RESOLVERS.find { it.name.contains("Google (DoH)") }
            "DOH_QUAD9" -> AVAILABLE_RESOLVERS.find { it.name.contains("Quad9 (DoH)") }
            "DOH_ADGUARD" -> AVAILABLE_RESOLVERS.find { it.name.contains("AdGuard (DoH)") }
            "DOT_CLOUDFLARE" -> AVAILABLE_RESOLVERS.find { it.name.contains("Cloudflare (DoT)") }
            "DOT_GOOGLE" -> AVAILABLE_RESOLVERS.find { it.name.contains("Google (DoT)") }
            "DOT_QUAD9" -> AVAILABLE_RESOLVERS.find { it.name.contains("Quad9 (DoT)") }
            else -> null // Auto mode
        }

        diagnosticsManager.updateConfigState(
            modeName = if (manualResolver != null) manualResolver!!.name else if (fakeDnsManager.isEnabled) "FakeDNS + Auto" else "Automatic (Smart)",
            fakeDnsActive = fakeDnsManager.isEnabled,
            leakProtection = true
        )
    }

    /**
     * Resolves incoming DNS packet from TUN interface and writes synthesized response back to [outStream].
     */
    fun handleTunDnsPacket(
        packet: IpPacket,
        outStream: FileOutputStream,
        onDownloadBytes: (Long) -> Unit
    ) {
        val queryPayload = packet.udpPayload
        if (queryPayload.size < 12) return

        val domain = packet.extractDnsQueryDomain().ifBlank { "unknown.query" }
        val startTime = System.currentTimeMillis()

        // 0. Zero-Latency Fast-Path for Reverse PTR and Local Lookups (*.in-addr.arpa, *.ip6.arpa, *.local)
        // Prevents applications like WhatsApp/Telegram from stalling 18+ seconds on internal FakeDNS IP lookups
        if (domain.endsWith(".in-addr.arpa", ignoreCase = true) || 
            domain.endsWith(".ip6.arpa", ignoreCase = true) || 
            domain.endsWith(".local", ignoreCase = true) ||
            domain.endsWith(".internal", ignoreCase = true)
        ) {
            val nxResponse = fakeDnsManager.buildNxDomainResponse(queryPayload)
            if (nxResponse != null) {
                sendDnsReply(packet, nxResponse, outStream, onDownloadBytes)
                diagnosticsManager.recordQuerySuccess(
                    domain = domain,
                    resolverName = "Local-Arpa-Fast",
                    resolverType = DnsResolverType.AUTO,
                    latencyMs = 0L,
                    cacheManager = cacheManager,
                    fakeDnsManager = fakeDnsManager
                )
                VpnLogger.logDns(domain, "Local-Arpa (0ms)", 0L, isSuccess = true)
                return
            }
        }

        // 0.1 Zero-Latency Fast-Path for AAAA (IPv6) queries
        // Returns instant empty NOERROR to prevent WhatsApp / Meta from routing to unroutable IPv6 addresses
        if (isAaaaRecordQuery(queryPayload)) {
            val emptyNoerror = fakeDnsManager.buildEmptyNoErrorResponse(queryPayload)
            if (emptyNoerror != null) {
                sendDnsReply(packet, emptyNoerror, outStream, onDownloadBytes)
                VpnLogger.logDns(domain, "IPv4-Prefer (0ms)", 0L, isSuccess = true)
                return
            }
        }

        // 1. FakeDNS Fast-Path (if enabled and query is A record)
        if (fakeDnsManager.isEnabled && isARecordQuery(queryPayload)) {
            val fakeIp = fakeDnsManager.allocateFakeIp(domain)
            val fakeDnsResponse = fakeDnsManager.buildFakeDnsResponse(queryPayload, fakeIp)
            if (fakeDnsResponse != null) {
                sendDnsReply(packet, fakeDnsResponse, outStream, onDownloadBytes)
                diagnosticsManager.recordQuerySuccess(
                    domain = domain,
                    resolverName = "FakeDNS Engine",
                    resolverType = DnsResolverType.FAKEDNS,
                    latencyMs = 0L,
                    cacheManager = cacheManager,
                    fakeDnsManager = fakeDnsManager
                )
                VpnLogger.logDns(domain, "FakeDNS ($fakeIp)", 0L, isSuccess = true)
                return
            }
        }

        // 2. Intelligent RAM Cache Fast-Path
        val txId = byteArrayOf(queryPayload[0], queryPayload[1])
        val cachedResponse = cacheManager.get(domain, 1, txId)
        if (cachedResponse != null) {
            sendDnsReply(packet, cachedResponse, outStream, onDownloadBytes)
            diagnosticsManager.recordQuerySuccess(
                domain = domain,
                resolverName = "RAM Cache",
                resolverType = DnsResolverType.AUTO,
                latencyMs = 0L,
                cacheManager = cacheManager,
                fakeDnsManager = fakeDnsManager
            )
            VpnLogger.logDns(domain, "RAM-Cache", 0L, isSuccess = true)
            return
        }

        // 3. Upstream Query with Fast 1200ms Failover
        val resolversToTry = if (manualResolver != null) {
            listOf(manualResolver!!) + AVAILABLE_RESOLVERS.filter { it != manualResolver }
        } else {
            getOrderedResolvers()
        }

        var resolvedData: ByteArray? = null
        var usedResolver: DnsResolver? = null

        // Try top 3 available resolvers with 1200ms timeout per attempt to eliminate long blocking
        for (resolver in resolversToTry.take(3)) {
            try {
                val res = resolver.resolve(queryPayload, vpnService, timeoutMs = 1200L)
                if (res != null && res.size >= 12) {
                    resolvedData = res
                    usedResolver = resolver
                    consecutiveFailures.set(0)
                    break
                } else {
                    handleResolverFailure(resolver)
                }
            } catch (e: Exception) {
                handleResolverFailure(resolver)
            }
        }

        val latency = System.currentTimeMillis() - startTime

        if (resolvedData != null && usedResolver != null) {
            // Cache successful resolution
            cacheManager.put(domain, 1, resolvedData)

            sendDnsReply(packet, resolvedData, outStream, onDownloadBytes)
            diagnosticsManager.recordQuerySuccess(
                domain = domain,
                resolverName = usedResolver.name,
                resolverType = usedResolver.type,
                latencyMs = latency,
                cacheManager = cacheManager,
                fakeDnsManager = fakeDnsManager
            )
            VpnLogger.logDns(domain, usedResolver.name, latency, isSuccess = true)
        } else {
            diagnosticsManager.recordQueryFailure(
                domain = domain,
                resolverName = "All Upstreams",
                error = "DNS Timeout on all resolvers",
                isTimeout = true,
                cacheManager = cacheManager
            )
            VpnLogger.logDns(domain, "Failed", latency, isSuccess = false, errorMsg = "All resolvers failed")
            VpnLogger.totalPacketsDroppedCount.incrementAndGet()
        }
    }

    private fun handleResolverFailure(failedResolver: DnsResolver) {
        val fails = consecutiveFailures.incrementAndGet()
        if (fails >= 2 && manualResolver == null) {
            // Trigger automatic failover to next resolver
            val oldIdx = activeResolverIndex.get()
            val newIdx = (oldIdx + 1) % AVAILABLE_RESOLVERS.size
            if (activeResolverIndex.compareAndSet(oldIdx, newIdx)) {
                consecutiveFailures.set(0)
                diagnosticsManager.recordFailover(
                    fromResolver = failedResolver.name,
                    toResolver = AVAILABLE_RESOLVERS[newIdx].name
                )
                VpnLogger.logConnection(TAG, "⚡ تغییر خودکار ریزالور DNS (Failover): ${failedResolver.name} ➔ ${AVAILABLE_RESOLVERS[newIdx].name}")
            }
        }
    }

    private fun getOrderedResolvers(): List<DnsResolver> {
        val currentIdx = activeResolverIndex.get() % AVAILABLE_RESOLVERS.size
        val primary = AVAILABLE_RESOLVERS[currentIdx]
        return listOf(primary) + AVAILABLE_RESOLVERS.filterIndexed { idx, _ -> idx != currentIdx }
    }

    private fun sendDnsReply(
        packet: IpPacket,
        dnsPayload: ByteArray,
        outStream: FileOutputStream,
        onDownloadBytes: (Long) -> Unit
    ) {
        try {
            val replyIpPacket = IpPacket.buildUdpPacket(
                srcIp = packet.destIp,
                dstIp = packet.sourceIp,
                srcPort = packet.udpDestPort,
                dstPort = packet.udpSourcePort,
                payload = dnsPayload
            )
            synchronized(outStream) {
                outStream.write(replyIpPacket)
                outStream.flush()
                onDownloadBytes(replyIpPacket.size.toLong())
            }
        } catch (_: Exception) {}
    }

    private fun isARecordQuery(payload: ByteArray): Boolean {
        return try {
            if (payload.size < 12) return false
            // Scan question section to find QTYPE = 1 (A record)
            var pos = 12
            while (pos < payload.size) {
                val len = payload[pos].toInt() and 0xFF
                if (len == 0) {
                    pos += 1
                    break
                }
                pos += 1 + len
            }
            if (pos + 2 <= payload.size) {
                val qtype = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
                qtype == 1 // Type A
            } else {
                true
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun isAaaaRecordQuery(payload: ByteArray): Boolean {
        return try {
            if (payload.size < 12) return false
            var pos = 12
            while (pos < payload.size) {
                val len = payload[pos].toInt() and 0xFF
                if (len == 0) {
                    pos += 1
                    break
                }
                pos += 1 + len
            }
            if (pos + 2 <= payload.size) {
                val qtype = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
                qtype == 28 // Type AAAA (IPv6)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun clearCache() {
        cacheManager.clear()
        fakeDnsManager.clear()
    }
}
