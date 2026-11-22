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

data class TweakoraSettings(
    val pollIntervalSec: Int = 4,
    val offlineMode: Boolean = false,
    val chargeGuardEnabled: Boolean = false,
    val chargeLimitPct: Int = 80
)

object AppPrefs {
    private const val PREFS = "tweakora_app_prefs"
    private const val UPDATES_FILE = "tweakora_update_tracker.json"
    private const val VAULT_FILE = "tweakora_kernel_vault.json"

    fun loadThemeConfig(context: Context): GlassThemeConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = runCatching { GlassThemeMode.valueOf(p.getString("theme_mode", "") ?: "") }.getOrDefault(GlassThemeMode.MIDNIGHT)
        val accent = runCatching { GlassAccent.valueOf(p.getString("theme_accent", "") ?: "") }.getOrDefault(GlassAccent.NEON_PURPLE)
        return GlassThemeConfig(mode, accent)
    }

    fun saveThemeConfig(context: Context, config: GlassThemeConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("theme_mode", config.mode.name).putString("theme_accent", config.accent.name).apply()
    }

    fun loadSettings(context: Context): TweakoraSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TweakoraSettings(
            pollIntervalSec = p.getInt("poll_sec", 4),
            offlineMode = p.getBoolean("offline_mode", false),
            chargeGuardEnabled = p.getBoolean("charge_guard", false),
            chargeLimitPct = p.getInt("charge_limit", 80)
        )
    }

    fun saveSettings(context: Context, s: TweakoraSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("poll_sec", s.pollIntervalSec).putBoolean("offline_mode", s.offlineMode)
            .putBoolean("charge_guard", s.chargeGuardEnabled).putInt("charge_limit", s.chargeLimitPct).apply()
    }

    fun resetAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun recordAndLoadUpdates(context: Context, dna: DeviceDna): List<UpdateRecord> {
        val file = File(context.filesDir, UPDATES_FILE)
        val existing = runCatching {
            if (!file.exists()) emptyList()
            else {
                val arr = JSONArray(file.readText())
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    UpdateRecord(o.optLong("timestampMs"), o.optString("androidVersion"), o.optString("securityPatch"), o.optString("fingerprint"), o.optString("kernel"))
                }
            }
        }.getOrDefault(emptyList())
        val latest = existing.firstOrNull()
        val updated = if (latest == null || latest.fingerprint != dna.buildFingerprint || latest.kernel != dna.kernelVersion) {
            listOf(UpdateRecord(System.currentTimeMillis(), dna.androidVersion, dna.securityPatch, dna.buildFingerprint, dna.kernelVersion)) + existing
        } else existing
        if (updated.size != existing.size) {
            val arr = JSONArray()
            updated.take(15).forEach { r ->
                arr.put(JSONObject().apply {
                    put("timestampMs", r.timestampMs); put("androidVersion", r.androidVersion)
                    put("securityPatch", r.securityPatch); put("fingerprint", r.fingerprint); put("kernel", r.kernel)
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
            arr.put(JSONObject().apply { put("name", p.name); put("governor", p.governor); put("swappiness", p.swappiness); put("savedAt", p.savedAt) })
        }
        File(context.filesDir, VAULT_FILE).writeText(arr.toString())
    }
}

data class UpdateRecord(val timestampMs: Long, val androidVersion: String, val securityPatch: String, val fingerprint: String, val kernel: String)
data class KernelVaultProfile(val name: String, val governor: String, val swappiness: String, val savedAt: Long)
data class HealthCheckItem(val title: String, val detail: String, val status: HealthStatus)
data class AppExposureInfo(val packageName: String, val appName: String, val exposureScore: Int, val riskyPermissions: List<String>)
data class ScannedFileItem(val path: String, val name: String, val sizeMb: Float, val isDuplicateHint: Boolean)

object ToolsEngine {
    fun runHealthScan(dna: DeviceDna): List<HealthCheckItem> {
        val ramPct = (dna.ramFraction * 100).toInt()
        val storagePct = (dna.storageFraction * 100).toInt()
        return listOf(
            HealthCheckItem("Internal Storage", "${String.format(Locale.US, "%.1f GB", dna.freeStorageGb)} free ($storagePct% used)", when { storagePct >= 90 -> HealthStatus.CRITICAL; storagePct >= 80 -> HealthStatus.NEEDS_ATTENTION; else -> HealthStatus.GOOD }),
            HealthCheckItem("Memory Pressure", "${dna.usedRamMb} / ${dna.totalRamMb} MB ($ramPct% used)", when { ramPct >= 88 -> HealthStatus.CRITICAL; ramPct >= 78 -> HealthStatus.NEEDS_ATTENTION; else -> HealthStatus.GOOD }),
            HealthCheckItem("Battery Health", "${dna.batteryPercent}% • ${dna.batteryHealth} • ${dna.batteryVoltageMv} mV", if (dna.batteryHealth == "Good" || dna.batteryHealth == "Unknown") HealthStatus.GOOD else HealthStatus.NEEDS_ATTENTION),
            HealthCheckItem("Thermal Envelope", "${String.format(Locale.US, "%.1f°C", dna.batteryTempC)} • ${dna.thermalStatus}", when { dna.batteryTempC >= 42f -> HealthStatus.CRITICAL; dna.batteryTempC >= 38f -> HealthStatus.NEEDS_ATTENTION; else -> HealthStatus.GOOD }),
            HealthCheckItem("Android Patch", "Android ${dna.androidVersion} • Patch ${dna.securityPatch}", HealthStatus.GOOD),
            HealthCheckItem("Privilege Tier", "Running in ${dna.privilegeTier.badge} mode", HealthStatus.GOOD)
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
        runCatching { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) }.getOrDefault(emptyList()).mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || pi.packageName == context.packageName) return@mapNotNull null
            val req = pi.requestedPermissions ?: emptyArray()
            val flg = pi.requestedPermissionsFlags ?: IntArray(0)
            var score = 0; val granted = mutableListOf<String>()
            req.forEachIndexed { idx, perm ->
                if (idx < flg.size && (flg[idx] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                    weights[perm]?.let { granted.add(it.first); score += it.second }
                }
            }
            AppExposureInfo(pi.packageName, pm.getApplicationLabel(ai).toString(), score.coerceAtMost(100), granted)
        }.sortedByDescending { it.exposureScore }
    }

    suspend fun scanLargeFiles(): List<ScannedFileItem> = withContext(Dispatchers.IO) {
        val roots = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        ).filter { it.exists() && it.canRead() }
        val found = mutableListOf<File>()
        roots.forEach { d -> runCatching { d.walkTopDown().maxDepth(3).forEach { f -> if (f.isFile && f.length() >= 10L * 1024L * 1024L) found.add(f) } } }
        val groups = found.groupBy { it.length() }
        found.sortedByDescending { it.length() }.take(30).map { f ->
            ScannedFileItem(f.absolutePath, f.name, f.length() / (1024f * 1024f), (groups[f.length()]?.size ?: 0) > 1)
        }
    }

    fun answerDiagnosticQuery(dna: DeviceDna, prompt: String): String {
        val q = prompt.lowercase()
        return when {
            q.contains("heat") || q.contains("thermal") -> "Thermal Diagnosis (${String.format(Locale.US, "%.1f°C", dna.batteryTempC)} — ${dna.thermalStatus}):\n" +
                if (dna.batteryTempC >= 39f) "• Elevated heat detected. Use Bypass Charging / Smart Charge Guard and cap refresh rate to 60–90Hz."
                else "• Cool & healthy (${dna.batteryTempC}°C). No thermal throttling detected."
            q.contains("battery") -> "Battery Audit (${dna.batteryPercent}% • ${dna.batteryHealth} • ${dna.batteryVoltageMv} mV):\n• Enable Cached Apps Freezer & Wi-Fi Scan Throttling in Tweaks, and keep Tweakora in 4s Eco polling mode."
            q.contains("bottleneck") -> "Bottleneck Analysis (${dna.manufacturer} ${dna.model}):\n• RAM: ${(dna.ramFraction * 100).toInt()}% used — ${if (dna.ramFraction > 0.82f) "Run RAM Cleaner before gaming." else "Healthy."}\n• Storage: ${String.format(Locale.US, "%.1f GB", dna.freeStorageGb)} free."
            else -> "System Summary (${dna.manufacturer} ${dna.model}):\n• ${dna.socName} (${dna.cpuCores} cores) + ${dna.gpuRenderer}.\n• Tier: ${dna.privilegeTier.badge}. Save a Time Machine snapshot before applying tweaks."
        }
    }
}

