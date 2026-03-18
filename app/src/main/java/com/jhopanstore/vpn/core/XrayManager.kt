package com.jhopanstore.vpn.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Manages Xray-core lifecycle on Android using libXray (in-process Go library).
 *
 * Key improvement over binary approach:
 *  - Xray runs in-process via libXray.aar (gomobile)
 *  - Socket protection via DialerController.protectFd() callback
 *  - No need to download/manage separate xray binary
 *  - Better battery and performance (no IPC overhead)
 *
 * libXray API:
 *  - LibXray.runXrayFromJSON(base64) — start Xray with inline JSON config
 *  - LibXray.stopXray() — stop running instance
 *  - LibXray.getXrayState() — check if running
 *  - LibXray.registerDialerController() — register VPN protect callback
 */
object XrayManager {
    private const val TAG = "XrayManager"
    const val SOCKS_PORT = 10808
    const val HTTP_PORT = 10809  // HTTP proxy for WiFi manual proxy (Android compatibility)

    /** Called when Xray state changes to stopped unexpectedly. */
    var onProcessDied: (() -> Unit)? = null

    /** When true, Xray SOCKS5 inbound listens on 0.0.0.0 (hotspot sharing). */
    @Volatile
    var hotspotSharing: Boolean = false

    // Track whether we intentionally stopped (vs unexpected death)
    @Volatile
    private var intentionalStop = false

    // Shared scope avoids dedicated long-lived thread for state checks
    private val monitorScope = CoroutineScope(Dispatchers.Default)
    private var monitorJob: Job? = null

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
     * Detect if traffic is routed through Cloudflare WORKERS (not just Cloudflare CDN).
     *
     * ┌─────────────────────────────────────────────────────────────────────────────┐
     * │ Case A — Cloudflare Workers (*.workers.dev / *.pages.dev)                  │
     * │   CF Workers CANNOT create UDP sockets → must use DNS-over-TCP.            │
     * ├─────────────────────────────────────────────────────────────────────────────┤
     * │ Case B — Bug Host / CDN Trick (CF CDN IP → VPS)                            │
     * │   The CDN is a plain TCP reverse proxy; the VPS handles VLESS.             │
     * │   VPS CAN create UDP sockets → normal DNS works.                           │
     * └─────────────────────────────────────────────────────────────────────────────┘
     */
    fun isCloudflareWorkers(cfg: VlessConfig): Boolean {
        val workersDomains = listOf(".workers.dev", ".pages.dev")
        val checkStrings = listOf(cfg.host, cfg.sni).filter { it.isNotBlank() }
        return checkStrings.any { s ->
            workersDomains.any { d -> s.endsWith(d, ignoreCase = true) }
        }
    }

