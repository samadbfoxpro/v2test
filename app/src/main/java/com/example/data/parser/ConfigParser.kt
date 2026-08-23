package com.example.data.parser

import android.net.Uri
import android.util.Base64
import com.example.data.model.ServerConfig
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ConfigParser {

    /**
     * Parses raw text which could be a single URI or multiple URIs separated by newlines
     */
    /**
     * Parses raw text which could be a single URI, multiple URIs, Base64 subscription, or JSON
     */
    fun parseInput(rawText: String): List<ServerConfig> {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return emptyList()

        // Check if text already contains explicit protocol lines
        val directLines = trimmed.lines().map { it.trim() }.filter { it.isNotBlank() }
        val directHasProtocols = directLines.any { line ->
            line.startsWith("vless://", ignoreCase = true) ||
            line.startsWith("vmess://", ignoreCase = true) ||
            line.startsWith("trojan://", ignoreCase = true) ||
            line.startsWith("ss://", ignoreCase = true) ||
            line.startsWith("hy2://", ignoreCase = true) ||
            line.startsWith("hysteria2://", ignoreCase = true) ||
            line.startsWith("tuic://", ignoreCase = true)
        }

        val textToParse = if (directHasProtocols) {
            trimmed
        } else {
            // Try base64 decoding (handling standard/url-safe and multi-line base64)
            val decoded = decodeBase64Safe(trimmed)
            if (decoded != null && (
                decoded.contains("vless://", ignoreCase = true) ||
                decoded.contains("vmess://", ignoreCase = true) ||
                decoded.contains("trojan://", ignoreCase = true) ||
                decoded.contains("ss://", ignoreCase = true) ||
                decoded.contains("hy2://", ignoreCase = true) ||
                decoded.contains("hysteria2://", ignoreCase = true) ||
                decoded.contains("://")
            )) {
                decoded
            } else {
                trimmed
            }
        }

        val results = mutableListOf<ServerConfig>()
        for (line in textToParse.lines()) {
            val cleanLine = line.trim()
            if (cleanLine.isNotBlank()) {
                val parsed = parseSingleUri(cleanLine)
                if (parsed != null) {
                    results.add(parsed)
                }
            }
        }
        return results
    }

    fun parseSingleUri(uriString: String): ServerConfig? {
        val trimmed = uriString.trim()
        return try {
            when {
                trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed)
                trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed)
                trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed)
                trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed)
                trimmed.startsWith("hysteria2://", ignoreCase = true) || trimmed.startsWith("hy2://", ignoreCase = true) -> parseHysteria2(trimmed)
                trimmed.startsWith("{") && trimmed.endsWith("}") -> parseV2rayJson(trimmed)
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseVless(uriString: String): ServerConfig? {
        // format: vless://uuid@host:port?param1=val1&param2=val2#remarks
        val uri = Uri.parse(uriString)
        val userInfo = uri.userInfo ?: ""
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val rawFragment = uri.fragment ?: ""
        val remarks = decodeUriComponent(rawFragment).ifBlank { "VLESS Server ($host)" }

        val type = uri.getQueryParameter("type") ?: "tcp"
        val security = uri.getQueryParameter("security") ?: "none"
        val flow = uri.getQueryParameter("flow") ?: ""
        val encryption = uri.getQueryParameter("encryption") ?: "none"
        val path = uri.getQueryParameter("path") ?: ""
        val headerHost = uri.getQueryParameter("host") ?: ""
        val sni = uri.getQueryParameter("sni") ?: headerHost.ifBlank { host }
        val pbk = uri.getQueryParameter("pbk") ?: "" // Reality public key
        val sid = uri.getQueryParameter("sid") ?: "" // Reality shortId
        val fp = uri.getQueryParameter("fp") ?: "chrome"

        return ServerConfig(
            name = remarks,
            protocol = "VLESS",
            address = host,
            port = port,
            uuid = userInfo,
            encryption = encryption,
            flow = flow,
            transportType = type,
            path = path,
            host = headerHost,
            security = security,
            sni = sni,
            publicKey = pbk,
            shortId = sid,
            fingerprint = fp,
            rawUri = uriString,
            countryCode = detectCountryCode(remarks, host)
        )
    }

    private fun parseVmess(uriString: String): ServerConfig? {
        // format: vmess://base64(json)
        val base64Part = uriString.substringAfter("vmess://").trim()
        val jsonString = decodeBase64Safe(base64Part) ?: return null
        val json = JSONObject(jsonString)

        val host = json.optString("add", "")
        if (host.isBlank()) return null
        val port = json.optInt("port", 443)
        val id = json.optString("id", "")
        val ps = json.optString("ps", "VMess Server ($host)")
        val net = json.optString("net", "tcp")
        val path = json.optString("path", "")
        val headerHost = json.optString("host", "")
        val tls = json.optString("tls", "none")
        val sni = json.optString("sni", headerHost.ifBlank { host })
        val type = json.optString("type", "none")

        return ServerConfig(
            name = ps,
            protocol = "VMESS",
            address = host,
            port = port,
            uuid = id,
            encryption = type.ifBlank { "auto" },
            transportType = net,
            path = path,
            host = headerHost,
            security = if (tls.equals("tls", ignoreCase = true)) "tls" else "none",
            sni = sni,
            rawUri = uriString,
            countryCode = detectCountryCode(ps, host)
        )
    }

    private fun parseTrojan(uriString: String): ServerConfig? {
        // format: trojan://password@host:port?param1=val1#remarks
        val uri = Uri.parse(uriString)
        val password = uri.userInfo ?: ""
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val rawFragment = uri.fragment ?: ""
        val remarks = decodeUriComponent(rawFragment).ifBlank { "Trojan ($host)" }

        val type = uri.getQueryParameter("type") ?: "tcp"
        val security = uri.getQueryParameter("security") ?: "tls"
        val sni = uri.getQueryParameter("sni") ?: host
        val serviceName = uri.getQueryParameter("serviceName") ?: uri.getQueryParameter("path") ?: ""

        return ServerConfig(
            name = remarks,
            protocol = "TROJAN",
            address = host,
            port = port,
            uuid = password,
            transportType = type,
            path = serviceName,
            security = security,
            sni = sni,
            rawUri = uriString,
            countryCode = detectCountryCode(remarks, host)
        )
    }

    private fun parseShadowsocks(uriString: String): ServerConfig? {
        // format: ss://base64(method:password)@host:port#remarks or ss://base64(method:password@host:port)#remarks
        val trimmed = uriString.substringAfter("ss://")
        val fragment = trimmed.substringAfter("#", "")
        val remarks = decodeUriComponent(fragment).ifBlank { "Shadowsocks Server" }
        val mainPart = trimmed.substringBefore("#")

        if (mainPart.contains("@")) {
            val userPart = mainPart.substringBefore("@")
            val hostPortRaw = mainPart.substringAfter("@").substringBefore("?")
            val decodedUser = decodeBase64Safe(userPart) ?: userPart
            val method = decodedUser.substringBefore(":")
            val password = decodedUser.substringAfter(":")
            val host = hostPortRaw.substringBefore(":")
            val port = hostPortRaw.substringAfter(":").toIntOrNull() ?: 8388

            return ServerConfig(
                name = remarks,
                protocol = "SHADOWSOCKS",
                address = host,
                port = port,
                uuid = password,
                encryption = method,
                security = "none",
                rawUri = uriString,
                countryCode = detectCountryCode(remarks, host)
            )
        } else {
            val decoded = decodeBase64Safe(mainPart) ?: return null
            val methodPass = decoded.substringBefore("@")
            val hostPort = decoded.substringAfter("@")
            val method = methodPass.substringBefore(":")
            val password = methodPass.substringAfter(":")
            val host = hostPort.substringBefore(":")
            val port = hostPort.substringAfter(":").toIntOrNull() ?: 8388

            return ServerConfig(
                name = remarks,
                protocol = "SHADOWSOCKS",
                address = host,
                port = port,
                uuid = password,
                encryption = method,
                security = "none",
                rawUri = uriString,
                countryCode = detectCountryCode(remarks, host)
            )
        }
    }

    private fun parseHysteria2(uriString: String): ServerConfig? {
        val uri = Uri.parse(uriString)
        val auth = uri.userInfo ?: ""
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val rawFragment = uri.fragment ?: ""
        val remarks = decodeUriComponent(rawFragment).ifBlank { "Hysteria 2 ($host)" }
        val sni = uri.getQueryParameter("sni") ?: host

        return ServerConfig(
            name = remarks,
            protocol = "HYSTERIA2",
            address = host,
            port = port,
            uuid = auth,
            transportType = "udp",
            security = "tls",
            sni = sni,
            rawUri = uriString,
            countryCode = detectCountryCode(remarks, host)
        )
    }

    private fun parseV2rayJson(jsonStr: String): ServerConfig? {
        val json = JSONObject(jsonStr)
        val outbounds = json.optJSONArray("outbounds") ?: return null
        if (outbounds.length() == 0) return null
        val firstOutbound = outbounds.getJSONObject(0)
        val protocol = firstOutbound.optString("protocol", "vless").uppercase()
        val settings = firstOutbound.optJSONObject("settings") ?: return null
        val vnext = settings.optJSONArray("vnext") ?: return null
        if (vnext.length() == 0) return null
        val serverObj = vnext.getJSONObject(0)
        val address = serverObj.optString("address", "")
        val port = serverObj.optInt("port", 443)
        val users = serverObj.optJSONArray("users")
        val uuid = if (users != null && users.length() > 0) users.getJSONObject(0).optString("id", "") else ""

        return ServerConfig(
            name = "Imported Xray Config ($address)",
            protocol = protocol,
            address = address,
            port = port,
            uuid = uuid,
            rawUri = jsonStr,
            countryCode = detectCountryCode("", address)
        )
    }

    fun exportToUri(server: ServerConfig): String {
        if (server.rawUri.isNotBlank() && server.rawUri.contains("://")) {
            return server.rawUri
        }

        val nameEncoded = try { Uri.encode(server.name) } catch (_: Exception) { server.name }
        return when (server.protocol.uppercase()) {
            "VLESS" -> {
                buildString {
                    append("vless://${server.uuid}@${server.address}:${server.port}?")
                    append("type=${if (server.transportType.isBlank()) "tcp" else server.transportType}")
                    if (server.security.isNotBlank()) append("&security=${server.security}")
                    if (server.sni.isNotBlank()) append("&sni=${server.sni}")
                    if (server.publicKey.isNotBlank()) append("&pbk=${server.publicKey}")
                    if (server.shortId.isNotBlank()) append("&sid=${server.shortId}")
                    if (server.fingerprint.isNotBlank()) append("&fp=${server.fingerprint}")
                    if (server.flow.isNotBlank()) append("&flow=${server.flow}")
                    if (server.path.isNotBlank()) append("&path=${Uri.encode(server.path)}")
                    if (server.host.isNotBlank()) append("&host=${server.host}")
                    append("#$nameEncoded")
                }
            }
            "VMESS" -> {
                val json = JSONObject().apply {
                    put("v", "2")
                    put("ps", server.name)
                    put("add", server.address)
                    put("port", server.port)
                    put("id", server.uuid)
                    put("aid", "0")
                    put("net", if (server.transportType.isBlank()) "tcp" else server.transportType)
                    put("type", "none")
                    put("host", server.host)
                    put("path", server.path)
                    put("tls", if (server.security.equals("tls", ignoreCase = true)) "tls" else "")
                    put("sni", server.sni)
                }
                val encoded = Base64.encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                "vmess://$encoded"
            }
            "TROJAN" -> {
                buildString {
                    append("trojan://${server.uuid}@${server.address}:${server.port}?")
                    if (server.security.isNotBlank()) append("security=${server.security}") else append("security=tls")
                    if (server.sni.isNotBlank()) append("&sni=${server.sni}")
                    if (server.transportType.isNotBlank() && server.transportType != "tcp") append("&type=${server.transportType}")
                    if (server.path.isNotBlank()) append("&path=${Uri.encode(server.path)}")
                    if (server.host.isNotBlank()) append("&host=${server.host}")
                    append("#$nameEncoded")
                }
            }
            "SHADOWSOCKS", "SS" -> {
                val auth = "${server.encryption}:${server.uuid}"
                val authEncoded = Base64.encodeToString(auth.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                "ss://$authEncoded@${server.address}:${server.port}#$nameEncoded"
            }
            "HYSTERIA2", "HY2" -> {
                buildString {
                    append("hysteria2://${server.uuid}@${server.address}:${server.port}?")
                    if (server.sni.isNotBlank()) append("sni=${server.sni}")
                    append("#$nameEncoded")
                }
            }
            else -> {
                if (server.rawUri.isNotBlank()) server.rawUri
                else "vless://${server.uuid}@${server.address}:${server.port}#$nameEncoded"
            }
        }
    }

    fun exportToJson(server: ServerConfig): String {
        val root = JSONObject()
        val log = JSONObject().apply {
            put("loglevel", "warning")
        }
        root.put("log", log)

        val inbound = JSONObject().apply {
            put("port", 10808)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
            put("tag", "socks-in")
        }
        root.put("inbounds", org.json.JSONArray().apply { put(inbound) })

        val outbound = JSONObject().apply {
            put("protocol", server.protocol.lowercase())
            put("tag", "proxy")
            val settings = JSONObject()
            when (server.protocol.uppercase()) {
                "VLESS" -> {
                    val user = JSONObject().apply {
                        put("id", server.uuid)
                        put("encryption", server.encryption)
                        if (server.flow.isNotBlank()) put("flow", server.flow)
                    }
                    val vnextItem = JSONObject().apply {
                        put("address", server.address)
                        put("port", server.port)
                        put("users", org.json.JSONArray().apply { put(user) })
                    }
                    settings.put("vnext", org.json.JSONArray().apply { put(vnextItem) })
                }
                "VMESS" -> {
                    val user = JSONObject().apply {
                        put("id", server.uuid)
                        put("alterId", 0)
                        put("security", server.encryption.ifBlank { "auto" })
                    }
                    val vnextItem = JSONObject().apply {
                        put("address", server.address)
                        put("port", server.port)
                        put("users", org.json.JSONArray().apply { put(user) })
                    }
                    settings.put("vnext", org.json.JSONArray().apply { put(vnextItem) })
                }
                "TROJAN" -> {
                    val serverItem = JSONObject().apply {
                        put("address", server.address)
                        put("port", server.port)
                        put("password", server.uuid)
                    }
                    settings.put("servers", org.json.JSONArray().apply { put(serverItem) })
                }
                "SHADOWSOCKS" -> {
                    val serverItem = JSONObject().apply {
                        put("address", server.address)
                        put("port", server.port)
                        put("method", server.encryption)
                        put("password", server.uuid)
                    }
                    settings.put("servers", org.json.JSONArray().apply { put(serverItem) })
                }
            }
            put("settings", settings)

            val streamSettings = JSONObject().apply {
                put("network", server.transportType)
                put("security", server.security)
                if (server.security.equals("reality", ignoreCase = true)) {
                    put("realitySettings", JSONObject().apply {
                        put("serverName", server.sni)
                        put("publicKey", server.publicKey)
                        put("shortId", server.shortId)
                        put("fingerprint", server.fingerprint)
                    })
                } else if (server.security.equals("tls", ignoreCase = true)) {
                    put("tlsSettings", JSONObject().apply {
                        put("serverName", server.sni)
                        put("fingerprint", server.fingerprint)
                    })
                }
            }
            put("streamSettings", streamSettings)
        }
        root.put("outbounds", org.json.JSONArray().apply { put(outbound) })

        return root.toString(2)
    }

    private fun decodeBase64Safe(input: String): String? {
        return try {
            val normalized = input.trim()
                .replace("-", "+")
                .replace("_", "/")
                .replace("\n", "")
                .replace("\r", "")
            val padLength = (4 - (normalized.length % 4)) % 4
            val padded = normalized + "=".repeat(padLength)
            val bytes = Base64.decode(padded, Base64.DEFAULT or Base64.NO_WRAP)
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun tryDecodeBase64Subscription(text: String): String? {
        if (text.contains("\n") || text.startsWith("vless://") || text.startsWith("vmess://") || text.startsWith("trojan://") || text.startsWith("ss://")) {
            return null
        }
        val decoded = decodeBase64Safe(text) ?: return null
        return if (decoded.contains("vless://") || decoded.contains("vmess://") || decoded.contains("trojan://") || decoded.contains("ss://")) {
            decoded
        } else {
            null
        }
    }

    private fun decodeUriComponent(raw: String): String {
        return try {
            URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            raw
        }
    }

    private fun detectCountryCode(remarks: String, host: String): String {
        val text = "$remarks $host".lowercase()
        return when {
            text.contains("🇩🇪") || text.contains("germany") || text.contains("frankfurt") || text.contains("de-") || text.contains(".de") -> "DE"
            text.contains("🇺🇸") || text.contains("usa") || text.contains("us-") || text.contains("united states") || text.contains("los angeles") || text.contains("new york") -> "US"
            text.contains("🇬🇧") || text.contains("uk") || text.contains("london") || text.contains("britain") || text.contains(".uk") -> "GB"
            text.contains("🇳🇱") || text.contains("netherlands") || text.contains("amsterdam") || text.contains("nl-") || text.contains(".nl") -> "NL"
            text.contains("🇫🇷") || text.contains("france") || text.contains("paris") || text.contains("fr-") || text.contains(".fr") -> "FR"
            text.contains("🇹🇷") || text.contains("turkey") || text.contains("istanbul") || text.contains("tr-") || text.contains(".tr") -> "TR"
            text.contains("🇮🇷") || text.contains("iran") || text.contains("tehran") || text.contains("ir-") || text.contains(".ir") -> "IR"
            text.contains("🇸🇬") || text.contains("singapore") || text.contains("sg-") || text.contains(".sg") -> "SG"
            text.contains("🇯🇵") || text.contains("japan") || text.contains("tokyo") || text.contains("jp-") || text.contains(".jp") -> "JP"
            text.contains("🇨🇦") || text.contains("canada") || text.contains("toronto") || text.contains("ca-") || text.contains(".ca") -> "CA"
            text.contains("🇫🇮") || text.contains("finland") || text.contains("helsinki") || text.contains("fi-") -> "FI"
            text.contains("🇸🇪") || text.contains("sweden") || text.contains("stockholm") || text.contains("se-") -> "SE"
            else -> "US"
        }
    }
}
