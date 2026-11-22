package com.tweakora

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- THEME & UPDATE TRACKER PERSISTENCE ----------

object AppPrefs {
    private const val PREFS = "tweakora_app_prefs"
    private const val UPDATES_FILE = "tweakora_update_tracker.json"
    private const val VAULT_FILE = "tweakora_kernel_vault.json"

    fun loadThemeConfig(context: Context): GlassThemeConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = runCatching { GlassThemeMode.valueOf(p.getString("theme_mode", "") ?: "") }
            .getOrDefault(GlassThemeMode.MIDNIGHT)
        val accent = runCatching { GlassAccent.valueOf(p.getString("theme_accent", "") ?: "") }
            .getOrDefault(GlassAccent.NEON_PURPLE)
        return GlassThemeConfig(mode, accent)
    }

    fun saveThemeConfig(context: Context, config: GlassThemeConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("theme_mode", config.mode.name)
            .putString("theme_accent", config.accent.name)
            .apply()
    }

    fun recordAndLoadUpdates(context: Context, dna: DeviceDna): List<UpdateRecord> {
        val file = File(context.filesDir, UPDATES_FILE)
        val existing = runCatching {
            if (!file.exists()) emptyList()
            else {
                val arr = JSONArray(file.readText())
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    UpdateRecord(
                        timestampMs = o.optLong("timestampMs"),
                        androidVersion = o.optString("androidVersion"),
                        securityPatch = o.optString("securityPatch"),
                        fingerprint = o.optString("fingerprint"),
                        kernel = o.optString("kernel")
                    )
                }
            }
        }.getOrDefault(emptyList())

        val latest = existing.firstOrNull()
        val updated = if (latest == null || latest.fingerprint != dna.buildFingerprint || latest.kernel != dna.kernelVersion) {
            listOf(
                UpdateRecord(
                    timestampMs = System.currentTimeMillis(),
                    androidVersion = dna.androidVersion,
                    securityPatch = dna.securityPatch,
                    fingerprint = dna.buildFingerprint,
                    kernel = dna.kernelVersion
                )
            ) + existing
        } else existing

        if (updated.size != existing.size) {
            val arr = JSONArray()
            updated.take(15).forEach { r ->
                arr.put(JSONObject().apply {
                    put("timestampMs", r.timestampMs)
                    put("androidVersion", r.androidVersion)
                    put("securityPatch", r.securityPatch)
                    put("fingerprint", r.fingerprint)
                    put("kernel", r.kernel)
                })
            }
            file.writeText(arr.toString())
        }
        return updated
    }

    fun loadKernelVault(context: Context): List<KernelVaultProfile> = runCatching {
        val f = File(context.filesDir, VAULT_FILE)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            KernelVaultProfile(o.optString("name"), o.optString("governor"), o.optString("swappiness"), o.optLong("savedAt"))
        }
    }.getOrDefault(emptyList())

    fun saveKernelVaultProfile(context: Context, profile: KernelVaultProfile) {
        val list = (listOf(profile) + loadKernelVault(context).filterNot { it.name == profile.name }).take(10)
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("governor", p.governor)
                put("swappiness", p.swappiness)
                put("savedAt", p.savedAt)
            })
        }
        File(context.filesDir, VAULT_FILE).writeText(arr.toString())
    }
}

data class UpdateRecord(
    val timestampMs: Long,
    val androidVersion: String,
    val securityPatch: String,
    val fingerprint: String,
    val kernel: String
)

data class KernelVaultProfile(
    val name: String,
    val governor: String,
    val swappiness: String,
    val savedAt: Long
)

data class HealthCheckItem(val title: String, val detail: String, val status: HealthStatus)

data class AppExposureInfo(
    val packageName: String,
    val appName: String,
    val exposureScore: Int,
    val riskyPermissions: List<String>
)

data class ScannedFileItem(
    val path: String,
    val name: String,
    val sizeMb: Float,
    val isDuplicateHint: Boolean
)

object ToolsEngine {

