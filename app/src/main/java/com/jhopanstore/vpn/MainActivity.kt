package com.jhopanstore.vpn
import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jhopanstore.vpn.utils.AutostartUtils
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jhopanstore.vpn.ui.MainScreen
import com.jhopanstore.vpn.ui.MainViewModel
import com.jhopanstore.vpn.ui.theme.JhopanStoreVPNTheme
class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN_FROM_NOTIFICATION = "com.jhopanstore.vpn.action.OPEN_FROM_NOTIFICATION"
        const val EXTRA_OPEN_FROM_NOTIFICATION = "open_from_notification"
    }

    private var lastBackPressAt: Long = 0L
    private var hasLoadedSettings = false
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* tidak perlu action apapun — user sudah lihat dialog */ }
    private var pendingViewModel: MainViewModel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JhopanStoreVPNTheme {
                val vm: MainViewModel = viewModel()
                pendingViewModel = vm
                var showAntiDcGuide by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    vm.loadSettings(this@MainActivity)
                    hasLoadedSettings = true
                    vm.checkHotspot()
                    vm.syncConnectionState()
                    val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    val guideShown = prefs.getBoolean("antidc_guide_shown", false)
                    if (!guideShown) {
                        showAntiDcGuide = true
                    } else {
                        requestNotificationPermission()
                    }
                }
                if (showAntiDcGuide) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { /* Force user to click Mengerti */ },
                        title = { Text("Panduan Anti Putus (Anti-DC)", fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text("Agar VPN tidak sering dberhentikan paksa oleh sistem HP Anda (terutama Xiaomi, Oppo, Vivo, Poco):")
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("1. Izinkan Mulai Otomatis (Autostart).")
                                Text("2. Matikan Penghemat Baterai untuk aplikasi ini (Bebas Batasan).")
                                Text("3. Wajib: Kunci aplikasi ini di 'Recent Apps' (tekan kotak navigasi, lalu tekan tahan JhopanStoreVPN > pilih Gembok).")
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { AutostartUtils.openAutostartSettings(this@MainActivity) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.secondary)
                                ) {
                                    Text("Buka Pengaturan Autostart")
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = { 
                                        try {
                                            startActivity(
                                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                    data = Uri.parse("package:$packageName")
                                                }
                                            )
                                        } catch (e: Exception) {
                                            Toast.makeText(this@MainActivity, "Gagal buka info baterai", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.secondary)
                                ) {
                                    Text("Matikan Penghemat Baterai")
                                }
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
                                    .putBoolean("antidc_guide_shown", true)
                                    .apply()
                                showAntiDcGuide = false
                                requestNotificationPermission()
                            }) { 
                                Text("Saya Mengerti") 
                            }
                        }
                    )
                }
                MainScreen(
                    viewModel = vm,
                    onConnect = { requestVpnPermission(vm) },
                    onDisconnect = { disconnectVpn(vm) },
                    onImportClipboard = { importFromClipboard(vm) },
                    onToggleProxy = { vm.toggleProxySharing(this@MainActivity) },
                    onCopyProxy = { copyProxyToClipboard(vm) },
                    onRequestExit = { requestExitFromMain() }
                )
            }
        }
    }
    override fun onResume() {
        super.onResume()
        pendingViewModel?.onAppForegrounded()
        if (hasLoadedSettings) {
            pendingViewModel?.pushNotificationPreferences(this)
            pendingViewModel?.pushRuntimePreferences(this)
        }
        pendingViewModel?.checkHotspot()
        pendingViewModel?.syncConnectionState()
        requestNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_FROM_NOTIFICATION || intent.getBooleanExtra(EXTRA_OPEN_FROM_NOTIFICATION, false)) {
            pendingViewModel?.syncConnectionState()
            pendingViewModel?.checkHotspot()
        }
    }
    override fun onPause() {
        super.onPause()
        pendingViewModel?.onAppBackgrounded()
        // Background save dengan apply() saat pause
        pendingViewModel?.saveSettings(this, immediate = false)
    }
    override fun onStop() {
        super.onStop()
        // Immediate save dengan commit() saat stop untuk mencegah data loss
        // jika app di-kill dari recent apps atau system
        pendingViewModel?.saveSettings(this, immediate = true)
    }
    override fun onDestroy() {
        super.onDestroy()
        // Final save dengan commit() saat destroy - last resort
        pendingViewModel?.saveSettings(this, immediate = true)
    }
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    private fun requestVpnPermission(vm: MainViewModel) {
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            pendingViewModel = vm
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpn()
        }
    }
    private fun startVpn() {
        pendingViewModel?.connect(this)
    }
    private fun disconnectVpn(vm: MainViewModel) {
        vm.disconnect(this)
    }
    private fun importFromClipboard(vm: MainViewModel) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            vm.importVlessUri(text)
        }
    }
    private fun copyProxyToClipboard(vm: MainViewModel) {
        // Use HTTP port (10809) for WiFi manual proxy, not SOCKS5 (10808)
        val text = "${vm.hotspotIp}:10809"
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Proxy", text))
        Toast.makeText(this, "Disalin: $text", Toast.LENGTH_SHORT).show()
    }
    private fun requestExitFromMain() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressAt <= 2000L) {
            finish()
            return
        }
        lastBackPressAt = now
        Toast.makeText(this, "Tekan Back sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
    }
}
