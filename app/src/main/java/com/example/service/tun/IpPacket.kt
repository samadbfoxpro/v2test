package com.example.service.tun

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * High-performance IPv4/IPv6, TCP, UDP and ICMP packet parser and constructor for Android TUN interface.
 */
class IpPacket(val rawData: ByteArray, val length: Int) {
    val version: Int
    val headerLength: Int
    val protocol: Int
    val sourceIp: ByteArray
    val destIp: ByteArray
    val sourceIpStr: String
    val destIpStr: String
    val payloadOffset: Int
    val payloadLength: Int

    init {
        val firstByte = rawData[0].toInt() and 0xFF
        version = firstByte ushr 4

        if (version == 4) {
            headerLength = (firstByte and 0x0F) * 4
            protocol = if (length > 9) rawData[9].toInt() and 0xFF else 0

            sourceIp = if (length >= 16) rawData.copyOfRange(12, 16) else ByteArray(4)
            destIp = if (length >= 20) rawData.copyOfRange(16, 20) else ByteArray(4)
            sourceIpStr = try { InetAddress.getByAddress(sourceIp).hostAddress ?: "0.0.0.0" } catch (_: Exception) { "0.0.0.0" }
            destIpStr = try { InetAddress.getByAddress(destIp).hostAddress ?: "0.0.0.0" } catch (_: Exception) { "0.0.0.0" }

            payloadOffset = headerLength
            val totalLength = if (length >= 4) {
                ((rawData[2].toInt() and 0xFF) shl 8) or (rawData[3].toInt() and 0xFF)
            } else length
            val validTotal = if (totalLength in 20..length) totalLength else length
            payloadLength = (validTotal - headerLength).coerceAtLeast(0)
        } else if (version == 6) {
            // IPv6 basic header is always 40 bytes
            headerLength = 40
            protocol = if (length > 6) rawData[6].toInt() and 0xFF else 0 // Next Header

            sourceIp = if (length >= 24) rawData.copyOfRange(8, 24) else ByteArray(16)
            destIp = if (length >= 40) rawData.copyOfRange(24, 40) else ByteArray(16)
            sourceIpStr = try { InetAddress.getByAddress(sourceIp).hostAddress ?: "::" } catch (_: Exception) { "::" }
            destIpStr = try { InetAddress.getByAddress(destIp).hostAddress ?: "::" } catch (_: Exception) { "::" }

            payloadOffset = 40
            val payloadLenFromHeader = if (length >= 6) {
                ((rawData[4].toInt() and 0xFF) shl 8) or (rawData[5].toInt() and 0xFF)
            } else 0
            payloadLength = if (payloadLenFromHeader in 0..(length - 40)) payloadLenFromHeader else (length - 40).coerceAtLeast(0)
        } else {
            headerLength = 0
            protocol = 0
            sourceIp = ByteArray(4)
            destIp = ByteArray(4)
            sourceIpStr = "0.0.0.0"
            destIpStr = "0.0.0.0"
            payloadOffset = 0
            payloadLength = 0
        }
    }

    val isUdp: Boolean get() = protocol == 17
    val isTcp: Boolean get() = protocol == 6
    val isIcmp: Boolean get() = protocol == 1 || protocol == 58 // ICMPv4 (1) or ICMPv6 (58)
    val isIpv4: Boolean get() = version == 4
    val isIpv6: Boolean get() = version == 6

    // UDP Fields
    val udpSourcePort: Int
        get() = if (isUdp && payloadLength >= 8 && rawData.size >= payloadOffset + 2) {
            ((rawData[payloadOffset].toInt() and 0xFF) shl 8) or (rawData[payloadOffset + 1].toInt() and 0xFF)
        } else 0

    val udpDestPort: Int
        get() = if (isUdp && payloadLength >= 8 && rawData.size >= payloadOffset + 4) {
            ((rawData[payloadOffset + 2].toInt() and 0xFF) shl 8) or (rawData[payloadOffset + 3].toInt() and 0xFF)
        } else 0