    fun runHealthScan(dna: DeviceDna): List<HealthCheckItem> {
        val ramPct = (dna.ramFraction * 100).toInt()
        val storagePct = (dna.storageFraction * 100).toInt()
        return listOf(
            HealthCheckItem(
                "Internal Storage",
                "${String.format(Locale.US, "%.1f GB", dna.freeStorageGb)} free ($storagePct% used)",
                when {
                    storagePct >= 90 -> HealthStatus.CRITICAL
                    storagePct >= 80 -> HealthStatus.NEEDS_ATTENTION
                    else -> HealthStatus.GOOD
                }
            ),
            HealthCheckItem(
                "Memory Pressure",
                "${dna.usedRamMb} / ${dna.totalRamMb} MB ($ramPct% used)",
                when {
                    ramPct >= 88 -> HealthStatus.CRITICAL
                    ramPct >= 78 -> HealthStatus.NEEDS_ATTENTION
                    else -> HealthStatus.GOOD
                }
            ),
            HealthCheckItem(
                "Battery Health",
                "${dna.batteryPercent}% • ${dna.batteryHealth} • ${dna.batteryVoltageMv} mV",
                if (dna.batteryHealth == "Good" || dna.batteryHealth == "Unknown") HealthStatus.GOOD else HealthStatus.NEEDS_ATTENTION
            ),
            HealthCheckItem(
                "Thermal Envelope",
                "${String.format(Locale.US, "%.1f°C", dna.batteryTempC)} • ${dna.thermalStatus}",
                when {
                    dna.batteryTempC >= 42f -> HealthStatus.CRITICAL
                    dna.batteryTempC >= 38f -> HealthStatus.NEEDS_ATTENTION
                    else -> HealthStatus.GOOD
                }
            ),
            HealthCheckItem(
                "Android Security Patch",
                "Android ${dna.androidVersion} • Patch ${dna.securityPatch}",
                HealthStatus.GOOD
            ),
            HealthCheckItem(
                "Privilege & Safety Tier",
                "Running in ${dna.privilegeTier.badge} mode",
                HealthStatus.GOOD
            )
        )
    }

    suspend fun scanAppsPrivacy(context: Context): List<AppExposureInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val weights = mapOf(
            android.Manifest.permission.CAMERA to ("Camera" to 20),
            android.Manifest.permission.RECORD_AUDIO to ("Mic" to 25),
            android.Manifest.permission.ACCESS_FINE_LOCATION to ("GPS" to 20),
            android.Manifest.permission.READ_CONTACTS to ("Contacts" to 15),
            android.Manifest.permission.READ_SMS to ("SMS" to 20),
            android.Manifest.permission.READ_CALL_LOG to ("Call Log" to 20),
            android.Manifest.permission.READ_EXTERNAL_STORAGE to ("Storage" to 10)
        )

        val pkgs = runCatching {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }.getOrDefault(emptyList())

        pkgs.mapNotNull { pi ->
            val appInfo = pi.applicationInfo ?: return@mapNotNull null
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem || pi.packageName == context.packageName) return@mapNotNull null

