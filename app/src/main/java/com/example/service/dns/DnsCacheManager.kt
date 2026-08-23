package com.example.service.dns

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance, in-memory DNS Cache with real record TTL tracking and live telemetry.
 */
class DnsCacheManager {
    data class CacheEntry(
        val rawResponse: ByteArray,
        val expiryTimestamp: Long,
        val ttlSeconds: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    val hitCount = AtomicLong(0)
    val missCount = AtomicLong(0)
    val totalQueries = AtomicLong(0)

    companion object {
        private const val DEFAULT_TTL_SECONDS = 300L // 5 minutes fallback
        private const val MAX_CACHE_ENTRIES = 2048
    }

    /**
     * Attempts to find a valid, non-expired DNS response for [domain] and [queryType].
     * If found, rewrites the first 2 bytes (Transaction ID) to match [queryTransactionId].
     */
    fun get(domain: String, queryType: Int, queryTransactionId: ByteArray): ByteArray? {
        totalQueries.incrementAndGet()
        val key = buildKey(domain, queryType)
        val entry = cache[key] ?: run {
            missCount.incrementAndGet()
            return null
        }

        val now = System.currentTimeMillis()
        if (now > entry.expiryTimestamp) {
            cache.remove(key)
            missCount.incrementAndGet()
            return null
        }

        hitCount.incrementAndGet()
        val responseCopy = entry.rawResponse.copyOf()
        if (responseCopy.size >= 2 && queryTransactionId.size >= 2) {
            responseCopy[0] = queryTransactionId[0]
            responseCopy[1] = queryTransactionId[1]
        }
        return responseCopy
    }

    /**
     * Stores a resolved DNS response packet, automatically parsing the minimum TTL from the answer section.
     */
    fun put(domain: String, queryType: Int, rawResponse: ByteArray) {
        if (domain.isBlank() || rawResponse.size < 12) return

        if (cache.size >= MAX_CACHE_ENTRIES) {
            purgeExpired()
            if (cache.size >= MAX_CACHE_ENTRIES) {
                // Drop a batch of older entries
                val iterator = cache.keys().iterator()
                var removed = 0
                while (iterator.hasNext() && removed < 100) {
                    cache.remove(iterator.next())
                    removed++
                }
            }
        }

        val parsedTtl = parseMinTtl(rawResponse).coerceIn(10L, 86400L)
        val expiry = System.currentTimeMillis() + (parsedTtl * 1000L)
        val key = buildKey(domain, queryType)
        cache[key] = CacheEntry(rawResponse.copyOf(), expiry, parsedTtl)
    }

    fun clear() {
        cache.clear()
    }

    fun purgeExpired() {
        val now = System.currentTimeMillis()
        val it = cache.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.expiryTimestamp < now) {
                it.remove()
            }
        }
    }

    fun size(): Int = cache.size

    val hitRatePercentage: Float
        get() {
            val hits = hitCount.get()
            val misses = missCount.get()
            val total = hits + misses
            return if (total > 0) (hits.toFloat() / total.toFloat()) * 100f else 0f
        }

    private fun buildKey(domain: String, queryType: Int): String {
        return "${domain.lowercase().trim('.')}:$queryType"
    }

    /**
     * Basic DNS response parser to extract the lowest TTL found in Answer records.
     */
    private fun parseMinTtl(dnsPacket: ByteArray): Long {
        return try {
            if (dnsPacket.size < 12) return DEFAULT_TTL_SECONDS
            val buffer = ByteBuffer.wrap(dnsPacket)
            buffer.position(4)
            val qdCount = buffer.short.toInt() and 0xFFFF
            val anCount = buffer.short.toInt() and 0xFFFF

            if (anCount == 0) return DEFAULT_TTL_SECONDS

            // Skip question section
            var pos = 12
            for (i in 0 until qdCount) {
                while (pos < dnsPacket.size) {
                    val len = dnsPacket[pos].toInt() and 0xFF
                    if (len == 0) {
                        pos += 1 // zero byte
                        break
                    }
                    if ((len and 0xC0) == 0xC0) {
                        pos += 2 // pointer
                        break
                    }
                    pos += 1 + len
                }
                pos += 4 // QTYPE (2) + QCLASS (2)
            }

            var minTtl = Long.MAX_VALUE

            // Parse answer records
            for (i in 0 until anCount) {
                if (pos >= dnsPacket.size) break
                // Name
                if ((dnsPacket[pos].toInt() and 0xC0) == 0xC0) {
                    pos += 2
                } else {
                    while (pos < dnsPacket.size) {
                        val len = dnsPacket[pos].toInt() and 0xFF
                        if (len == 0) {
                            pos += 1
                            break
                        }
                        pos += 1 + len
                    }
                }

                if (pos + 10 > dnsPacket.size) break
                val type = ((dnsPacket[pos].toInt() and 0xFF) shl 8) or (dnsPacket[pos + 1].toInt() and 0xFF)
                val ttl = ((dnsPacket[pos + 4].toLong() and 0xFF) shl 24) or
                        ((dnsPacket[pos + 5].toLong() and 0xFF) shl 16) or
                        ((dnsPacket[pos + 6].toLong() and 0xFF) shl 8) or
                        (dnsPacket[pos + 7].toLong() and 0xFF)
                val rdLength = ((dnsPacket[pos + 8].toInt() and 0xFF) shl 8) or (dnsPacket[pos + 9].toInt() and 0xFF)

                if (ttl in 1..minTtl) {
                    minTtl = ttl
                }
                pos += 10 + rdLength
            }

            if (minTtl != Long.MAX_VALUE && minTtl > 0) minTtl else DEFAULT_TTL_SECONDS
        } catch (_: Exception) {
            DEFAULT_TTL_SECONDS
        }
    }
}
