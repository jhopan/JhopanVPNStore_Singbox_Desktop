package com.jhopanstore.vpn.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import io.github.sagernet.libbox.Libbox

/**
 * Manages sing-box core lifecycle on Android using libbox (in-process Go library).
 *
 * Architecture:
 *   Apps → sing-box (TUN built-in + urltest auto-select) → internet
 *
 * Key features:
 *   - All-in-one: TUN, proxy, DNS in single process
 *   - urltest outbound group: automatic failover, zero-restart
 *   - Simplified Workers detection with per-outbound DNS strategy
 *   - PlatformInterface for VPN socket protection
 */
object SingboxManager {
    private const val TAG = "SingboxManager"
    const val SOCKS_PORT = 10808
    const val HTTP_PORT = 10809

    /** Called when sing-box service stops unexpectedly. */
    var onServiceStopped: (() -> Unit)? = null

    /** When true, mixed inbound listens on 0.0.0.0 (hotspot sharing). */
    @Volatile
    var hotspotSharing: Boolean = false

    @Volatile
    private var isServiceRunning = false

    /**
     * Resolve domain to IP address before VPN tunnel is up.
     * Must be called on a background thread, before TUN is established.
     */
    fun resolveDomain(host: String): String? {
        return try {
            val addresses = InetAddress.getAllByName(host)
            val ipv4 = addresses.firstOrNull { it is Inet4Address }
            val selected = ipv4 ?: addresses.firstOrNull()
            selected?.hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve $host: ${e.message}")
            null
        }
    }

    /**
     * Build sing-box configuration JSON.
     *
     * @param accounts     List of VLESS configs (index 0 = main, rest = backups)
     * @param dns1         Primary DNS server
     * @param dns2         Secondary DNS server
     * @param resolvedIps  Map of address → resolved IP (pre-resolved before TUN up)
     * @param urlTestUrl   URL for urltest health checks
     * @param urlTestInterval Interval between tests (e.g. "30s")
     * @param urlTestTolerance Tolerance in ms (main stays selected if within this range)
     * @param mtu          MTU for TUN interface
     */
    fun buildConfig(
        accounts: List<VlessConfig>,
        basePath: String,
        dns1: String = "8.8.8.8",
        dns2: String = "8.8.4.4",
        resolvedIps: Map<String, String> = emptyMap(),
        urlTestUrl: String = "https://www.gstatic.com/generate_204",
        urlTestInterval: String = "30s",
        urlTestTolerance: Int = 100,
        mtu: Int = 1400,
        customRules: List<Map<String, String>> = emptyList()
    ): String {
        val root = JSONObject()

        // ── Log ──
        root.put("log", JSONObject().apply {
            put("level", "warn")
            put("timestamp", true)
        })

        // ── Analyze accounts for DNS strategy ──
        val hasWorkerAccounts = accounts.any { WorkerDetector.isCloudflareWorkers(it) }
        val hasVpsAccounts = accounts.any { !WorkerDetector.isCloudflareWorkers(it) }
        Log.i(TAG, "Accounts: ${accounts.size} total, workers=$hasWorkerAccounts, vps=$hasVpsAccounts")

        // ── DNS ──
        val d1 = dns1.ifBlank { "8.8.8.8" }
        val d2 = dns2.ifBlank { "8.8.4.4" }
        root.put("dns", buildDnsConfig(d1, d2, accounts))

        // ── Inbounds ──
        root.put("inbounds", buildInbounds(mtu))

        // ── Outbounds ──
        val outboundTags = mutableListOf<String>()
        val outboundsArray = JSONArray()

        // Build VLESS outbound for each account
        accounts.forEachIndexed { index, cfg ->
            val tag = if (index == 0) "main" else "backup-${index}"
            outboundTags.add(tag)

            val isWorker = WorkerDetector.isCloudflareWorkers(cfg)
            val serverAddress = resolvedIps[cfg.address] ?: cfg.address

            outboundsArray.put(buildVlessOutbound(tag, cfg, serverAddress))
            Log.i(TAG, "Outbound '$tag': ${cfg.address}:${cfg.port} [${if (isWorker) "Workers" else "VPS"}]")
        }

        // urltest group — automatic failover (only if multiple accounts)
        if (accounts.size > 1) {
            val urltestGroup = JSONObject().apply {
                put("type", "urltest")
                put("tag", "auto-select")
                put("outbounds", JSONArray().apply {
                    outboundTags.forEach { put(it) }
                })
                put("url", urlTestUrl)
                put("interval", urlTestInterval)
                put("tolerance", urlTestTolerance)
            }
            // Insert urltest at position 0 (before individual outbounds)
            val finalOutbounds = JSONArray()
            finalOutbounds.put(urltestGroup)
            for (i in 0 until outboundsArray.length()) {
                finalOutbounds.put(outboundsArray.getJSONObject(i))
            }

            // Add utility outbounds
            finalOutbounds.put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            })
            finalOutbounds.put(JSONObject().apply {
                put("type", "block")
                put("tag", "reject")
            })
            finalOutbounds.put(JSONObject().apply {
                put("type", "dns")
                put("tag", "dns-out")
            })

