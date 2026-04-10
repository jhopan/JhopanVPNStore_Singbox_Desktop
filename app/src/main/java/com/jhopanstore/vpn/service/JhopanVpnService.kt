package com.jhopanstore.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import com.jhopanstore.vpn.MainActivity
import com.jhopanstore.vpn.R
import com.jhopanstore.vpn.core.SingboxManager
import com.jhopanstore.vpn.core.VlessConfig
import com.jhopanstore.vpn.core.VlessParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.github.sagernet.libbox.CommandServer
import io.github.sagernet.libbox.CommandServerHandler
import io.github.sagernet.libbox.Libbox
import io.github.sagernet.libbox.PlatformInterface
import io.github.sagernet.libbox.SetupOptions
import io.github.sagernet.libbox.TunOptions
import io.github.sagernet.libbox.BoxService

import io.github.sagernet.libbox.InterfaceUpdateListener
import io.github.sagernet.libbox.NetworkInterfaceIterator
import io.github.sagernet.libbox.WIFIState
import io.github.sagernet.libbox.SystemProxyStatus
import java.io.IOException

/**
 * Android VpnService that routes traffic through sing-box core (via libbox).
 *
 * Architecture:
 *   Apps → sing-box (TUN built-in + urltest) → internet
 *
 * Key design:
 *   - All-in-one: sing-box handles TUN, proxy, DNS internally
 *   - urltest outbound group: automatic failover, zero-restart
 *   - PlatformInterface: VPN socket protection + TUN management
 */
class JhopanVpnService : VpnService() {

    enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    companion object {
        private const val TAG = "JhopanVpnService"
        private const val CHANNEL_ID = "jhopan_vpn_channel"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "ACTION_STOP"
        private const val ACTION_UPDATE_NOTIFICATION_PREFS = "ACTION_UPDATE_NOTIFICATION_PREFS"
        private const val ACTION_UPDATE_RUNTIME_PREFS = "ACTION_UPDATE_RUNTIME_PREFS"
        const val EXTRA_VLESS_URI = "vless_uri"
        const val EXTRA_BACKUP_URIS = "backup_uris"  // JSON array of backup VLESS URIs
        const val EXTRA_DNS1 = "dns1"
        const val EXTRA_DNS2 = "dns2"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_AUTO_RECONNECT = "auto_reconnect"
        const val EXTRA_NOTIFY_USAGE = "notify_usage"
        const val EXTRA_NOTIFY_SPEED = "notify_speed"
        const val EXTRA_WAKE_LOCK = "wake_lock"
        const val EXTRA_KEEP_ALIVE = "keep_alive"
        const val EXTRA_KEEP_ALIVE_INTERVAL_SEC = "keep_alive_interval_sec"
        const val EXTRA_MAX_RECONNECT = "max_reconnect_attempts"
        const val EXTRA_NETWORK_DELAY = "network_delay"
        const val EXTRA_URLTEST_URL = "urltest_url"
        const val EXTRA_URLTEST_INTERVAL = "urltest_interval"
        const val EXTRA_URLTEST_TOLERANCE = "urltest_tolerance"
        const val EXTRA_CUSTOM_RULES = "custom_rules"
        private var maxReconnectAttempts = 3
        private var networkReconnectDelaySeconds = 2
        private const val DEFAULT_MTU = 1400

        data class UsageSnapshot(val downloadBytes: Long, val uploadBytes: Long)

        @Volatile private var usageTrackingActive = false
        @Volatile private var usageStartRxBytes = 0L
        @Volatile private var usageStartTxBytes = 0L
        @Volatile private var notifyUsageEnabled = false
        @Volatile private var notifySpeedEnabled = false
        @Volatile private var wakeLockEnabled = false
        @Volatile private var keepAliveEnabled = false
        @Volatile private var keepAliveIntervalSeconds = 45

        @Volatile
        var isRunning = false
            private set

        private val _state = MutableStateFlow(VpnState.DISCONNECTED)
        val state: StateFlow<VpnState> = _state

        @Volatile
        var isStopping = false
            private set

        private fun normalizeMtu(mtu: Int): Int = mtu.coerceIn(1280, 1500)

        private fun readUidTraffic(uid: Int): Pair<Long, Long> {
            val uidRx = TrafficStats.getUidRxBytes(uid)
            val uidTx = TrafficStats.getUidTxBytes(uid)
            return if (uidRx >= 0L && uidTx >= 0L) {
                Pair(uidRx, uidTx)
            } else {
                Pair(TrafficStats.getTotalRxBytes(), TrafficStats.getTotalTxBytes())
            }
        }

        fun markUsageSessionStart(context: Context) {
            val (rx, tx) = readUidTraffic(context.applicationInfo.uid)
            usageStartRxBytes = rx
            usageStartTxBytes = tx
            usageTrackingActive = true
        }

        fun clearUsageSession() {
            usageTrackingActive = false
            usageStartRxBytes = 0L
            usageStartTxBytes = 0L
        }

        fun getUsageSnapshot(context: Context): UsageSnapshot {
            if (!usageTrackingActive || !isRunning) return UsageSnapshot(0L, 0L)
            val (rx, tx) = readUidTraffic(context.applicationInfo.uid)
            val download = (rx - usageStartRxBytes).coerceAtLeast(0L)
            val upload = (tx - usageStartTxBytes).coerceAtLeast(0L)
            return UsageSnapshot(download, upload)
        }

        fun start(
            context: Context,
            vlessUri: String,
            backupUris: String,  // JSON array string
            dns1: String,
            dns2: String,
            mtu: Int,
            autoReconnect: Boolean,
            notifyUsageEnabled: Boolean,
            notifySpeedEnabled: Boolean,
            wakeLockEnabled: Boolean,
            keepAliveEnabled: Boolean,
            keepAliveIntervalSeconds: Int,
            maxReconnect: Int,
            reconnectDelay: Int,
            urlTestUrl: String,
            urlTestInterval: String,
            urlTestTolerance: Int,
            customRulesJson: String
        ) {
            val intent = Intent(context, JhopanVpnService::class.java).apply {
                putExtra(EXTRA_VLESS_URI, vlessUri)
                putExtra(EXTRA_BACKUP_URIS, backupUris)
                putExtra(EXTRA_DNS1, dns1)
                putExtra(EXTRA_DNS2, dns2)
                putExtra(EXTRA_MTU, normalizeMtu(mtu))
                putExtra(EXTRA_AUTO_RECONNECT, autoReconnect)
                putExtra(EXTRA_NOTIFY_USAGE, notifyUsageEnabled)
                putExtra(EXTRA_NOTIFY_SPEED, notifySpeedEnabled)
                putExtra(EXTRA_WAKE_LOCK, wakeLockEnabled)
                putExtra(EXTRA_KEEP_ALIVE, keepAliveEnabled)
                putExtra(EXTRA_KEEP_ALIVE_INTERVAL_SEC, keepAliveIntervalSeconds)
                putExtra(EXTRA_MAX_RECONNECT, maxReconnect)
                putExtra(EXTRA_NETWORK_DELAY, reconnectDelay)
                putExtra(EXTRA_URLTEST_URL, urlTestUrl)
                putExtra(EXTRA_URLTEST_INTERVAL, urlTestInterval)
                putExtra(EXTRA_URLTEST_TOLERANCE, urlTestTolerance)
                putExtra(EXTRA_CUSTOM_RULES, customRulesJson)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateNotificationSettings(context: Context, notifyUsage: Boolean, notifySpeed: Boolean) {
            notifyUsageEnabled = notifyUsage
            notifySpeedEnabled = notifySpeed
            if (!isRunning) return
            val intent = Intent(context, JhopanVpnService::class.java).apply {
                action = ACTION_UPDATE_NOTIFICATION_PREFS
                putExtra(EXTRA_NOTIFY_USAGE, notifyUsage)
                putExtra(EXTRA_NOTIFY_SPEED, notifySpeed)
            }
            context.startService(intent)
        }

        fun updateRuntimeSettings(context: Context, wakeLock: Boolean, keepAlive: Boolean, keepAliveIntervalSec: Int, maxReconnect: Int, reconnectDelay: Int) {
            wakeLockEnabled = wakeLock
            keepAliveEnabled = keepAlive
            keepAliveIntervalSeconds = keepAliveIntervalSec.coerceIn(20, 300)
            maxReconnectAttempts = maxReconnect
            networkReconnectDelaySeconds = reconnectDelay.coerceIn(1, 60)
            if (!isRunning) return
            val intent = Intent(context, JhopanVpnService::class.java).apply {
                action = ACTION_UPDATE_RUNTIME_PREFS
                putExtra(EXTRA_WAKE_LOCK, wakeLock)
                putExtra(EXTRA_KEEP_ALIVE, keepAlive)
                putExtra(EXTRA_KEEP_ALIVE_INTERVAL_SEC, keepAliveIntervalSeconds)
                putExtra(EXTRA_MAX_RECONNECT, maxReconnect)
                putExtra(EXTRA_NETWORK_DELAY, networkReconnectDelaySeconds)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, JhopanVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var commandServer: CommandServer? = null
    private var boxService: BoxService? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var reconnectWakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var contentPendingIntent: PendingIntent? = null
    private var stopPendingIntent: PendingIntent? = null
    private var notificationBuilder: Notification.Builder? = null
    private var notificationStatsJob: Job? = null
    private var keepAliveJob: Job? = null
    private var stableWakeLockRefreshJob: Job? = null
    private var stableWakeLock: PowerManager.WakeLock? = null
    private var baseNotificationText = "Disconnected"
    private var lastSpeedSampleDownloadBytes = 0L
    private var lastSpeedSampleUploadBytes = 0L
    private var lastSpeedSampleTimeMs = 0L

    // State for auto-reconnect
    private var lastVlessUri: String? = null
    private var lastBackupUris: String = "[]"
    private var lastDns1: String = "8.8.8.8"
    private var lastDns2: String = "8.8.4.4"
    private var lastMtu: Int = DEFAULT_MTU
    private var lastUrlTestUrl: String = "https://www.gstatic.com/generate_204"
    private var lastUrlTestInterval: String = "30s"
    private var lastUrlTestTolerance: Int = 100
    private var lastCustomRulesJson: String = "[]"
    private var autoReconnect = false
    private var reconnectAttempts = 0

    // ── PlatformInterface ──────────────────────────────────────────
    // sing-box calls these when it needs platform-specific operations

    private val platformInterface = object : PlatformInterface {
        override fun autoDetectInterfaceControl(fd: Int) {
            // Protect socket from VPN routing loop
            val ok = protect(fd)
            if (!ok) Log.w(TAG, "Failed to protect fd=$fd")
        }

        override fun openTun(options: TunOptions): Int {
            // Build VPN TUN interface using Android VpnService.Builder
            val builder = Builder()
                .setSession("JhopanStoreVPN")
                .setMtu(options.mtu)

            // Add addresses from sing-box config
            try {
                val inet4Addr = options.inet4Address
                if (inet4Addr != null && inet4Addr.hasNext()) {
                    while (inet4Addr.hasNext()) {
                        val prefix = inet4Addr.next()
                        builder.addAddress(prefix.address(), prefix.prefix())
                    }
                } else {
                    builder.addAddress("172.19.0.1", 30)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Using default TUN address: ${e.message}")
                builder.addAddress("172.19.0.1", 30)
            }

            // Add routes
            builder.addRoute("0.0.0.0", 0)

            // Add DNS servers
            builder.addDnsServer(lastDns1.ifBlank { "8.8.8.8" })
            builder.addDnsServer(lastDns2.ifBlank { "8.8.4.4" })

            // Exclude our own app to prevent VPN routing loops
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {}

            tunFd = builder.establish()
            if (tunFd == null) {
                Log.e(TAG, "Failed to establish TUN interface")
                return -1
            }

            val fd = tunFd!!.fd
            Log.i(TAG, "TUN established, fd=$fd")
            return fd
        }

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
        override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int): Int = 0
        override fun packageNameByUid(uid: Int): String = ""
        override fun uidByPackageName(packageName: String): Int = 0
        override fun getInterfaces(): io.github.sagernet.libbox.NetworkInterfaceIterator? = null
        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun readWIFIState(): io.github.sagernet.libbox.WIFIState? = null
        override fun clearDNSCache() {}
        override fun useProcFS(): Boolean = false
        override fun writeLog(message: String) { Log.d(TAG, message) }
        override fun sendNotification(notification: io.github.sagernet.libbox.Notification) {}
        override fun startDefaultInterfaceMonitor(listener: io.github.sagernet.libbox.InterfaceUpdateListener) {}
        override fun closeDefaultInterfaceMonitor(listener: io.github.sagernet.libbox.InterfaceUpdateListener) {}
    }

    // ── CommandServerHandler ──────────────────────────────────────
    // Lifecycle callbacks from sing-box

    private val serverHandler = object : CommandServerHandler {
        override fun serviceReload() {
            Log.i(TAG, "sing-box service reloading")
        }

        override fun postServiceClose() {
            Log.i(TAG, "sing-box service closed")
            SingboxManager.markStopped()
            if (!isStopping) {
                // Unexpected stop
                Log.w(TAG, "sing-box stopped unexpectedly")
                SingboxManager.onServiceStopped?.invoke()
            }
        }

        override fun getSystemProxyStatus(): io.github.sagernet.libbox.SystemProxyStatus? = null
        override fun setSystemProxyEnabled(enabled: Boolean) {}
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize libbox
        try {
            val options = SetupOptions()
            options.basePath = filesDir.absolutePath
            Libbox.setup(options)
            Log.i(TAG, "libbox setup complete, version=${Libbox.version()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup libbox", e)
        }

        // Create CommandServer
        try {
            commandServer = CommandServer(serverHandler, 31337)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create CommandServer", e)
        }

        // Monitor network changes
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_RUNTIME_PREFS) {
            wakeLockEnabled = intent.getBooleanExtra(EXTRA_WAKE_LOCK, wakeLockEnabled)
            keepAliveEnabled = intent.getBooleanExtra(EXTRA_KEEP_ALIVE, keepAliveEnabled)
            keepAliveIntervalSeconds = intent.getIntExtra(EXTRA_KEEP_ALIVE_INTERVAL_SEC, keepAliveIntervalSeconds).coerceIn(20, 300)
            maxReconnectAttempts = intent.getIntExtra(EXTRA_MAX_RECONNECT, maxReconnectAttempts)
            networkReconnectDelaySeconds = intent.getIntExtra(EXTRA_NETWORK_DELAY, networkReconnectDelaySeconds)
            if (isRunning) {
                keepAliveJob?.cancel()
                keepAliveJob = null
                applyStableWakeLockMode()
                applyKeepAliveMode()
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_UPDATE_NOTIFICATION_PREFS) {
            notifyUsageEnabled = intent.getBooleanExtra(EXTRA_NOTIFY_USAGE, notifyUsageEnabled)
            notifySpeedEnabled = intent.getBooleanExtra(EXTRA_NOTIFY_SPEED, notifySpeedEnabled)
            if (isRunning) {
                if (notifyUsageEnabled || notifySpeedEnabled) {
                    ensureNotificationStatsLoop()
                } else {
                    notificationStatsJob?.cancel()
                    notificationStatsJob = null
                }
                updateNotification(baseNotificationText)
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            isStopping = true
            autoReconnect = false
            disconnect()
            stopSelf()
            return START_NOT_STICKY
        }

        val vlessUri = intent?.getStringExtra(EXTRA_VLESS_URI)
        if (vlessUri.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val backupUris = intent.getStringExtra(EXTRA_BACKUP_URIS) ?: "[]"
        val dns1 = intent.getStringExtra(EXTRA_DNS1) ?: "8.8.8.8"
        val dns2 = intent.getStringExtra(EXTRA_DNS2) ?: "8.8.4.4"
        val mtu = intent.getIntExtra(EXTRA_MTU, DEFAULT_MTU).coerceIn(1280, 1500)
        autoReconnect = intent.getBooleanExtra(EXTRA_AUTO_RECONNECT, false)
        notifyUsageEnabled = intent.getBooleanExtra(EXTRA_NOTIFY_USAGE, notifyUsageEnabled)
        notifySpeedEnabled = intent.getBooleanExtra(EXTRA_NOTIFY_SPEED, notifySpeedEnabled)
        wakeLockEnabled = intent.getBooleanExtra(EXTRA_WAKE_LOCK, wakeLockEnabled)
        keepAliveEnabled = intent.getBooleanExtra(EXTRA_KEEP_ALIVE, keepAliveEnabled)
        keepAliveIntervalSeconds = intent.getIntExtra(EXTRA_KEEP_ALIVE_INTERVAL_SEC, keepAliveIntervalSeconds).coerceIn(20, 300)
        maxReconnectAttempts = intent.getIntExtra(EXTRA_MAX_RECONNECT, maxReconnectAttempts)
        networkReconnectDelaySeconds = intent.getIntExtra(EXTRA_NETWORK_DELAY, networkReconnectDelaySeconds)
        val urlTestUrl = intent.getStringExtra(EXTRA_URLTEST_URL) ?: "https://www.gstatic.com/generate_204"
        val urlTestInterval = intent.getStringExtra(EXTRA_URLTEST_INTERVAL) ?: "30s"
        val urlTestTolerance = intent.getIntExtra(EXTRA_URLTEST_TOLERANCE, 100)
        val customRulesJson = intent.getStringExtra(EXTRA_CUSTOM_RULES) ?: "[]"

        isStopping = false
        _state.value = VpnState.CONNECTING

        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

        serviceScope.launch {
            connect(vlessUri, backupUris, dns1, dns2, mtu, urlTestUrl, urlTestInterval, urlTestTolerance, customRulesJson)
        }

        return START_STICKY
    }

    private suspend fun connect(
        vlessUri: String,
        backupUris: String,
        dns1: String,
        dns2: String,
        mtu: Int,
        urlTestUrl: String,
        urlTestInterval: String,
        urlTestTolerance: Int,
        customRulesJson: String
    ) {
        try {
            // Save for reconnection
            lastVlessUri = vlessUri
            lastBackupUris = backupUris
            lastDns1 = dns1
            lastDns2 = dns2
            lastMtu = mtu
            lastUrlTestUrl = urlTestUrl
            lastUrlTestInterval = urlTestInterval
            lastUrlTestTolerance = urlTestTolerance
            lastCustomRulesJson = customRulesJson
            reconnectAttempts = 0

            if (isStopping) return

            // Parse main account
            val mainCfg = VlessParser.parse(vlessUri).getOrElse {
                Log.e(TAG, "Failed to parse main VLESS URI", it)
                _state.value = VpnState.FAILED
                updateNotification("Parse error")
                stopSelf()
                return
            }

            // Parse backup accounts
            val allAccounts = mutableListOf(mainCfg)
            try {
                val backupArray = org.json.JSONArray(backupUris)
                for (i in 0 until backupArray.length()) {
                    val uri = backupArray.getString(i)
                    VlessParser.parse(uri).getOrNull()?.let { allAccounts.add(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse backup URIs: ${e.message}")
            }

            Log.i(TAG, "Accounts: ${allAccounts.size} total (1 main + ${allAccounts.size - 1} backup)")

            // Pre-resolve proxy server domains BEFORE VPN TUN is up
            val resolvedIps = mutableMapOf<String, String>()
            allAccounts.forEach { cfg ->
                SingboxManager.resolveDomain(cfg.address)?.let {
                    resolvedIps[cfg.address] = it
                    Log.i(TAG, "Resolved: ${cfg.address} → $it")
                }
            }

            // Set up auto-reconnect callback
            SingboxManager.onServiceStopped = {
                if (autoReconnect && lastVlessUri != null) {
                    serviceScope.launch {
                        val pm = getSystemService(POWER_SERVICE) as PowerManager
                        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jhopanvpn:reconnect")
                        wl.acquire(60_000L)
                        reconnectWakeLock = wl
                        try {
                            var success = false
                            while (autoReconnect && reconnectAttempts < maxReconnectAttempts && !success) {
                                reconnectAttempts++
                                val waitMs = maxOf(
                                    networkReconnectDelaySeconds * 1000L,
                                    minOf(3000L shl (reconnectAttempts - 1), 60_000L)
                                )
                                Log.w(TAG, "Reconnecting in ${waitMs}ms (attempt $reconnectAttempts)")
                                _state.value = VpnState.CONNECTING
                                updateNotification("Reconnecting ($reconnectAttempts)...")
                                delay(waitMs)

                                // Close old TUN
                                try { tunFd?.close() } catch (_: Exception) {}
                                tunFd = null

                                // Rebuild config and restart service
                                val uri = lastVlessUri ?: break
                                val reCfg = VlessParser.parse(uri).getOrNull() ?: break
                                val reAccounts = mutableListOf(reCfg)
                                try {
                                    val arr = org.json.JSONArray(lastBackupUris)
                                    for (i in 0 until arr.length()) {
                                        VlessParser.parse(arr.getString(i)).getOrNull()?.let { reAccounts.add(it) }
                                    }
                                } catch (_: Exception) {}

                                val reResolved = mutableMapOf<String, String>()
                                reAccounts.forEach { c ->
                                    SingboxManager.resolveDomain(c.address)?.let { reResolved[c.address] = it }
                                }

                                val customRulesList = mutableListOf<Map<String, String>>()
                                try {
                                    val rulesArr = org.json.JSONArray(lastCustomRulesJson)
                                    for (i in 0 until rulesArr.length()) {
                                        val obj = rulesArr.getJSONObject(i)
                                        customRulesList.add(
                                            mapOf(
                                                "name" to obj.optString("name", ""),
                                                "fileName" to obj.optString("fileName", "${obj.optString("name", "")}.json"),
                                                "targetOutbound" to obj.optString("targetOutbound", "direct")
                                            )
                                        )
                                    }
                                } catch (_: Exception) {}

                                val configJson = SingboxManager.buildConfig(
                                    reAccounts, filesDir.absolutePath, lastDns1, lastDns2, reResolved,
                                    lastUrlTestUrl, lastUrlTestInterval, lastUrlTestTolerance, lastMtu,
                                    customRulesList
                                )

                                try {
                                    boxService?.close()
                                    boxService = Libbox.newService(configJson, platformInterface)
                                    boxService?.start()
                                    commandServer?.setService(boxService)
                                    commandServer?.start()
                                    
                                    delay(2000)  // Give sing-box time to start
                                    SingboxManager.markRunning()
                                    success = true
                                } catch (e: Exception) {
                                    Log.e(TAG, "Reconnect failed: ${e.message}")
                                }
                            }

                            if (success) {
                                reconnectAttempts = 0
                                isRunning = true
                                _state.value = VpnState.CONNECTED
                                updateNotification("Connected")
                                ensureNotificationStatsLoop()
                                applyStableWakeLockMode()
                                applyKeepAliveMode()
                            } else {
                                Log.e(TAG, "Auto-reconnect exhausted")
                                disconnect()
                                stopSelf()
                            }
                        } finally {
                            wl.release()
                            reconnectWakeLock = null
                        }
                    }
                } else {
                    Log.e(TAG, "Auto-reconnect disabled or no URI")
                    disconnect()
                    stopSelf()
                }
            }

            // Build sing-box config
            if (isStopping) return
            val customRulesList = mutableListOf<Map<String, String>>()
            try {
                val rulesArr = org.json.JSONArray(customRulesJson)
                for (i in 0 until rulesArr.length()) {
                    val obj = rulesArr.getJSONObject(i)
                    customRulesList.add(
                        mapOf(
                            "name" to obj.optString("name", ""),
                            "fileName" to obj.optString("fileName", "${obj.optString("name", "")}.json"),
                            "targetOutbound" to obj.optString("targetOutbound", "direct")
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse custom rules: ${e.message}")
            }

            Log.i(TAG, "Configuring sing-box with ${allAccounts.size} account(s)")
            val configJson = SingboxManager.buildConfig(
                allAccounts, filesDir.absolutePath, dns1, dns2, resolvedIps,
                urlTestUrl, urlTestInterval, urlTestTolerance, mtu,
                customRulesList
            )

            // Validate config
            try {
                Libbox.checkConfig(configJson)
                Log.i(TAG, "Config validation passed")
            } catch (e: Exception) {
                Log.e(TAG, "Config validation failed: ${e.message}")
                _state.value = VpnState.FAILED
                updateNotification("Config error")
                stopSelf()
                return
            }

            // Start sing-box service
            // This will trigger PlatformInterface.openTun() which creates the TUN
            if (isStopping) return
            updateNotification("Starting sing-box...")

            var started = false
            while (!started && !isStopping) {
                try {
                    // Clean up previous attempt
                    try { boxService?.close() } catch (_: Exception) {}
                    boxService = null
                    try { commandServer?.close() } catch (_: Exception) {}

                    // Re-create CommandServer for clean state
                    commandServer = CommandServer(serverHandler, 31337)

                    boxService = Libbox.newService(configJson, platformInterface)
                    boxService?.start()
                    commandServer?.setService(boxService)
                    commandServer?.start()
                    
                    SingboxManager.markRunning()
                    started = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start sing-box: ${e.message}")
                    // Clean up failed attempt
                    try { boxService?.close() } catch (_: Exception) {}
                    boxService = null
                    try { commandServer?.close() } catch (_: Exception) {}
                    commandServer = null

                    if (autoReconnect && reconnectAttempts < maxReconnectAttempts) {
                        reconnectAttempts++
                        val waitMs = maxOf(
                            networkReconnectDelaySeconds * 1000L,
                            minOf(3000L shl (reconnectAttempts - 1), 60_000L)
                        )
                        Log.w(TAG, "Retrying start in ${waitMs}ms (attempt $reconnectAttempts)")
                        updateNotification("Retry ($reconnectAttempts)...")
                        delay(waitMs)
                    } else {
                        _state.value = VpnState.FAILED
                        updateNotification("Start failed")
                        stopSelf()
                        return
                    }
                }
            }
            if (isStopping) { boxService?.close(); commandServer?.close(); return }

            // Wait briefly for service to stabilize
            delay(1500)

            if (isStopping) {
                disconnect()
                return
            }

            if (!usageTrackingActive) {
                markUsageSessionStart(this@JhopanVpnService)
            }
            lastSpeedSampleDownloadBytes = 0L
            lastSpeedSampleUploadBytes = 0L
            lastSpeedSampleTimeMs = System.currentTimeMillis()

            isRunning = true
            reconnectAttempts = 0
            _state.value = VpnState.CONNECTED
            Log.i(TAG, "VPN connected successfully (sing-box)")
            updateNotification("Connected")
            ensureNotificationStatsLoop()
            applyStableWakeLockMode()
            applyKeepAliveMode()

        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
            if (!isStopping) _state.value = VpnState.FAILED
            disconnect()
            stopSelf()
        }
    }

    private fun disconnect() {
        isRunning = false
        SingboxManager.markStopped()
        clearUsageSession()
        notificationStatsJob?.cancel()
        notificationStatsJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        stableWakeLockRefreshJob?.cancel()
        stableWakeLockRefreshJob = null
        stableWakeLock?.let { if (it.isHeld) it.release() }
        stableWakeLock = null
        SingboxManager.onServiceStopped = null

        reconnectWakeLock?.let { if (it.isHeld) it.release() }
        reconnectWakeLock = null

        // Stop sing-box service cleanly
        try { boxService?.close() } catch (_: Exception) {}
        boxService = null
        try { commandServer?.close() } catch (_: Exception) {}
        commandServer = null

        // Close TUN
        try {
            tunFd?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing TUN", e)
        }
        tunFd = null

        _state.value = VpnState.DISCONNECTED
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "VPN disconnected")
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        disconnect()
        try {
            commandServer?.close()
        } catch (_: Exception) {}
        commandServer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        autoReconnect = false
        disconnect()
        stopSelf()
        super.onRevoke()
    }

    // --- Network callback ---

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val uri = lastVlessUri ?: return
                if (!isStopping && autoReconnect) {
                    val current = _state.value
                    if (current == VpnState.DISCONNECTED || current == VpnState.FAILED) {
                        Log.i(TAG, "Network restored (state=$current) — triggering reconnect")
                        _state.value = VpnState.CONNECTING
                        updateNotification("Reconnecting...")
                        serviceScope.launch {
                            delay(networkReconnectDelaySeconds * 1000L)
                            connect(uri, lastBackupUris, lastDns1, lastDns2, lastMtu,
                                lastUrlTestUrl, lastUrlTestInterval, lastUrlTestTolerance, lastCustomRulesJson)
                        }
                    }
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.i(TAG, "NetworkCallback registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register NetworkCallback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            Log.i(TAG, "NetworkCallback unregistered")
        } catch (_: Exception) {}
    }

    // --- Notification helpers ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JhopanStoreVPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private data class NotificationPayload(
        val title: String,
        val contentText: String,
        val subText: String?
    )

    private fun buildNotification(text: String): Notification {
        if (contentPendingIntent == null) {
            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_FROM_NOTIFICATION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_FROM_NOTIFICATION, true)
            }
            contentPendingIntent = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        if (stopPendingIntent == null) {
            stopPendingIntent = PendingIntent.getService(
                this, 1,
                Intent(this, JhopanVpnService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        if (notificationBuilder == null) {
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_HIGH)
            }

            notificationBuilder = builder
                .setContentTitle("JhopanStoreVPN")
                .setSmallIcon(R.drawable.ic_vpn_key)
                .setContentIntent(contentPendingIntent)
                .addAction(
                    Notification.Action.Builder(
                        null, "Disconnect", stopPendingIntent
                    ).build()
                )
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setAutoCancel(false)
        }

        val payload = buildNotificationPayload(text)

        return notificationBuilder!!
            .setContentTitle(payload.title)
            .setContentText(payload.contentText)
            .setSubText(payload.subText)
            .build()
    }

    private fun updateNotification(text: String) {
        baseNotificationText = text
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun ensureNotificationStatsLoop() {
        if (!isRunning || (!notifyUsageEnabled && !notifySpeedEnabled)) return
        if (notificationStatsJob != null) return
        notificationStatsJob = serviceScope.launch {
            while (isRunning && (notifyUsageEnabled || notifySpeedEnabled)) {
                updateNotification(baseNotificationText)
                delay(2000)
            }
            notificationStatsJob = null
        }
    }

    private fun applyStableWakeLockMode() {
        if (!isRunning || !wakeLockEnabled) {
            stableWakeLockRefreshJob?.cancel()
            stableWakeLockRefreshJob = null
            stableWakeLock?.let { if (it.isHeld) it.release() }
            stableWakeLock = null
            return
        }

        if (stableWakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            stableWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jhopanvpn:stable")
        }

        if (stableWakeLockRefreshJob != null) return

        stableWakeLockRefreshJob = serviceScope.launch {
            while (isRunning && wakeLockEnabled) {
                try {
                    val wl = stableWakeLock ?: break
                    if (!wl.isHeld) {
                        wl.acquire(10 * 60 * 1000L)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed acquiring stable wakelock: ${e.message}")
                }
                delay(5 * 60 * 1000L)
            }
            stableWakeLockRefreshJob = null
        }
    }

    private fun applyKeepAliveMode() {
        if (!isRunning || !keepAliveEnabled) {
            keepAliveJob?.cancel()
            keepAliveJob = null
            return
        }

        if (keepAliveJob != null) return

        keepAliveJob = serviceScope.launch {
            val intervalMs = (keepAliveIntervalSeconds.coerceIn(20, 300) * 1000L)
            while (isRunning && keepAliveEnabled) {
                try {
                    // Lightweight probe to the mixed inbound
                    java.net.Socket("127.0.0.1", SingboxManager.SOCKS_PORT).close()
                } catch (_: Exception) {}
                delay(intervalMs)
            }
            keepAliveJob = null
        }
    }

    private fun buildNotificationPayload(baseText: String): NotificationPayload {
        if (!isRunning) {
            return NotificationPayload(
                title = "JhopanStoreVPN",
                contentText = baseText,
                subText = null
            )
        }

        val isConnectedState = baseText.equals("Connected", ignoreCase = true)
        val title = if (isConnectedState) "Connected" else baseText

        if (!notifyUsageEnabled && !notifySpeedEnabled) {
            return NotificationPayload(
                title = title,
                contentText = if (isConnectedState) "VPN active" else baseText,
                subText = null
            )
        }

        val snapshot = getUsageSnapshot(this)
        val usageLine = "DL ${formatBytes(snapshot.downloadBytes)} | UL ${formatBytes(snapshot.uploadBytes)}"

        var speedLine: String? = null
        if (notifySpeedEnabled) {
            val now = System.currentTimeMillis()
            val elapsedMs = (now - lastSpeedSampleTimeMs).coerceAtLeast(1L)
            val downDelta = (snapshot.downloadBytes - lastSpeedSampleDownloadBytes).coerceAtLeast(0L)
            val upDelta = (snapshot.uploadBytes - lastSpeedSampleUploadBytes).coerceAtLeast(0L)
            val downPerSec = downDelta * 1000L / elapsedMs
            val upPerSec = upDelta * 1000L / elapsedMs
            speedLine = "${formatBytes(downPerSec)}/s ↓ ${formatBytes(upPerSec)}/s ↑"

            lastSpeedSampleDownloadBytes = snapshot.downloadBytes
            lastSpeedSampleUploadBytes = snapshot.uploadBytes
            lastSpeedSampleTimeMs = now
        }

        val contentLine = speedLine ?: if (notifyUsageEnabled) usageLine else "VPN active"
        val subLine = if (notifySpeedEnabled && notifyUsageEnabled) usageLine else null

        return NotificationPayload(
            title = title,
            contentText = contentLine,
            subText = subLine
        )
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024L) return "$value B"
        if (value < 1024L * 1024L) return String.format("%.1f KB", value / 1024.0)
        if (value < 1024L * 1024L * 1024L) return String.format("%.1f MB", value / (1024.0 * 1024.0))
        return String.format("%.2f GB", value / (1024.0 * 1024.0 * 1024.0))
    }
}
