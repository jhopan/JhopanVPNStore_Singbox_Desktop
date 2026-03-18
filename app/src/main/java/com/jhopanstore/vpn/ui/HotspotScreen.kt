package com.jhopanstore.vpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HotspotScreen(
    viewModel: MainViewModel,
    onClose: () -> Unit,
    onToggleProxy: () -> Unit,
    onCheckHotspot: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.WifiTethering,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(text = "Bagikan VPN", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    text = "Bagikan koneksi VPN ke device lain via proxy.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // â”€â”€ Langkah 1: Hotspot â”€â”€
        StepLabel(number = "1", done = viewModel.isHotspotDetected)
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.WifiTethering,
                        contentDescription = null,
                        tint = if (viewModel.isHotspotDetected) Color(0xFF4CAF50)
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Hotspot HP", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            text = if (viewModel.isHotspotDetected) "Aktif - ${viewModel.hotspotIp}"
                                   else "Tidak aktif",
                            fontSize = 12.sp,
                            color = if (viewModel.isHotspotDetected) Color(0xFF4CAF50)
                                    else MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (!viewModel.isHotspotDetected) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Aktifkan hotspot HP kamu terlebih dahulu, lalu kembali ke halaman ini.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onCheckHotspot,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Cek Hotspot")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // â”€â”€ Langkah 2: Proxy â”€â”€
        StepLabel(number = "2", done = viewModel.isProxySharingActive)
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Proxy VPN", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            text = if (viewModel.isProxySharingActive) "Aktif" else "Tidak aktif",
                            fontSize = 12.sp,
                            color = if (viewModel.isProxySharingActive) Color(0xFF4CAF50)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    Switch(
                        checked = viewModel.isProxySharingActive,
                        onCheckedChange = { if (viewModel.isHotspotDetected && viewModel.isConnected) onToggleProxy() },
                        enabled = viewModel.isHotspotDetected && viewModel.isConnected
                    )
                }

                if (!viewModel.isConnected) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "VPN harus aktif untuk menggunakan proxy sharing.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Info proxy + panduan ketika aktif
                if (viewModel.isProxySharingActive && viewModel.hotspotIp.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))

                    // Info host & port
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            ProxyInfoRow(label = "Host", value = viewModel.hotspotIp)
                            Spacer(Modifier.height(8.dp))
                            ProxyInfoRow(label = "SOCKS5 Port", value = "10808")
                            Spacer(Modifier.height(4.dp))
                            ProxyInfoRow(label = "HTTP Port", value = "10809")
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Panduan per platform
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "Cara setting di device lain:",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            GuideItem(
                                platform = "[Android] — HTTP Proxy (port 10809)",
                                steps = "1. Sambungkan ke hotspot HP ini\n2. Settings > WiFi > Tahan nama jaringan > Ubah jaringan\n3. Opsi Lanjutan > Proxy > Manual\n4. Hostname: ${viewModel.hotspotIp}  Port: 10809\n5. Simpan"
                            )
                            Spacer(Modifier.height(8.dp))
                            GuideItem(
                                platform = "[iOS] — HTTP Proxy (port 10809)",
                                steps = "1. Sambungkan ke hotspot HP ini\n2. Settings > Wi-Fi > tekan (i) di jaringan\n3. Configure Proxy > Manual\n4. Server: ${viewModel.hotspotIp}  Port: 10809\n5. Save"
                            )
                            Spacer(Modifier.height(8.dp))
                            GuideItem(
                                platform = "[Windows] — Pilih salah satu:",
                                steps = "\u2022 HTTP: Settings > Network > Proxy > Manual\n  Address: ${viewModel.hotspotIp}  Port: 10809\n\u2022 SOCKS5: Proxifier / SocksCap\n  ${viewModel.hotspotIp}:10808"
                            )
                            Spacer(Modifier.height(8.dp))
                            GuideItem(
                                platform = "[Linux] — Pilih salah satu:",
                                steps = "\u2022 HTTP: Settings > Network > Proxy > Manual\n  HTTP Proxy: ${viewModel.hotspotIp}:10809\n\u2022 SOCKS5 (terminal):\n  export all_proxy=socks5://${viewModel.hotspotIp}:10808"
                            )
                            Spacer(Modifier.height(8.dp))
                            GuideItem(
                                platform = "[macOS] — Pilih salah satu:",
                                steps = "\u2022 HTTP: System Settings > Network > [WiFi]\n  Details > Proxies > Web Proxy (HTTP): ${viewModel.hotspotIp}:10809\n\u2022 SOCKS5: Details > Proxies > SOCKS Proxy:\n  ${viewModel.hotspotIp}:10808"
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Tutup")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StepLabel(number: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(50),
            color = if (done) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (done) "\u2713" else number,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (number == "1") "Aktifkan Hotspot HP" else "Aktifkan Proxy VPN",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = if (done) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ProxyInfoRow(label: String, value: String) {
    Row {
        Text(
            text = "$label  ",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GuideItem(platform: String, steps: String) {
    Column {
        Text(platform, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text(
            text = steps,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            lineHeight = 16.sp
        )
    }
}