    val udpPayload: ByteArray
        get() = if (isUdp && payloadLength >= 8 && rawData.size >= payloadOffset + 8) {
            val udpLen = (((rawData[payloadOffset + 4].toInt() and 0xFF) shl 8) or (rawData[payloadOffset + 5].toInt() and 0xFF)) - 8
            val safeLen = udpLen.coerceIn(0, payloadLength - 8)
            rawData.copyOfRange(payloadOffset + 8, payloadOffset + 8 + safeLen)
        } else ByteArray(0)

    // TCP Fields
    val tcpSourcePort: Int
        get() = if (isTcp && payloadLength >= 20 && rawData.size >= payloadOffset + 2) {
            ((rawData[payloadOffset].toInt() and 0xFF) shl 8) or (rawData[payloadOffset + 1].toInt() and 0xFF)
        } else 0

    val tcpDestPort: Int
        get() = if (isTcp && payloadLength >= 20 && rawData.size >= payloadOffset + 4) {
            ((rawData[payloadOffset + 2].toInt() and 0xFF) shl 8) or (rawData[payloadOffset + 3].toInt() and 0xFF)
        } else 0

    val tcpSeqNum: Long
        get() = if (isTcp && payloadLength >= 20 && rawData.size >= payloadOffset + 8) {
            var seq = 0L
            for (i in 4..7) {
                seq = (seq shl 8) or ((rawData[payloadOffset + i].toInt() and 0xFF).toLong())
            }
            seq
        } else 0L

    val tcpAckNum: Long
        get() = if (isTcp && payloadLength >= 20 && rawData.size >= payloadOffset + 12) {
            var ack = 0L
            for (i in 8..11) {
                ack = (ack shl 8) or ((rawData[payloadOffset + i].toInt() and 0xFF).toLong())
            }
            ack
        } else 0L

    val tcpHeaderLength: Int
        get() = if (isTcp && payloadLength >= 20 && rawData.size >= payloadOffset + 13) {
            ((rawData[payloadOffset + 12].toInt() and 0xF0) ushr 4) * 4
        } else 0

    val tcpFlags: Int
        get() = if (isTcp && payloadLength >= 20 && rawData.size >= payloadOffset + 14) {
            rawData[payloadOffset + 13].toInt() and 0x3F
        } else 0

    val isSyn: Boolean get() = isTcp && (tcpFlags and 0x02) != 0
    val isAck: Boolean get() = isTcp && (tcpFlags and 0x10) != 0
    val isFin: Boolean get() = isTcp && (tcpFlags and 0x01) != 0
    val isRst: Boolean get() = isTcp && (tcpFlags and 0x04) != 0

    val tcpPayload: ByteArray
        get() = if (isTcp && payloadLength > tcpHeaderLength && rawData.size >= payloadOffset + payloadLength) {
            rawData.copyOfRange(payloadOffset + tcpHeaderLength, payloadOffset + payloadLength)
        } else ByteArray(0)

