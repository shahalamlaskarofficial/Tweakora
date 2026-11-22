package com.tweakora

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.ui.graphics.Color
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
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TweakCategory(val label: String) {
    ALL("All"), PERFORMANCE("Performance"), BATTERY("Battery"), GAMING("Gaming"),
    THERMAL("Thermal"), DISPLAY("Display"), UI("UI"), NETWORK("Network"),
    PRIVACY("Privacy"), STORAGE("Storage"), BACKGROUND("Background"), ROOT_ONLY("Root Lab")
}

enum class TweakRisk(val badge: String) { SAFE("SAFE"), ADVANCED("ADVANCED"), ROOT("ROOT"), UNSUPPORTED("UNSUPPORTED") }

enum class SettingNamespace { GLOBAL, SYSTEM, SECURE, SHELL_CMD, SYSFS }

data class TweakOption(val label: String, val value: String)

data class TweakSpec(
    val id: String,
    val name: String,
    val category: TweakCategory,
    val summary: String,
    val technicalDetail: String,
    val expectedBenefit: String,
    val namespace: SettingNamespace,
    val keyOrPath: String,
    val options: List<TweakOption>,
    val defaultValue: String,
    val minSdk: Int = 26,
    val requiredTier: PrivilegeTier = PrivilegeTier.SHIZUKU,
    val baseRisk: TweakRisk = TweakRisk.SAFE,
    val conflictsWithIds: Set<String> = emptySet(),
    val fallbackSettingsAction: String? = null
)

data class TweakHistoryRecord(
    val id: Long, val tweakId: String, val tweakName: String, val timestampMs: Long,
    val previousValue: String, val newValue: String, val tierUsed: String,
    val succeeded: Boolean, val message: String
)

data class TimeMachineSnapshot(
    val id: Long, val label: String, val createdAtMs: Long,
    val buildFingerprint: String, val kernelVersion: String, val values: Map<String, String>
)

object PrivilegedRunner {
    fun isShizukuGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestShizuku(code: Int = 1002): Boolean = runCatching {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) { Shizuku.requestPermission(code); true } else false
    }.getOrDefault(false)

    suspend fun runShell(command: String, requireRoot: Boolean): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val rootShell = runCatching { Shell.getShell() }.getOrNull()
        if (rootShell?.isRoot == true) {
            val res = Shell.cmd(command).exec()
            val out = (res.out + res.err).joinToString("\n").trim()
            return@withContext res.isSuccess to out.ifBlank { if (res.isSuccess) "Applied via Root" else "Root command failed" }
        }
        if (requireRoot) return@withContext false to "Root access required for this hardware/kernel control."
        if (isShizukuGranted()) {
            return@withContext runCatching {
                val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                m.isAccessible = true
                val proc = m.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
                val out = proc.inputStream.bufferedReader().readText().trim()
                val err = proc.errorStream.bufferedReader().readText().trim()
                val code = proc.waitFor()
                (code == 0) to out.ifBlank { err }.ifBlank { if (code == 0) "Applied via Shizuku" else "Exit $code" }
            }.getOrElse { false to "Shizuku error: ${it.message}" }
        }
        false to "Requires Shizuku (no root) or Root access."
    }
}