            root.put("outbounds", finalOutbounds)
        } else {
            // Single account — no urltest needed
            outboundsArray.put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            })
            outboundsArray.put(JSONObject().apply {
                put("type", "block")
                put("tag", "reject")
            })
            outboundsArray.put(JSONObject().apply {
                put("type", "dns")
                put("tag", "dns-out")
            })
            root.put("outbounds", outboundsArray)
        }

        // ── Experimental (cache file in writable directory) ──
        root.put("experimental", JSONObject().apply {
            put("cache_file", JSONObject().apply {
                put("enabled", true)
                put("path", "$basePath/cache.db")
                put("store_fakeip", false)
            })
        })

        // ── Route ──
        root.put("route", buildRoute(accounts.size > 1, customRules, basePath))

        val configJson = root.toString()
        Log.d(TAG, "Generated config: ${configJson.length} bytes")
        return configJson
    }

    private fun buildDnsConfig(dns1: String, dns2: String, accounts: List<VlessConfig>): JSONObject {
        return JSONObject().apply {
            val servers = JSONArray()

            // Check if ANY account is a Worker
            val anyWorker = accounts.any { WorkerDetector.isCloudflareWorkers(it) }

            if (anyWorker) {
                // Use TCP DNS for Workers compatibility
                servers.put(JSONObject().apply {
                    put("tag", "dns-main")
                    put("address", "tcp://$dns1")
                    put("strategy", "prefer_ipv4")
                })
                servers.put(JSONObject().apply {
                    put("tag", "dns-backup")
                    put("address", "tcp://$dns2")
                    put("strategy", "prefer_ipv4")
                })
            } else {
                // Standard UDP DNS for VPS
                servers.put(JSONObject().apply {
                    put("tag", "dns-main")
                    put("address", dns1)
                    put("strategy", "prefer_ipv4")
                })
                servers.put(JSONObject().apply {
                    put("tag", "dns-backup")
                    put("address", dns2)
                    put("strategy", "prefer_ipv4")
                })
            }

            // Local DNS for direct traffic
            servers.put(JSONObject().apply {
                put("tag", "dns-local")
                put("address", "local")
            })

            put("servers", servers)

            // DNS rules
            put("rules", JSONArray().apply {
                // Direct outbound uses local DNS
                put(JSONObject().apply {
                    put("outbound", "direct")
                    put("server", "dns-local")
                })
            })
        }
    }

    private fun buildInbounds(mtu: Int): JSONArray {
        return JSONArray().apply {
            // TUN inbound — sing-box manages TUN directly
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("inet4_address", "172.19.0.1/30")
                put("mtu", mtu)
                put("auto_route", true)
                put("strict_route", true)
                put("sniff", true)
                put("sniff_override_destination", false)
            })

            // Mixed inbound for SOCKS5 + HTTP proxy (hotspot sharing)
            val listenAddr = if (hotspotSharing) "0.0.0.0" else "127.0.0.1"
            put(JSONObject().apply {
                put("type", "mixed")
                put("tag", "mixed-in")
                put("listen", listenAddr)
                put("listen_port", SOCKS_PORT)
            })

            // HTTP proxy for WiFi manual proxy (hotspot sharing)
            if (hotspotSharing) {
                put(JSONObject().apply {
                    put("type", "http")
                    put("tag", "http-in")
                    put("listen", "0.0.0.0")
                    put("listen_port", HTTP_PORT)
                })
            }
        }
    }

    private fun buildVlessOutbound(
        tag: String,
        cfg: VlessConfig,
        serverAddress: String
    ): JSONObject {
        return JSONObject().apply {
            put("type", "vless")
            put("tag", tag)
            put("server", serverAddress)
            put("server_port", cfg.port)
            put("uuid", cfg.uuid)

            // TLS
            if (cfg.security == "tls") {
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", cfg.sni.ifEmpty { cfg.address })
                    put("insecure", cfg.allowInsecure)
                    // uTLS fingerprint for better anti-detection
                    put("utls", JSONObject().apply {
                        put("enabled", true)
                        put("fingerprint", "chrome")
                    })
                })
            }

            // Transport (WebSocket)
            if (cfg.type == "ws") {
                val wsHost = cfg.host.ifEmpty { cfg.sni.ifEmpty { cfg.address } }
                put("transport", JSONObject().apply {
                    put("type", "ws")
                    put("path", cfg.path)
                    put("headers", JSONObject().apply {
                        put("Host", wsHost)
                    })
                })
            }
        }
    }

    private fun buildRoute(hasUrlTest: Boolean, customRules: List<Map<String, String>>, basePath: String): JSONObject {
        return JSONObject().apply {
            // Rely on VpnService.protect() instead of internal interface detection
            // which fails on Android 11+ without explicit NetworkInterfaceIterator
            put("auto_detect_interface", false)
            put("override_android_vpn", true)

            // Rule sets definition
            put("rules", JSONArray().apply {
                // Custom Routing Rules injected directly
                customRules.forEach { rule ->
                    val name = rule["name"] ?: ""
                    val fileName = rule["fileName"] ?: "${name}.json"
                    val targetOutbound = rule["targetOutbound"] ?: "direct"
                    val filePath = "$basePath/rules/$fileName"
                    
                    val file = java.io.File(filePath)
                    if (name.isNotBlank() && file.exists()) {
                        try {
                            val ruleContent = JSONObject(file.readText())
                            if (ruleContent.has("rules")) {
                                val jsonRules = ruleContent.getJSONArray("rules")
                                for (i in 0 until jsonRules.length()) {
                                    val ruleObj = jsonRules.getJSONObject(i)
                                    // Inject outbound target
                                    ruleObj.put("outbound", targetOutbound)
                                    put(ruleObj)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("SingboxManager", "Failed to parse $fileName", e)
                        }
                    }
                }

                // DNS hijacking
                put(JSONObject().apply {
                    put("protocol", "dns")
                    put("outbound", "dns-out")
                })

                // Bypass local traffic when NOT hotspot sharing
                if (!hotspotSharing) {
                    put(JSONObject().apply {
                        put("ip_cidr", JSONArray().apply {
                            put("10.0.0.0/8")
                            put("172.16.0.0/12")
                            put("192.168.0.0/16")
                            put("127.0.0.0/8")
                        })
                        put("outbound", "direct")
                    })
                }
            })

            // Default outbound
            put("final", if (hasUrlTest) "auto-select" else "main")
        }
    }

    /**
     * Start sing-box service.
     *
     * Note: The actual service start uses libbox CommandServer which is
     * managed by JhopanVpnService. This method is called by the service
     * after setting up PlatformInterface.
     */
    fun markRunning() {
        isServiceRunning = true
    }

    fun markStopped() {
        isServiceRunning = false
    }

    fun isRunning(): Boolean = isServiceRunning
}
