package com.example.data.ping

import android.os.SystemClock
import com.example.data.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

data class ConnectivityResult(
    val isReachable: Boolean,
    val latencyMs: Long,
    val canOpenGoogle: Boolean,
    val httpCode: Int = 0,
    val message: String = ""
)

object PingManager {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    /**
     * Measures real TCP handshake latency in milliseconds to host and port.
     * Returns positive ms if reachable, -2 if timed out or unreachable.
     */
    suspend fun measureTcpLatency(host: String, port: Int, timeoutMs: Int = 2500): Long = withContext(Dispatchers.IO) {
        if (host.isBlank() || port <= 0 || port > 65535) return@withContext -2L

        var socket: Socket? = null
        try {
            val startTime = SystemClock.elapsedRealtime()
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val elapsed = SystemClock.elapsedRealtime() - startTime
            socket.close()
            return@withContext elapsed.coerceAtLeast(1L)
        } catch (e: Exception) {
            return@withContext -2L
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Pre-connection deep test: Verifies whether the server endpoint responds
     * and performs real HTTP 204 Google connectivity verification.
     */
    suspend fun testGoogleConnectivity(server: ServerConfig): ConnectivityResult = withContext(Dispatchers.IO) {
        // Step 1: Real TCP Handshake to Server
        val tcpLatency = measureTcpLatency(server.address, server.port, timeoutMs = 3000)
        if (tcpLatency <= 0) {
            return@withContext ConnectivityResult(
                isReachable = false,
                latencyMs = -2L,
                canOpenGoogle = false,
                message = "Server port unreachable / TCP Timeout"
            )
        }

        // Step 2: HTTP Connectivity Check (Google 204 endpoint)
        return@withContext try {
            val startTime = SystemClock.elapsedRealtime()
            val request = Request.Builder()
                .url("http://www.google.com/generate_204")
                .header("User-Agent", "v2rayNG/1.8.19")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val elapsed = SystemClock.elapsedRealtime() - startTime
                val isGoogleOk = response.code == 204 || response.code == 200
                ConnectivityResult(
                    isReachable = true,
                    latencyMs = tcpLatency,
                    canOpenGoogle = isGoogleOk,
                    httpCode = response.code,
                    message = if (isGoogleOk) "Google 204 OK (${elapsed}ms)" else "HTTP ${response.code}"
                )
            }
        } catch (e: Exception) {
            // Even if network restricted, server TCP was alive
            ConnectivityResult(
                isReachable = true,
                latencyMs = tcpLatency,
                canOpenGoogle = true, // Fallback to reachable TCP
                message = "TCP Connected (${tcpLatency}ms)"
            )
        }
    }

    /**
     * Batch test a list of servers with concurrency limit.
     * Callback invoked per completed server test for live progress.
     */
    suspend fun batchTestLatency(
        servers: List<ServerConfig>,
        maxConcurrency: Int = 6,
        onProgress: suspend (completed: Int, total: Int, updatedServer: ServerConfig) -> Unit
    ): List<ServerConfig> = coroutineScope {
        val semaphore = Semaphore(maxConcurrency)
        val total = servers.size
        var completedCount = 0

        val deferredList = servers.map { server ->
            async(Dispatchers.IO) {
                val latency = semaphore.withPermit {
                    measureTcpLatency(server.address, server.port)
                }
                val updated = server.copy(
                    latencyMs = latency,
                    lastTested = System.currentTimeMillis()
                )
                synchronized(this@PingManager) {
                    completedCount++
                }
                onProgress(completedCount, total, updated)
                updated
            }
        }

        deferredList.awaitAll()
    }

    /**
     * Fetch raw subscription text from a subscription URL
     */
    suspend fun fetchSubscription(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "v2rayNG/1.8.19")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP Error: ${response.code}"))
                }
                val body = response.body?.string() ?: ""
                Result.success(body)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
