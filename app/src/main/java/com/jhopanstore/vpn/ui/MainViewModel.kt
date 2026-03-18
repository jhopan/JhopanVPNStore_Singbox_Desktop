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
import com.jhopanstore.vpn.core.XrayManager
import com.jhopanstore.vpn.service.JhopanVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URL

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        // Compiled once — avoids per-call Regex instantiation in detectHotspotIp()
        private val HOTSPOT_IP_172_REGEX = Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")
    }

    private val appContext = application.applicationContext

    // Connection fields — address includes port, e.g. "example.com:443"
    var address by mutableStateOf("")
    var uuid by mutableStateOf("")

    // Settings
    var path by mutableStateOf("/vless")
    var sni by mutableStateOf("")
    var host by mutableStateOf("")
    var dns1 by mutableStateOf("8.8.8.8")
    var dns2 by mutableStateOf("8.8.4.4")
    var mtu by mutableStateOf("1400")
    var allowInsecure by mutableStateOf(true)
    var autoReconnect by mutableStateOf(true)
    var autoPing by mutableStateOf(true)
    var pingUrl by mutableStateOf("https://dns.google")
    var pingIntervalSeconds by mutableStateOf("5")
    var showUsageInApp by mutableStateOf(true)
    var showUsageInNotification by mutableStateOf(false)
    var showSpeedInNotification by mutableStateOf(false)
    var wakeLockEnabled by mutableStateOf(false)
    var keepAliveEnabled by mutableStateOf(false)
    var keepAliveIntervalSeconds by mutableStateOf("45")
    var maxReconnectAttempts by mutableStateOf("3")
    var networkReconnectDelaySeconds by mutableStateOf("2")

    // State
    var isConnected by mutableStateOf(false)
    var isConnecting by mutableStateOf(false)
    var statusText by mutableStateOf("Disconnected")
    var pingResult by mutableStateOf("-")
    var downloadUsage by mutableStateOf("0 B")
    var uploadUsage by mutableStateOf("0 B")

    // Flag untuk menandai sedang restart VPN (jangan reset proxy state)
    private var isRestarting = false

    // Job reference so ping burst is cancellable on disconnect
    private var pingJob: Job? = null
    private var usageUiJob: Job? = null
    // Safety timeout job — cancelled as soon as connection resolves (avoids 30s dangling coroutine)
    private var timeoutJob: Job? = null
    private var isAppInForeground = false

    init {
        // Collect StateFlow dari service — langsung update UI tanpa polling
        viewModelScope.launch {
            JhopanVpnService.state.collectLatest { state ->
                when (state) {
                    JhopanVpnService.VpnState.CONNECTED -> {
                        timeoutJob?.cancel()  // connection resolved — stop wasting 30s
                        isConnected = true
                        isConnecting = false
                        statusText = "Connected"
                        isRestarting = false  // Reset flag setelah berhasil connect
                        if (autoPing) startPingLoop() else pingResult = "Off"
                        startUsageUiLoop()
                    }
                    JhopanVpnService.VpnState.CONNECTING -> {
                        isConnecting = true
                        isConnected = false
                        statusText = "Connecting..."
                    }
                    JhopanVpnService.VpnState.FAILED -> {
                        timeoutJob?.cancel()  // connection resolved — stop wasting 30s
                        isConnected = false
                        isConnecting = false
                        statusText = "Connection failed"
                        pingResult = "-"
                        downloadUsage = "0 B"
                        uploadUsage = "0 B"
                        isRestarting = false  // Reset flag
                        // Hanya reset proxy state jika BUKAN sedang restart
                        if (!isRestarting) {
                            isProxySharingActive = false
                            XrayManager.hotspotSharing = false
                        }
                    }
                    JhopanVpnService.VpnState.DISCONNECTED -> {
                        // Hanya update jika memang sedang connected/connecting
                        // Hindari reset state saat app baru buka (initial DISCONNECTED)
                        if (isConnected || isConnecting) {
                            isConnected = false
                            isConnecting = false
                            statusText = "Disconnected"
                            pingResult = "-"
                            downloadUsage = "0 B"
                            uploadUsage = "0 B"
                            // Jangan reset proxy state jika sedang restart VPN
                            if (!isRestarting) {
                                isProxySharingActive = false
                                XrayManager.hotspotSharing = false
                            }
                        }
                    }
                }
            }
        }
    }

    // Hotspot sharing state
    var isHotspotDetected by mutableStateOf(false)
    var hotspotIp by mutableStateOf("")
    var isProxySharingActive by mutableStateOf(false)

    /** Split "host:port" input; defaults port to 443 */
    private fun parseAddress(): Pair<String, Int> {
        val trimmed = address.trim()
        val lastColon = trimmed.lastIndexOf(':')
        return if (lastColon > 0) {
            val host = trimmed.substring(0, lastColon)
            val port = trimmed.substring(lastColon + 1).toIntOrNull() ?: 443
            Pair(host, port)
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

    fun connect(context: Context) {
        if (address.isEmpty() || uuid.isEmpty()) {
            statusText = "Enter address and UUID"
            return
        }

        // State CONNECTING akan diset oleh StateFlow collector saat service kirim sinyal
        isConnecting = true
        statusText = "Connecting..."

        val uri = buildVlessUri()
        JhopanVpnService.start(
            context,
            uri,
            dns1,
            dns2,
            parseMtu(),
            autoReconnect,
            showUsageInNotification,
            showSpeedInNotification,
            wakeLockEnabled,
            keepAliveEnabled,
            parseKeepAliveIntervalSeconds(), parseMaxReconnectAttempts(), parseNetworkReconnectDelay())

        // Safety timeout: jika service hang total (tidak emit state apapun),
        // paksa reset setelah 30 detik agar field tidak terkunci selamanya.
        // Stored as timeoutJob so it can be cancelled immediately when state resolves.
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
        // reset proxy sharing when VPN disconnects
        isProxySharingActive = false
        XrayManager.hotspotSharing = false
    }

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
        if (!enabled) {
            usageUiJob?.cancel()
            usageUiJob = null
        } else {
            startUsageUiLoop()
        }
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

    fun pushNotificationPreferences(context: Context) {
        JhopanVpnService.updateNotificationSettings(context, showUsageInNotification, showSpeedInNotification)
    }

    fun pushRuntimePreferences(context: Context) {
        JhopanVpnService.updateRuntimeSettings(context, wakeLockEnabled, keepAliveEnabled, parseKeepAliveIntervalSeconds(), parseMaxReconnectAttempts(), parseNetworkReconnectDelay())
    }

    fun updatePingIntervalSeconds(input: String) {
        pingIntervalSeconds = input.filter { it.isDigit() }.take(3)
    }

    fun onAppForegrounded() {
        isAppInForeground = true
        startUsageUiLoop()
    }

    fun onAppBackgrounded() {
        isAppInForeground = false
        usageUiJob?.cancel()
        usageUiJob = null
    }

    // --- Hotspot Sharing ---

    /** Cek apakah hotspot aktif berdasarkan NetworkInterface. Panggil dari onResume. */
    fun checkHotspot() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = detectHotspotIp()
            withContext(Dispatchers.Main) {
                hotspotIp = ip ?: ""
                isHotspotDetected = ip != null
                // Kalau hotspot dimatikan saat proxy sharing aktif, reset
                if (!isHotspotDetected && isProxySharingActive) {
                    isProxySharingActive = false
                    XrayManager.hotspotSharing = false
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
                
                // Skip interface yang PASTI BUKAN hotspot:
                // - tun: VPN tunnel interface
                // - rmnet/r_rmnet/ccmni: Data seluler interface (Qualcomm/Mediatek)
                // - ppp: Point-to-point protocol
                // - dummy: Virtual dummy interface
                // - v4-/clat: IPv4/IPv6 translation layer
                if (name.startsWith("tun") || name.startsWith("rmnet") ||
                    name.startsWith("ppp") || name.startsWith("dummy") ||
                    name.startsWith("v4-") || name.startsWith("clat") ||
                    name.startsWith("ccmni") || name.startsWith("r_rmnet")) continue
                
                for (addr in iface.inetAddresses.toList()) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    
                    // Terima semua IP private range yang umum dipakai hotspot:
                    // 192.168.x.x (paling umum untuk hotspot)
                    // 10.x.x.x (beberapa device pakai range ini)
                    // 172.16.x.x - 172.31.x.x (jarang tapi mungkin)
                    val isPrivateRange = ip.startsWith("192.168.") || 
                                        ip.startsWith("10.") ||
                                        ip.matches(HOTSPOT_IP_172_REGEX)
                    
                    if (isPrivateRange) {
                        // Return IP ASLI device, JANGAN ubah jadi .1!
                        // Device hotspot Android ITU SENDIRI yang jadi gateway & proxy server
                        // Device lain harus connect ke IP ini, bukan gateway .1
                        return ip
                    }
                }
            }
            null
        } catch (e: Exception) { 
            Log.e("MainViewModel", "Error detecting hotspot IP", e)
            null 
        }
    }

    /** Toggle proxy sharing on/off. Restart VPN dengan binding baru. */
    fun toggleProxySharing(context: Context) {
        if (!isConnected) return
        isProxySharingActive = !isProxySharingActive
        XrayManager.hotspotSharing = isProxySharingActive
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
            JhopanVpnService.start(
                context,
                uri,
                dns1,
                dns2,
                parseMtu(),
                autoReconnect,
                showUsageInNotification,
                showSpeedInNotification,
                wakeLockEnabled,
                keepAliveEnabled,
                parseKeepAliveIntervalSeconds(), parseMaxReconnectAttempts(), parseNetworkReconnectDelay())
            // Wait for a terminal state with no polling — StateFlow push, max 20s
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

    // --- Persistence ---

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
        
        // Use commit() untuk immediate save (synchronous) agar tidak hilang saat app di-kill
        // Use apply() untuk background save (asynchronous) saat tidak urgent
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
        if (!autoPing) pingResult = "Off"
    }

    /** Sync isConnected dengan status service yang sebenarnya. Panggil dari onResume.
     *  StateFlow sudah handle update real-time, ini hanya untuk kasus
     *  app dibuka ulang saat VPN service masih jalan di background.
     */
    fun syncConnectionState() {
        val running = JhopanVpnService.isRunning
        // Hanya sync jika ada ketidaksesuaian dan TIDAK sedang dalam proses connecting
        if (running && !isConnected && !isConnecting) {
            isConnected = true
            isConnecting = false
            statusText = "Connected"
            if (autoPing) startPingLoop() else pingResult = "Off"
            startUsageUiLoop()
        } else if (!running && isConnected) {
            // Service mati tapi UI masih showing connected
            isConnected = false
            isConnecting = false
            statusText = "Disconnected"
            pingResult = "-"
            downloadUsage = "0 B"
            uploadUsage = "0 B"
            isProxySharingActive = false
            XrayManager.hotspotSharing = false
        }
        // Jika isConnecting == true dan running == false: biarkan StateFlow yang handle
    }

    // --- Ping ---

    private fun startPingLoop() {
        if (!autoPing) {
            pingResult = "Off"
            return
        }
        pingJob?.cancel()
        pingResult = "..."
        val proxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress("127.0.0.1", com.jhopanstore.vpn.core.XrayManager.SOCKS_PORT)
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

                    if (code in 200..399) {
                        pingResult = "$elapsed ms"
                    } else {
                        pingResult = "HTTP $code"
                    }
                } catch (_: Exception) {
                    pingResult = "Timeout"
                }

                delay(parsePingIntervalMs())
            }
            pingJob = null  // burst complete, free reference
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

    private fun parseMtu(): Int {
        val parsed = mtu.trim().toIntOrNull() ?: 1400
        return parsed.coerceIn(1280, 1500)
    }

    fun parseMaxReconnectAttempts(): Int {
        return maxReconnectAttempts.trim().toIntOrNull() ?: 3
    }

    fun parseNetworkReconnectDelay(): Int {
        return networkReconnectDelaySeconds.trim().toIntOrNull() ?: 2
    }

    fun parseKeepAliveIntervalSeconds(): Int {
        val parsed = keepAliveIntervalSeconds.trim().toIntOrNull() ?: 45
        return parsed.coerceIn(20, 300)
    }
}





