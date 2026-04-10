@file:OptIn(ExperimentalMaterial3Api::class)
package com.jhopanstore.vpn.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhopanstore.vpn.R

private val KEEP_ALIVE_INTERVAL_OPTIONS = listOf("20", "45", "60", "90", "120")
private val MAX_RECONNECT_OPTIONS = listOf("3", "5", "10", "999")
private val TOLERANCE_OPTIONS = listOf("50", "100", "200", "300", "500")

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val tabs = listOf("Connection", "DNS", "Ping", "Rules", "Behavior")
    var selectedTab by remember { mutableStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_logo),
                contentDescription = "Logo",
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Settings",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) {
                Text("Close", fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tab row
        TabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontSize = 13.sp,
                            fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tab content — each tab is independently scrollable
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> ConnectionTab(viewModel, clipboardManager)
                1 -> DnsTab(viewModel)
                2 -> PingTab(viewModel, context)
                3 -> RulesTab(viewModel)
                4 -> BehaviorTab(viewModel, context)
            }
        }
    }
}

// ══════════════════════════════════════════
//  Tab 1: Connection
// ══════════════════════════════════════════

@Composable
private fun ConnectionTab(
    viewModel: MainViewModel,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        SectionHeader("Main Connection")

        SettingRow("Path:") {
            OutlinedTextField(
                value = viewModel.path,
                onValueChange = { viewModel.path = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !viewModel.isConnected,
                placeholder = { Text("/vless  or  IP-port for Workers") }
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = viewModel.allowInsecure,
                onCheckedChange = { viewModel.allowInsecure = it },
                enabled = !viewModel.isConnected
            )
            Text("Allow Insecure TLS (skip verify)", fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))
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
    }
}

// ══════════════════════════════════════════
//  Tab 2: DNS
// ══════════════════════════════════════════

@Composable
private fun DnsTab(viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        SectionHeader("DNS Servers")

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

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "ℹ️  DNS otomatis pakai TCP jika akun terdeteksi sebagai Cloudflare Workers. Jika path adalah format IP-port (misal /103.6.207.108-8080), otomatis Workers.",
                fontSize = 12.sp,
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        SectionHeader("Network")

        SettingRow("MTU:") {
            OutlinedTextField(
                value = viewModel.mtu,
                onValueChange = { viewModel.mtu = it.filter { c -> c.isDigit() }.take(4) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !viewModel.isConnected,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("1280–1500. Default: 1400", fontSize = 11.sp) }
            )
        }
    }
}

// ══════════════════════════════════════════
//  Tab 3: Ping
// ══════════════════════════════════════════

@Composable
private fun PingTab(viewModel: MainViewModel, context: android.content.Context) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
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

        Spacer(modifier = Modifier.height(20.dp))
        SectionHeader("Reconnect")

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
    }
}

// ══════════════════════════════════════════
//  Tab 4: Behavior
// ══════════════════════════════════════════

@Composable
private fun BehaviorTab(viewModel: MainViewModel, context: android.content.Context) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        SectionHeader("Connection Behavior")

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

        Spacer(modifier = Modifier.height(12.dp))
        SectionHeader("Performance")

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

        Spacer(modifier = Modifier.height(12.dp))
        SectionHeader("Display")

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
    }
}

// ══════════════════════════════════════════
//  Tab 5: Rules
// ══════════════════════════════════════════

