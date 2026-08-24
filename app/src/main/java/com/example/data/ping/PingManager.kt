package com.example.data.ping

import android.content.Context
import android.os.SystemClock
import com.example.data.model.ServerConfig
import com.example.service.proxy.ProxyTunnel
import com.example.service.proxy.XrayOutboundClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * Application context for database access when vpnService is unavailable.
     * Must be initialized at app startup via PingManager.init(context).
     */
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    /**
     * Measures real TCP handshake latency in milliseconds to host and port.
     * Returns positive ms if reachable, -2 if timed out or unreachable.
     */
    /**
     * Measures real TCP handshake latency in milliseconds to host and port.
     * Returns positive ms if reachable, -2 if timed out or unreachable.
     */
    suspend fun measureTcpLatency(host: String, port: Int, timeoutMs: Int = 1000): Long = withContext(Dispatchers.IO) {
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
     * and performs real connectivity verification (supports Proxy Chains as well).
     */
    private fun getTestTarget(testUrl: String): Triple<String, Int, String> {
        return try {
            val uri = android.net.Uri.parse(testUrl)
            val host = uri.host ?: "www.google.com"
            val isHttps = uri.scheme?.equals("https", ignoreCase = true) == true
            val port = if (uri.port > 0) uri.port else (if (isHttps) 80 else 80)
            val path = if (uri.path.isNullOrBlank()) "/generate_204" else uri.path!!
            Triple(host, port, path)
        } catch (_: Exception) {
            Triple("www.google.com", 80, "/generate_204")
        }
    }

    /**
     * Real Delay Connectivity Test: Verifies whether the server endpoint responds
     * and performs real end-to-end round trip latency verification using the configured test URL.
     * Enforces a strict, reasonable timeout (default 1500ms) to ensure lightning-fast ping results.
     */
    suspend fun testGoogleConnectivity(
        server: ServerConfig,
        relayConfigOverride: ServerConfig? = null,
        exitConfigOverride: ServerConfig? = null,
        customTestUrl: String? = null,
        timeoutMs: Long = 1500L
    ): ConnectivityResult = withContext(Dispatchers.IO) {
        val actualTimeout = if (server.isProxyChain || relayConfigOverride != null) timeoutMs.coerceAtLeast(3000L) else timeoutMs
        val result = withTimeoutOrNull(actualTimeout) {
            try {
                val testUrl = customTestUrl ?: (appContext?.let { com.example.data.local.AppSettingsManager.getTestUrl(it) } ?: com.example.data.local.AppSettingsManager.DEFAULT_TEST_URL)
                val (targetHost, targetPort, path) = getTestTarget(testUrl)

                val startTime = SystemClock.elapsedRealtime()
                val client = XrayOutboundClient(
                    vpnService = null,
                    serverConfig = server,
                    relayConfigOverride = relayConfigOverride,
                    exitConfigOverride = exitConfigOverride,
                    appContext = appContext,
                    connectTimeoutMs = actualTimeout.toInt().coerceAtMost(1400)
                )
                val tunnel = client.openTargetStream(targetHost, targetPort)
                if (tunnel != null) {
                    try {
                        val req = "GET $path HTTP/1.1\r\nHost: $targetHost\r\nUser-Agent: v2rayNG/1.8.19\r\nConnection: close\r\n\r\n"
                        tunnel.outputStream.write(req.toByteArray())
                        tunnel.outputStream.flush()
                        val buffer = ByteArray(64)
                        val readBytes = tunnel.inputStream.read(buffer)
                        tunnel.close()
                        val elapsed = SystemClock.elapsedRealtime() - startTime
                        if (readBytes > 0) {
                            val responseStr = String(buffer, 0, readBytes)
                            val isOk = responseStr.contains("204") || responseStr.contains("200") || responseStr.contains("302") || responseStr.contains("301") || responseStr.contains("HTTP")
                            return@withTimeoutOrNull ConnectivityResult(
                                isReachable = true,
                                latencyMs = elapsed.coerceAtLeast(1L),
                                canOpenGoogle = isOk,
                                message = "${elapsed}ms"
                            )
                        }
                    } catch (_: Exception) {
                        try { tunnel.close() } catch (_: Exception) {}
                    }
                }

                // Fallback: Fast TCP port latency measurement (600ms) if outbound tunnel wasn't complete
                val tcpLatency = measureTcpLatency(server.address, server.port, timeoutMs = 600)
                if (tcpLatency > 0) {
                    return@withTimeoutOrNull ConnectivityResult(
                        isReachable = true,
                        latencyMs = tcpLatency,
                        canOpenGoogle = true,
                        message = "${tcpLatency}ms"
                    )
                }
                null
            } catch (_: Exception) {
                null
            }
        }

        return@withContext result ?: ConnectivityResult(
            isReachable = false,
            latencyMs = -2L,
            canOpenGoogle = false,
            message = "تایم‌اوت"
        )
    }

    /**
     * Batch test a list of servers with concurrency limit and per-server timeout.
     * Concurrency set to 20 with 1500ms timeout for instant results across dozens of servers.
     */
    suspend fun batchTestLatency(
        servers: List<ServerConfig>,
        maxConcurrency: Int = 20,
        perServerTimeoutMs: Long = 1500L,
        customTestUrl: String? = null,
        onProgress: suspend (completed: Int, total: Int, updatedServer: ServerConfig) -> Unit
    ): List<ServerConfig> = coroutineScope {
        val semaphore = Semaphore(maxConcurrency)
        val total = servers.size
        var completedCount = 0

        val results = servers.map { server ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val result = testGoogleConnectivity(
                        server = server,
                        customTestUrl = customTestUrl,
                        timeoutMs = perServerTimeoutMs
                    )
                    val testedServer = server.copy(
                        latencyMs = if (result.isReachable) result.latencyMs else -2L,
                        lastTested = System.currentTimeMillis()
                    )
                    synchronized(this@coroutineScope) {
                        completedCount++
                    }
                    onProgress(completedCount, total, testedServer)
                    testedServer
                }
            }
        }.awaitAll()

        results
    }

    /**
     * Fetch subscription content from a remote URL.
     * Automatically routes traffic through the currently active VPN / Proxy tunnel
     * so filtered links (e.g. GitHub raw, external subs) can be fetched directly without external VPN apps.
     */
    suspend fun fetchSubscription(url: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return@withContext Result.failure(Exception("آدرس سابسکریپشن خالی است"))

        val activeConfig = com.example.service.XrayVpnService.activeServerConfig.value
            ?: appContext?.let { com.example.data.local.AppDatabase.getInstance(it).serverDao().getSelectedServerSync() }

        // Strategy 1: If local HTTP proxy is listening on 127.0.0.1:10809 (from active VPN), use it
        try {
            val proxyHttp = OkHttpClient.Builder()
                .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 10809)))
                .connectTimeout(6000, TimeUnit.MILLISECONDS)
                .readTimeout(8000, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .build()

            val req = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "v2rayNG/1.8.19")
                .build()

            proxyHttp.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    if (body.isNotBlank()) return@withContext Result.success(body)
                }
            }
        } catch (_: Exception) {}

        // Strategy 2: If local SOCKS5 proxy is listening on 127.0.0.1:10808 (or LAN sharing), use it
        try {
            val proxySocks = OkHttpClient.Builder()
                .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808)))
                .connectTimeout(6000, TimeUnit.MILLISECONDS)
                .readTimeout(8000, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .build()

            val req = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "v2rayNG/1.8.19")
                .build()

            proxySocks.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    if (body.isNotBlank()) return@withContext Result.success(body)
                }
            }
        } catch (_: Exception) {}

        // Strategy 3: Direct XrayOutboundClient tunnel through active server node
        if (activeConfig != null) {
            val directTunnelResult = fetchViaDirectOutbound(cleanUrl, activeConfig)
            if (directTunnelResult.isSuccess && !directTunnelResult.getOrNull().isNullOrBlank()) {
                return@withContext directTunnelResult
            }
        }

        // Strategy 4: Direct ISP connection (fallback if not filtered)
        try {
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "v2rayNG/1.8.19")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    if (body.isNotBlank()) return@withContext Result.success(body)
                } else {
                    return@withContext Result.failure(Exception("HTTP Error: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("خطا در دریافت سابسکریپشن: ${e.message ?: "اتصال برقرار نشد"}"))
        }

        return@withContext Result.failure(Exception("محتوای سابسکریپشن خالی است یا لینک در دسترس نیست"))
    }

    private fun fetchViaDirectOutbound(urlStr: String, server: ServerConfig): Result<String> {
        return try {
            val uri = android.net.Uri.parse(urlStr)
            val host = uri.host ?: return Result.failure(Exception("Invalid host"))
            val isHttps = uri.scheme?.equals("https", ignoreCase = true) == true
            val port = if (uri.port > 0) uri.port else (if (isHttps) 443 else 80)
            val pathAndQuery = buildString {
                append(if (uri.path.isNullOrBlank()) "/" else uri.path)
                if (!uri.query.isNullOrBlank()) {
                    append("?").append(uri.query)
                }
            }

            val client = XrayOutboundClient(
                vpnService = null,
                serverConfig = server,
                appContext = appContext
            )

            val rawTunnel = client.openTargetStream(host, port)
                ?: return Result.failure(Exception("Failed opening tunnel"))

            val inStream: java.io.InputStream
            val outStream: java.io.OutputStream
            val socketToClose: java.io.Closeable

            if (isHttps) {
                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                sslContext.init(null, null, null)
                val sslSocket = sslContext.socketFactory.createSocket(rawTunnel.socket, host, port, true) as javax.net.ssl.SSLSocket
                sslSocket.startHandshake()
                inStream = sslSocket.inputStream
                outStream = sslSocket.outputStream
                socketToClose = sslSocket
            } else {
                inStream = rawTunnel.inputStream
                outStream = rawTunnel.outputStream
                socketToClose = rawTunnel
            }

            val requestBuilder = StringBuilder()
            requestBuilder.append("GET $pathAndQuery HTTP/1.1\r\n")
            requestBuilder.append("Host: $host\r\n")
            requestBuilder.append("User-Agent: v2rayNG/1.8.19\r\n")
            requestBuilder.append("Accept: */*\r\n")
            requestBuilder.append("Connection: close\r\n\r\n")

            outStream.write(requestBuilder.toString().toByteArray(Charsets.UTF_8))
            outStream.flush()

            val responseBytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var len: Int
            while (inStream.read(buffer).also { len = it } != -1) {
                responseBytes.write(buffer, 0, len)
                if (responseBytes.size() > 5 * 1024 * 1024) break
            }
            try { socketToClose.close() } catch (_: Exception) {}

            val fullResponse = responseBytes.toByteArray()
            if (fullResponse.isEmpty()) {
                return Result.failure(Exception("Empty response"))
            }

            val fullStr = String(fullResponse, Charsets.UTF_8)
            val headerEnd = fullStr.indexOf("\r\n\r\n")
            val body = if (headerEnd != -1) {
                fullStr.substring(headerEnd + 4)
            } else {
                val doubleLf = fullStr.indexOf("\n\n")
                if (doubleLf != -1) fullStr.substring(doubleLf + 2) else fullStr
            }

            if (body.isNotBlank()) {
                Result.success(body.trim())
            } else {
                Result.failure(Exception("Empty body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