object TweakEngine {
    val catalog: List<TweakSpec> = listOf(
        // UI & Performance
        TweakSpec("anim_window", "Window Animation Scale", TweakCategory.UI, "Speeds up app window open & close animations.", "Modifies Settings.Global window_animation_scale.", "Snappier app launches.", SettingNamespace.GLOBAL, "window_animation_scale", listOf(TweakOption("Off", "0.0"), TweakOption("0.5x", "0.5"), TweakOption("1.0x", "1.0")), "1.0", fallbackSettingsAction = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        TweakSpec("anim_transition", "Transition Animation Scale", TweakCategory.UI, "Speeds up screen-to-screen transitions.", "Modifies Settings.Global transition_animation_scale.", "Faster navigation between menus.", SettingNamespace.GLOBAL, "transition_animation_scale", listOf(TweakOption("Off", "0.0"), TweakOption("0.5x", "0.5"), TweakOption("1.0x", "1.0")), "1.0", fallbackSettingsAction = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        TweakSpec("anim_duration", "Animator Duration Scale", TweakCategory.PERFORMANCE, "Accelerates UI spinners and layout ripples.", "Modifies Settings.Global animator_duration_scale.", "Reduced UI wait feel.", SettingNamespace.GLOBAL, "animator_duration_scale", listOf(TweakOption("0.5x", "0.5"), TweakOption("0.75x", "0.75"), TweakOption("1.0x", "1.0")), "1.0", fallbackSettingsAction = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        TweakSpec("long_press_timeout", "Touch & Hold Responsiveness", TweakCategory.UI, "Shortens long-press recognition delay.", "Modifies Settings.Secure long_press_timeout in ms.", "Faster context menu and input feel.", SettingNamespace.SECURE, "long_press_timeout", listOf(TweakOption("250ms Fast", "250"), TweakOption("400ms Stock", "400"), TweakOption("600ms", "600")), "400"),
        TweakSpec("haptic_feedback", "System Haptic Vibration", TweakCategory.UI, "Controls touch vibration motor feedback.", "Modifies Settings.System haptic_feedback_enabled.", "Disabling saves small battery & reduces input delay.", SettingNamespace.SYSTEM, "haptic_feedback_enabled", listOf(TweakOption("On", "1"), TweakOption("Off", "0")), "1"),
        TweakSpec("hw_overlays", "GPU Compositing Priority", TweakCategory.PERFORMANCE, "Controls hardware overlay usage during rendering.", "Configures Settings.Global debug.hwui.renderer / overlay hints.", "Helps diagnose display flicker or boost frame consistency.", SettingNamespace.GLOBAL, "force_resizable_activities", listOf(TweakOption("Enabled", "1"), TweakOption("Stock", "0")), "0", baseRisk = TweakRisk.ADVANCED),

        // Display & Gaming
        TweakSpec("peak_refresh", "Peak Refresh Rate Ceiling", TweakCategory.DISPLAY, "Sets max screen refresh rate.", "Updates Settings.System peak_refresh_rate.", "60Hz saves battery; 120Hz maximizes smoothness.", SettingNamespace.SYSTEM, "peak_refresh_rate", listOf(TweakOption("60 Hz", "60.0"), TweakOption("90 Hz", "90.0"), TweakOption("120 Hz", "120.0")), "120.0", baseRisk = TweakRisk.ADVANCED, conflictsWithIds = setOf("min_refresh"), fallbackSettingsAction = Settings.ACTION_DISPLAY_SETTINGS),
        TweakSpec("min_refresh", "Minimum Refresh Rate Floor", TweakCategory.GAMING, "Keeps refresh rate locked higher during motion.", "Updates Settings.System min_refresh_rate.", "Reduces refresh-rate drop stutter in games.", SettingNamespace.SYSTEM, "min_refresh_rate", listOf(TweakOption("60 Hz", "60.0"), TweakOption("90 Hz", "90.0"), TweakOption("120 Hz", "120.0")), "60.0", baseRisk = TweakRisk.ADVANCED, conflictsWithIds = setOf("peak_refresh"), fallbackSettingsAction = Settings.ACTION_DISPLAY_SETTINGS),
        TweakSpec("game_overlay", "Game Driver Mode Hint", TweakCategory.GAMING, "Toggles updatable graphics driver preference.", "Configures Settings.Global updatable_driver_all_apps.", "Can improve Vulkan/OpenGL stability on supported SoCs.", SettingNamespace.GLOBAL, "updatable_driver_all_apps", listOf(TweakOption("Default", "0"), TweakOption("Game Driver", "1"), TweakOption("Prerelease", "2")), "0", minSdk = 29, baseRisk = TweakRisk.ADVANCED),
        TweakSpec("brightness_mode", "Adaptive Display Brightness", TweakCategory.DISPLAY, "Controls automatic ambient-light brightness.", "Modifies Settings.System screen_brightness_mode.", "Manual locks steady brightness during gaming.", SettingNamespace.SYSTEM, "screen_brightness_mode", listOf(TweakOption("Auto", "1"), TweakOption("Manual", "0")), "1", fallbackSettingsAction = Settings.ACTION_DISPLAY_SETTINGS),

        // Battery & Thermal
        TweakSpec("adaptive_battery", "Adaptive Battery Intelligence", TweakCategory.BATTERY, "Limits rarely used apps without forcing full Battery Saver.", "Modifies Settings.Global adaptive_battery_management_enabled.", "Extends standby time.", SettingNamespace.GLOBAL, "adaptive_battery_management_enabled", listOf(TweakOption("On", "1"), TweakOption("Off", "0")), "1", minSdk = 28),
        TweakSpec("app_standby", "App Standby Buckets", TweakCategory.BATTERY, "Throttles idle app sync & background jobs.", "Modifies Settings.Global app_standby_enabled.", "Lower overnight drain.", SettingNamespace.GLOBAL, "app_standby_enabled", listOf(TweakOption("On", "1"), TweakOption("Off", "0")), "1"),
        TweakSpec("screen_timeout", "Screen Auto-Off Timeout", TweakCategory.BATTERY, "Controls idle display power-off timer.", "Modifies Settings.System screen_off_timeout.", "Saves display power & AMOLED life.", SettingNamespace.SYSTEM, "screen_off_timeout", listOf(TweakOption("30s", "30000"), TweakOption("1m", "60000"), TweakOption("2m", "120000")), "60000", fallbackSettingsAction = Settings.ACTION_DISPLAY_SETTINGS),
        TweakSpec("low_power_trigger", "Auto Battery Saver Level", TweakCategory.BATTERY, "Sets battery percentage for automatic power saving.", "Modifies Settings.Global low_power_trigger_level.", "Prevents sudden shutdowns.", SettingNamespace.GLOBAL, "low_power_trigger_level", listOf(TweakOption("Off (0%)", "0"), TweakOption("15%", "15"), TweakOption("25%", "25")), "15"),
        TweakSpec("thermal_stay_awake", "Stay Awake While Charging", TweakCategory.THERMAL, "Prevents screen staying permanently lit on charger.", "Modifies Settings.Global stay_on_while_plugged_in.", "Keeps screen & battery cooler while charging.", SettingNamespace.GLOBAL, "stay_on_while_plugged_in", listOf(TweakOption("Off (Cooler)", "0"), TweakOption("USB On", "2")), "0"),

        // Background & Storage
        TweakSpec("cached_freezer", "Cached Apps Freezer", TweakCategory.BACKGROUND, "Freezes CPU execution for cached background apps.", "Configures Settings.Global cached_apps_freezer (Android 11+).", "Cuts background heat & drain without killing RAM.", SettingNamespace.GLOBAL, "cached_apps_freezer", listOf(TweakOption("Enabled", "enabled"), TweakOption("Default", "default"), TweakOption("Disabled", "disabled")), "default", minSdk = 30, baseRisk = TweakRisk.ADVANCED),
        TweakSpec("phantom_procs", "Phantom Process Monitor", TweakCategory.BACKGROUND, "Controls Android's background child-process killer (helpful for Termux/Winlator).", "Configures Settings.Global settings_enable_monitor_phantom_procs.", "Prevents sudden Termux/Linux container termination.", SettingNamespace.GLOBAL, "settings_enable_monitor_phantom_procs", listOf(TweakOption("Disable Killer", "false"), TweakOption("Stock", "true")), "true", minSdk = 31, baseRisk = TweakRisk.ADVANCED),
        TweakSpec("storage_low_pct", "Low Storage Warning Threshold", TweakCategory.STORAGE, "Adjusts when Android triggers aggressive cache trimming.", "Modifies Settings.Global sys_storage_threshold_percentage.", "Keeps flash I/O responsive by reserving buffer space.", SettingNamespace.GLOBAL, "sys_storage_threshold_percentage", listOf(TweakOption("5% Stock", "5"), TweakOption("10% Safe", "10")), "5", baseRisk = TweakRisk.ADVANCED),

        // Network & Privacy
        TweakSpec("wifi_scan_throttle", "Wi-Fi Scan Throttling", TweakCategory.NETWORK, "Limits background Wi-Fi scanning spikes.", "Modifies Settings.Global wifi_scan_throttle_enabled.", "Steadier gaming ping & lower radio drain.", SettingNamespace.GLOBAL, "wifi_scan_throttle_enabled", listOf(TweakOption("Throttled", "1"), TweakOption("Off", "0")), "1", minSdk = 29, fallbackSettingsAction = Settings.ACTION_WIFI_SETTINGS),
        TweakSpec("mobile_data_always_on", "Mobile Data Always Active", TweakCategory.NETWORK, "Controls keeping cellular radio awake on Wi-Fi.", "Modifies Settings.Global mobile_data_always_on.", "Disabling saves battery when on strong Wi-Fi.", SettingNamespace.GLOBAL, "mobile_data_always_on", listOf(TweakOption("Off (Eco)", "0"), TweakOption("On (Fast Switch)", "1")), "1"),
        TweakSpec("private_dns", "Private DNS Privacy Shield", TweakCategory.PRIVACY, "Encrypts DNS & optionally filters trackers system-wide.", "Sets Settings.Global private_dns_mode & specifier.", "Encrypted lookups + ad/tracker filtering.", SettingNamespace.SHELL_CMD, "private_dns", listOf(TweakOption("AdGuard", "hostname:dns.adguard-dns.com"), TweakOption("Cloudflare", "hostname:one.one.one.one"), TweakOption("Auto", "opportunistic")), "opportunistic", minSdk = 28, fallbackSettingsAction = Settings.ACTION_WIRELESS_SETTINGS),
        TweakSpec("telemetry_optout", "Reduce System Error Reporting", TweakCategory.PRIVACY, "Minimizes automatic background error telemetry.", "Sets Settings.Global send_action_app_error to 0.", "Cleaner privacy footprint.", SettingNamespace.GLOBAL, "send_action_app_error", listOf(TweakOption("Minimal (0)", "0"), TweakOption("Stock (1)", "1")), "1"),

        // Root Lab Only
        TweakSpec("cpu_governor", "CPU Scaling Governor (CPU0)", TweakCategory.ROOT_ONLY, "Controls how aggressively the kernel scales CPU frequency.", "Writes /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor.", "Tune between schedutil, performance, or powersave.", SettingNamespace.SYSFS, "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor", listOf(TweakOption("schedutil", "schedutil"), TweakOption("performance", "performance"), TweakOption("powersave", "powersave")), "schedutil", requiredTier = PrivilegeTier.ROOT, baseRisk = TweakRisk.ROOT),
        TweakSpec("vm_swappiness", "Kernel VM Swappiness", TweakCategory.ROOT_ONLY, "Controls RAM-to-ZRAM swapping aggressiveness.", "Writes /proc/sys/vm/swappiness.", "Lower keeps active apps in physical RAM.", SettingNamespace.SYSFS, "/proc/sys/vm/swappiness", listOf(TweakOption("10 (RAM First)", "10"), TweakOption("40 (Balanced)", "40"), TweakOption("60 (Stock)", "60")), "60", requiredTier = PrivilegeTier.ROOT, baseRisk = TweakRisk.ROOT)
    )

    fun evaluateRisk(spec: TweakSpec, deviceTier: PrivilegeTier): TweakRisk {
        if (Build.VERSION.SDK_INT < spec.minSdk) return TweakRisk.UNSUPPORTED
        if (spec.namespace == SettingNamespace.SYSFS && deviceTier != PrivilegeTier.ROOT) return TweakRisk.ROOT
        return spec.baseRisk
    }

    fun detectConflicts(spec: TweakSpec, currentValues: Map<String, String>): String? {
        if (spec.id == "min_refresh") {
            val peak = currentValues["peak_refresh"]?.toFloatOrNull()
            if (peak != null && peak <= 60f) return "Conflict: Peak Refresh Rate is 60Hz. Raise Peak before raising Minimum."
        }
        if (spec.id == "peak_refresh") {
            val min = currentValues["min_refresh"]?.toFloatOrNull()
            if (min != null && min > 60f) return "Conflict: Minimum Refresh Rate is ${min.toInt()}Hz. Lower Minimum first."
        }
        return null
    }

    suspend fun readCurrentValue(context: Context, spec: TweakSpec): String = withContext(Dispatchers.IO) {
        runCatching {
            when (spec.namespace) {
                SettingNamespace.GLOBAL -> Settings.Global.getString(context.contentResolver, spec.keyOrPath) ?: spec.defaultValue
                SettingNamespace.SYSTEM -> Settings.System.getString(context.contentResolver, spec.keyOrPath) ?: spec.defaultValue
                SettingNamespace.SECURE -> Settings.Secure.getString(context.contentResolver, spec.keyOrPath) ?: spec.defaultValue
                SettingNamespace.SHELL_CMD -> {
                    val mode = Settings.Global.getString(context.contentResolver, "private_dns_mode") ?: "opportunistic"
                    val host = Settings.Global.getString(context.contentResolver, "private_dns_specifier") ?: ""
                    if (mode == "hostname" && host.isNotBlank()) "hostname:$host" else mode
                }
                SettingNamespace.SYSFS -> {
                    val direct = runCatching { File(spec.keyOrPath).readText().trim() }.getOrNull()
                    if (!direct.isNullOrBlank()) direct
                    else if (Shell.isAppGrantedRoot() == true) Shell.cmd("cat ${spec.keyOrPath}").exec().out.firstOrNull()?.trim() ?: "Restricted"
                    else "Root Required"
                }
            }
        }.getOrDefault(spec.defaultValue)
    }

    suspend fun readAllValues(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        catalog.associate { it.id to readCurrentValue(context, it) }
    }

    suspend fun applyTweak(context: Context, spec: TweakSpec, targetValue: String): TweakHistoryRecord = withContext(Dispatchers.IO) {
        val previous = readCurrentValue(context, spec)
        val cmd = when (spec.namespace) {
            SettingNamespace.GLOBAL -> "settings put global ${spec.keyOrPath} $targetValue"
            SettingNamespace.SYSTEM -> "settings put system ${spec.keyOrPath} $targetValue"
            SettingNamespace.SECURE -> "settings put secure ${spec.keyOrPath} $targetValue"
            SettingNamespace.SHELL_CMD -> if (targetValue.startsWith("hostname:")) {
                "settings put global private_dns_mode hostname && settings put global private_dns_specifier ${targetValue.removePrefix("hostname:")}"
            } else "settings put global private_dns_mode $targetValue"
            SettingNamespace.SYSFS -> "echo '$targetValue' > ${spec.keyOrPath}"
        }
        val (ok, msg) = PrivilegedRunner.runShell(cmd, requireRoot = spec.requiredTier == PrivilegeTier.ROOT)
        val tierLabel = when {
            Shell.isAppGrantedRoot() == true -> "ROOT"
            PrivilegedRunner.isShizukuGranted() -> "SHIZUKU"
            else -> "STANDARD"
        }
        val rec = TweakHistoryRecord(System.currentTimeMillis(), spec.id, spec.name, System.currentTimeMillis(), previous, targetValue, tierLabel, ok, msg)
        TweakStore.appendHistory(context, rec)
        rec
    }
}

object TweakStore {
    private const val HISTORY_FILE = "tweakora_history.json"
    private const val SNAPSHOT_FILE = "tweakora_snapshots.json"

    fun loadHistory(context: Context): List<TweakHistoryRecord> = runCatching {
        val f = File(context.filesDir, HISTORY_FILE)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TweakHistoryRecord(o.optLong("id"), o.optString("tweakId"), o.optString("tweakName"), o.optLong("timestampMs"), o.optString("previousValue"), o.optString("newValue"), o.optString("tierUsed"), o.optBoolean("succeeded"), o.optString("message"))
        }.sortedByDescending { it.timestampMs }
    }.getOrDefault(emptyList())

    fun appendHistory(context: Context, record: TweakHistoryRecord) {
        val list = (listOf(record) + loadHistory(context)).take(80)
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("tweakId", r.tweakId); put("tweakName", r.tweakName); put("timestampMs", r.timestampMs)
                put("previousValue", r.previousValue); put("newValue", r.newValue); put("tierUsed", r.tierUsed); put("succeeded", r.succeeded); put("message", r.message)
            })
        }
        File(context.filesDir, HISTORY_FILE).writeText(arr.toString())
    }

    fun loadSnapshots(context: Context): List<TimeMachineSnapshot> = runCatching {
        val f = File(context.filesDir, SNAPSHOT_FILE)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val vo = o.optJSONObject("values") ?: JSONObject()
            val map = mutableMapOf<String, String>()
            vo.keys().forEach { k -> map[k] = vo.optString(k) }
            TimeMachineSnapshot(o.optLong("id"), o.optString("label"), o.optLong("createdAtMs"), o.optString("buildFingerprint"), o.optString("kernelVersion"), map)
        }.sortedByDescending { it.createdAtMs }
    }.getOrDefault(emptyList())

    fun saveSnapshots(context: Context, snapshots: List<TimeMachineSnapshot>) {
        val arr = JSONArray()
        snapshots.take(25).forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("label", s.label); put("createdAtMs", s.createdAtMs)
                put("buildFingerprint", s.buildFingerprint); put("kernelVersion", s.kernelVersion); put("values", JSONObject(s.values))
            })
        }
        File(context.filesDir, SNAPSHOT_FILE).writeText(arr.toString())
    }

    fun createSnapshot(context: Context, label: String, values: Map<String, String>): TimeMachineSnapshot {
        val kernel = runCatching { File("/proc/version").readText().trim().substringBefore(" (") }.getOrDefault("Unknown")
        val snap = TimeMachineSnapshot(System.currentTimeMillis(), label.ifBlank { "Snapshot ${SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date())}" }, System.currentTimeMillis(), Build.FINGERPRINT, kernel, values)
        saveSnapshots(context, listOf(snap) + loadSnapshots(context))
        return snap
    }

    fun exportBackupJson(context: Context): String {
        val root = JSONObject().apply {
            put("app", "Tweakora"); put("exportedAt", System.currentTimeMillis()); put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            val sf = File(context.filesDir, SNAPSHOT_FILE)
            put("snapshots", if (sf.exists()) JSONArray(sf.readText()) else JSONArray())
        }
        return root.toString(2)
    }

    fun importBackupJson(context: Context, rawJson: String): Result<Int> = runCatching {
        val arr = JSONObject(rawJson).getJSONArray("snapshots")
        val imported = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val vo = o.optJSONObject("values") ?: JSONObject()
            val map = mutableMapOf<String, String>()
            vo.keys().forEach { k -> map[k] = vo.optString(k) }
            TimeMachineSnapshot(o.optLong("id", System.currentTimeMillis() + i), o.optString("label", "Imported"), o.optLong("createdAtMs", System.currentTimeMillis()), o.optString("buildFingerprint", Build.FINGERPRINT), o.optString("kernelVersion", "Unknown"), map)
        }
        saveSnapshots(context, (imported + loadSnapshots(context)).distinctBy { it.id })
        imported.size
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweaksAndTimeMachineScreen(deviceTier: PrivilegeTier, onTierRefresh: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = LocalGlassConfig.current

    var activeSection by remember { mutableIntStateOf(0) }
    var selectedCategory by remember { mutableStateOf(TweakCategory.ALL) }
    var currentValues: Map<String, String> by remember { mutableStateOf(emptyMap()) }
    var history by remember { mutableStateOf<List<TweakHistoryRecord>>(emptyList()) }
    var snapshots by remember { mutableStateOf<List<TimeMachineSnapshot>>(emptyList()) }
    var inspectedTweak by remember { mutableStateOf<TweakSpec?>(null) }
    var diffSnapshot by remember { mutableStateOf<TimeMachineSnapshot?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    fun reloadAll() {
        scope.launch {
            currentValues = TweakEngine.readAllValues(context)
            history = withContext(Dispatchers.IO) { TweakStore.loadHistory(context) }
            snapshots = withContext(Dispatchers.IO) { TweakStore.loadSnapshots(context) }
        }
    }

    LaunchedEffect(Unit) { reloadAll() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("TWEAK ENGINE (${TweakEngine.catalog.size})", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Text("Control & Time Machine", style = MaterialTheme.typography.headlineSmall)
                }
                GlassStatusPill(deviceTier.badge, if (deviceTier == PrivilegeTier.ROOT) cfg.accent.primary else cfg.accent.secondary)
            }
        }

        item {
            GlassSegmentedTabs(items = listOf("Library", "Time Machine", "History"), selectedIndex = activeSection, onSelect = { activeSection = it })
        }

        if (deviceTier == PrivilegeTier.STANDARD) {
            item {
                GlassCard(tint = GlassPalette.WarmAmber, glowAccent = GlassPalette.WarmAmber) {
                    Text("STANDARD MODE", style = MaterialTheme.typography.labelMedium, color = GlassPalette.WarmAmber)
                    Spacer(Modifier.height(4.dp))
                    Text("Inspect all ${TweakEngine.catalog.size} live values & save snapshots now. Grant Shizuku (no root) or Root to apply tweaks directly.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassPillButton("Grant Shizuku", onClick = {
                            statusBanner = if (PrivilegedRunner.requestShizuku()) "Shizuku prompt opened. Tap Refresh after allowing." else "Start Shizuku app first."
                        })
                        TextButton(onClick = { onTierRefresh(); reloadAll() }) { Text("Refresh", color = cfg.accent.secondary) }
                    }
                }
            }
        }

        statusBanner?.let { msg ->
            item {
                GlassCard(tint = GlassPalette.CyberCyan, glowAccent = GlassPalette.CyberCyan) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { statusBanner = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        if (activeSection == 0) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TweakCategory.entries) { cat ->
                        FilterChip(selected = selectedCategory == cat, onClick = { selectedCategory = cat }, label = { Text(cat.label, fontSize = 12.sp) })
                    }
                }
            }
            val filtered = TweakEngine.catalog.filter { selectedCategory == TweakCategory.ALL || it.category == selectedCategory }
            items(filtered, key = { it.id }) { spec ->
                val risk = TweakEngine.evaluateRisk(spec, deviceTier)
                val riskColor = when (risk) {
                    TweakRisk.SAFE -> GlassPalette.StatusGood
                    TweakRisk.ADVANCED -> cfg.accent.secondary
                    TweakRisk.ROOT -> cfg.accent.primary
                    TweakRisk.UNSUPPORTED -> GlassPalette.StatusDanger
                }
                val liveVal = currentValues[spec.id] ?: "..."
                val conflict = TweakEngine.detectConflicts(spec, currentValues)
                GlassCard(glowAccent = riskColor, onClick = { inspectedTweak = spec }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(spec.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        GlassStatusPill(risk.badge, riskColor)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(spec.summary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Live: $liveVal", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cfg.accent.secondary)
                        Text("${spec.category.label} • API ${spec.minSdk}+", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (conflict != null) { Spacer(Modifier.height(6.dp)); Text(conflict, fontSize = 12.sp, color = GlassPalette.WarmAmber) }
                }
            }
        }

        if (activeSection == 1) {
            item {
                GlassActionBanner("Capture System Snapshot", "Save all ${TweakEngine.catalog.size} current values before tweaking", Icons.Rounded.Save, onClick = {
                    scope.launch {
                        val fresh = TweakEngine.readAllValues(context)
                        withContext(Dispatchers.IO) { TweakStore.createSnapshot(context, "", fresh) }
                        reloadAll()
                        statusBanner = "Time Machine snapshot saved."
                    }
                })
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassToolTile("Export JSON", "Copy backup", Icons.Rounded.ContentCopy, cfg.accent.secondary, onClick = {
                        val json = TweakStore.exportBackupJson(context)
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Tweakora Backup", json))
                        statusBanner = "Backup JSON copied (${json.length} chars)."
                    }, modifier = Modifier.weight(1f))
                    GlassToolTile("Import JSON", "Restore file", Icons.Rounded.Upload, cfg.accent.primary, onClick = { showImportDialog = true }, modifier = Modifier.weight(1f))
                }
            }
            if (snapshots.isEmpty()) {
                item { GlassCard { Text("No snapshots yet. Capture a baseline snapshot above before tweaking.", style = MaterialTheme.typography.bodySmall) } }
            } else {
                items(snapshots, key = { it.id }) { snap ->
                    val diffCount = snap.values.count { (k, v) -> currentValues[k] != null && currentValues[k] != v }
                    val timeText = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.US).format(Date(snap.createdAtMs))
                    GlassCard(glowAccent = cfg.accent.primary) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(snap.label, style = MaterialTheme.typography.titleMedium); Text(timeText, style = MaterialTheme.typography.bodySmall) }
                            GlassStatusPill(if (diffCount == 0) "MATCHES" else "$diffCount DIFF", if (diffCount == 0) GlassPalette.StatusGood else GlassPalette.WarmAmber)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassPillButton("Compare Diff", onClick = { diffSnapshot = snap }, gradient = listOf(cfg.accent.primary, GlassPalette.ElectricBlue))
                            GlassPillButton("Restore All", onClick = {
                                scope.launch {
                                    var okCnt = 0; var errCnt = 0
                                    snap.values.forEach { (id, oldVal) ->
                                        val sp = TweakEngine.catalog.firstOrNull { it.id == id } ?: return@forEach
                                        if (currentValues[id] != oldVal && oldVal != "Root Required" && oldVal != "Restricted") {
                                            if (TweakEngine.applyTweak(context, sp, oldVal).succeeded) okCnt++ else errCnt++
                                        }
                                    }
                                    reloadAll(); statusBanner = "Restored $okCnt setting(s), $errCnt skipped."
                                }
                            })
                        }
                    }
                }
            }
        }

        if (activeSection == 2) {
            if (history.isEmpty()) {
                item { GlassCard { Text("No tweak operations yet. Every applied tweak logs its previous value here for 1-tap Undo.", style = MaterialTheme.typography.bodySmall) } }
            } else {
                items(history, key = { it.id }) { rec ->
                    val ts = SimpleDateFormat("MMM dd HH:mm:ss", Locale.US).format(Date(rec.timestampMs))
                    val sp = TweakEngine.catalog.firstOrNull { it.id == rec.tweakId }
                    GlassCard(glowAccent = if (rec.succeeded) GlassPalette.StatusGood else GlassPalette.StatusDanger) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(rec.tweakName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            GlassStatusPill(if (rec.succeeded) rec.tierUsed else "BLOCKED", if (rec.succeeded) GlassPalette.StatusGood else GlassPalette.StatusDanger)
                        }
                        Text("${rec.previousValue} → ${rec.newValue}", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = cfg.accent.secondary)
                        Text("$ts • ${rec.message}", style = MaterialTheme.typography.bodySmall)
                        if (rec.succeeded && sp != null) {
                            Spacer(Modifier.height(8.dp))
                            GlassPillButton("Undo (${rec.previousValue})", onClick = {
                                scope.launch {
                                    val u = TweakEngine.applyTweak(context, sp, rec.previousValue)
                                    reloadAll()
                                    statusBanner = if (u.succeeded) "Reverted ${sp.name}." else u.message
                                }
                            }, gradient = listOf(cfg.accent.primary, GlassPalette.NeonMagenta))
                        }
                    }
                }
            }
        }
    }

    inspectedTweak?.let { spec ->
        val risk = TweakEngine.evaluateRisk(spec, deviceTier)
        val liveVal = currentValues[spec.id] ?: spec.defaultValue
        val conflict = TweakEngine.detectConflicts(spec, currentValues)
        var chosenVal by remember(spec.id) { mutableStateOf(liveVal) }

        ModalBottomSheet(onDismissRequest = { inspectedTweak = null }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.name, style = MaterialTheme.typography.headlineSmall, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    GlassStatusPill(risk.badge, cfg.accent.primary)
                }
                Text(spec.summary, style = MaterialTheme.typography.bodyMedium)
                GlassCard {
                    Text("TECHNICAL DETAIL", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Spacer(Modifier.height(4.dp))
                    Text(spec.technicalDetail, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("Benefit: ${spec.expectedBenefit}", style = MaterialTheme.typography.bodySmall, color = GlassPalette.StatusGood)
                    Text("Requires: ${spec.requiredTier.badge} • API ${spec.minSdk}+ • Live: $liveVal", fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (conflict != null) Text(conflict, fontSize = 12.5.sp, color = GlassPalette.WarmAmber)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(spec.options) { opt ->
                        FilterChip(selected = chosenVal == opt.value, onClick = { chosenVal = opt.value }, label = { Text(opt.label, fontSize = 13.sp) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassPillButton("Apply Value", onClick = {
                        scope.launch {
                            val rec = TweakEngine.applyTweak(context, spec, chosenVal)
                            reloadAll(); statusBanner = if (rec.succeeded) "Applied ${spec.name} = $chosenVal" else rec.message
                            inspectedTweak = null
                        }
                    }, modifier = Modifier.weight(1f))
                    GlassPillButton("Reset Default", onClick = {
                        scope.launch {
                            val rec = TweakEngine.applyTweak(context, spec, spec.defaultValue)
                            reloadAll(); statusBanner = if (rec.succeeded) "Reset ${spec.name}" else rec.message
                            inspectedTweak = null
                        }
                    }, gradient = listOf(cfg.accent.primary, GlassPalette.NeonMagenta), modifier = Modifier.weight(1f))
                }
                spec.fallbackSettingsAction?.let { act ->
                    TextButton(onClick = { runCatching { context.startActivity(Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Open Android Settings (Manual Fallback)", fontSize = 13.sp, color = cfg.accent.secondary)
                    }
                }
            }
        }
    }

    diffSnapshot?.let { snap ->
        val diffs = snap.values.entries.filter { (k, v) -> currentValues[k] != v }
        AlertDialog(
            onDismissRequest = { diffSnapshot = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Config Diff: ${snap.label}", style = MaterialTheme.typography.titleMedium) },
            text = {
                if (diffs.isEmpty()) Text("Device matches this snapshot 100%.", style = MaterialTheme.typography.bodySmall)
                else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    diffs.forEach { (id, saved) ->
                        val title = TweakEngine.catalog.firstOrNull { it.id == id }?.name ?: id
                        Text("$title: $saved → ${currentValues[id] ?: "?"}", fontSize = 12.5.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { diffSnapshot = null }) { Text("Close") } }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Import Backup JSON", style = MaterialTheme.typography.titleMedium) },
            text = { OutlinedTextField(value = importText, onValueChange = { importText = it }, placeholder = { Text("Paste exported JSON") }, maxLines = 6) },
            confirmButton = {
                TextButton(onClick = {
                    statusBanner = TweakStore.importBackupJson(context, importText).fold({ "Imported $it snapshot(s)." }, { "Invalid JSON: ${it.message}" })
                    importText = ""; showImportDialog = false; reloadAll()
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("Cancel") } }
        )
    }
}
// END OF TWEAKENGINE
