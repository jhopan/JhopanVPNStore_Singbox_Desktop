package com.jhopanstore.vpn.core

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Manages the tun2socks binary lifecycle on Android.
 *
 * tun2socks reads raw IP packets from the VPN TUN file descriptor
 * and forwards them through Xray's SOCKS5 proxy.
 *
 * Traffic flow:
 *   Other apps → TUN fd → tun2socks → SOCKS5 (127.0.0.1:10808) → Xray → internet
 *
 * Uses a JNI native helper (libspawn_helper.so) to fork+exec without
 * closing file descriptors — Android's ProcessBuilder closes all fds
 * except stdin/stdout/stderr, making the TUN fd inaccessible to child processes.
 */
object Tun2socksManager {
    private const val TAG = "Tun2socksManager"
    private const val TUN2SOCKS_VERSION = "v2.6.0"

    init {
        System.loadLibrary("spawn_helper")
    }

    // JNI native methods (implemented in spawn_helper.c)
    private external fun nativeStartProcess(cmd: Array<String>): IntArray?
    private external fun nativeStopProcess(pid: Int)
    private external fun nativeWaitProcess(pid: Int): Int
    private external fun nativeIsAlive(pid: Int): Boolean

    private var processPid: Int = -1
    private var pipeReadFd: Int = -1

    /**
     * Start tun2socks process using native fork (preserves TUN fd).
     *
     * @param context   Android context for file access
     * @param tunFd     Raw file descriptor number of the TUN interface
     * @param socksPort SOCKS5 proxy port (Xray's inbound, default 10808)
     * @return true if the process started and is alive
     */
    fun start(context: Context, tunFd: Int, socksPort: Int = XrayManager.SOCKS_PORT): Boolean {
        return try {
            stop()

            val bin = ensureBinary(context)
            if (bin == null) {
                Log.e(TAG, "tun2socks binary not found — run ./gradlew downloadTun2socks")
                return false
            }
            Log.d(TAG, "Using tun2socks binary: ${bin.absolutePath}")

            val cmd = arrayOf(
                bin.absolutePath,
                "-device", "fd://$tunFd",
                "-proxy", "socks5://127.0.0.1:$socksPort",
                "-loglevel", "warn",
                "-udp-timeout", "30s"
            )
            Log.i(TAG, "Starting tun2socks: ${cmd.joinToString(" ")}")

            // Use native fork+exec to preserve TUN fd in child process
            val result = nativeStartProcess(cmd)
            if (result == null || result[0] <= 0) {
                Log.e(TAG, "Native fork failed for tun2socks")
                return false
            }

            processPid = result[0]
            pipeReadFd = result[1]
            Log.i(TAG, "tun2socks started with PID $processPid")

            // PENTING: jangan tutup pipe fd segera.
            // Go runtime (dan tun2socks) tetap menulis ke stderr saat startup
            // meski loglevel diset silent/warn. Jika read-end pipe kita tutup
            // sebelum proses berhenti menulis, proses menerima SIGPIPE dan mati.
            // Solusi: drain pipe in background — discard output, tapi jaga read-end tetap open.
            val drainFd = pipeReadFd
            pipeReadFd = -1
            Thread {
                try {
                    ParcelFileDescriptor.adoptFd(drainFd).use { pfd ->
                        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                        val buf = ByteArray(4096)
                        while (stream.read(buf) != -1) { /* discard — cegah SIGPIPE */ }
                    }
                } catch (_: Exception) {}
            }.apply {
                isDaemon = true
                name = "tun2socks-drain"
            }.start()

            // Monitor proses mati di background
            Thread {
                val exitCode = nativeWaitProcess(processPid)
                Log.w(TAG, "tun2socks exited with code $exitCode")
                processPid = -1
            }.apply {
                isDaemon = true
                name = "tun2socks-monitor"
            }.start()

            // Tunggu 500ms — menangkap crash cepat (SIGPIPE dll) sebelum lapor "alive"
            Thread.sleep(500)
            val alive = processPid > 0 && nativeIsAlive(processPid)
            Log.d(TAG, "tun2socks alive: $alive")
            if (!alive) Log.w(TAG, "tun2socks tidak hidup setelah 500ms — kemungkinan crash saat startup")
            alive
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tun2socks", e)
            false
        }
    }

    /**
     * Stop the tun2socks process if running.
     */
    fun stop() {
        val pid = processPid
        processPid = -1
        pipeReadFd = -1  // ParcelFileDescriptor.adoptFd took ownership
        if (pid > 0) {
            nativeStopProcess(pid)
            Log.d(TAG, "tun2socks stopped (pid=$pid)")
        }
    }

    fun isRunning(): Boolean = processPid > 0 && nativeIsAlive(processPid)

    // ───────────────────────────────────────────────────────────
    //  Binary resolution: jniLibs/libtun2socks.so → filesDir → download
    // ───────────────────────────────────────────────────────────

    private fun ensureBinary(context: Context): File? {
        // 1. Bundled at build time as libtun2socks.so in native libs
        val nativeBin = File(context.applicationInfo.nativeLibraryDir, "libtun2socks.so")
        if (nativeBin.exists()) {
            Log.d(TAG, "Found tun2socks in nativeLibraryDir")
            return nativeBin
        }

        // 2. Previously downloaded to filesDir
        val localBin = File(context.filesDir, "tun2socks")
        if (localBin.exists() && localBin.canExecute()) {
            Log.d(TAG, "Found tun2socks in filesDir")
            return localBin
        }

        // 3. Download from GitHub releases at runtime
        return downloadBinary(context, localBin)
    }

    private fun downloadBinary(context: Context, target: File): File? {
        return try {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val arch = when {
                "arm64" in abi   -> "linux-arm64"
                "arm" in abi     -> "linux-armv7"
                "x86_64" in abi  -> "linux-amd64"
                "x86" in abi     -> "linux-386"
                else             -> "linux-arm64"
            }

            val url = "https://github.com/xjasonlyu/tun2socks/releases/download/" +
                    "$TUN2SOCKS_VERSION/tun2socks-$arch.zip"
            Log.d(TAG, "Downloading tun2socks ($abi) from $url")

            val tmp = File(context.cacheDir, "tun2socks.zip")

            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = true
            }.also { conn ->
                conn.inputStream.use { src ->
                    FileOutputStream(tmp).use { dst -> src.copyTo(dst) }
                }
            }

            // Extract binary from zip
            var found = false
            ZipInputStream(tmp.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.startsWith("tun2socks")) {
                        FileOutputStream(target).use { out -> zis.copyTo(out) }
                        found = true
                        break
                    }
                    entry = zis.nextEntry
                }
            }
            tmp.delete()

            if (found) {
                target.setExecutable(true)
                Log.d(TAG, "tun2socks downloaded successfully: ${target.length() / 1024} KB")
                target
            } else {
                Log.e(TAG, "tun2socks binary not found inside zip")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download tun2socks: ${e.message}", e)
            null
        }
    }
}
