package com.example.service.proxy.shadowsocks

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Implements Shadowsocks SIP008 AEAD Protocol (aes-128-gcm, aes-256-gcm).
 *
 * CRITICAL: All writes to rawOut are batched (salt + encLen + encPayload in one write)
 * to avoid WebSocket frame fragmentation when tunneled through VLESS/WS relays.
 */
class ShadowsocksAeadTunnel(
    private val rawIn: InputStream,
    private val rawOut: OutputStream,
    private val method: String,
    private val password: String
) {
    private val keySize = when (method.lowercase()) {
        "aes-128-gcm" -> 16
        "aes-256-gcm", "chacha20-ietf-poly1305" -> 32
        else -> 16
    }
    private val saltSize = keySize
    private val tagSize = 16
    private val maxPayloadSize = 0x3FFF // 16383 bytes

    val inputStream: InputStream
    val outputStream: OutputStream

    private val masterKey: ByteArray
    private val encSubKey: ByteArray
    private val encSalt: ByteArray
    private val encNonce = ByteArray(12)
    private var decSubKey: ByteArray? = null
    private val decNonce = ByteArray(12)

    init {
        // Step 1: Derive master key from password
        masterKey = evpBytesToKey(password.toByteArray(Charsets.UTF_8), keySize)

        // Step 2: Generate random salt for upstream (client -> server)
        // NOTE: Salt is NOT sent here — it is batched with the first encrypted chunk
        // in performHandshake() to avoid WebSocket frame fragmentation.
        encSalt = ByteArray(saltSize)
        SecureRandom().nextBytes(encSalt)

        // Step 3: Derive subkey for upstream
        encSubKey = hkdfSha1(masterKey, encSalt, "ss-subkey".toByteArray(Charsets.US_ASCII), keySize)

        // Streams
        outputStream = ShadowsocksOutputStream()
        inputStream = ShadowsocksInputStream()
    }

    /**
     * Handshake: Packs target address and writes salt + first AEAD chunk as ONE atomic write.
     * This is critical for proxy chain reliability — fragmented writes break WS relay tunnels.
     */
    fun performHandshake(targetHost: String, targetPort: Int) {
        val buffer = ByteBuffer.allocate(512)
        if (targetHost.contains(":")) {
            // IPv6
            buffer.put(4.toByte())
            val ipBytes = InetAddress.getByName(targetHost).address
            buffer.put(ipBytes)
        } else {
            val isIpv4 = targetHost.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))
            if (isIpv4) {
                buffer.put(1.toByte()) // IPv4
                val ipBytes = InetAddress.getByName(targetHost).address
                buffer.put(ipBytes)
            } else {
                buffer.put(3.toByte()) // Domain
                val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
                buffer.put(hostBytes.size.toByte())
                buffer.put(hostBytes)
            }
        }
        buffer.putShort(targetPort.toShort())

        buffer.flip()
        val addrBytes = ByteArray(buffer.remaining())
        buffer.get(addrBytes)

        // Encrypt address as the first AEAD chunk
        val lenBuf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
            .putShort(addrBytes.size.toShort()).array()
        val encLen = encryptAead(encSubKey, encNonce, lenBuf)
        val encPayload = encryptAead(encSubKey, encNonce, addrBytes)

        // CRITICAL: Batch salt + encLen + encPayload into ONE single write
        // This ensures the VLESS/WS relay sends everything in one TCP segment to the SS server
        val combined = ByteArray(encSalt.size + encLen.size + encPayload.size)
        System.arraycopy(encSalt, 0, combined, 0, encSalt.size)
        System.arraycopy(encLen, 0, combined, encSalt.size, encLen.size)
        System.arraycopy(encPayload, 0, combined, encSalt.size + encLen.size, encPayload.size)

        rawOut.write(combined)
        rawOut.flush()
    }

    private fun encryptAead(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(tagSize * 8, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        val encrypted = cipher.doFinal(plaintext)
        incrementNonce(nonce)
        return encrypted
    }

    private fun decryptAead(key: ByteArray, nonce: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(tagSize * 8, nonce)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        val decrypted = cipher.doFinal(ciphertextWithTag)
        incrementNonce(nonce)
        return decrypted
    }

    private inner class ShadowsocksOutputStream : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var remaining = len
            var currentOffset = off

            while (remaining > 0) {
                val chunkSize = minOf(remaining, maxPayloadSize)
                val chunk = ByteArray(chunkSize)
                System.arraycopy(b, currentOffset, chunk, 0, chunkSize)

                // 1. Encrypt 2-byte chunk length
                val lenBuf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(chunkSize.toShort()).array()
                val encLen = encryptAead(encSubKey, encNonce, lenBuf)

                // 2. Encrypt payload
                val encPayload = encryptAead(encSubKey, encNonce, chunk)

                // CRITICAL: Combine encLen + encPayload into single write
                // to avoid WebSocket frame fragmentation through relay tunnels
                val combined = ByteArray(encLen.size + encPayload.size)
                System.arraycopy(encLen, 0, combined, 0, encLen.size)
                System.arraycopy(encPayload, 0, combined, encLen.size, encPayload.size)
                rawOut.write(combined)

                currentOffset += chunkSize
                remaining -= chunkSize
            }
            rawOut.flush()
        }

        override fun flush() = rawOut.flush()
        override fun close() = rawOut.close()
    }

    private inner class ShadowsocksInputStream : InputStream() {
        private var decSaltRead = false
        private var chunkBuffer = ByteArray(0)
        private var chunkOffset = 0

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n > 0) buf[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!decSaltRead) {
                // Read downstream server salt
                val decSalt = readExact(saltSize) ?: return -1
                decSubKey = hkdfSha1(masterKey, decSalt, "ss-subkey".toByteArray(Charsets.US_ASCII), keySize)
                decSaltRead = true
            }

            while (chunkOffset >= chunkBuffer.size) {
                val nextChunk = readNextChunk() ?: return -1
                chunkBuffer = nextChunk
                chunkOffset = 0
                if (chunkBuffer.isEmpty()) {
                    continue
                }
            }

            val available = chunkBuffer.size - chunkOffset
            val toRead = minOf(len, available)
            System.arraycopy(chunkBuffer, chunkOffset, b, off, toRead)
            chunkOffset += toRead
            return toRead
        }

        private fun readNextChunk(): ByteArray? {
            val key = decSubKey ?: return null

            // 1. Read encrypted 2-byte length + tag (2 + 16 = 18 bytes)
            val encLenWithTag = readExact(2 + tagSize) ?: return null
            val lenBytes = try {
                decryptAead(key, decNonce, encLenWithTag)
            } catch (_: Exception) {
                return null
            }

            val payloadLength = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0x3FFF
            if (payloadLength < 0) return null
            if (payloadLength == 0) return ByteArray(0)

            // 2. Read encrypted payload + tag
            val encPayloadWithTag = readExact(payloadLength + tagSize) ?: return null
            return try {
                decryptAead(key, decNonce, encPayloadWithTag)
            } catch (_: Exception) {
                null
            }
        }

        private fun readExact(size: Int): ByteArray? {
            val buf = ByteArray(size)
            var totalRead = 0
            while (totalRead < size) {
                val n = rawIn.read(buf, totalRead, size - totalRead)
                if (n == -1) return null
                totalRead += n
            }
            return buf
        }

        override fun close() = rawIn.close()
        override fun available(): Int = (chunkBuffer.size - chunkOffset) + rawIn.available()
    }

    companion object {
        fun evpBytesToKey(password: ByteArray, keyLen: Int): ByteArray {
            val md5 = MessageDigest.getInstance("MD5")
            val key = ByteArray(keyLen)
            var offset = 0
            var d = ByteArray(0)
            while (offset < keyLen) {
                md5.reset()
                if (d.isNotEmpty()) md5.update(d)
                md5.update(password)
                d = md5.digest()
                val toCopy = minOf(d.size, keyLen - offset)
                System.arraycopy(d, 0, key, offset, toCopy)
                offset += toCopy
            }
            return key
        }

        fun hkdfSha1(masterKey: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(salt, "HmacSHA1"))
            val prk = mac.doFinal(masterKey)

            mac.init(SecretKeySpec(prk, "HmacSHA1"))
            val okm = ByteArray(outLen)
            var t = ByteArray(0)
            var generated = 0
            var counter: Byte = 1
            while (generated < outLen) {
                mac.reset()
                if (t.isNotEmpty()) mac.update(t)
                mac.update(info)
                mac.update(counter)
                t = mac.doFinal()
                val toCopy = minOf(t.size, outLen - generated)
                System.arraycopy(t, 0, okm, generated, toCopy)
                generated += toCopy
                counter++
            }
            return okm
        }

        fun incrementNonce(nonce: ByteArray) {
            for (i in 0 until nonce.size) {
                nonce[i] = (nonce[i] + 1).toByte()
                if (nonce[i] != 0.toByte()) break
            }
        }
    }
}
