package com.jhopanstore.vpn.core

import java.net.URI
import java.net.URLDecoder

/**
 * VLESS URI parser — parses vless:// links into VlessConfig.
 */
data class VlessConfig(
    val address: String = "",
    val port: Int = 443,
    val uuid: String = "",
    val path: String = "/vless",
    val sni: String = "",
    val host: String = "",
    val security: String = "tls",
    val type: String = "ws",
    val allowInsecure: Boolean = true
)

object VlessParser {

    fun parse(uri: String): Result<VlessConfig> {
        return try {
            val trimmed = uri.trim()
            if (!trimmed.startsWith("vless://")) {
                return Result.failure(IllegalArgumentException("Not a vless:// URI"))
            }

            // vless://UUID@address:port?params#name
            val withoutScheme = trimmed.removePrefix("vless://")

            // Split off fragment (#name)
            val withoutFragment = withoutScheme.substringBefore("#")

            // Split UUID from rest
            val atIndex = withoutFragment.indexOf('@')
            if (atIndex < 0) {
                return Result.failure(IllegalArgumentException("Missing @ in URI"))
            }

            val uuid = withoutFragment.substring(0, atIndex)
            val rest = withoutFragment.substring(atIndex + 1)

            // Split address:port from params
            val questionIndex = rest.indexOf('?')
            val hostPort: String
            val queryString: String

            if (questionIndex >= 0) {
                hostPort = rest.substring(0, questionIndex)
                queryString = rest.substring(questionIndex + 1)
            } else {
                hostPort = rest
                queryString = ""
            }

            // Parse host:port
            val lastColon = hostPort.lastIndexOf(':')
            val address: String
            val port: Int

            if (lastColon > 0) {
                address = hostPort.substring(0, lastColon)
                port = hostPort.substring(lastColon + 1).toIntOrNull() ?: 443
            } else {
                address = hostPort
                port = 443
            }

            // Parse query params
            val params = mutableMapOf<String, String>()
            if (queryString.isNotEmpty()) {
                queryString.split("&").forEach { pair ->
                    val eqIndex = pair.indexOf('=')
                    if (eqIndex > 0) {
                        val key = pair.substring(0, eqIndex)
                        val value = URLDecoder.decode(pair.substring(eqIndex + 1), "UTF-8")
                        params[key] = value
                    }
                }
            }

            // Validate UUID format: 8-4-4-4-12 hex chars
            val uuidRegex = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")
            if (!uuidRegex.matches(uuid)) {
                return Result.failure(IllegalArgumentException("UUID tidak valid: format yang benar xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"))
            }

            val config = VlessConfig(
                address = address,
                port = port,
                uuid = uuid,
                path = params["path"] ?: "/vless",
                sni = params["sni"] ?: address,
                host = params["host"] ?: address,
                security = params["security"] ?: "tls",
                type = params["type"] ?: "ws",
                allowInsecure = params["allowInsecure"]?.toBoolean() ?: true
            )

            Result.success(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