@Composable
fun SettingsAndToolsScreen(
    dna: DeviceDna?,
    themeConfig: GlassThemeConfig,
    settings: TweakoraSettings,
    onUpdateTheme: (GlassThemeConfig) -> Unit,
    onUpdateSettings: (TweakoraSettings) -> Unit,
    onResetAll: () -> Unit,
    onOpenCleaner: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenRootLab: () -> Unit,
    onOpenSmartRules: () -> Unit,
    onRefreshDna: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = LocalGlassConfig.current

    var subTab by remember { mutableIntStateOf(0) }
    var sensors by remember { mutableStateOf<List<String>>(emptyList()) }
    var appsAudit by remember { mutableStateOf<List<AppExposureInfo>>(emptyList()) }
    var appSearch by remember { mutableStateOf("") }
    var largeFiles by remember { mutableStateOf<List<ScannedFileItem>>(emptyList()) }
    var scanningStorage by remember { mutableStateOf(false) }
    var confirmDeleteFile by remember { mutableStateOf<ScannedFileItem?>(null) }
    var aiResponse by remember { mutableStateOf<String?>(null) }
    var updates by remember { mutableStateOf<List<UpdateRecord>>(emptyList()) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    // Off-main-thread lazy loading so switching to Tools is 100% instant
    LaunchedEffect(subTab, dna?.buildFingerprint) {
        when (subTab) {
            1 -> if (sensors.isEmpty()) {
                sensors = withContext(Dispatchers.IO) {
                    context.getSystemService(SensorManager::class.java)
                        ?.getSensorList(Sensor.TYPE_ALL)?.map { "${it.name} (${it.vendor})" } ?: emptyList()
                }
            }
            2 -> if (appsAudit.isEmpty()) {
                appsAudit = ToolsEngine.scanAppsPrivacy(context)
            }
            3 -> if (dna != null && updates.isEmpty()) {
                updates = withContext(Dispatchers.IO) { AppPrefs.recordAndLoadUpdates(context, dna) }
            }
        }
    }

    if (dna == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = cfg.accent.secondary) }
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
                    Text("SYSTEM MANAGEMENT", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Text("Tools & Settings", style = MaterialTheme.typography.headlineSmall)
                }
                GlassPillButton("Root Lab", onClick = onOpenRootLab, gradient = listOf(cfg.accent.primary, GlassPalette.WarmAmber))
            }
        }

        item {
            GlassSegmentedTabs(listOf("Diagnostics", "Hardware", "Apps", "Settings"), selectedIndex = subTab, onSelect = { subTab = it })
        }

        toastMsg?.let { m -> item { GlassCard(tint = GlassPalette.CyberCyan, glowAccent = GlassPalette.CyberCyan) { Text(m, style = MaterialTheme.typography.bodyMedium) } } }

        // TAB 0: ORGANIZED DIAGNOSTICS, LAB DIRECTORY & AI
        if (subTab == 0) {
            item {
                GlassBox(title = "Specialized Labs", accent = cfg.accent.secondary, badge = "4 HUBS") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassBoxTile("Cleaner", "RAM & Bypass", Icons.Rounded.CleaningServices, GlassPalette.StatusGood, onOpenCleaner, Modifier.weight(1f))
                        GlassBoxTile("Sources", "Sensors+Audio", Icons.Rounded.Sensors, cfg.accent.secondary, onOpenSources, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassBoxTile("Smart Rules", "Auto & Sim", Icons.Rounded.Bolt, GlassPalette.NeonMagenta, onOpenSmartRules, Modifier.weight(1f))
                        GlassBoxTile("Root Lab", dna.privilegeTier.badge, Icons.Rounded.Science, GlassPalette.WarmAmber, onOpenRootLab, Modifier.weight(1f))
                    }
                }
            }
            item {
                GlassBox(title = "Tweakora Intelligence (Offline)", accent = cfg.accent.primary, badge = "LOCAL AI") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("Analyze My Phone", "Why Is It Heating?", "Check Bottlenecks", "Battery Audit")) { p ->
                            FilterChip(selected = false, onClick = { aiResponse = ToolsEngine.answerDiagnosticQuery(dna, p) }, label = { Text(p, fontSize = 12.sp) })
                        }
                    }
                    aiResponse?.let { Text(it.trim(), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item {
                GlassBox(title = "Device Health Audit", accent = GlassPalette.StatusGood, badge = "${dna.healthScore}/100") {
                    ToolsEngine.runHealthScan(dna).forEach { item ->
                        val c = when (item.status) { HealthStatus.GOOD -> GlassPalette.StatusGood; HealthStatus.NEEDS_ATTENTION -> GlassPalette.WarmAmber; HealthStatus.CRITICAL -> GlassPalette.StatusDanger }
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(item.detail, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.width(8.dp))
                            GlassStatusPill(item.status.label.uppercase(), c)
                        }
                    }
                }
            }
        }

        // TAB 1: ORGANIZED HARDWARE INSPECTOR
        if (subTab == 1) {
            item {
                GlassBox(title = "Compute & Graphics DNA", accent = cfg.accent.secondary, badge = "${dna.cpuCores} CORES") {
                    GlassSpecRow("Model", "${dna.manufacturer} ${dna.model} (${dna.deviceCodeName})")
                    GlassSpecRow("Processor", dna.socName)
                    GlassSpecRow("Architecture", dna.supportedAbis.joinToString(", "))
                    GlassSpecRow("GPU Renderer", dna.gpuRenderer)
                    GlassSpecRow("OpenGL ES", dna.gpuGlVersion)
                    GlassSpecRow("Display", dna.displayText)
                    GlassSpecRow("Android", "${dna.androidVersion} (API ${dna.sdkInt}) • ${dna.securityPatch}")
                    GlassSpecRow("Kernel", dna.kernelVersion)
                    GlassPillButton(
                        text = "Copy Complete Hardware DNA Report",
                        onClick = {
                            val report = "=== TWEAKORA DNA ===\nDevice: ${dna.manufacturer} ${dna.model} (${dna.deviceCodeName})\nSoC: ${dna.socName} (${dna.cpuCores}c)\nGPU: ${dna.gpuRenderer}\nDisplay: ${dna.displayText}\nRAM: ${dna.totalRamMb} MB | Android ${dna.androidVersion} (${dna.securityPatch})\nKernel: ${dna.kernelVersion}"
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Tweakora DNA", report))
                            toastMsg = "Hardware DNA report copied."
                        },
                        gradient = listOf(cfg.accent.primary, cfg.accent.secondary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                GlassBox(title = "Hardware Sensors", accent = GlassPalette.WarmAmber, badge = "${sensors.size}", collapsible = true, initiallyExpanded = true) {
                    if (sensors.isEmpty()) Text("Loading sensors...", style = MaterialTheme.typography.bodySmall)
                    else sensors.take(20).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
                }
            }
        }

        // TAB 2: ORGANIZED STORAGE & APP PRIVACY MANAGER
        if (subTab == 2) {
            item {
                GlassBox(title = "Storage & Special Access", accent = cfg.accent.secondary, badge = "${String.format(Locale.US, "%.0f", dna.freeStorageGb)} GB FREE") {
                    GlassGaugeRow("Internal Storage", String.format(Locale.US, "%.1f / %.1f GB", dna.usedStorageGb, dna.totalStorageGb), dna.storageFraction, cfg.accent.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPillButton(if (scanningStorage) "Scanning..." else "Scan Large Files", onClick = {
                            scope.launch { scanningStorage = true; largeFiles = ToolsEngine.scanLargeFiles(); scanningStorage = false }
                        }, modifier = Modifier.weight(1f))
                        GlassPillButton("Special Access", onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }, gradient = listOf(cfg.accent.primary, GlassPalette.WarmAmber), modifier = Modifier.weight(1f))
                    }
                }
            }
            items(largeFiles, key = { it.path }) { f ->
                GlassCard(glowAccent = if (f.isDuplicateHint) GlassPalette.WarmAmber else cfg.accent.secondary) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(String.format(Locale.US, "%.1f MB%s", f.sizeMb, if (f.isDuplicateHint) " • Duplicate Hint" else ""), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { confirmDeleteFile = f }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = GlassPalette.StatusDanger) }
                    }
                }
            }
            item {
                GlassBox(title = "Installed Apps & Privacy Score", accent = cfg.accent.primary, badge = "${appsAudit.size}") {
                    OutlinedTextField(value = appSearch, onValueChange = { appSearch = it }, placeholder = { Text("Search installed apps...") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
            val filteredApps = appsAudit.filter { appSearch.isBlank() || it.appName.contains(appSearch, true) }.take(25)
            items(filteredApps, key = { it.packageName }) { app ->
                val ec = when { app.exposureScore >= 60 -> GlassPalette.StatusDanger; app.exposureScore >= 30 -> GlassPalette.WarmAmber; else -> GlassPalette.StatusGood }
                GlassCard(glowAccent = ec) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(app.appName, style = MaterialTheme.typography.titleMedium)
                            Text(if (app.riskyPermissions.isEmpty()) "Clean permissions" else "Active: ${app.riskyPermissions.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                        }
                        GlassStatusPill("RISK ${app.exposureScore}", ec)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(it) } }) { Text("Launch", color = cfg.accent.secondary) }
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Info") }
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Uninstall", color = GlassPalette.StatusDanger) }
                    }
                }
            }
        }

        // TAB 3: ORGANIZED SETTINGS, ECO MODE, DEFAULT APP & ABOUT DEVELOPER
        if (subTab == 3) {
            item {
                GlassBox(title = "Appearance & Accents", accent = cfg.accent.primary, badge = themeConfig.mode.label.uppercase()) {
                    GlassSegmentedTabs(GlassThemeMode.entries.map { it.label }, GlassThemeMode.entries.indexOf(themeConfig.mode), { onUpdateTheme(themeConfig.copy(mode = GlassThemeMode.entries[it])) })
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(GlassAccent.entries) { a -> FilterChip(selected = themeConfig.accent == a, onClick = { onUpdateTheme(themeConfig.copy(accent = a)) }, label = { Text(a.label, fontSize = 12.sp) }) }
                    }
                }
            }
            item {
                GlassBox(title = "Battery Eco & Default App", accent = GlassPalette.StatusGood, badge = "0% BG DRAIN") {
                    Text("Live telemetry pauses automatically when minimized. Choose foreground refresh rate:", style = MaterialTheme.typography.bodySmall)
                    val intervals = listOf(2, 4, 8, 0)
                    GlassSegmentedTabs(listOf("2s", "4s Eco", "8s", "Manual"), intervals.indexOf(settings.pollIntervalSec).coerceAtLeast(1), { onUpdateSettings(settings.copy(pollIntervalSec = intervals[it])) })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Offline Mode", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = settings.offlineMode, onCheckedChange = { onUpdateSettings(settings.copy(offlineMode = it)) })
                    }
                    GlassPillButton(
                        text = "Set Tweakora as Default Assistant / Control App",
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                toastMsg = "Tap 'Digital assistant app' → select Tweakora."
                            }.onFailure {
                                runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        },
                        gradient = listOf(cfg.accent.primary, cfg.accent.secondary),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPillButton("Grant Shizuku", onClick = {
                            toastMsg = if (PrivilegedRunner.requestShizuku()) "Shizuku prompt opened." else "Start Shizuku app first."
                            onRefreshDna()
                        }, modifier = Modifier.weight(1f))
                        GlassPillButton("Reset Settings", onClick = { onResetAll(); toastMsg = "Settings reset." }, gradient = listOf(GlassPalette.WarmAmber, GlassPalette.StatusDanger), modifier = Modifier.weight(1f))
                    }
                }
            }
            item {
                GlassBox(title = "Update Impact Tracker", accent = GlassPalette.WarmAmber, collapsible = true, initiallyExpanded = false) {
                    updates.forEach { u ->
                        val dt = SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date(u.timestampMs))
                        Text("Android ${u.androidVersion} • Patch ${u.securityPatch} ($dt)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Kernel: ${u.kernel}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
            item {
                GlassBox(title = "About Developer", accent = cfg.accent.secondary, badge = "CREATOR") {
                    Text("Shahalam Laskar", style = MaterialTheme.typography.headlineSmall, fontSize = 20.sp)
                    Text("Independent Android developer building Tweakora — More Control. More Power.", style = MaterialTheme.typography.bodySmall)
                    GlassSpecRow("Email", "shahalamlaskar.official@gmail.com")
                    GlassSpecRow("Version", "Tweakora ${BuildConfig.VERSION_NAME}")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassPillButton("Email Developer", onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:shahalamlaskar.official@gmail.com")).apply {
                                    putExtra(Intent.EXTRA_SUBJECT, "Tweakora Feedback"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            }
                        }, modifier = Modifier.weight(1f))
                        GlassPillButton("Copy Email", onClick = {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Email", "shahalamlaskar.official@gmail.com"))
                            toastMsg = "Developer email copied."
                        }, gradient = listOf(cfg.accent.primary, cfg.accent.glow), modifier = Modifier.weight(1f))
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
            text = { Text("Delete '${fileItem.name}' (${String.format(Locale.US, "%.1f MB", fileItem.sizeMb)})?", style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    val ok = runCatching { File(fileItem.path).delete() }.getOrDefault(false)
                    toastMsg = if (ok) "Deleted ${fileItem.name}." else "Grant All Files Access to delete."
                    if (ok) largeFiles = largeFiles.filterNot { it.path == fileItem.path }
                    confirmDeleteFile = null
                }) { Text("Delete", color = GlassPalette.StatusDanger) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteFile = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun RootLabScreen(dna: DeviceDna?, onBack: () -> Unit, onRefreshDna: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = LocalGlassConfig.current

    var currentGov by remember { mutableStateOf("Reading...") }
    var availableGovs by remember { mutableStateOf<List<String>>(emptyList()) }
    var swappiness by remember { mutableStateOf("Reading...") }
    var modules by remember { mutableStateOf<List<String>>(emptyList()) }
    var bootScripts by remember { mutableStateOf<List<String>>(emptyList()) }
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
                currentGov = if (isRoot) Shell.cmd("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").exec().out.firstOrNull() ?: "Unavailable"
                else runCatching { File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").readText().trim() }.getOrDefault("Root Required")
                availableGovs = if (isRoot) Shell.cmd("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors").exec().out.firstOrNull()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList() else emptyList()
                swappiness = if (isRoot) Shell.cmd("cat /proc/sys/vm/swappiness").exec().out.firstOrNull() ?: "Unavailable"
                else runCatching { File("/proc/sys/vm/swappiness").readText().trim() }.getOrDefault("Root Required")
                modules = if (isRoot) Shell.cmd("ls /data/adb/modules 2>/dev/null").exec().out.filter { it.isNotBlank() } else emptyList()
                bootScripts = if (isRoot) Shell.cmd("ls /data/adb/service.d /data/adb/post-fs-data.d 2>/dev/null").exec().out.filter { it.isNotBlank() && !it.endsWith(":") } else emptyList()
                propsList = runCatching { Runtime.getRuntime().exec("getprop").inputStream.bufferedReader().readLines().take(200) }.getOrDefault(emptyList())
                vault = AppPrefs.loadKernelVault(context)
            }
        }
    }

    LaunchedEffect(Unit) { loadRootTelemetry() }
    val isRooted = dna?.privilegeTier == PrivilegeTier.ROOT

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("ADVANCED KERNEL LAB", style = MaterialTheme.typography.labelMedium, color = GlassPalette.WarmAmber)
                    Text("Root Laboratory", style = MaterialTheme.typography.headlineSmall)
                }
                GlassPillButton("Back", onClick = onBack, gradient = listOf(cfg.accent.primary, cfg.accent.secondary))
            }
        }
        item {
            GlassBox(title = "Superuser & Safety Guard", accent = if (isRooted) GlassPalette.StatusGood else GlassPalette.WarmAmber, badge = if (isRooted) "GRANTED" else "LOCKED") {
                Text(if (isRooted) "Root Access Active" else rootMethod, style = MaterialTheme.typography.titleMedium)
                Text("Kernel: ${dna?.kernelVersion ?: "Unknown"}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                GlassPillButton("Check Root", onClick = { onRefreshDna(); loadRootTelemetry() })
            }
        }
        statusMsg?.let { m -> item { GlassCard(tint = GlassPalette.CyberCyan, glowAccent = GlassPalette.CyberCyan) { Text(m, style = MaterialTheme.typography.bodyMedium) } } }
        item {
            GlassBox(title = "CPU Governor & VM", accent = cfg.accent.primary, badge = currentGov) {
                GlassSpecRow("Active Governor", currentGov)
                GlassSpecRow("VM Swappiness", swappiness)
                if (availableGovs.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(availableGovs) { gov ->
                            FilterChip(selected = gov == currentGov, onClick = {
                                scope.launch {
                                    val (ok, out) = PrivilegedRunner.runShell("for c in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo '$gov' > \"\$c\"; done", true)
                                    statusMsg = if (ok) "Governor set to $gov" else out; loadRootTelemetry()
                                }
                            }, label = { Text(gov, fontSize = 12.sp) })
                        }
                    }
                }
            }
        }
        item {
            GlassBox(title = "Modules & Boot Auditor", accent = cfg.accent.secondary, badge = "${modules.size} MODS") {
                GlassSpecRow("Root Modules", if (isRooted) "${modules.size} detected" else "Root Required")
                if (modules.isNotEmpty()) Text(modules.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = cfg.accent.secondary)
                GlassSpecRow("Boot Scripts", if (isRooted) "${bootScripts.size} script(s)" else "Root Required")
            }
        }
        item {
            GlassBox(title = "Kernel Profile Vault", accent = cfg.accent.primary, badge = "${vault.size} SAVED") {
                GlassPillButton("Save Current Profile", onClick = {
                    val label = "Vault ${SimpleDateFormat("HH:mm", Locale.US).format(Date())}"
                    AppPrefs.saveKernelVaultProfile(context, KernelVaultProfile(label, currentGov, swappiness, System.currentTimeMillis()))
                    loadRootTelemetry(); statusMsg = "Saved '$label' to Vault."
                })
                vault.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold); Text("Gov: ${p.governor} • Swap: ${p.swappiness}", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = {
                            scope.launch {
                                val (ok, out) = PrivilegedRunner.runShell("echo '${p.governor}' > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor && echo '${p.swappiness}' > /proc/sys/vm/swappiness", true)
                                statusMsg = if (ok) "Restored ${p.name}" else out; loadRootTelemetry()
                            }
                        }) { Text("Restore", color = cfg.accent.secondary) }
                    }
                }
            }
        }
        item {
            GlassBox(title = "System Properties (getprop)", accent = GlassPalette.TextMuted, collapsible = true, initiallyExpanded = false) {
                OutlinedTextField(value = propQuery, onValueChange = { propQuery = it }, placeholder = { Text("Filter properties...") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                propsList.filter { propQuery.isBlank() || it.contains(propQuery, true) }.take(20).forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
// END OF TOOLSSETTINGSROOT
