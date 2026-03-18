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
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.jhopanstore.vpn.MainActivity
import com.jhopanstore.vpn.R
import com.jhopanstore.vpn.core.Tun2socksManager
import com.jhopanstore.vpn.core.VlessConfig
import com.jhopanstore.vpn.core.XrayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import libXray.LibXray
import java.io.IOException

/**
 * Android VpnService that creates a TUN interface and routes traffic
 * through Xray (via libXray in-process) + tun2socks bridge.
 *
 * Traffic flow:
 *   Apps → TUN fd → tun2socks → SOCKS5 → Xray (libXray) → internet
 *
 * Key improvement with libXray:
 *   - registerDialerController(protectFd) ensures Xray's outgoing sockets
 *     are protected from VPN routing (prevents routing loops)
 *   - No xray binary process to manage
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
        private var maxReconnectAttempts = 3
        private var networkReconnectDelaySeconds = 2
        private const val DEFAULT_MTU = 1400

        data class UsageSnapshot(val downloadBytes: Long, val uploadBytes: Long)

        @Volatile
        private var usageTrackingActive = false

        @Volatile
        private var usageStartRxBytes = 0L

        @Volatile
        private var usageStartTxBytes = 0L

        @Volatile
        private var notifyUsageEnabled = false

        @Volatile
        private var notifySpeedEnabled = false

        @Volatile
        private var wakeLockEnabled = false

        @Volatile
        private var keepAliveEnabled = false

        @Volatile
        private var keepAliveIntervalSeconds = 45

        @Volatile
        var isRunning = false
            private set

        // StateFlow untuk UI — ViewModel collect ini, tidak perlu polling
        private val _state = MutableStateFlow(VpnState.DISCONNECTED)
        val state: StateFlow<VpnState> = _state

        // Flag untuk cancel background thread saat stop dipanggil
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

        fun start(context: Context, 
            vlessUri: String, 
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
            reconnectDelay: Int
        )  {
            val intent = Intent(context, JhopanVpnService::class.java).apply {
                putExtra(EXTRA_VLESS_URI, vlessUri)
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
    private var lastDns1: String = "8.8.8.8"
    private var lastDns2: String = "8.8.4.4"
    private var lastMtu: Int = DEFAULT_MTU
    private var autoReconnect = false
    private var reconnectAttempts = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Register VPN socket protection callback with libXray.
        // When Xray creates outgoing connections, protectFd() is called
        // so those sockets bypass the VPN TUN → prevents routing loops.
        LibXray.registerDialerController(object : libXray.DialerController {
            override fun protectFd(fd: Long): Boolean {
                val protected_ = protect(fd.toInt())
                if (!protected_) {
                    Log.w(TAG, "Failed to protect fd=$fd")
                }
                return protected_
            }
        })
        Log.i(TAG, "Registered DialerController for socket protection")

        // Monitor network changes — reconnect when network comes back after loss
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

        // Reset flag setiap kali ada koneksi baru
        isStopping = false
        _state.value = VpnState.CONNECTING

        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

        serviceScope.launch {
            connect(vlessUri, dns1, dns2, mtu)
        }

        return START_STICKY
    }

    private suspend fun connect(vlessUri: String, dns1: String, dns2: String, mtu: Int) {
        try {
            // Save for reconnection
            lastVlessUri = vlessUri
            lastDns1 = dns1
            lastDns2 = dns2
            lastMtu = mtu
            reconnectAttempts = 0

            if (isStopping) return

            val parseResult = com.jhopanstore.vpn.core.VlessParser.parse(vlessUri)
            val cfg = parseResult.getOrElse {
                Log.e(TAG, "Failed to parse VLESS URI", it)
                _state.value = VpnState.FAILED
                updateNotification("Parse error")
                stopSelf()
                return
            }

            // Pre-resolve proxy server domain to IP BEFORE VPN TUN is established
            val resolvedIp = XrayManager.resolveDomain(cfg.address)
            Log.i(TAG, "Proxy server: ${cfg.address} -> ${resolvedIp ?: "unresolved (using domain)"}")

            // Set up death callback for auto-reconnect (iteratif, bukan rekursif)
            XrayManager.onProcessDied = {
                if (autoReconnect && lastVlessUri != null) {
                    serviceScope.launch {
                        // Cegah CPU sleep saat proses reconnect di Doze mode
                        val pm = getSystemService(POWER_SERVICE) as PowerManager
                        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jhopanvpn:reconnect")
                        wl.acquire(60_000L) // max 60 detik
                        reconnectWakeLock = wl
                        try {
                            var success = false
                            while (autoReconnect && reconnectAttempts < maxReconnectAttempts && !success) {
                                reconnectAttempts++
                                val waitMs = maxOf(
                                    networkReconnectDelaySeconds * 1000L,
                                    minOf(3000L shl (reconnectAttempts - 1), 60_000L)
                                )
                                Log.w(TAG, "Xray died, reconnecting in ${waitMs}ms (attempt $reconnectAttempts)")
                                updateNotification("Reconnecting ($reconnectAttempts)...")
                                delay(waitMs)

                                // Bersihkan state lama
                                Tun2socksManager.stop()
                                try { tunFd?.close() } catch (_: Exception) {}
                                tunFd = null

                                // Restart Xray
                                val uri = lastVlessUri ?: break
                                val parsedCfg = com.jhopanstore.vpn.core.VlessParser.parse(uri).getOrNull() ?: break
                                val reconnectResolvedIp = XrayManager.resolveDomain(parsedCfg.address)
                                val xrayStarted = XrayManager.start(this@JhopanVpnService, parsedCfg, lastDns1, lastDns2, reconnectResolvedIp)
                                if (!xrayStarted) continue

                                // Probe SOCKS5 port — proceed as soon as ready, max 5s
                                var portUp = false
                                for (probe in 0 until 20) {
                                    try {
                                        java.net.Socket("127.0.0.1", XrayManager.SOCKS_PORT).close()
                                        portUp = true
                                    } catch (_: Exception) {}
                                    if (portUp) break
                                    delay(250)
                                }
                                if (!portUp) { XrayManager.stop(); continue }

                                // Re-establish TUN
                                val builder = Builder()
                                    .setSession("JhopanStoreVPN")
                                    .addAddress("10.0.0.2", 24)
                                    .addRoute("0.0.0.0", 0)
                                    .addDnsServer(lastDns1.ifBlank { "8.8.8.8" })
                                    .addDnsServer(lastDns2.ifBlank { "8.8.4.4" })
                                    .setMtu(lastMtu)
                                builder.addDisallowedApplication(packageName)
                                tunFd = builder.establish()
                                if (tunFd == null) { XrayManager.stop(); continue }

                                val tun2socksOk = Tun2socksManager.start(this@JhopanVpnService, tunFd!!.fd)
                                if (!tun2socksOk) { XrayManager.stop(); tunFd?.close(); tunFd = null; continue }

                                success = true
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

            // Start Xray core via libXray (in-process) — iterative retry, no stack accumulation
            if (isStopping) return

            var started = false
            while (!started && !isStopping) {
                started = XrayManager.start(this@JhopanVpnService, cfg, dns1, dns2, resolvedIp)
                if (!started) {
                    if (autoReconnect && reconnectAttempts < maxReconnectAttempts) {
                        reconnectAttempts++
                        val waitMs = maxOf(
                            networkReconnectDelaySeconds * 1000L,
                            minOf(3000L shl (reconnectAttempts - 1), 60_000L)
                        )
                        Log.w(TAG, "Retrying Xray start in ${waitMs}ms (attempt $reconnectAttempts)")
                        updateNotification("Retry ($reconnectAttempts)...")
                        delay(waitMs)
                    } else {
                        Log.e(TAG, "Failed to start Xray — giving up")
                        _state.value = VpnState.FAILED
                        updateNotification("Xray start failed")
                        stopSelf()
                        return
                    }
                }
            }
            if (isStopping) { XrayManager.stop(); return }

            // Probe SOCKS5 port until ready — max 10s (typically ready in 200-500ms)
            updateNotification("Waiting for Xray...")
            var portReady = false
            for (probe in 0 until 40) {
                if (isStopping) { XrayManager.stop(); return }
                try {
                    java.net.Socket("127.0.0.1", XrayManager.SOCKS_PORT).close()
                    portReady = true
                } catch (_: Exception) {}
                if (portReady) break
                delay(250)
            }
            if (!portReady) {
                Log.e(TAG, "Xray SOCKS5 port not ready after 10s")
                XrayManager.stop()
                _state.value = VpnState.FAILED
                updateNotification("Xray timeout")
                stopSelf()
                return
            }

            // Establish TUN interface
            val builder = Builder()
                .setSession("JhopanStoreVPN")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(dns1.ifBlank { "8.8.8.8" })
                .addDnsServer(dns2.ifBlank { "8.8.4.4" })
                .setMtu(mtu)

            // Exclude our own app as defense-in-depth (protectFd handles this too)
            builder.addDisallowedApplication(packageName)

            if (isStopping) {
                XrayManager.stop()
                return
            }

            tunFd = builder.establish()
            if (tunFd == null) {
                Log.e(TAG, "Failed to establish TUN interface")
                XrayManager.stop()
                _state.value = VpnState.FAILED
                updateNotification("TUN failed")
                stopSelf()
                return
            }

            val tunFdNum = tunFd!!.fd
            Log.d(TAG, "TUN fd number: $tunFdNum")

            // Clear O_CLOEXEC flag so tun2socks child process can inherit the fd
            try {
                val fileDescriptor = tunFd!!.fileDescriptor
                val flags = Os.fcntlInt(fileDescriptor, OsConstants.F_GETFD, 0)
                Os.fcntlInt(fileDescriptor, OsConstants.F_SETFD, flags and OsConstants.FD_CLOEXEC.inv())
                Log.d(TAG, "Cleared O_CLOEXEC on TUN fd")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear O_CLOEXEC: ${e.message}")
            }

            // Start tun2socks to bridge TUN ↔ Xray SOCKS5 proxy
            val tun2socksStarted = Tun2socksManager.start(this@JhopanVpnService, tunFdNum)
            if (!tun2socksStarted) {
                Log.e(TAG, "Failed to start tun2socks")
                XrayManager.stop()
                tunFd?.close()
                tunFd = null
                _state.value = VpnState.FAILED
                updateNotification("tun2socks failed")
                stopSelf()
                return
            }

            // Cek sekali lagi: kalau isStopping, jangan set CONNECTED
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
            Log.i(TAG, "VPN connected successfully (libXray + tun2socks)")
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
        clearUsageSession()
        notificationStatsJob?.cancel()
        notificationStatsJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        stableWakeLockRefreshJob?.cancel()
        stableWakeLockRefreshJob = null
        stableWakeLock?.let { if (it.isHeld) it.release() }
        stableWakeLock = null
        XrayManager.onProcessDied = null

        // Release WakeLock jika masih aktif dari proses reconnect
        reconnectWakeLock?.let { if (it.isHeld) it.release() }
        reconnectWakeLock = null

        // Stop in reverse order: tun2socks first, then TUN, then Xray
        Tun2socksManager.stop()
        XrayManager.stop()

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

    /**
     * Register a NetworkCallback that triggers reconnect when network is restored
     * while the VPN is in DISCONNECTED or FAILED state.
     *
     * Scenario: user is connected → network drops → Xray dies → state goes FAILED/DISCONNECTED
     * → network comes back → this callback fires → VPN reconnects automatically.
     *
     * We intentionally do NOT reconnect if state is CONNECTING or CONNECTED to avoid
     * racing against an in-progress connection attempt.
     */
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
                            delay(networkReconnectDelaySeconds * 1000L) // allow network to stabilize based on user setting
                            connect(uri, lastDns1, lastDns2, lastMtu)
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
                // LOW: persistent notification without sound/vibration → less battery
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
                this, 0,
                openAppIntent,
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
                        // Bound hold time to avoid accidental endless lock on edge cases.
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
                    // Lightweight local probe to keep user-space pipeline warm without heavy traffic.
                    java.net.Socket("127.0.0.1", XrayManager.SOCKS_PORT).close()
                } catch (_: Exception) {
                    // Keep-alive probe is best-effort; monitor/reconnect paths handle failures.
                }
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





