package com.example.service.dns

import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages FakeDNS virtual IP mappings (198.18.0.0/15) for ultra-low latency local DNS synthesis.
 * Seamlessly maps virtual IPs back to real hostnames for TCP/UDP outbound routing.
 */
class FakeDnsManager {
    // 198.18.0.0/15: 198.18.0.2 to 198.19.255.254 (131,070 usable IP addresses)
    private val startIpLong = ipToLong("198.18.0.2")
    private val endIpLong = ipToLong("198.19.255.254")
    private val currentOffset = AtomicInteger(0)

    private val domainToIp = ConcurrentHashMap<String, String>()
    private val ipToDomain = ConcurrentHashMap<String, String>()

    @Volatile
    var isEnabled: Boolean = true

    fun allocateFakeIp(domain: String): String {
        val cleanDomain = domain.lowercase().trim('.')
        val existing = domainToIp[cleanDomain]
        if (existing != null) return existing

        val totalIps = (endIpLong - startIpLong).toInt()
        val offset = currentOffset.getAndIncrement() % totalIps
        val ipLong = startIpLong + offset
        val ipStr = longToIp(ipLong)

        // If old mapping existed for this IP, remove it
        val oldDomain = ipToDomain.remove(ipStr)
        if (oldDomain != null) {
            domainToIp.remove(oldDomain)
        }

        domainToIp[cleanDomain] = ipStr
        ipToDomain[ipStr] = cleanDomain
        return ipStr
    }

    fun getRealHost(ipOrHost: String): String? {
        if (!isEnabled) return null
        return ipToDomain[ipOrHost.trim()]
    }

    fun isFakeIp(ip: String): Boolean {
        if (!isEnabled) return false
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return first == 198 && (second == 18 || second == 19)
    }

    fun getMappingCount(): Int = domainToIp.size

    fun clear() {
        domainToIp.clear()
        ipToDomain.clear()
        currentOffset.set(0)
    }

    /**
     * Synthesizes a valid RFC 1035 DNS A response containing the allocated Fake IP.
     */
    fun buildFakeDnsResponse(queryPayload: ByteArray, fakeIp: String): ByteArray? {
        return try {
            if (queryPayload.size < 12) return null
            val ipBytes = InetAddress.getByName(fakeIp).address
            if (ipBytes.size != 4) return null

            // Copy header and query section
            val response = ByteArray(queryPayload.size + 16) // 16 bytes for answer record
            System.arraycopy(queryPayload, 0, response, 0, queryPayload.size)

            val buffer = ByteBuffer.wrap(response)
            // Flags: Standard query response, No error, Authoritative (0x8180)
            buffer.put(2, 0x81.toByte())
            buffer.put(3, 0x80.toByte())
            // Questions: same (at index 4)
            // Answer RRs = 1 (at index 6)
            buffer.putShort(6, 1.toShort())
            // Authority RRs = 0 (at index 8)
            buffer.putShort(8, 0.toShort())
            // Additional RRs = 0 (at index 10)
            buffer.putShort(10, 0.toShort())

            // Append Answer record at the end of the query section
            val answerOffset = queryPayload.size
            buffer.position(answerOffset)
            // Pointer to Domain Name in Question section (offset 12 = 0xC00C)
            buffer.putShort(0xC00C.toShort())
            // TYPE = A (0x0001)
            buffer.putShort(1.toShort())
            // CLASS = IN (0x0001)
            buffer.putShort(1.toShort())
            // TTL = 10 seconds (short TTL so cache updates when FakeDNS toggled)
            buffer.putInt(10)
            // RDLENGTH = 4 bytes (IPv4)
            buffer.putShort(4.toShort())
            // RDATA = Fake IPv4 address
            buffer.put(ipBytes)

            response
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Synthesizes an immediate RFC 1035 NXDOMAIN response (0ms latency).
     * Prevents apps from hanging when querying reverse PTR or unroutable internal records.
     */
    fun buildNxDomainResponse(queryPayload: ByteArray): ByteArray? {
        return try {
            if (queryPayload.size < 12) return null
            val response = ByteArray(queryPayload.size)
            System.arraycopy(queryPayload, 0, response, 0, queryPayload.size)
            // Flags: 0x8183 (Response, Authoritative, Recursion Available, RCODE 3 = NXDOMAIN)
            response[2] = 0x81.toByte()
            response[3] = 0x83.toByte()
            response[6] = 0 // Answer RRs
            response[7] = 0
            response
        } catch (_: Exception) {
            null
        }
    }

    private fun ipToLong(ip: String): Long {
        val parts = ip.split(".")
        return (parts[0].toLong() shl 24) or
                (parts[1].toLong() shl 16) or
                (parts[2].toLong() shl 8) or
                parts[3].toLong()
    }

    private fun longToIp(ipLong: Long): String {
        return "${(ipLong ushr 24) and 0xFF}.${(ipLong ushr 16) and 0xFF}.${(ipLong ushr 8) and 0xFF}.${ipLong and 0xFF}"
    }
}