            val requested = pi.requestedPermissions ?: emptyArray()
            val flags = pi.requestedPermissionsFlags ?: IntArray(0)
            var score = 0
            val grantedList = mutableListOf<String>()
            requested.forEachIndexed { idx, perm ->
                val isGranted = idx < flags.size && (flags[idx] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                val entry = weights[perm]
                if (isGranted && entry != null) {
                    grantedList.add(entry.first)
                    score += entry.second
                }
            }
            AppExposureInfo(
                packageName = pi.packageName,
                appName = pm.getApplicationLabel(appInfo).toString(),
                exposureScore = score.coerceAtMost(100),
                riskyPermissions = grantedList
            )
        }.sortedByDescending { it.exposureScore }
    }

    suspend fun scanLargeFiles(): List<ScannedFileItem> = withContext(Dispatchers.IO) {
        val roots = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        ).filter { it.exists() && it.canRead() }

        val found = mutableListOf<File>()
        roots.forEach { dir ->
            runCatching {
                dir.walkTopDown().maxDepth(3).forEach { f ->
                    if (f.isFile && f.length() >= 10L * 1024L * 1024L) {
                        found.add(f)
                    }
                }
            }
        }
        val sizeGroups = found.groupBy { it.length() }
        found.sortedByDescending { it.length() }.take(30).map { f ->
            ScannedFileItem(
                path = f.absolutePath,
                name = f.name,
                sizeMb = f.length() / (1024f * 1024f),
                isDuplicateHint = (sizeGroups[f.length()]?.size ?: 0) > 1
            )
        }
    }

    fun answerDiagnosticQuery(dna: DeviceDna, prompt: String): String {
        val q = prompt.lowercase()
        return when {
            q.contains("heat") || q.contains("temp") || q.contains("thermal") -> buildString {
                appendLine("Thermal Diagnosis (${String.format(Locale.US, "%.1f°C", dna.batteryTempC)} — ${dna.thermalStatus}):")
                if (dna.batteryTempC >= 39f) {
                    appendLine("• Battery temperature is elevated. Charging while gaming or locked high refresh rate is the top heat driver.")
                    appendLine("• Action: Lower Peak Refresh Rate to 60–90Hz in Tweaks and avoid fast charging during heavy 3D loads.")
                } else {
                    appendLine("• Temperatures are currently cool (${dna.batteryTempC}°C). No thermal throttling is restricting ${dna.socName}.")
                }
            }
            q.contains("battery") || q.contains("drain") -> buildString {
                appendLine("Battery Audit (${dna.batteryPercent}% • ${dna.batteryHealth} • ${dna.batteryVoltageMv} mV):")
                appendLine("• Enable 'Cached Apps Freezer' and 'Wi-Fi Scan Throttling' in Tweaks to cut idle drain without turning on harsh Battery Saver.")
                appendLine("• Keep screen timeout at 30–60s to extend screen-on life.")
            }
            q.contains("bottleneck") || q.contains("lag") || q.contains("fps") -> buildString {
                appendLine("Bottleneck Analysis for ${dna.manufacturer} ${dna.model}:")
                appendLine("• RAM: ${(dna.ramFraction * 100).toInt()}% used (${dna.usedRamMb}/${dna.totalRamMb} MB) — ${if (dna.ramFraction > 0.82f) "High pressure; close heavy background apps before gaming." else "Healthy headroom."}")
                appendLine("• Storage: ${String.format(Locale.US, "%.1f GB", dna.freeStorageGb)} free — ${if (dna.storageFraction > 0.85f) "Low space slows flash I/O; clean large files." else "Healthy flash headroom."}")
                appendLine("• Run the CPU Consistency Benchmark in Monitor → Perf Lab for real stability measurements.")
            }
            else -> buildString {
                appendLine("Tweakora Intelligence Summary (${dna.manufacturer} ${dna.model}):")
                appendLine("• Hardware: ${dna.socName} (${dna.cpuCores} cores) + ${dna.gpuRenderer}.")
                appendLine("• Current Privilege Mode: ${dna.privilegeTier.badge}.")
                appendLine("• Top Recommendations: Use 0.5x animations for instant UI speed, enable Private DNS for privacy, and save a Time Machine snapshot first.")
            }
        }
    }
}

// ---------- SETTINGS & UTILITIES HUB SCREEN ----------