    fun buildConfig(
        cfg: VlessConfig,
        dns1: String = "8.8.8.8",
        dns2: String = "8.8.4.4",
        resolvedIp: String? = null
    ): String {
        val cloudflare = isCloudflareWorkers(cfg)
        Log.i(TAG, "Server type: ${if (cloudflare) "Cloudflare Workers (TCP-only DNS)" else "VPS/CDN-proxy (UDP+TCP)"}")

        val root = JSONObject()

        root.put("log", JSONObject().apply { put("loglevel", "none") })

        // Disable per-connection stats tracking — saves CPU on every packet
        root.put("policy", JSONObject().apply {
            put("system", JSONObject().apply {
                put("statsInboundUplink", false)
                put("statsInboundDownlink", false)
                put("statsOutboundUplink", false)
                put("statsOutboundDownlink", false)
            })
        })

        // -- inbounds --
        root.put("inbounds", JSONArray().apply {
            // SOCKS5 inbound (for tun2socks and advanced usage)
            put(JSONObject().apply {
                put("tag", "socks-in")
                put("port", SOCKS_PORT)
                put("listen", if (hotspotSharing) "0.0.0.0" else "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().apply { put("udp", true) })
            })
            // HTTP inbound (for WiFi manual proxy when hotspot sharing)
            // Android WiFi manual proxy only supports HTTP, not SOCKS5!
            if (hotspotSharing) {
                put(JSONObject().apply {
                    put("tag", "http-in")
                    put("port", HTTP_PORT)
                    put("listen", "0.0.0.0")
                    put("protocol", "http")
                })
            }
        })

        // -- outbounds --
        val wsHost = cfg.host.ifEmpty { cfg.sni.ifEmpty { cfg.address } }
        val wsSettings = JSONObject().apply {
            put("path", cfg.path)
            put("headers", JSONObject().apply {
                put("Host", wsHost)
            })
        }

        val tlsSettings = JSONObject().apply {
            put("serverName", cfg.sni.ifEmpty { cfg.address })
            put("allowInsecure", cfg.allowInsecure)
        }

        val streamSettings = JSONObject().apply {
            put("network", cfg.type)
            put("security", cfg.security)
            put("wsSettings", wsSettings)
            if (cfg.security == "tls") put("tlsSettings", tlsSettings)
        }

        val serverAddress = resolvedIp ?: cfg.address

        root.put("outbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "proxy")
                put("protocol", "vless")
                put("settings", JSONObject().apply {
                    put("vnext", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", serverAddress)
                            put("port", cfg.port)
                            put("users", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("id", cfg.uuid)
                                    put("encryption", "none")
                                })
                            })
                        })
                    })
                })
                put("streamSettings", streamSettings)
            })
            put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
                put("settings", JSONObject().apply { put("domainStrategy", "UseIPv4") })
            })
            if (cloudflare) {
                put(JSONObject().apply { put("tag", "dns-out"); put("protocol", "dns") })
            }
        })

        // -- routing --
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray().apply {
                if (cloudflare) {
                    put(JSONObject().apply {
                        put("type", "field")
                        put("inboundTag", JSONArray().apply { put("socks-in") })
                        put("port", "53")
                        put("outboundTag", "dns-out")
                    })
                } else {
                    // Hanya bypass local traffic jika TIDAK sedang berbagi hotspot
                    // Ketika hotspot sharing aktif, semua traffic (termasuk dari device lain)
                    // harus di-route melalui VPN proxy, bukan direct
                    if (!hotspotSharing) {
                        put(JSONObject().apply {
                            put("type", "field")
                            put("ip", JSONArray().apply {
                                put("10.0.0.0/8")
                                put("172.16.0.0/12")
                                put("192.168.0.0/16")
                                put("127.0.0.0/8")
                            })
                            put("outboundTag", "direct")
                        })
                    }
                }
            })
        })

        // -- dns --
        root.put("dns", JSONObject().apply {
            val d1 = dns1.ifBlank { "8.8.8.8" }
            val d2 = dns2.ifBlank { "8.8.4.4" }
            put("servers", JSONArray().apply {
                if (cloudflare) {
                    put("tcp://$d1")
                    put("tcp://$d2")
                } else {
                    put(d1)
                    put(d2)
                }
            })
            put("queryStrategy", "UseIPv4")
        })

        return root.toString()
    }

    /**
     * Parse libXray base64-encoded response.
     * Returns Pair(success, message).
     */
    private fun parseResponse(base64Response: String): Pair<Boolean, String> {
        return try {
            val json = String(android.util.Base64.decode(base64Response, android.util.Base64.DEFAULT))
            val obj = JSONObject(json)
            val success = obj.optBoolean("success", false)
            val data = obj.optString("data", "")
            Pair(success, data)
        } catch (e: Exception) {
            Pair(false, "Failed to parse response: ${e.message}")
        }
    }

    /**
     * Start Xray-core via libXray (in-process Go library).
     * Returns true if started successfully.
     */
    fun start(
        context: Context,
        cfg: VlessConfig,
        dns1: String = "8.8.8.8",
        dns2: String = "8.8.4.4",
        resolvedIp: String? = null
    ): Boolean {
        return try {
            stop()
            intentionalStop = false

            val datDir = context.filesDir.absolutePath
            val mphCachePath = context.cacheDir.absolutePath + "/mph_cache"

            // Generate Xray config JSON
            val configJson = buildConfig(cfg, dns1, dns2, resolvedIp)

            // Create base64-encoded request using libXray's factory method
            val requestBase64 = LibXray.newXrayRunFromJSONRequest(datDir, mphCachePath, configJson)

            // Start Xray core (non-blocking — starts goroutines and returns)
            val responseBase64 = LibXray.runXrayFromJSON(requestBase64)
            val (success, message) = parseResponse(responseBase64)

            if (!success) {
                Log.e(TAG, "libXray runXrayFromJSON failed: $message")
                return false
            }

            Log.i(TAG, "Xray started successfully via libXray")

            // Monitor Xray state in background for unexpected death
            monitorJob?.cancel()
            monitorJob = monitorScope.launch {
                delay(3000) // initial delay — let Xray fully stabilize
                while (!intentionalStop && LibXray.getXrayState()) {
                    delay(10000) // check every 10 seconds — reduces CPU wake cycles
                }
                if (!intentionalStop) {
                    Log.w(TAG, "Xray stopped unexpectedly")
                    onProcessDied?.invoke()
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Xray via libXray", e)
            false
        }
    }

    /**
     * Stop Xray-core via libXray.
     */
    fun stop() {
        intentionalStop = true
        monitorJob?.cancel()
        monitorJob = null

        try {
            if (LibXray.getXrayState()) {
                val response = LibXray.stopXray()
                val (success, message) = parseResponse(response)
                Log.d(TAG, "Xray stopped: success=$success msg=$message")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Xray: ${e.message}")
        }
    }

    /**
     * Check if Xray-core is currently running.
     */
    fun isRunning(): Boolean {
        return try {
            LibXray.getXrayState()
        } catch (e: Exception) {
            false
        }
    }
}
