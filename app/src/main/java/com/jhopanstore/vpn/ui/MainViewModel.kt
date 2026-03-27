package com.jhopanstore.vpn.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jhopanstore.vpn.core.VlessConfig
import com.jhopanstore.vpn.core.VlessParser
import com.jhopanstore.vpn.core.SingboxManager
import com.jhopanstore.vpn.service.JhopanVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URL

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private val HOTSPOT_IP_172_REGEX = Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")
    }

    private val appContext = application.applicationContext

    // ── Main Account ──
    var address by mutableStateOf("")
    var uuid by mutableStateOf("")
    var path by mutableStateOf("/vless")
    var sni by mutableStateOf("")
    var host by mutableStateOf("")
    var allowInsecure by mutableStateOf(true)

    // ── DNS & Network ──
    var dns1 by mutableStateOf("8.8.8.8")
    var dns2 by mutableStateOf("8.8.4.4")
    var mtu by mutableStateOf("1400")

    // ── Ping ──
    var pingUrl by mutableStateOf("https://dns.google")
    var pingIntervalSeconds by mutableStateOf("5")
    var autoPing by mutableStateOf(true)

    // ── Behavior ──
    var autoReconnect by mutableStateOf(true)
    var showUsageInApp by mutableStateOf(true)
    var showUsageInNotification by mutableStateOf(false)
    var showSpeedInNotification by mutableStateOf(false)
    var wakeLockEnabled by mutableStateOf(false)
    var keepAliveEnabled by mutableStateOf(false)
    var keepAliveIntervalSeconds by mutableStateOf("45")
    var maxReconnectAttempts by mutableStateOf("3")
    var networkReconnectDelaySeconds by mutableStateOf("2")

    // ── Backup Accounts (multiple) ──
    data class BackupAccount(
        val address: String = "",
        val uuid: String = "",
        val path: String = "/vless",
        val sni: String = "",
        val host: String = "",
        val allowInsecure: Boolean = true,
        val remark: String = ""
    ) {
        fun isValid(): Boolean = address.isNotBlank() && uuid.isNotBlank()

        /** Build a VLESS URI from this backup account's fields. */
        fun toVlessUri(): String {
            val parts = address.trim().split(":")
            val addr = parts.getOrElse(0) { address }
            val port = parts.getOrElse(1) { "443" }.toIntOrNull() ?: 443
            val actualSni = sni.ifEmpty { addr }
            val actualHost = host.ifEmpty { addr }
            return "vless://$uuid@$addr:$port?type=ws&security=tls&path=${
                java.net.URLEncoder.encode(path, "UTF-8")
            }&sni=$actualSni&host=$actualHost&allowInsecure=$allowInsecure#${
                java.net.URLEncoder.encode(remark.ifEmpty { "Backup" }, "UTF-8")
            }"
        }
    }

    var backupAccounts by mutableStateOf(listOf<BackupAccount>())
        private set

    // ── Fallback / URL Test Settings ──
    var fallbackEnabled by mutableStateOf(true)
    var fallbackTestUrl by mutableStateOf("https://www.gstatic.com/generate_204")
    var fallbackTestInterval by mutableStateOf("30")
    var fallbackTolerance by mutableStateOf("100")

    // ── Connection State ──
    var isConnected by mutableStateOf(false)
    var isConnecting by mutableStateOf(false)
    var statusText by mutableStateOf("Disconnected")
    var pingResult by mutableStateOf("-")
    var downloadUsage by mutableStateOf("0 B")
    var uploadUsage by mutableStateOf("0 B")

    private var isRestarting = false
    private var pingJob: Job? = null
    private var usageUiJob: Job? = null
    private var timeoutJob: Job? = null
    private var isAppInForeground = false

    // ── Hotspot Sharing ──
    var isHotspotDetected by mutableStateOf(false)
    var hotspotIp by mutableStateOf("")
    var isProxySharingActive by mutableStateOf(false)

    init {
        viewModelScope.launch {
            JhopanVpnService.state.collectLatest { state ->
                when (state) {
                    JhopanVpnService.VpnState.CONNECTED -> {
                        timeoutJob?.cancel()
                        isConnected = true
                        isConnecting = false
                        statusText = "Connected"
                        isRestarting = false
                        if (autoPing) startPingLoop() else pingResult = "Off"
                        startUsageUiLoop()
                    }
                    JhopanVpnService.VpnState.CONNECTING -> {
                        isConnecting = true
                        isConnected = false
                        statusText = "Connecting..."
                    }
                    JhopanVpnService.VpnState.FAILED -> {
                        timeoutJob?.cancel()
                        isConnected = false
                        isConnecting = false
                        statusText = "Connection failed"
                        pingResult = "-"
                        downloadUsage = "0 B"
                        uploadUsage = "0 B"
                        isRestarting = false
                        if (!isRestarting) {
                            isProxySharingActive = false
                            SingboxManager.hotspotSharing = false
                        }
                    }
                    JhopanVpnService.VpnState.DISCONNECTED -> {
                        if (isConnected || isConnecting) {
                            isConnected = false
                            isConnecting = false
                            statusText = "Disconnected"
                            pingResult = "-"
                            downloadUsage = "0 B"
                            uploadUsage = "0 B"
                            if (!isRestarting) {
                                isProxySharingActive = false
                                SingboxManager.hotspotSharing = false
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Address parsing ──

    private fun parseAddress(): Pair<String, Int> {
        val trimmed = address.trim()
        val lastColon = trimmed.lastIndexOf(':')
        return if (lastColon > 0) {
            val h = trimmed.substring(0, lastColon)
            val p = trimmed.substring(lastColon + 1).toIntOrNull() ?: 443
            Pair(h, p)
        } else {
            Pair(trimmed, 443)
        }
    }

    private fun buildVlessUri(): String {
        val (addr, port) = parseAddress()
        val actualSni = sni.ifEmpty { addr }
        val actualHost = host.ifEmpty { addr }
        return "vless://$uuid@$addr:$port?type=ws&security=tls&path=${
            java.net.URLEncoder.encode(path, "UTF-8")
        }&sni=$actualSni&host=$actualHost&allowInsecure=$allowInsecure#JhopanStoreVPN"
    }

    /** Build JSON array of backup VLESS URIs. */
    private fun buildBackupUrisJson(): String {
        val arr = JSONArray()
        backupAccounts.filter { it.isValid() }.forEach { arr.put(it.toVlessUri()) }
        return arr.toString()
    }

    // ── Import ──

    fun importVlessUri(uri: String) {
        val result = VlessParser.parse(uri)
        result.onSuccess { cfg ->
            address = "${cfg.address}:${cfg.port}"
            uuid = cfg.uuid
            path = cfg.path
            sni = cfg.sni
            host = cfg.host
            allowInsecure = cfg.allowInsecure
            statusText = "Imported"
        }.onFailure {
            statusText = "Invalid VLESS URI"
        }
    }

    // ── Connect / Disconnect ──

    fun connect(context: Context) {
        if (address.isEmpty() || uuid.isEmpty()) {
            statusText = "Enter address and UUID"
            return
        }

        isConnecting = true
        statusText = "Connecting..."

        val uri = buildVlessUri()
        val backupUrisJson = buildBackupUrisJson()
        val urlTestInterval = "${parseFallbackTestInterval()}s"
        val urlTestTolerance = parseFallbackTolerance()

        JhopanVpnService.start(
            context,
            uri,
            backupUrisJson,
            dns1, dns2,
            parseMtu(),
            autoReconnect,
            showUsageInNotification,
            showSpeedInNotification,
            wakeLockEnabled,
            keepAliveEnabled,
            parseKeepAliveIntervalSeconds(),
            parseMaxReconnectAttempts(),
            parseNetworkReconnectDelay(),
            if (fallbackEnabled) fallbackTestUrl else "https://www.gstatic.com/generate_204",
            urlTestInterval,
            urlTestTolerance
        )

        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(30_000)
            if (isConnecting && !isConnected) {
                isConnecting = false
                statusText = "Connection timeout"
            }
            timeoutJob = null
        }
    }

    fun disconnect(context: Context) {
        timeoutJob?.cancel()
        timeoutJob = null
        pingJob?.cancel()
        pingJob = null
        usageUiJob?.cancel()
        usageUiJob = null
        JhopanVpnService.stop(context)
        isConnected = false
        isConnecting = false
        statusText = "Disconnected"
        pingResult = "-"
        downloadUsage = "0 B"
        uploadUsage = "0 B"
        isProxySharingActive = false
        SingboxManager.hotspotSharing = false
    }

    // ── Backup Account Management ──

    fun addBackupAccount(account: BackupAccount = BackupAccount()) {
        backupAccounts = backupAccounts + account
    }

    fun removeBackupAccount(index: Int) {
        if (index in backupAccounts.indices) {
            backupAccounts = backupAccounts.toMutableList().apply { removeAt(index) }
        }
    }

    fun updateBackupAccount(index: Int, account: BackupAccount) {
        if (index in backupAccounts.indices) {
            backupAccounts = backupAccounts.toMutableList().apply { set(index, account) }
        }
    }

    fun importBackupFromUri(uri: String): Boolean {
        val result = VlessParser.parse(uri)
        return result.fold(
            onSuccess = { cfg ->
                val parts = "${cfg.address}:${cfg.port}"
                addBackupAccount(BackupAccount(
                    address = parts,
                    uuid = cfg.uuid,
                    path = cfg.path,
                    sni = cfg.sni,
                    host = cfg.host,
                    allowInsecure = cfg.allowInsecure,
                    remark = cfg.remark.ifEmpty { "Backup ${backupAccounts.size + 1}" }
                ))
                true
            },
            onFailure = { false }
        )
    }

    // ── Settings toggles ──

    fun setAutoPingEnabled(enabled: Boolean) {
        autoPing = enabled
        if (!enabled) {
            pingJob?.cancel()
            pingJob = null
            pingResult = "Off"
            return
        }
        pingResult = "-"
        if (isConnected) startPingLoop()
    }

    fun updateShowUsageInApp(enabled: Boolean) {
        showUsageInApp = enabled
        if (!enabled) { usageUiJob?.cancel(); usageUiJob = null }
        else startUsageUiLoop()
    }

    fun updateShowUsageInNotification(context: Context, enabled: Boolean) {
        showUsageInNotification = enabled
        JhopanVpnService.updateNotificationSettings(context, showUsageInNotification, showSpeedInNotification)
    }

    fun updateShowSpeedInNotification(context: Context, enabled: Boolean) {
        showSpeedInNotification = enabled
        JhopanVpnService.updateNotificationSettings(context, showUsageInNotification, showSpeedInNotification)
    }

    fun updateWakeLockEnabled(context: Context, enabled: Boolean) {
        wakeLockEnabled = enabled
        JhopanVpnService.updateRuntimeSettings(context, wakeLockEnabled, keepAliveEnabled, parseKeepAliveIntervalSeconds(), parseMaxReconnectAttempts(), parseNetworkReconnectDelay())
    }

    fun updateKeepAliveEnabled(context: Context, enabled: Boolean) {
        keepAliveEnabled = enabled
        JhopanVpnService.updateRuntimeSettings(context, wakeLockEnabled, keepAliveEnabled, parseKeepAliveIntervalSeconds(), parseMaxReconnectAttempts(), parseNetworkReconnectDelay())
    }

    fun updateMaxReconnectAttempts(context: Context, selected: String) {
        maxReconnectAttempts = selected
        JhopanVpnService.updateRuntimeSettings(context, wakeLockEnabled, keepAliveEnabled, parseKeepAliveIntervalSeconds(), parseMaxReconnectAttempts(), parseNetworkReconnectDelay())
    }

    fun updateNetworkReconnectDelay(context: Context, selected: String) {
        networkReconnectDelaySeconds = selected
        JhopanVpnService.updateRuntimeSettings(context, wakeLockEnabled, keepAliveEnabled, parseKeepAliveIntervalSeconds(), parseMaxReconnectAttempts(), parseNetworkReconnectDelay())
    }

    fun updateKeepAliveIntervalSeconds(context: Context, selected: String) {
        keepAliveIntervalSeconds = selected
        JhopanVpnService.updateRuntimeSettings(context, wakeLockEnabled, keepAliveEnabled, parseKeepAliveIntervalSeconds(), parseMaxReconnectAttempts(), parseNetworkReconnectDelay())
    }

    fun updatePingIntervalSeconds(input: String) {
        pingIntervalSeconds = input.filter { it.isDigit() }.take(3)
    }

    fun pushNotificationPreferences(context: Context) {
        JhopanVpnService.updateNotificationSettings(context, showUsageInNotification, showSpeedInNotification)
    }

    fun pushRuntimePreferences(context: Context) {
        JhopanVpnService.updateRuntimeSettings(context, wakeLockEnabled, keepAliveEnabled, parseKeepAliveIntervalSeconds(), parseMaxReconnectAttempts(), parseNetworkReconnectDelay())
    }

    // ── App lifecycle ──

    fun onAppForegrounded() {
        isAppInForeground = true
        startUsageUiLoop()
    }

    fun onAppBackgrounded() {
        isAppInForeground = false
        usageUiJob?.cancel()
        usageUiJob = null
    }

    // ── Hotspot Sharing ──

    fun checkHotspot() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = detectHotspotIp()
            withContext(Dispatchers.Main) {
                hotspotIp = ip ?: ""
                isHotspotDetected = ip != null
                if (!isHotspotDetected && isProxySharingActive) {
                    isProxySharingActive = false
                    SingboxManager.hotspotSharing = false
                }
            }
        }
    }

    private fun detectHotspotIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("rmnet") ||
                    name.startsWith("ppp") || name.startsWith("dummy") ||
                    name.startsWith("v4-") || name.startsWith("clat") ||
                    name.startsWith("ccmni") || name.startsWith("r_rmnet")) continue

                for (addr in iface.inetAddresses.toList()) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    val isPrivateRange = ip.startsWith("192.168.") ||
                        ip.startsWith("10.") ||
                        ip.matches(HOTSPOT_IP_172_REGEX)

                    if (isPrivateRange) return ip
                }
            }
            null
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error detecting hotspot IP", e)
            null
        }
    }

    fun toggleProxySharing(context: Context) {
        if (!isConnected) return
        isProxySharingActive = !isProxySharingActive
        SingboxManager.hotspotSharing = isProxySharingActive
        Log.d("MainViewModel", "Toggle proxy sharing: $isProxySharingActive")
        restartVpn(context)
    }

    private fun restartVpn(context: Context) {
        isRestarting = true
        Log.d("MainViewModel", "Restarting VPN (isRestarting=$isRestarting, proxyActive=$isProxySharingActive)")
        JhopanVpnService.stop(context)
        isConnecting = true
        statusText = "Reconnecting..."
        viewModelScope.launch {
            delay(1500)
            val uri = buildVlessUri()
            val backupUrisJson = buildBackupUrisJson()
            val urlTestInterval = "${parseFallbackTestInterval()}s"
            val urlTestTolerance = parseFallbackTolerance()

            JhopanVpnService.start(
                context, uri, backupUrisJson,
                dns1, dns2, parseMtu(),
                autoReconnect, showUsageInNotification, showSpeedInNotification,
                wakeLockEnabled, keepAliveEnabled,
                parseKeepAliveIntervalSeconds(), parseMaxReconnectAttempts(), parseNetworkReconnectDelay(),
                if (fallbackEnabled) fallbackTestUrl else "https://www.gstatic.com/generate_204",
                urlTestInterval, urlTestTolerance
            )

            val finalState = withTimeoutOrNull(20_000L) {
                JhopanVpnService.state.first {
                    it == JhopanVpnService.VpnState.CONNECTED || it == JhopanVpnService.VpnState.FAILED
                }
            }
            isConnecting = false
            isConnected = (finalState == JhopanVpnService.VpnState.CONNECTED)
            statusText = if (isConnected) "Connected" else "Reconnect failed"
            isRestarting = false
            if (isConnected) Log.d("MainViewModel", "VPN restarted successfully (proxyActive=$isProxySharingActive)")
        }
    }

    // ── Persistence ──

    fun saveSettings(context: Context, immediate: Boolean = false) {
        val editor = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE).edit()
            .putString("address", address)
            .putString("uuid", uuid)
            .putString("path", path)
            .putString("sni", sni)
            .putString("host", host)
            .putString("dns1", dns1)
            .putString("dns2", dns2)
            .putString("mtu", mtu)
            .putString("pingUrl", pingUrl)
            .putString("pingIntervalSeconds", pingIntervalSeconds)
            .putBoolean("allowInsecure", allowInsecure)
            .putBoolean("autoReconnect", autoReconnect)
            .putBoolean("autoPing", autoPing)
            .putBoolean("showUsageInApp", showUsageInApp)
            .putBoolean("showUsageInNotification", showUsageInNotification)
            .putBoolean("showSpeedInNotification", showSpeedInNotification)
            .putBoolean("wakeLockEnabled", wakeLockEnabled)
            .putBoolean("keepAliveEnabled", keepAliveEnabled)
            .putString("keepAliveIntervalSeconds", keepAliveIntervalSeconds)
            .putString("maxReconnectAttempts", maxReconnectAttempts)
            .putString("networkReconnectDelaySeconds", networkReconnectDelaySeconds)
            // Backup accounts (JSON)
            .putString("backupAccounts", serializeBackupAccounts())
            // Fallback settings
            .putBoolean("fallbackEnabled", fallbackEnabled)
            .putString("fallbackTestUrl", fallbackTestUrl)
            .putString("fallbackTestInterval", fallbackTestInterval)
            .putString("fallbackTolerance", fallbackTolerance)

        if (immediate) {
            editor.commit()
            Log.d("MainViewModel", "Settings saved immediately (commit)")
        } else {
            editor.apply()
            Log.d("MainViewModel", "Settings saved in background (apply)")
        }
    }

    fun loadSettings(context: Context) {
        val p = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        address = p.getString("address", "") ?: ""
        uuid = p.getString("uuid", "") ?: ""
        path = p.getString("path", "/vless") ?: "/vless"
        sni = p.getString("sni", "") ?: ""
        host = p.getString("host", "") ?: ""
        dns1 = p.getString("dns1", "8.8.8.8") ?: "8.8.8.8"
        dns2 = p.getString("dns2", "8.8.4.4") ?: "8.8.4.4"
        mtu = p.getString("mtu", "1400") ?: "1400"
        pingUrl = p.getString("pingUrl", "https://dns.google") ?: "https://dns.google"
        pingIntervalSeconds = p.getString("pingIntervalSeconds", "5") ?: "5"
        allowInsecure = p.getBoolean("allowInsecure", true)
        autoReconnect = p.getBoolean("autoReconnect", true)
        autoPing = p.getBoolean("autoPing", true)
        showUsageInApp = p.getBoolean("showUsageInApp", true)
        showUsageInNotification = p.getBoolean("showUsageInNotification", false)
        showSpeedInNotification = p.getBoolean("showSpeedInNotification", false)
        wakeLockEnabled = p.getBoolean("wakeLockEnabled", false)
        keepAliveEnabled = p.getBoolean("keepAliveEnabled", false)
        keepAliveIntervalSeconds = p.getString("keepAliveIntervalSeconds", "45") ?: "45"
        maxReconnectAttempts = p.getString("maxReconnectAttempts", "3") ?: "3"
        networkReconnectDelaySeconds = p.getString("networkReconnectDelaySeconds", "2") ?: "2"
        // Backup accounts
        val backupJson = p.getString("backupAccounts", "[]") ?: "[]"
        backupAccounts = deserializeBackupAccounts(backupJson)
        // Fallback
        fallbackEnabled = p.getBoolean("fallbackEnabled", true)
        fallbackTestUrl = p.getString("fallbackTestUrl", "https://www.gstatic.com/generate_204") ?: "https://www.gstatic.com/generate_204"
        fallbackTestInterval = p.getString("fallbackTestInterval", "30") ?: "30"
        fallbackTolerance = p.getString("fallbackTolerance", "100") ?: "100"

        if (!autoPing) pingResult = "Off"
    }

    private fun serializeBackupAccounts(): String {
        val arr = JSONArray()
        backupAccounts.forEach { acc ->
            arr.put(org.json.JSONObject().apply {
                put("address", acc.address)
                put("uuid", acc.uuid)
                put("path", acc.path)
                put("sni", acc.sni)
                put("host", acc.host)
                put("allowInsecure", acc.allowInsecure)
                put("remark", acc.remark)
            })
        }
        return arr.toString()
    }

    private fun deserializeBackupAccounts(json: String): List<BackupAccount> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                BackupAccount(
                    address = obj.optString("address", ""),
                    uuid = obj.optString("uuid", ""),
                    path = obj.optString("path", "/vless"),
                    sni = obj.optString("sni", ""),
                    host = obj.optString("host", ""),
                    allowInsecure = obj.optBoolean("allowInsecure", true),
                    remark = obj.optString("remark", "")
                )
            }
        } catch (e: Exception) {
            Log.w("MainViewModel", "Failed to deserialize backup accounts: ${e.message}")
            emptyList()
        }
    }

    fun syncConnectionState() {
        val running = JhopanVpnService.isRunning
        if (running && !isConnected && !isConnecting) {
            isConnected = true
            isConnecting = false
            statusText = "Connected"
            if (autoPing) startPingLoop() else pingResult = "Off"
            startUsageUiLoop()
        } else if (!running && isConnected) {
            isConnected = false
            isConnecting = false
            statusText = "Disconnected"
            pingResult = "-"
            downloadUsage = "0 B"
            uploadUsage = "0 B"
            isProxySharingActive = false
            SingboxManager.hotspotSharing = false
        }
    }

    // ── Ping ──

    private fun startPingLoop() {
        if (!autoPing) { pingResult = "Off"; return }
        pingJob?.cancel()
        pingResult = "..."
        val proxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress("127.0.0.1", SingboxManager.SOCKS_PORT)
        )
        pingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isConnected && autoPing) {
                try {
                    val conn = URL(pingUrl).openConnection(proxy) as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.requestMethod = "HEAD"
                    val start = System.currentTimeMillis()
                    conn.connect()
                    val code = conn.responseCode
                    val elapsed = System.currentTimeMillis() - start
                    pingResult = if (code in 200..399) "$elapsed ms" else "HTTP $code"
                } catch (_: Exception) {
                    pingResult = "Timeout"
                }
                delay(parsePingIntervalMs())
            }
            pingJob = null
        }
    }

    private fun startUsageUiLoop() {
        if (!isConnected || !isAppInForeground || !showUsageInApp) return
        usageUiJob?.cancel()
        usageUiJob = viewModelScope.launch(Dispatchers.IO) {
            while (isConnected && isAppInForeground && showUsageInApp) {
                val snapshot = JhopanVpnService.getUsageSnapshot(appContext)
                downloadUsage = formatBytes(snapshot.downloadBytes)
                uploadUsage = formatBytes(snapshot.uploadBytes)
                delay(2000)
            }
        }
    }

    // ── Helpers ──

    private fun parsePingIntervalMs(): Long {
        val seconds = pingIntervalSeconds.trim().toIntOrNull()?.coerceIn(1, 300) ?: 5
        return seconds * 1000L
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024L) return "$value B"
        if (value < 1024L * 1024L) return String.format("%.1f KB", value / 1024.0)
        if (value < 1024L * 1024L * 1024L) return String.format("%.1f MB", value / (1024.0 * 1024.0))
        return String.format("%.2f GB", value / (1024.0 * 1024.0 * 1024.0))
    }

    private fun parseMtu(): Int = (mtu.trim().toIntOrNull() ?: 1400).coerceIn(1280, 1500)
    fun parseMaxReconnectAttempts(): Int = maxReconnectAttempts.trim().toIntOrNull() ?: 3
    fun parseNetworkReconnectDelay(): Int = networkReconnectDelaySeconds.trim().toIntOrNull() ?: 2
    fun parseKeepAliveIntervalSeconds(): Int = (keepAliveIntervalSeconds.trim().toIntOrNull() ?: 45).coerceIn(20, 300)
    private fun parseFallbackTestInterval(): Int = (fallbackTestInterval.trim().toIntOrNull() ?: 30).coerceIn(5, 300)
    private fun parseFallbackTolerance(): Int = (fallbackTolerance.trim().toIntOrNull() ?: 100).coerceIn(0, 5000)
}