@Composable
fun SettingsAndToolsScreen(
    dna: DeviceDna?,
    themeConfig: GlassThemeConfig,
    onUpdateTheme: (GlassThemeConfig) -> Unit,
    onOpenRootLab: () -> Unit,
    onOpenSmartRules: () -> Unit,
    onRefreshDna: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = LocalGlassConfig.current

    var subTab by remember { mutableIntStateOf(0) } // 0=Health+AI, 1=Hardware, 2=Storage+Apps, 3=Settings
    var appsAudit by remember { mutableStateOf<List<AppExposureInfo>>(emptyList()) }
    var appSearch by remember { mutableStateOf("") }
    var largeFiles by remember { mutableStateOf<List<ScannedFileItem>>(emptyList()) }
    var scanningStorage by remember { mutableStateOf(false) }
    var confirmDeleteFile by remember { mutableStateOf<ScannedFileItem?>(null) }
    var aiResponse by remember { mutableStateOf<String?>(null) }
    var updates by remember { mutableStateOf<List<UpdateRecord>>(emptyList()) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    val sensors = remember {
        val sm = context.getSystemService(SensorManager::class.java)
        sm?.getSensorList(Sensor.TYPE_ALL)?.map { "${it.name} (${it.vendor})" } ?: emptyList()
    }

    LaunchedEffect(subTab, dna?.buildFingerprint) {
        if (subTab == 2 && appsAudit.isEmpty()) {
            appsAudit = ToolsEngine.scanAppsPrivacy(context)
        }
        if (subTab == 3 && dna != null) {
            updates = withContext(Dispatchers.IO) { AppPrefs.recordAndLoadUpdates(context, dna) }
        }
    }

    if (dna == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = cfg.accent.secondary)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("DIAGNOSTICS & CONTROL", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Text("Tools & Settings", style = MaterialTheme.typography.headlineSmall)
                }
                GlassPillButton(
                    text = "Root Lab",
                    onClick = onOpenRootLab,
                    gradient = listOf(cfg.accent.primary, GlassPalette.WarmAmber)
                )
            }
        }

        item {
            GlassSegmentedTabs(
                items = listOf("Health", "Hardware", "Apps", "Settings"),
                selectedIndex = subTab,
                onSelect = { subTab = it }
            )
        }

        toastMsg?.let { m ->
            item {
                GlassCard(tint = GlassPalette.CyberCyan, glowAccent = GlassPalette.CyberCyan) {
                    Text(m, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // SUB-TAB 0: DEVICE HEALTH SCANNER + TWEAKORA INTELLIGENCE AI
        if (subTab == 0) {
            item {
                GlassActionBanner(
                    title = "Smart Rules & Optimization Simulator",
                    subtitle = "Automate thermal/battery alerts & preview tweaks before applying",
                    icon = Icons.Rounded.Bolt,
                    onClick = onOpenSmartRules
                )
            }

            item {
                GlassCard(tint = cfg.accent.primary, glowAccent = cfg.accent.primary) {
                    Text("TWEAKORA INTELLIGENCE (OFFLINE)", style = MaterialTheme.typography.labelMedium, color = cfg.accent.primary)
                    Spacer(Modifier.height(6.dp))
                    Text("Tap a diagnostic prompt to analyze your real device telemetry:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    val prompts = listOf("Analyze My Phone", "Why Is It Heating?", "Check Bottlenecks", "Battery Drain Audit")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(prompts) { p ->
                            FilterChip(
                                selected = false,
                                onClick = { aiResponse = ToolsEngine.answerDiagnosticQuery(dna, p) },
                                label = { Text(p, fontSize = 12.5.sp) }
                            )
                        }
                    }
                    aiResponse?.let { ans ->
                        Spacer(Modifier.height(12.dp))
                        Text(ans.trim(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            val checks = ToolsEngine.runHealthScan(dna)
            items(checks) { item ->
                val c = when (item.status) {
                    HealthStatus.GOOD -> GlassPalette.StatusGood
                    HealthStatus.NEEDS_ATTENTION -> GlassPalette.WarmAmber
                    HealthStatus.CRITICAL -> GlassPalette.StatusDanger
                }
                GlassCard(glowAccent = c) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text(item.detail, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        GlassStatusPill(item.status.label.uppercase(), c)
                    }
                }
            }
        }

        // SUB-TAB 1: HARDWARE INSPECTOR + EXPORT
        if (subTab == 1) {
            item {
                GlassActionBanner(
                    title = "Export Complete Device DNA Report",
                    subtitle = "Copy SoC, GPU, Display, Kernel & ${sensors.size} Sensors",
                    icon = Icons.Rounded.ContentCopy,
                    onClick = {
                        val report = buildString {
                            appendLine("=== TWEAKORA SYSTEM DNA REPORT ===")
                            appendLine("Device: ${dna.manufacturer} ${dna.model} (${dna.deviceCodeName})")
                            appendLine("SoC: ${dna.socName} | Cores: ${dna.cpuCores} | ABIs: ${dna.supportedAbis.joinToString()}")
                            appendLine("GPU: ${dna.gpuRenderer} (${dna.gpuVendor})")
                            appendLine("OpenGL: ${dna.gpuGlVersion}")
                            appendLine("Display: ${dna.displayText}")
                            appendLine("RAM: ${dna.totalRamMb} MB | Storage: ${String.format(Locale.US, "%.1f GB", dna.totalStorageGb)}")
                            appendLine("Android: ${dna.androidVersion} (API ${dna.sdkInt}) | Patch: ${dna.securityPatch}")
                            appendLine("Kernel: ${dna.kernelVersion}")
                            appendLine("Sensors (${sensors.size}): ${sensors.take(15).joinToString("; ")}")
                        }
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("Tweakora Hardware Report", report))
                        toastMsg = "Complete Hardware Report copied to clipboard."
                    }
                )
            }

            item {
                GlassCard {
                    Text("PROCESSOR & GRAPHICS", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Spacer(Modifier.height(8.dp))
                    GlassSpecRow("Model", "${dna.manufacturer} ${dna.model}")
                    GlassSpecRow("Codename", dna.deviceCodeName)
                    GlassSpecRow("Processor", dna.socName)
                    GlassSpecRow("Architecture", dna.supportedAbis.joinToString(", "))
                    GlassSpecRow("CPU Cores", "${dna.cpuCores}")
                    GlassSpecRow("GPU Renderer", dna.gpuRenderer)
                    GlassSpecRow("GPU Vendor", dna.gpuVendor)
                    GlassSpecRow("OpenGL ES", dna.gpuGlVersion)
                }
            }

            item {
                GlassCard {
                    Text("SYSTEM & DISPLAY", style = MaterialTheme.typography.labelMedium, color = cfg.accent.primary)
                    Spacer(Modifier.height(8.dp))
                    GlassSpecRow("Display", dna.displayText)
                    GlassSpecRow("Android", "${dna.androidVersion} (API ${dna.sdkInt})")
                    GlassSpecRow("Security Patch", dna.securityPatch)
                    GlassSpecRow("Build ID", dna.buildId)
                    GlassSpecRow("Kernel", dna.kernelVersion)
                }
            }

            item {
                GlassCard {
                    Text("PHYSICAL SENSORS (${sensors.size})", style = MaterialTheme.typography.labelMedium, color = GlassPalette.WarmAmber)
                    Spacer(Modifier.height(8.dp))
                    sensors.take(20).forEach { s ->
                        Text("• $s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(3.dp))
                    }
                }
            }
        }

        // SUB-TAB 2: STORAGE INTELLIGENCE + FULL APP & PRIVACY MANAGER
        if (subTab == 2) {
            item {
                GlassCard(glowAccent = cfg.accent.secondary) {
                    Text("STORAGE INTELLIGENCE", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Spacer(Modifier.height(8.dp))
                    GlassGaugeRow(
                        "Internal Storage",
                        String.format(Locale.US, "%.1f / %.1f GB (%.1f GB free)", dna.usedStorageGb, dna.totalStorageGb, dna.freeStorageGb),
                        dna.storageFraction,
                        cfg.accent.secondary
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPillButton(
                            text = if (scanningStorage) "Scanning..." else "Scan Large Files",
                            onClick = {
                                scope.launch {
                                    scanningStorage = true
                                    largeFiles = ToolsEngine.scanLargeFiles()
                                    scanningStorage = false
                                    if (largeFiles.isEmpty()) {
                                        toastMsg = "No >10MB files accessible. Tap Grant Access to scan all folders."
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                            GlassPillButton(
                                text = "Grant Access",
                                onClick = {
                                    runCatching {
                                        val i = Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(i)
                                    }
                                },
                                gradient = listOf(GlassPalette.WarmAmber, GlassPalette.NeonMagenta)
                            )
                        }
                    }
                }
            }

            if (largeFiles.isNotEmpty()) {
                items(largeFiles, key = { it.path }) { f ->
                    GlassCard(glowAccent = if (f.isDuplicateHint) GlassPalette.WarmAmber else cfg.accent.secondary) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text(
                                    String.format(Locale.US, "%.1f MB%s", f.sizeMb, if (f.isDuplicateHint) " • Possible Duplicate" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (f.isDuplicateHint) GlassPalette.WarmAmber else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { confirmDeleteFile = f }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = GlassPalette.StatusDanger)
                            }
                        }
                    }
                }
            }

            item {
                GlassCard {
                    Text("APP MANAGER & PRIVACY EXPOSURE (${appsAudit.size})", style = MaterialTheme.typography.labelMedium, color = cfg.accent.primary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = appSearch,
                        onValueChange = { appSearch = it },
                        placeholder = { Text("Search installed apps...") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            val filteredApps = appsAudit.filter {
                appSearch.isBlank() || it.appName.contains(appSearch, ignoreCase = true) || it.packageName.contains(appSearch, ignoreCase = true)
            }.take(30)

            items(filteredApps, key = { it.packageName }) { app ->
                val expColor = when {
                    app.exposureScore >= 60 -> GlassPalette.StatusDanger
                    app.exposureScore >= 30 -> GlassPalette.WarmAmber
                    else -> GlassPalette.StatusGood
                }
                GlassCard(glowAccent = expColor) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(app.appName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (app.riskyPermissions.isEmpty()) "Clean permissions"
                                else "Active: ${app.riskyPermissions.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        GlassStatusPill("RISK ${app.exposureScore}", expColor)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            context.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(it)
                            }
                        }) { Text("Launch", fontSize = 13.sp, color = cfg.accent.secondary) }

                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) { Text("App Info", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface) }

                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) { Text("Uninstall", fontSize = 13.sp, color = GlassPalette.StatusDanger) }
                    }
                }
            }
        }

        // SUB-TAB 3: SETTINGS, PERSONALIZATION, UPDATE TRACKER & ABOUT DEVELOPER
        if (subTab == 3) {
            item {
                GlassCard(glowAccent = cfg.accent.primary) {
                    Text("LIQUID GLASS THEME & ACCENTS", style = MaterialTheme.typography.labelMedium, color = cfg.accent.primary)
                    Spacer(Modifier.height(10.dp))
                    GlassSegmentedTabs(
                        items = GlassThemeMode.entries.map { it.label },
                        selectedIndex = GlassThemeMode.entries.indexOf(themeConfig.mode),
                        onSelect = { idx -> onUpdateTheme(themeConfig.copy(mode = GlassThemeMode.entries[idx])) }
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(GlassAccent.entries) { a ->
                            FilterChip(
                                selected = themeConfig.accent == a,
                                onClick = { onUpdateTheme(themeConfig.copy(accent = a)) },
                                label = { Text(a.label, fontSize = 12.5.sp) }
                            )
                        }
                    }
                }
            }

            item {
                GlassCard {
                    Text("PERMISSIONS HUB", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassPillButton(
                            text = "Grant Shizuku",
                            onClick = {
                                val ok = PrivilegedRunner.requestShizuku()
                                toastMsg = if (ok) "Shizuku prompt opened." else "Start Shizuku app first."
                                onRefreshDna()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        GlassPillButton(
                            text = "Usage Access",
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            },
                            gradient = listOf(cfg.accent.primary, cfg.accent.glow),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item {
                GlassCard {
                    Text("UPDATE IMPACT TRACKER", style = MaterialTheme.typography.labelMedium, color = GlassPalette.WarmAmber)
                    Spacer(Modifier.height(8.dp))
                    updates.forEach { u ->
                        val date = SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date(u.timestampMs))
                        Text("Android ${u.androidVersion} • Patch ${u.securityPatch} ($date)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Kernel: ${u.kernel}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            // DEDICATED ABOUT DEVELOPER CARD
            item {
                GlassCard(tint = cfg.accent.primary, glowAccent = cfg.accent.secondary) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Person, contentDescription = null, tint = cfg.accent.secondary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("ABOUT DEVELOPER", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                        }
                        GlassStatusPill("CREATOR", cfg.accent.primary)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Shahalam Laskar", style = MaterialTheme.typography.headlineSmall, fontSize = 21.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Independent Android developer building Tweakora — a transparent, zero-fake-controls smartphone control centre crafted for real performance, diagnostics, and user control.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    GlassSpecRow("Developer Name", "Shahalam Laskar")
                    GlassSpecRow("Email", "shahalamlaskar.official@gmail.com")
                    GlassSpecRow("App Version", "Tweakora ${BuildConfig.VERSION_NAME}")
                    GlassSpecRow("Tagline", "More Control. More Power.")
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassPillButton(
                            text = "Email Developer",
                            onClick = {
                                runCatching {
                                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:shahalamlaskar.official@gmail.com")).apply {
                                        putExtra(Intent.EXTRA_SUBJECT, "Tweakora Feedback")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(i)
                                }.onFailure {
                                    toastMsg = "No email app found. Email copied instead."
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        GlassPillButton(
                            text = "Copy Email",
                            onClick = {
                                val cm = context.getSystemService(ClipboardManager::class.java)
                                cm?.setPrimaryClip(ClipData.newPlainText("Developer Email", "shahalamlaskar.official@gmail.com"))
                                toastMsg = "Developer email copied to clipboard."
                            },
                            gradient = listOf(cfg.accent.primary, cfg.accent.glow),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    confirmDeleteFile?.let { fileItem ->
        AlertDialog(
            onDismissRequest = { confirmDeleteFile = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Confirm Safe Cleanup", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "Permanently delete '${fileItem.name}' (${String.format(Locale.US, "%.1f MB", fileItem.sizeMb)})?\nPath: ${fileItem.path}",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val deleted = runCatching { File(fileItem.path).delete() }.getOrDefault(false)
                    toastMsg = if (deleted) "Deleted ${fileItem.name}." else "Could not delete file (grant All Files Access)."
                    if (deleted) largeFiles = largeFiles.filterNot { it.path == fileItem.path }
                    confirmDeleteFile = null
                }) { Text("Delete", color = GlassPalette.StatusDanger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFile = null }) { Text("Cancel") }
            }
        )
    }
}

// ---------- ROOT LABORATORY SCREEN ----------

@Composable
fun RootLabScreen(
    dna: DeviceDna?,
    onBack: () -> Unit,
    onRefreshDna: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = LocalGlassConfig.current

    var currentGov by remember { mutableStateOf("Reading...") }
    var availableGovs by remember { mutableStateOf<List<String>>(emptyList()) }
    var swappiness by remember { mutableStateOf("Reading...") }
    var propQuery by remember { mutableStateOf("") }
    var propsList by remember { mutableStateOf<List<String>>(emptyList()) }
    var vault by remember { mutableStateOf<List<KernelVaultProfile>>(emptyList()) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    val rootMethod = remember {
        when {
            File("/data/adb/ksu").exists() -> "KernelSU Detected"
            File("/data/adb/magisk").exists() -> "Magisk Detected"
            File("/data/adb/ap").exists() -> "APatch Detected"
            File("/system/bin/su").exists() || File("/system/xbin/su").exists() -> "SU Binary Detected"
            else -> "No Root Binary Found"
        }
    }

    fun loadRootTelemetry() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val isRoot = Shell.isAppGrantedRoot() == true
                currentGov = if (isRoot) {
                    Shell.cmd("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").exec().out.firstOrNull() ?: "Unavailable"
                } else {
                    runCatching { File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").readText().trim() }.getOrDefault("Root Required")
                }
                availableGovs = if (isRoot) {
                    Shell.cmd("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors").exec().out
                        .firstOrNull()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
                } else emptyList()
                swappiness = if (isRoot) {
                    Shell.cmd("cat /proc/sys/vm/swappiness").exec().out.firstOrNull() ?: "Unavailable"
                } else {
                    runCatching { File("/proc/sys/vm/swappiness").readText().trim() }.getOrDefault("Root Required")
                }
                propsList = runCatching {
                    val p = Runtime.getRuntime().exec("getprop")
                    p.inputStream.bufferedReader().readLines().take(250)
                }.getOrDefault(emptyList())
                vault = AppPrefs.loadKernelVault(context)
            }
        }
    }

    LaunchedEffect(Unit) { loadRootTelemetry() }

    val isRooted = dna?.privilegeTier == PrivilegeTier.ROOT

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("ADVANCED KERNEL INTERFACE", style = MaterialTheme.typography.labelMedium, color = GlassPalette.WarmAmber)
                    Text("Root Laboratory", style = MaterialTheme.typography.headlineSmall)
                }
                GlassPillButton(text = "Back", onClick = onBack, gradient = listOf(cfg.accent.primary, cfg.accent.secondary))
            }
        }

        item {
            GlassCard(
                tint = if (isRooted) GlassPalette.StatusGood else GlassPalette.WarmAmber,
                glowAccent = if (isRooted) GlassPalette.StatusGood else GlassPalette.WarmAmber
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SUPERUSER & SAFETY GUARD", style = MaterialTheme.typography.labelMedium, color = if (isRooted) GlassPalette.StatusGood else GlassPalette.WarmAmber)
                        Text(if (isRooted) "Root Access Active" else rootMethod, style = MaterialTheme.typography.titleMedium)
                        Text("Kernel: ${dna?.kernelVersion ?: "Unknown"}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    GlassPillButton(text = "Check Root", onClick = { onRefreshDna(); loadRootTelemetry() })
                }
            }
        }

        statusMsg?.let { msg ->
            item {
                GlassCard(tint = GlassPalette.CyberCyan, glowAccent = GlassPalette.CyberCyan) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            GlassCard(glowAccent = cfg.accent.primary) {
                Text("CPU GOVERNOR & KERNEL VM", style = MaterialTheme.typography.labelMedium, color = cfg.accent.primary)
                Spacer(Modifier.height(8.dp))
                GlassSpecRow("Active Governor (CPU0)", currentGov)
                GlassSpecRow("VM Swappiness", swappiness)
                if (availableGovs.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(availableGovs) { gov ->
                            FilterChip(
                                selected = gov == currentGov,
                                onClick = {
                                    scope.launch {
                                        val (ok, out) = PrivilegedRunner.runShell(
                                            "for c in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo '$gov' > \"\$c\"; done",
                                            requireRoot = true
                                        )
                                        statusMsg = if (ok) "Applied CPU governor: $gov" else out
                                        loadRootTelemetry()
                                    }
                                },
                                label = { Text(gov, fontSize = 12.sp) }
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text("Grant Superuser permission to unlock live CPU governor & kernel frequency tuning.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            GlassCard(glowAccent = cfg.accent.secondary) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("KERNEL PROFILE VAULT", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    GlassPillButton(
                        text = "Save Current",
                        onClick = {
                            val label = "Vault ${SimpleDateFormat("HH:mm", Locale.US).format(Date())}"
                            AppPrefs.saveKernelVaultProfile(context, KernelVaultProfile(label, currentGov, swappiness, System.currentTimeMillis()))
                            loadRootTelemetry()
                            statusMsg = "Saved '$label' to Kernel Profile Vault."
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (vault.isEmpty()) {
                    Text("Save your working kernel configuration before testing changes.", style = MaterialTheme.typography.bodySmall)
                } else {
                    vault.forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text("Gov: ${p.governor} • Swap: ${p.swappiness}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    val (ok, out) = PrivilegedRunner.runShell(
                                        "echo '${p.governor}' > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor && echo '${p.swappiness}' > /proc/sys/vm/swappiness",
                                        requireRoot = true
                                    )
                                    statusMsg = if (ok) "Restored ${p.name}" else out
                                    loadRootTelemetry()
                                }
                            }) { Text("Restore", color = GlassPalette.CyberCyan, fontSize = 13.sp) }
                        }
                    }
                }
            }
        }

        item {
            GlassCard {
                Text("SYSTEM PROPERTY VIEWER (getprop)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = propQuery,
                    onValueChange = { propQuery = it },
                    placeholder = { Text("Filter properties (e.g. ro.soc, debug)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                val filteredProps = propsList.filter { propQuery.isBlank() || it.contains(propQuery, ignoreCase = true) }.take(25)
                filteredProps.forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}
