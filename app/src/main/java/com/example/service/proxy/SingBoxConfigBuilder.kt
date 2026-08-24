package com.example.service.proxy

import com.example.data.model.RoutingMode
import com.example.data.model.ServerConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal configuration builder that generates RFC-compliant JSON configurations
 * for Sing-Box / Native Core engine supporting VLESS Reality, Hysteria 2, gRPC, Trojan, and Shadowsocks.
 */
object SingBoxConfigBuilder {

    fun buildConfig(
        server: ServerConfig,
        routingMode: RoutingMode = RoutingMode.BYPASS_LAN_AND_IRAN,
        lanHttpPort: Int = 0,
        lanSocksPort: Int = 0,
        mtu: Int = 1400
    ): String {
        val root = JSONObject()

        // 1. Log Config
        val logObj = JSONObject().apply {
            put("level", "warn")
            put("timestamp", true)
        }
        root.put("log", logObj)

        // 2. DNS Configuration
        val dnsObj = JSONObject().apply {
            val serversArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "dns-remote")
                    put("address", "udp://1.1.1.1")
                    put("detour", "proxy-out")
                })
                put(JSONObject().apply {
                    put("tag", "dns-direct")
                    put("address", "https://1.1.1.1/dns-query")
                    put("detour", "direct-out")
                })
            }
            put("servers", serversArr)
            put("strategy", "prefer_ipv4")
        }
        root.put("dns", dnsObj)

        // 3. Inbounds (TUN + optional LAN sharing)
        val inboundsArr = JSONArray()

        val tunInbound = JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "tun0")
            put("inet4_address", "172.19.0.1/30")
            put("mtu", mtu)
            put("auto_route", true)
            put("strict_route", false)
            put("stack", "system")
            put("sniff", true)
            put("sniff_override_destination", true)
        }
        inboundsArr.put(tunInbound)

        if (lanHttpPort > 0) {
            inboundsArr.put(JSONObject().apply {
                put("type", "mixed")
                put("tag", "mixed-in")
                put("listen", "0.0.0.0")
                put("listen_port", lanHttpPort)
            })
        }
        root.put("inbounds", inboundsArr)

        // 4. Outbounds (Main Proxy + Direct + Block + DNS)
        val outboundsArr = JSONArray()

        val proxyOutbound = buildOutboundObject(server)
        outboundsArr.put(proxyOutbound)

        outboundsArr.put(JSONObject().apply {
            put("type", "direct")
            put("tag", "direct-out")
        })

        outboundsArr.put(JSONObject().apply {
            put("type", "block")
            put("tag", "block-out")
        })

        outboundsArr.put(JSONObject().apply {
            put("type", "dns")
            put("tag", "dns-out")
        })

        root.put("outbounds", outboundsArr)

        // 5. Routing Rules
        val routeObj = JSONObject().apply {
            put("auto_detect_interface", true)
            val rulesArr = JSONArray()

            // DNS Traffic Rule
            rulesArr.put(JSONObject().apply {
                put("protocol", "dns")
                put("outbound", "dns-out")
            })

            // Bypass LAN and Iran Domains / IPs if enabled
            if (routingMode == RoutingMode.BYPASS_LAN_AND_IRAN) {
                rulesArr.put(JSONObject().apply {
                    put("geoip", JSONArray().apply { put("private"); put("ir") })
                    put("outbound", "direct-out")
                })
                rulesArr.put(JSONObject().apply {
                    put("geosite", JSONArray().apply { put("private"); put("ir") })
                    put("outbound", "direct-out")
                })
            }

            // Default Route -> Proxy Out
            rulesArr.put(JSONObject().apply {
                put("network", "tcp,udp")
                put("outbound", "proxy-out")
            })

            put("rules", rulesArr)
        }
        root.put("route", routeObj)

        return root.toString(2)
    }

    private fun buildOutboundObject(server: ServerConfig): JSONObject {
        val out = JSONObject()
        out.put("tag", "proxy-out")

        when (server.protocol.uppercase()) {
            "HYSTERIA2", "HY2" -> {
                out.put("type", "hysteria2")
                out.put("server", server.address)
                out.put("server_port", server.port)
                out.put("password", server.uuid)

                if (server.upMbps > 0) out.put("up_mbps", server.upMbps)
                if (server.downMbps > 0) out.put("down_mbps", server.downMbps)

                if (server.obfs.isNotBlank()) {
                    val obfsObj = JSONObject().apply {
                        put("type", server.obfs)
                        put("password", server.obfsPassword)
                    }
                    out.put("obfs", obfsObj)
                }

                val tlsObj = JSONObject().apply {
                    put("enabled", true)
                    put("server_name", server.sni.ifBlank { server.address })
                    put("insecure", server.insecure)
                }
                out.put("tls", tlsObj)
            }

            "VLESS" -> {
                out.put("type", "vless")
                out.put("server", server.address)
                out.put("server_port", server.port)
                out.put("uuid", server.uuid)
                if (server.flow.isNotBlank()) {
                    out.put("flow", server.flow)
                }

                // Transport (ws, grpc, tcp)
                if (server.transportType.equals("ws", ignoreCase = true)) {
                    val transportObj = JSONObject().apply {
                        put("type", "ws")
                        put("path", server.path.ifBlank { "/" })
                        if (server.host.isNotBlank()) {
                            put("headers", JSONObject().apply { put("Host", server.host) })
                        }
                    }
                    out.put("transport", transportObj)
                } else if (server.transportType.equals("grpc", ignoreCase = true)) {
                    val transportObj = JSONObject().apply {
                        put("type", "grpc")
                        put("service_name", server.serviceName.ifBlank { server.path })
                    }
                    out.put("transport", transportObj)
                }

                // TLS / REALITY Security
                if (server.isReality) {
                    val tlsObj = JSONObject().apply {
                        put("enabled", true)
                        put("server_name", server.sni.ifBlank { server.address })
                        val utlsObj = JSONObject().apply {
                            put("enabled", true)
                            put("fingerprint", server.fingerprint.ifBlank { "chrome" })
                        }
                        put("utls", utlsObj)

                        val realityObj = JSONObject().apply {
                            put("enabled", true)
                            put("public_key", server.publicKey)
                            put("short_id", server.shortId)
                        }
                        put("reality", realityObj)
                    }
                    out.put("tls", tlsObj)
                } else if (server.isTls) {
                    val tlsObj = JSONObject().apply {
                        put("enabled", true)
                        put("server_name", server.sni.ifBlank { server.address })
                        put("insecure", server.insecure)
                        val utlsObj = JSONObject().apply {
                            put("enabled", true)
                            put("fingerprint", server.fingerprint.ifBlank { "chrome" })
                        }
                        put("utls", utlsObj)
                    }
                    out.put("tls", tlsObj)
                }
            }

            "TROJAN" -> {
                out.put("type", "trojan")
                out.put("server", server.address)
                out.put("server_port", server.port)
                out.put("password", server.uuid)

                if (server.transportType.equals("ws", ignoreCase = true)) {
                    out.put("transport", JSONObject().apply {
                        put("type", "ws")
                        put("path", server.path.ifBlank { "/" })
                    })
                } else if (server.transportType.equals("grpc", ignoreCase = true)) {
                    out.put("transport", JSONObject().apply {
                        put("type", "grpc")
                        put("service_name", server.serviceName.ifBlank { server.path })
                    })
                }

                out.put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", server.sni.ifBlank { server.address })
                    put("insecure", server.insecure)
                })
            }

            "SHADOWSOCKS", "SS" -> {
                out.put("type", "shadowsocks")
                out.put("server", server.address)
                out.put("server_port", server.port)
                out.put("method", server.encryption.ifBlank { "aes-128-gcm" })
                out.put("password", server.uuid)
            }

            "VMESS" -> {
                out.put("type", "vmess")
                out.put("server", server.address)
                out.put("server_port", server.port)
                out.put("uuid", server.uuid)
                out.put("security", server.encryption.ifBlank { "auto" })

                if (server.transportType.equals("ws", ignoreCase = true)) {
                    out.put("transport", JSONObject().apply {
                        put("type", "ws")
                        put("path", server.path.ifBlank { "/" })
                    })
                } else if (server.transportType.equals("grpc", ignoreCase = true)) {
                    out.put("transport", JSONObject().apply {
                        put("type", "grpc")
                        put("service_name", server.serviceName.ifBlank { server.path })
                    })
                }

                if (server.isTls) {
                    out.put("tls", JSONObject().apply {
                        put("enabled", true)
                        put("server_name", server.sni.ifBlank { server.address })
                        put("insecure", server.insecure)
                    })
                }
            }

            else -> {
                out.put("type", "vless")
                out.put("server", server.address)
                out.put("server_port", server.port)
                out.put("uuid", server.uuid)
            }
        }

        return out
    }
}
