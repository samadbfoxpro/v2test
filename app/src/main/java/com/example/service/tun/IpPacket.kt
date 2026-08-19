package com.example.service.tun

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Lightweight IPv4, TCP, UDP and ICMP packet parser and constructor for Android TUN interface.
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
        headerLength = (firstByte and 0x0F) * 4
        protocol = rawData[9].toInt() and 0xFF

        sourceIp = rawData.copyOfRange(12, 16)
        destIp = rawData.copyOfRange(16, 20)
        sourceIpStr = InetAddress.getByAddress(sourceIp).hostAddress ?: "0.0.0.0"
        destIpStr = InetAddress.getByAddress(destIp).hostAddress ?: "0.0.0.0"

        payloadOffset = headerLength
        val totalLength = ((rawData[2].toInt() and 0xFF) shl 8) or (rawData[3].toInt() and 0xFF)
        val validTotal = if (totalLength in 20..length) totalLength else length
        payloadLength = (validTotal - headerLength).coerceAtLeast(0)
    }

    val isUdp: Boolean get() = protocol == 17
    val isTcp: Boolean get() = protocol == 6
    val isIcmp: Boolean get() = protocol == 1

    // UDP Fields
    val udpSourcePort: Int
        get() = if (isUdp && payloadLength >= 8) {
            ((rawData[payloadOffset].toInt() and 0xFF) shl 8) or (rawData[payloadOffset + 1].toInt() and 0xFF)
        } else 0

    val udpDestPort: Int
        get() = if (isUdp && payloadLength >= 8) {
            ((rawData[payloadOffset + 2].toInt() and 0xFF) shl 8) or (rawData[payloadOffset + 3].toInt() and 0xFF)
        } else 0

    val udpPayload: ByteArray
        get() = if (isUdp && payloadLength >= 8) {
            val udpLen = (((rawData[payloadOffset + 4].toInt() and 0xFF) shl 8) or (rawData[payloadOffset + 5].toInt() and 0xFF)) - 8
            val safeLen = udpLen.coerceIn(0, payloadLength - 8)
            rawData.copyOfRange(payloadOffset + 8, payloadOffset + 8 + safeLen)
        } else ByteArray(0)

    // TCP Fields
    val tcpSourcePort: Int
        get() = if (isTcp && payloadLength >= 20) {
            ((rawData[payloadOffset].toInt() and 0xFF) shl 8) or (rawData[payloadOffset + 1].toInt() and 0xFF)
        } else 0

    val tcpDestPort: Int
        get() = if (isTcp && payloadLength >= 20) {
            ((rawData[payloadOffset + 2].toInt() and 0xFF) shl 8) or (rawData[payloadOffset + 3].toInt() and 0xFF)
        } else 0

    val tcpSeqNum: Long
        get() = if (isTcp && payloadLength >= 20) {
            var seq = 0L
            for (i in 4..7) {
                seq = (seq shl 8) or ((rawData[payloadOffset + i].toInt() and 0xFF).toLong())
            }
            seq
        } else 0L

    val tcpAckNum: Long
        get() = if (isTcp && payloadLength >= 20) {
            var ack = 0L
            for (i in 8..11) {
                ack = (ack shl 8) or ((rawData[payloadOffset + i].toInt() and 0xFF).toLong())
            }
            ack
        } else 0L

    val tcpHeaderLength: Int
        get() = if (isTcp && payloadLength >= 20) {
            ((rawData[payloadOffset + 12].toInt() and 0xF0) ushr 4) * 4
        } else 0

    val tcpFlags: Int
        get() = if (isTcp && payloadLength >= 20) {
            rawData[payloadOffset + 13].toInt() and 0x3F
        } else 0

    val isSyn: Boolean get() = isTcp && (tcpFlags and 0x02) != 0
    val isAck: Boolean get() = isTcp && (tcpFlags and 0x10) != 0
    val isFin: Boolean get() = isTcp && (tcpFlags and 0x01) != 0
    val isRst: Boolean get() = isTcp && (tcpFlags and 0x04) != 0

    val tcpPayload: ByteArray
        get() = if (isTcp && payloadLength > tcpHeaderLength) {
            rawData.copyOfRange(payloadOffset + tcpHeaderLength, payloadOffset + payloadLength)
        } else ByteArray(0)

    companion object {
        /**
         * Builds an IPv4 TCP response or data packet.
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
            val tcpHeaderLen = 20
            val tcpTotalLen = tcpHeaderLen + payload.size
            val totalIpLen = 20 + tcpTotalLen
            val packet = ByteArray(totalIpLen)
            val buffer = ByteBuffer.wrap(packet)

            // IPv4 Header
            buffer.put(0x45.toByte())
            buffer.put(0x00.toByte())
            buffer.putShort(totalIpLen.toShort())
            buffer.putShort(0.toShort())
            buffer.putShort(0x4000.toShort()) // Don't fragment
            buffer.put(64.toByte()) // TTL
            buffer.put(6.toByte()) // Protocol TCP
            buffer.putShort(0.toShort()) // Checksum
            buffer.put(srcIp)
            buffer.put(dstIp)

            // IP Checksum
            val ipChecksum = calculateChecksum(packet, 0, 20)
            packet[10] = (ipChecksum ushr 8).toByte()
            packet[11] = (ipChecksum and 0xFF).toByte()

            // TCP Header
            buffer.putShort(srcPort.toShort())
            buffer.putShort(dstPort.toShort())
            buffer.putInt(seqNum.toInt())
            buffer.putInt(ackNum.toInt())
            buffer.put(0x50.toByte()) // Data offset 5 (20 bytes)
            buffer.put(flags.toByte())
            buffer.putShort(windowSize.coerceIn(0, 65535).toShort())
            buffer.putShort(0.toShort()) // TCP Checksum placeholder
            buffer.putShort(0.toShort()) // Urgent pointer
            if (payload.isNotEmpty()) {
                buffer.put(payload)
            }

            // Compute TCP Checksum with Pseudo-header
            val pseudo = ByteArray(12 + tcpTotalLen)
            System.arraycopy(srcIp, 0, pseudo, 0, 4)
            System.arraycopy(dstIp, 0, pseudo, 4, 4)
            pseudo[8] = 0
            pseudo[9] = 6 // Protocol TCP
            pseudo[10] = (tcpTotalLen ushr 8).toByte()
            pseudo[11] = (tcpTotalLen and 0xFF).toByte()
            System.arraycopy(packet, 20, pseudo, 12, tcpTotalLen)

            val tcpChecksum = calculateChecksum(pseudo, 0, pseudo.size)
            packet[20 + 16] = (tcpChecksum ushr 8).toByte()
            packet[20 + 17] = (tcpChecksum and 0xFF).toByte()

            return packet
        }

        /**
         * Builds an IPv4 UDP response packet (e.g. for DNS answers).
         */
        fun buildUdpPacket(
            srcIp: ByteArray,
            dstIp: ByteArray,
            srcPort: Int,
            dstPort: Int,
            payload: ByteArray
        ): ByteArray {
            val totalIpLen = 20 + 8 + payload.size
            val packet = ByteArray(totalIpLen)
            val buffer = ByteBuffer.wrap(packet)

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

            // UDP Header
            buffer.putShort(srcPort.toShort())
            buffer.putShort(dstPort.toShort())
            buffer.putShort((8 + payload.size).toShort())
            buffer.putShort(0.toShort()) // Checksum (0 is allowed in IPv4 UDP)
            buffer.put(payload)

            return packet
        }

        /**
         * Builds an ICMP Echo Reply in response to an Echo Request.
         */
        fun buildIcmpEchoReply(reqPacket: IpPacket): ByteArray? {
            if (!reqPacket.isIcmp || reqPacket.payloadLength < 8) return null
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
