package com.jhopanstore.vpn.core

import android.util.Log

/**
 * Simplified Cloudflare Workers detection.
 *
 * Workers CANNOT create UDP sockets → DNS must use TCP.
 * VPS can do everything → normal UDP DNS works.
 *
 * Detection rules (in priority order):
 * 1. Path is IP-port pattern (e.g. /103.6.207.108-8080) → Workers
 * 2. Host/SNI is .workers.dev or .pages.dev → Workers
 * 3. Non-standard path + worker keyword in host/SNI → Workers
 */
object WorkerDetector {
    private const val TAG = "WorkerDetector"

    // Regex: path starts with IP address followed by dash and port
    // e.g. "103.6.207.108-8080" or "1.2.3.4-443"
    private val IP_PORT_PATH = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}-\d+$""")

    // Known Cloudflare Workers/Pages domains
    private val WORKER_DOMAINS = listOf(".workers.dev", ".pages.dev")

    // Keywords that suggest CDN/Worker usage
    private val WORKER_KEYWORDS = listOf("worker", "cdn", "cf-", "cloudflare", "pages")

    // Common VPS paths
    private val VPS_PATHS = listOf("/vless", "/vmess", "/trojan", "/ss", "/ws", "/websocket")

    /**
     * Detect if traffic is routed through Cloudflare Workers.
     */
    fun isCloudflareWorkers(cfg: VlessConfig): Boolean {
        val path = cfg.path.trimStart('/')

        // Rule 1: Path is IP-port pattern → definitely Workers
        if (IP_PORT_PATH.matches(path)) {
            Log.i(TAG, "Workers detected: path '$path' is IP-port pattern")
            return true
        }

        // Rule 2: Host/SNI is .workers.dev or .pages.dev
        val domains = listOf(cfg.host, cfg.sni).filter { it.isNotBlank() }
        if (domains.any { d -> WORKER_DOMAINS.any { d.endsWith(it, ignoreCase = true) } }) {
            Log.i(TAG, "Workers detected: domain is workers.dev/pages.dev")
            return true
        }

        // Rule 3: Non-standard path + worker keyword in host/SNI
        val isStandardPath = VPS_PATHS.any { vp ->
            path.equals(vp.trimStart('/'), ignoreCase = true) ||
            path.startsWith(vp.trimStart('/') + "/", ignoreCase = true)
        }
        if (!isStandardPath && path.isNotEmpty() && domains.any { d ->
            WORKER_KEYWORDS.any { k -> d.contains(k, ignoreCase = true) }
        }) {
            Log.i(TAG, "Workers detected: non-standard path + worker keyword")
            return true
        }

        Log.d(TAG, "VPS detected for ${cfg.address}")
        return false
    }
}
