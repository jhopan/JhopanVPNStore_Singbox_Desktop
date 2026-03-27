package com.jhopanstore.vpn.core

import android.util.Log

/**
 * Enhanced Cloudflare Workers detection.
 *
 * Uses multiple signals to determine if a VLESS config routes through
 * Cloudflare Workers vs a direct VPS connection.
 *
 * Workers CANNOT create UDP sockets → DNS must use TCP.
 * VPS can do everything → normal UDP DNS works.
 */
object WorkerDetector {
    private const val TAG = "WorkerDetector"

    // Known Cloudflare Workers/Pages domains
    private val WORKER_DOMAINS = listOf(
        ".workers.dev",
        ".pages.dev"
    )

    // Keywords that suggest CDN/Worker usage in host/SNI
    private val WORKER_KEYWORDS = listOf(
        "worker", "cdn", "cf-", "cloudflare",
        "pages", "wrangler"
    )

    // Common non-worker paths (direct VPS usually uses these)
    private val VPS_PATHS = listOf(
        "/vless", "/vmess", "/trojan", "/ss",
        "/ws", "/websocket"
    )

    /**
     * Detect if traffic is routed through Cloudflare Workers.
     *
     * Detection signals (weighted):
     * 1. Domain ends with .workers.dev or .pages.dev → STRONG signal
     * 2. Path is NOT a common VPS path (e.g. not /vless) → MEDIUM signal
     * 3. Port 80 + security "none" → MEDIUM signal (Workers often use port 80)
     * 4. Host/SNI contains worker-related keywords → WEAK signal
     * 5. Address is a Cloudflare IP but SNI differs → WEAK signal (CDN trick)
     *
     * Returns true if >= 1 STRONG signal, or >= 2 MEDIUM/WEAK signals.
     */
    fun isCloudflareWorkers(cfg: VlessConfig): Boolean {
        var score = 0

        // Signal 1: Domain check (STRONG = +3)
        val checkStrings = listOf(cfg.host, cfg.sni).filter { it.isNotBlank() }
        val hasDomainSignal = checkStrings.any { s ->
            WORKER_DOMAINS.any { d -> s.endsWith(d, ignoreCase = true) }
        }
        if (hasDomainSignal) {
            score += 3
            Log.d(TAG, "Signal 1 (domain): Workers domain detected")
        }

        // Signal 2: Path check (MEDIUM = +2)
        val pathLower = cfg.path.lowercase().trim()
        val isCommonVpsPath = VPS_PATHS.any { pathLower == it || pathLower.startsWith("$it/") }
        if (!isCommonVpsPath && pathLower.isNotEmpty()) {
            score += 2
            Log.d(TAG, "Signal 2 (path): Non-standard path '$pathLower' → likely Workers")
        }

        // Signal 3: Port 80 + no TLS (MEDIUM = +2)
        if (cfg.port == 80 && cfg.security == "none") {
            score += 2
            Log.d(TAG, "Signal 3 (port): Port 80 + no TLS → likely Workers")
        }

        // Signal 4: Host/SNI keyword check (WEAK = +1)
        val hasKeyword = checkStrings.any { s ->
            WORKER_KEYWORDS.any { k -> s.contains(k, ignoreCase = true) }
        }
        if (hasKeyword) {
            score += 1
            Log.d(TAG, "Signal 4 (keyword): Worker keyword in host/SNI")
        }

        // Signal 5: Address != SNI/Host (CDN trick) (WEAK = +1)
        val addressIsIp = cfg.address.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
        if (addressIsIp && cfg.sni.isNotBlank() && cfg.sni != cfg.address) {
            score += 1
            Log.d(TAG, "Signal 5 (CDN trick): IP address with different SNI")
        }

        val isWorker = score >= 3
        Log.i(TAG, "Detection result: score=$score → ${if (isWorker) "WORKERS (TCP DNS)" else "VPS (normal DNS)"}")
        return isWorker
    }
}
