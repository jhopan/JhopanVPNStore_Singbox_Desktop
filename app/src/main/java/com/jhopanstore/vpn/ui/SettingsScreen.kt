package com.jhopanstore.vpn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhopanstore.vpn.R

private val KEEP_ALIVE_INTERVAL_OPTIONS = listOf("20", "45", "60", "90", "120")
private val MAX_RECONNECT_OPTIONS = listOf("3", "5", "10", "999")
private val TOLERANCE_OPTIONS = listOf("50", "100", "200", "300", "500")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        Text(
            text = "Settings",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Logo
        Image(
            painter = painterResource(id = R.drawable.ic_logo),
            contentDescription = "Logo",
            modifier = Modifier.size(100.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ═══════════════════════════════
        //  Connection
        // ═══════════════════════════════
        SectionHeader("Connection")

        SettingRow("Path:") {
            OutlinedTextField(
                value = viewModel.path,
                onValueChange = { viewModel.path = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !viewModel.isConnected
            )
        }

        SettingRow("SNI:") {
            OutlinedTextField(
                value = viewModel.sni,
                onValueChange = { viewModel.sni = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !viewModel.isConnected
            )
        }

        SettingRow("Host:") {
            OutlinedTextField(
                value = viewModel.host,
                onValueChange = { viewModel.host = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !viewModel.isConnected
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ═══════════════════════════════
        //  Backup Accounts
        // ═══════════════════════════════
        SectionHeader("Backup Accounts")

        if (viewModel.backupAccounts.isEmpty()) {
            Text(
                text = "Belum ada akun backup. Tambahkan untuk mengaktifkan fallback otomatis.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                textAlign = TextAlign.Center
            )
        }

        viewModel.backupAccounts.forEachIndexed { index, account ->
            BackupAccountCard(
                index = index,
                account = account,
                enabled = !viewModel.isConnected,
                onUpdate = { updated -> viewModel.updateBackupAccount(index, updated) },
                onDelete = { viewModel.removeBackupAccount(index) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.addBackupAccount() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                enabled = !viewModel.isConnected
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Tambah", fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = {
                    val text = clipboardManager.getText()?.text ?: ""
                    if (text.startsWith("vless://")) {
                        val ok = viewModel.importBackupFromUri(text)
                        if (!ok) viewModel.statusText = "Invalid backup URI"
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                enabled = !viewModel.isConnected
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Paste URI", fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ═══════════════════════════════
        //  Fallback / URL Test
        // ═══════════════════════════════
        SectionHeader("Fallback / URL Test")

        CheckboxRow(
            label = "Enable Fallback (urltest)",
            checked = viewModel.fallbackEnabled,
            onCheckedChange = { viewModel.fallbackEnabled = it },
            enabled = !viewModel.isConnected
        )

        SettingRow("Test URL:") {
            OutlinedTextField(
                value = viewModel.fallbackTestUrl,
                onValueChange = { viewModel.fallbackTestUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = viewModel.fallbackEnabled && !viewModel.isConnected
            )
        }

        SettingRow("Interval:") {
            OutlinedTextField(
                value = viewModel.fallbackTestInterval,
                onValueChange = { viewModel.fallbackTestInterval = it.filter { c -> c.isDigit() }.take(3) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = viewModel.fallbackEnabled && !viewModel.isConnected,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = { Text("s", fontSize = 12.sp) },
                supportingText = { Text("Interval URL test dalam detik (5-300).", fontSize = 11.sp) }
            )
        }

        SettingRow("Tolerance:") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TOLERANCE_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = viewModel.fallbackTolerance == option,
                        onClick = { viewModel.fallbackTolerance = option },
                        enabled = viewModel.fallbackEnabled && !viewModel.isConnected,
                        label = { Text("${option}ms", fontSize = 12.sp) }
                    )
                }
            }
        }
        Text(
            text = "Akun utama tetap dipakai jika selisih latency ≤ tolerance. Makin besar nilai = makin setia ke akun utama.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 84.dp, top = 2.dp, bottom = 4.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ═══════════════════════════════
        //  DNS
        // ═══════════════════════════════
        SectionHeader("DNS")

        SettingRow("DNS 1:") {
            OutlinedTextField(
                value = viewModel.dns1,
                onValueChange = { viewModel.dns1 = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !viewModel.isConnected,
                isError = viewModel.dns1.isNotBlank() && !isValidIpOrEmpty(viewModel.dns1),
                supportingText = if (viewModel.dns1.isNotBlank() && !isValidIpOrEmpty(viewModel.dns1)) {
                    { Text("Format tidak valid (contoh: 8.8.8.8)", color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                } else null
            )
        }

        SettingRow("DNS 2:") {
            OutlinedTextField(
                value = viewModel.dns2,
                onValueChange = { viewModel.dns2 = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !viewModel.isConnected,
                isError = viewModel.dns2.isNotBlank() && !isValidIpOrEmpty(viewModel.dns2),
                supportingText = if (viewModel.dns2.isNotBlank() && !isValidIpOrEmpty(viewModel.dns2)) {
                    { Text("Format tidak valid (contoh: 8.8.4.4)", color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                } else null
            )
        }

        Text(
            text = "DNS otomatis pakai TCP jika akun terdeteksi sebagai Cloudflare Workers.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 84.dp, top = 2.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ═══════════════════════════════
        //  HTTP Ping
        // ═══════════════════════════════
        SectionHeader("HTTP Ping")

        SettingRow("Ping URL:") {
            OutlinedTextField(
                value = viewModel.pingUrl,
                onValueChange = { viewModel.pingUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        SettingRow("Interval:") {
            OutlinedTextField(
                value = viewModel.pingIntervalSeconds,
                onValueChange = { viewModel.updatePingIntervalSeconds(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = viewModel.autoPing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = { Text("s", fontSize = 12.sp) },
                isError = viewModel.pingIntervalSeconds.isNotBlank() && !isValidPingInterval(viewModel.pingIntervalSeconds),
                supportingText = {
                    if (viewModel.pingIntervalSeconds.isNotBlank() && !isValidPingInterval(viewModel.pingIntervalSeconds)) {
                        Text("Gunakan 1-300 detik.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    } else {
                        Text("Satuan detik (s).", fontSize = 11.sp)
                    }
                }
            )
        }

        SettingRow("Net Delay:") {
            OutlinedTextField(
                value = viewModel.networkReconnectDelaySeconds,
                onValueChange = { viewModel.updateNetworkReconnectDelay(context, it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = viewModel.autoReconnect,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = { Text("s", fontSize = 12.sp) },
                isError = viewModel.networkReconnectDelaySeconds.isNotBlank() && !isValidReconnectDelay(viewModel.networkReconnectDelaySeconds),
                supportingText = {
                    if (viewModel.networkReconnectDelaySeconds.isNotBlank() && !isValidReconnectDelay(viewModel.networkReconnectDelaySeconds)) {
                        Text("Gunakan 1-60 detik.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    } else {
                        Text("Jeda sebelum coba reconnect saat jaringan putus.", fontSize = 11.sp)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ═══════════════════════════════
        //  Behavior
        // ═══════════════════════════════
        SectionHeader("Behavior")

        CheckboxRow(
            label = "Auto Reconnect",
            checked = viewModel.autoReconnect,
            onCheckedChange = {
                viewModel.autoReconnect = it
                viewModel.pushRuntimePreferences(context)
            }
        )

        CheckboxRow(
            label = "Auto Ping",
            checked = viewModel.autoPing,
            onCheckedChange = { viewModel.setAutoPingEnabled(it) }
        )

        CheckboxRow(
            label = "Wake Lock (Optimized)",
            checked = viewModel.wakeLockEnabled,
            onCheckedChange = { viewModel.updateWakeLockEnabled(context, it) }
        )

        CheckboxRow(
            label = "Keep Alive (Optimized)",
            checked = viewModel.keepAliveEnabled,
            onCheckedChange = { viewModel.updateKeepAliveEnabled(context, it) }
        )

        SettingRow("KA Intv:") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                KEEP_ALIVE_INTERVAL_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = viewModel.keepAliveIntervalSeconds == option,
                        onClick = { viewModel.updateKeepAliveIntervalSeconds(context, option) },
                        enabled = viewModel.keepAliveEnabled,
                        label = { Text("${option}s", fontSize = 12.sp) }
                    )
                }
            }
        }

        SettingRow("Max Retry:") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MAX_RECONNECT_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = viewModel.maxReconnectAttempts == option,
                        onClick = { viewModel.updateMaxReconnectAttempts(context, option) },
                        label = { Text(if (option == "999") "∞" else "${option}x", fontSize = 12.sp) }
                    )
                }
            }
        }
        Text(
            text = "Jumlah maksimal percobaan reconnect otomatis (∞ = tanpa batas).",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 84.dp, top = 2.dp)
        )

        CheckboxRow(
            label = "Show Usage In App",
            checked = viewModel.showUsageInApp,
            onCheckedChange = { viewModel.updateShowUsageInApp(it) }
        )

        CheckboxRow(
            label = "Show Usage In Notification",
            checked = viewModel.showUsageInNotification,
            onCheckedChange = { viewModel.updateShowUsageInNotification(context, it) }
        )

        CheckboxRow(
            label = "Show Speed In Notification",
            checked = viewModel.showSpeedInNotification,
            onCheckedChange = { viewModel.updateShowSpeedInNotification(context, it) }
        )

        CheckboxRow(
            label = "Allow Insecure TLS (skip verify)",
            checked = viewModel.allowInsecure,
            onCheckedChange = { viewModel.allowInsecure = it },
            enabled = !viewModel.isConnected
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Close button
        Button(
            onClick = onClose,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.widthIn(min = 120.dp)
        ) {
            Text("Close")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── Backup Account Card ──────────────────────────────────────────

@Composable
private fun BackupAccountCard(
    index: Int,
    account: MainViewModel.BackupAccount,
    enabled: Boolean,
    onUpdate: (MainViewModel.BackupAccount) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = account.remark.ifEmpty { "Backup ${index + 1}" },
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )

                if (account.address.isNotBlank()) {
                    Text(
                        text = account.address.take(20),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Expand",
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp),
                    enabled = enabled
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Expandable fields
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = account.remark,
                    onValueChange = { onUpdate(account.copy(remark = it)) },
                    label = { Text("Nama", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = enabled
                )
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = account.address,
                    onValueChange = { onUpdate(account.copy(address = it)) },
                    label = { Text("Address (host:port)", fontSize = 12.sp) },
                    placeholder = { Text("example.com:443") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = enabled
                )
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = account.uuid,
                    onValueChange = { onUpdate(account.copy(uuid = it)) },
                    label = { Text("UUID", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = enabled
                )
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = account.path,
                    onValueChange = { onUpdate(account.copy(path = it)) },
                    label = { Text("Path", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = enabled
                )
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = account.sni,
                    onValueChange = { onUpdate(account.copy(sni = it)) },
                    label = { Text("SNI", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = enabled
                )
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = account.host,
                    onValueChange = { onUpdate(account.copy(host = it)) },
                    label = { Text("Host", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = enabled
                )
                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = account.allowInsecure,
                        onCheckedChange = { onUpdate(account.copy(allowInsecure = it)) },
                        enabled = enabled
                    )
                    Text("Allow Insecure", fontSize = 13.sp)
                }
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────

private fun isValidIpOrEmpty(ip: String): Boolean {
    if (ip.isBlank()) return true
    val parts = ip.trim().split(".")
    if (parts.size != 4) return false
    return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } ?: false }
}

private fun isValidPingInterval(value: String): Boolean {
    val number = value.trim().toIntOrNull() ?: return false
    return number in 1..300
}

private fun isValidReconnectDelay(value: String): Boolean {
    val number = value.trim().toIntOrNull() ?: return false
    return number in 1..60
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = "\u2014 $title \u2014",
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun SettingRow(
    label: String,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(76.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 14.sp)
    }
}