@Composable
private fun RulesTab(viewModel: MainViewModel) {
    var selectedRuleName by remember { mutableStateOf("") }
    var importStatus by remember { mutableStateOf("") }
    var showRuleNameInput by remember { mutableStateOf(false) }
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { fileUri ->
            if (fileUri != null && selectedRuleName.isNotEmpty()) {
                viewModel.importRuleFromFile(fileUri, selectedRuleName)
                importStatus = "Imported! Click Apply to activate rule."
                selectedRuleName = ""
                showRuleNameInput = false
            }
        }
    )
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        SectionHeader("Manual Rule Import")

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "📥 Download rule JSON dari GitHub via browser → Import ke aplikasi → Pilih rute → Klik Apply untuk aktifkan.",
                fontSize = 12.sp,
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                lineHeight = 16.sp
            )
        }

        // New info card para RouteRules.git
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "📦 Download Rules dari Repository:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                
                // Clickable GitHub link
                val uriHandler = LocalUriHandler.current
                val context = LocalContext.current
                
                Text(
                    text = buildAnnotatedString {
                        append("1. Buka: ")
                        pushStringAnnotation(tag = "URL", annotation = "https://github.com/jhopan/RouteRules.git")
                        withStyle(style = SpanStyle(
                            color = Color(0xFF2196F3),  // Material Blue
                            textDecoration = TextDecoration.Underline
                        )) {
                            append("https://github.com/jhopan/RouteRules.git")
                        }
                        pop()
                    },
                    fontSize = 11.sp,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable {
                            try {
                                uriHandler.openUri("https://github.com/jhopan/RouteRules.git")
                            } catch (e: Exception) {
                                // Fallback jika openUri tidak work
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                intent.data = android.net.Uri.parse("https://github.com/jhopan/RouteRules.git")
                                context.startActivity(intent)
                            }
                        }
                )
                
                Text(
                    text = "2. Pilih folder 'singbox' → 'rules'",
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "3. Download file .json yang diinginkan",
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "4. Import ke aplikasi menggunakan tombol di bawah",
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        if (viewModel.customRules.isEmpty()) {
            Text(
                text = "Belum ada custom rule. Import rule baru dari file.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                textAlign = TextAlign.Center
            )
        }

        viewModel.customRules.forEachIndexed { index, rule ->
            CustomRuleCard(
                index = index,
                rule = rule,
                viewModel = viewModel
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Import rule button
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedButton(
                onClick = { showRuleNameInput = !showRuleNameInput },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("📁 Import Rule File", fontSize = 13.sp)
            }

            if (showRuleNameInput) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = selectedRuleName,
                    onValueChange = { selectedRuleName = it.replace(" ", "_") },
                    label = { Text("Beri nama rule (tanpa spasi)", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("contoh: situsX, youtube") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (selectedRuleName.isNotEmpty()) {
                                filePickerLauncher.launch("application/json")
                            } else {
                                importStatus = "Masukkan nama rule dulu!"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Pilih File", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { 
                            showRuleNameInput = false
                            selectedRuleName = ""
                            importStatus = ""
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Batal", fontSize = 12.sp)
                    }
                }
            }

            if (importStatus.isNotBlank()) {
                Text(
                    text = importStatus,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun CustomRuleCard(
    index: Int,
    rule: MainViewModel.CustomRule,
    viewModel: MainViewModel
) {
    var expanded by remember { mutableStateOf(false) }
    var applyStatus by remember { mutableStateOf("") }
    var showEditMode by remember { mutableStateOf(false) }
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { fileUri ->
            if (fileUri != null) {
                // Re-import file for existing rule
                viewModel.importRuleFromFile(fileUri, rule.name, existingRuleIndex = index)
                applyStatus = "File updated! Click Apply to activate."
            }
        }
    )
    
    // Dynamically build targets
    val targets = mutableListOf("direct", "reject", "main")
    for (i in viewModel.backupAccounts.indices) {
        targets.add("backup-$i")
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.name.ifEmpty { "Rule ${index + 1}" },
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "File: ${rule.fileName}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "► ${rule.targetOutbound}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

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
                    onClick = { viewModel.deleteRule(index) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(modifier = Modifier.fillMaxWidth(), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // Target Rute section
                Text("📍 Target Rute:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(6.dp))
                
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(targets.size) { i ->
                        val t = targets[i]
                        FilterChip(
                            selected = rule.targetOutbound == t,
                            onClick = { viewModel.updateRuleTargetOutbound(index, t) },
                            label = { Text(t, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action buttons row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Apply button (disabled kalau VPN belum connected)
                    Button(
                        onClick = {
                            if (!viewModel.isConnected) {
                                applyStatus = "❌ VPN harus connected dulu di layar utama!"
                            } else {
                                applyStatus = "⏳ Applying..."
                                viewModel.applyRule(rule.name) { msg -> 
                                    applyStatus = "✅ $msg" 
                                }
                            }
                        },
                        enabled = viewModel.isConnected,
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("✓ Apply", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    
                    // Edit file button
                    OutlinedButton(
                        onClick = { filePickerLauncher.launch("application/json") },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("✎ Edit File", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                
                if (applyStatus.isNotBlank()) {
                    Text(
                        text = applyStatus, 
                        fontSize = 11.sp, 
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
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
        text = title,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp)
    )
    Divider(
        modifier = Modifier.padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
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
            fontSize = 13.sp,
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
        Text(text = label, fontSize = 13.sp)
    }
}