    /**
     * Extracts queried domain name from DNS UDP payload (RFC 1035).
     */
    fun extractDnsQueryDomain(): String {
        return try {
            val payload = udpPayload
            if (payload.size < 12) return ""
            var pos = 12 // Skip DNS header (12 bytes)
            val domainBuilder = StringBuilder()

            while (pos < payload.size) {
                val len = payload[pos].toInt() and 0xFF
                if (len == 0) break
                pos++
                if (pos + len > payload.size) break
                if (domainBuilder.isNotEmpty()) domainBuilder.append('.')
                domainBuilder.append(String(payload, pos, len, Charsets.US_ASCII))
                pos += len
            }
            domainBuilder.toString()
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        /**
         * Builds an IPv4 or IPv6 TCP response or data packet.
         */
        fun buildTcpPacket(
            srcIp: ByteArray,
            dstIp: ByteArray,
            srcPort: Int,
            dstPort: Int,
            seqNum: Long,
            ackNum: Long,
            flags: Int,
            windowSize: Int = 65535,
            payload: ByteArray = ByteArray(0)
        ): ByteArray {
            val isIpv6 = srcIp.size == 16 || dstIp.size == 16
            val ipHeaderLen = if (isIpv6) 40 else 20
            val isSynAck = (flags and 0x12) == 0x12 // SYN + ACK
            val tcpHeaderLen = if (isSynAck) 24 else 20 // 4 extra bytes for MSS option (1360)
            val tcpTotalLen = tcpHeaderLen + payload.size
            val totalIpLen = ipHeaderLen + tcpTotalLen
            val packet = ByteArray(totalIpLen)
            val buffer = ByteBuffer.wrap(packet)

            if (!isIpv6) {
                // IPv4 Header
                buffer.put(0x45.toByte())
                buffer.put(0x00.toByte())
                buffer.putShort(totalIpLen.toShort())
                buffer.putShort(0.toShort())
                buffer.putShort(0x4000.toShort()) // Don't fragment
                buffer.put(64.toByte()) // TTL
                buffer.put(6.toByte()) // Protocol TCP
                buffer.putShort(0.toShort()) // Checksum placeholder
                buffer.put(srcIp)
                buffer.put(dstIp)

                // IP Checksum
                val ipChecksum = calculateChecksum(packet, 0, 20)
                packet[10] = (ipChecksum ushr 8).toByte()
                packet[11] = (ipChecksum and 0xFF).toByte()
            } else {
                // IPv6 Header
                buffer.put(0x60.toByte()) // Version 6, Traffic Class 0
                buffer.put(0x00.toByte())
                buffer.putShort(0.toShort()) // Flow Label 0
                buffer.putShort(tcpTotalLen.toShort()) // Payload Length
                buffer.put(6.toByte()) // Next Header: TCP
                buffer.put(64.toByte()) // Hop Limit
                val safeSrc = if (srcIp.size == 16) srcIp else ByteArray(16).apply { System.arraycopy(srcIp, 0, this, 12, 4) }
                val safeDst = if (dstIp.size == 16) dstIp else ByteArray(16).apply { System.arraycopy(dstIp, 0, this, 12, 4) }
                buffer.put(safeSrc)
                buffer.put(safeDst)
            }

            // TCP Header
            val tcpStart = ipHeaderLen
            buffer.position(tcpStart)
            buffer.putShort(srcPort.toShort())
            buffer.putShort(dstPort.toShort())
            buffer.putInt(seqNum.toInt())
            buffer.putInt(ackNum.toInt())
            val dataOffset = if (isSynAck) 6 else 5
            buffer.put(((dataOffset shl 4) or 0).toByte())
            buffer.put(flags.toByte())
            buffer.putShort(windowSize.coerceIn(0, 65535).toShort())
            buffer.putShort(0.toShort()) // TCP Checksum placeholder
            buffer.putShort(0.toShort()) // Urgent pointer
            if (isSynAck) {
                // TCP Option: Maximum Segment Size (MSS) = 1360 (Kind 2, Length 4, Value 0x0550)
                buffer.put(0x02.toByte())
                buffer.put(0x04.toByte())
                buffer.put(0x05.toByte())
                buffer.put(0x50.toByte())
            }
            if (payload.isNotEmpty()) {
                buffer.put(payload)
            }

            // Compute TCP Checksum with Pseudo-header
            val pseudoLen = (if (isIpv6) 40 else 12) + tcpTotalLen
            val pseudo = ByteArray(pseudoLen)
            if (!isIpv6) {
                System.arraycopy(srcIp, 0, pseudo, 0, 4)
                System.arraycopy(dstIp, 0, pseudo, 4, 4)
                pseudo[8] = 0
                pseudo[9] = 6 // Protocol TCP
                pseudo[10] = (tcpTotalLen ushr 8).toByte()
                pseudo[11] = (tcpTotalLen and 0xFF).toByte()
                System.arraycopy(packet, 20, pseudo, 12, tcpTotalLen)
            } else {
                val safeSrc = if (srcIp.size == 16) srcIp else ByteArray(16).apply { System.arraycopy(srcIp, 0, this, 12, 4) }
                val safeDst = if (dstIp.size == 16) dstIp else ByteArray(16).apply { System.arraycopy(dstIp, 0, this, 12, 4) }
                System.arraycopy(safeSrc, 0, pseudo, 0, 16)
                System.arraycopy(safeDst, 0, pseudo, 16, 16)
                pseudo[32] = 0
                pseudo[33] = 0
                pseudo[34] = (tcpTotalLen ushr 8).toByte()
                pseudo[35] = (tcpTotalLen and 0xFF).toByte()
                pseudo[36] = 0
                pseudo[37] = 0
                pseudo[38] = 0
                pseudo[39] = 6 // Next Header: TCP
                System.arraycopy(packet, 40, pseudo, 40, tcpTotalLen)
            }

            val tcpChecksum = calculateChecksum(pseudo, 0, pseudo.size)
            packet[tcpStart + 16] = (tcpChecksum ushr 8).toByte()
            packet[tcpStart + 17] = (tcpChecksum and 0xFF).toByte()

            return packet
        }

        /**
         * Builds an IPv4 or IPv6 UDP response packet (e.g. for DNS answers).
         */
        fun buildUdpPacket(
            srcIp: ByteArray,
            dstIp: ByteArray,
            srcPort: Int,
            dstPort: Int,
            payload: ByteArray
        ): ByteArray {
            val isIpv6 = srcIp.size == 16 || dstIp.size == 16
            val ipHeaderLen = if (isIpv6) 40 else 20
            val udpTotalLen = 8 + payload.size
            val totalIpLen = ipHeaderLen + udpTotalLen
            val packet = ByteArray(totalIpLen)
            val buffer = ByteBuffer.wrap(packet)

            if (!isIpv6) {
                // IPv4 Header
                buffer.put(0x45.toByte()) // Version 4, IHL 5
                buffer.put(0x00.toByte()) // TOS
                buffer.putShort(totalIpLen.toShort()) // Total length
                buffer.putShort(0.toShort()) // ID
                buffer.putShort(0x4000.toShort()) // Flags (Don't Fragment)
                buffer.put(64.toByte()) // TTL
                buffer.put(17.toByte()) // Protocol UDP
                buffer.putShort(0.toShort()) // Checksum placeholder
                buffer.put(srcIp)
                buffer.put(dstIp)

                // Calculate IPv4 Header Checksum
                val ipChecksum = calculateChecksum(packet, 0, 20)
                packet[10] = (ipChecksum ushr 8).toByte()
                packet[11] = (ipChecksum and 0xFF).toByte()
            } else {
                // IPv6 Header
                buffer.put(0x60.toByte())
                buffer.put(0x00.toByte())
                buffer.putShort(0.toShort())
                buffer.putShort(udpTotalLen.toShort())
                buffer.put(17.toByte()) // UDP
                buffer.put(64.toByte())
                val safeSrc = if (srcIp.size == 16) srcIp else ByteArray(16).apply { System.arraycopy(srcIp, 0, this, 12, 4) }
                val safeDst = if (dstIp.size == 16) dstIp else ByteArray(16).apply { System.arraycopy(dstIp, 0, this, 12, 4) }
                buffer.put(safeSrc)
                buffer.put(safeDst)
            }

            // UDP Header
            val udpStart = ipHeaderLen
            buffer.position(udpStart)
            buffer.putShort(srcPort.toShort())
            buffer.putShort(dstPort.toShort())
            buffer.putShort(udpTotalLen.toShort())
            buffer.putShort(0.toShort()) // Checksum
            buffer.put(payload)

            if (isIpv6) {
                val pseudoLen = 40 + udpTotalLen
                val pseudo = ByteArray(pseudoLen)
                val safeSrc = if (srcIp.size == 16) srcIp else ByteArray(16).apply { System.arraycopy(srcIp, 0, this, 12, 4) }
                val safeDst = if (dstIp.size == 16) dstIp else ByteArray(16).apply { System.arraycopy(dstIp, 0, this, 12, 4) }
                System.arraycopy(safeSrc, 0, pseudo, 0, 16)
                System.arraycopy(safeDst, 0, pseudo, 16, 16)
                pseudo[32] = 0
                pseudo[33] = 0
                pseudo[34] = (udpTotalLen ushr 8).toByte()
                pseudo[35] = (udpTotalLen and 0xFF).toByte()
                pseudo[36] = 0
                pseudo[37] = 0
                pseudo[38] = 0
                pseudo[39] = 17 // UDP
                System.arraycopy(packet, 40, pseudo, 40, udpTotalLen)

                var udpChecksum = calculateChecksum(pseudo, 0, pseudo.size)
                if (udpChecksum == 0) udpChecksum = 0xFFFF
                packet[udpStart + 6] = (udpChecksum ushr 8).toByte()
                packet[udpStart + 7] = (udpChecksum and 0xFF).toByte()
            }

            return packet
        }

        /**
         * Builds an ICMP Echo Reply in response to an Echo Request.
         */
        fun buildIcmpEchoReply(reqPacket: IpPacket): ByteArray? {
            if (!reqPacket.isIcmp || reqPacket.payloadLength < 8 || !reqPacket.isIpv4) return null
            val icmpType = reqPacket.rawData[reqPacket.payloadOffset].toInt() and 0xFF
            if (icmpType != 8) return null // Only respond to echo request (8)

            val totalIpLen = 20 + reqPacket.payloadLength
            val packet = ByteArray(totalIpLen)
            val buffer = ByteBuffer.wrap(packet)

            // IPv4 Header (Swap src/dst)
            buffer.put(0x45.toByte())
            buffer.put(0x00.toByte())
            buffer.putShort(totalIpLen.toShort())
            buffer.putShort(0.toShort())
            buffer.putShort(0x4000.toShort())
            buffer.put(64.toByte())
            buffer.put(1.toByte()) // ICMP
            buffer.putShort(0.toShort())
            buffer.put(reqPacket.destIp)
            buffer.put(reqPacket.sourceIp)

            val ipChecksum = calculateChecksum(packet, 0, 20)
            packet[10] = (ipChecksum ushr 8).toByte()
            packet[11] = (ipChecksum and 0xFF).toByte()

            // Copy ICMP payload
            System.arraycopy(reqPacket.rawData, reqPacket.payloadOffset, packet, 20, reqPacket.payloadLength)
            packet[20] = 0 // ICMP Type 0: Echo Reply
            packet[21] = 0 // Code 0
            packet[22] = 0 // Checksum reset
            packet[23] = 0

            val icmpChecksum = calculateChecksum(packet, 20, reqPacket.payloadLength)
            packet[22] = (icmpChecksum ushr 8).toByte()
            packet[23] = (icmpChecksum and 0xFF).toByte()

            return packet
        }

        fun calculateChecksum(buf: ByteArray, offset: Int, length: Int): Int {
            var sum = 0
            var i = offset
            while (i < offset + length - 1) {
                val byte1 = buf[i].toInt() and 0xFF
                val byte2 = buf[i + 1].toInt() and 0xFF
                sum += (byte1 shl 8) or byte2
                i += 2
            }
            if (i < offset + length) {
                sum += (buf[i].toInt() and 0xFF) shl 8
            }
            while (sum ushr 16 > 0) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            return sum.inv() and 0xFFFF
        }
    }
}
