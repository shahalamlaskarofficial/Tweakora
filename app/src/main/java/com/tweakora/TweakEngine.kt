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

// ---------- MODELS ----------

enum class TweakCategory(val label: String) {
    ALL("All"),
    PERFORMANCE("Performance"),
    BATTERY("Battery"),
    GAMING("Gaming"),
    DISPLAY("Display"),
    UI("UI"),
    NETWORK("Network"),
    PRIVACY("Privacy"),
    BACKGROUND("Background"),
    ROOT_ONLY("Root Lab")
}

enum class TweakRisk(val badge: String) {
    SAFE("SAFE"),
    ADVANCED("ADVANCED"),
    ROOT("ROOT"),
    UNSUPPORTED("UNSUPPORTED")
}

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
    val id: Long,
    val tweakId: String,
    val tweakName: String,
    val timestampMs: Long,
    val previousValue: String,
    val newValue: String,
    val tierUsed: String,
    val succeeded: Boolean,
    val message: String
)

data class TimeMachineSnapshot(
    val id: Long,
    val label: String,
    val createdAtMs: Long,
    val buildFingerprint: String,
    val kernelVersion: String,
    val values: Map<String, String>
)

// ---------- PRIVILEGED COMMAND BRIDGE ----------

object PrivilegedRunner {

    fun isShizukuGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestShizuku(code: Int = 1002): Boolean = runCatching {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
            Shizuku.requestPermission(code)
            true
        } else false
    }.getOrDefault(false)

    suspend fun runShell(command: String, requireRoot: Boolean): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            // 1. Try Root first if granted
            val rootShell = runCatching { Shell.getShell() }.getOrNull()
            if (rootShell?.isRoot == true) {
                val res = Shell.cmd(command).exec()
                val output = (res.out + res.err).joinToString("\n").trim()
                return@withContext res.isSuccess to output.ifBlank { if (res.isSuccess) "Applied via Root" else "Root command failed" }
            }

            if (requireRoot) {
                return@withContext false to "Root access required. Grant Superuser permission to apply this tweak."
            }

            // 2. Try Shizuku non-root shell
            if (isShizukuGranted()) {
                return@withContext runCatching {
                    val method = Shizuku::class.java.getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java
                    )
                    method.isAccessible = true
                    val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
                    val stdout = process.inputStream.bufferedReader().readText().trim()
                    val stderr = process.errorStream.bufferedReader().readText().trim()
                    val code = process.waitFor()
                    (code == 0) to (stdout.ifBlank { stderr }.ifBlank { if (code == 0) "Applied via Shizuku" else "Exit $code" })
                }.getOrElse {
                    false to "Shizuku shell unavailable: ${it.message}"
                }
            }

            false to "Requires Shizuku or Root access to modify this system setting safely."
        }
}

// ---------- BUILT-IN TWEAK REGISTRY & ENGINE ----------

object TweakEngine {

    val catalog: List<TweakSpec> = listOf(
        TweakSpec(
            id = "anim_window",
            name = "Window Animation Scale",
            category = TweakCategory.UI,
            summary = "Speeds up app window open and close transitions.",
            technicalDetail = "Modifies Settings.Global window_animation_scale (0.5x makes UI feel 2x snappier without disabling motion).",
            expectedBenefit = "Faster perceived responsiveness when launching apps and dialogs.",
            namespace = SettingNamespace.GLOBAL,
            keyOrPath = "window_animation_scale",
            options = listOf(TweakOption("Off", "0.0"), TweakOption("0.5x Fast", "0.5"), TweakOption("1.0x Stock", "1.0")),
            defaultValue = "1.0",
            requiredTier = PrivilegeTier.SHIZUKU,
            baseRisk = TweakRisk.SAFE,
            fallbackSettingsAction = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        ),
        TweakSpec(
            id = "anim_transition",
            name = "Transition Animation Scale",
            category = TweakCategory.UI,
            summary = "Reduces delay when switching between screens and tabs.",
            technicalDetail = "Modifies Settings.Global transition_animation_scale.",
            expectedBenefit = "Snappier screen transitions across Android and third-party apps.",
            namespace = SettingNamespace.GLOBAL,
            keyOrPath = "transition_animation_scale",
            options = listOf(TweakOption("Off", "0.0"), TweakOption("0.5x Fast", "0.5"), TweakOption("1.0x Stock", "1.0")),
            defaultValue = "1.0",
            requiredTier = PrivilegeTier.SHIZUKU,
            baseRisk = TweakRisk.SAFE,
            fallbackSettingsAction = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        ),
        TweakSpec(
            id = "anim_duration",
            name = "Animator Duration Scale",
            category = TweakCategory.PERFORMANCE,
            summary = "Accelerates UI progress spinners, button ripples, and layout animations.",
            technicalDetail = "Modifies Settings.Global animator_duration_scale.",
            expectedBenefit = "Reduced UI wait time across system menus.",
            namespace = SettingNamespace.GLOBAL,
            keyOrPath = "animator_duration_scale",
            options = listOf(TweakOption("0.5x Fast", "0.5"), TweakOption("0.75x", "0.75"), TweakOption("1.0x Stock", "1.0")),
            defaultValue = "1.0",
            requiredTier = PrivilegeTier.SHIZUKU,
            baseRisk = TweakRisk.SAFE,
            fallbackSettingsAction = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        ),
        TweakSpec(
            id = "peak_refresh",
            name = "Peak Display Refresh Rate",
            category = TweakCategory.DISPLAY,
            summary = "Sets the maximum refresh rate ceiling for scrolling and gaming.",
            technicalDetail = "Updates Settings.System peak_refresh_rate. Lowering to 60Hz extends battery; 120Hz maximizes smoothness on supported panels.",
            expectedBenefit = "Choose between maximum fluidity or lower display power draw.",
            namespace = SettingNamespace.SYSTEM,
            keyOrPath = "peak_refresh_rate",
            options = listOf(TweakOption("60 Hz", "60.0"), TweakOption("90 Hz", "90.0"), TweakOption("120 Hz", "120.0")),
            defaultValue = "120.0",
            requiredTier = PrivilegeTier.SHIZUKU,
            baseRisk = TweakRisk.ADVANCED,
            conflictsWithIds = setOf("min_refresh"),
            fallbackSettingsAction = Settings.ACTION_DISPLAY_SETTINGS
        ),
        TweakSpec(
            id = "min_refresh",
            name = "Minimum Refresh Rate Floor",
            category = TweakCategory.GAMING,
            summary = "Prevents the screen from dropping to 60Hz during fast motion or gaming.",
            technicalDetail = "Updates Settings.System min_refresh_rate. Keeping this high increases display power consumption.",
            expectedBenefit = "Eliminates refresh-rate switching stutter in supported games.",
            namespace = SettingNamespace.SYSTEM,
            keyOrPath = "min_refresh_rate",
            options = listOf(TweakOption("Auto (60)", "60.0"), TweakOption("90 Hz", "90.0"), TweakOption("120 Hz", "120.0")),
            defaultValue = "60.0",
            requiredTier = PrivilegeTier.SHIZUKU,
            baseRisk = TweakRisk.ADVANCED,
            conflictsWithIds = setOf("peak_refresh"),
            fallbackSettingsAction = Settings.ACTION_DISPLAY_SETTINGS
        ),
        TweakSpec(
            id = "cached_freezer",
            name = "Cached Apps Freezer",
            category = TweakCategory.BACKGROUND,
            summary = "Suspends CPU execution for cached background apps until you reopen them.",
            technicalDetail = "Configures Settings.Global cached_apps_freezer (Android 11+). Reduces background wakeups without killing RAM state.",
            expectedBenefit = "Lower idle battery drain and less background CPU contention.",
            namespace = SettingNamespace.GLOBAL,
            keyOrPath = "cached_apps_freezer",
            options = listOf(TweakOption("Enabled", "enabled"), TweakOption("Default", "default"), TweakOption("Disabled", "disabled")),
            defaultValue = "default",
            minSdk = 30,
            requiredTier = PrivilegeTier.SHIZUKU,
            baseRisk = TweakRisk.ADVANCED,
            fallbackSettingsAction = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        ),
        TweakSpec(
            id = "adaptive_battery",
            name = "Adaptive Battery Intelligence",
            category = TweakCategory.BATTERY,
            summary = "Learns app usage habits to limit rarely used background apps.",
            technicalDetail = "Toggles Settings.Global adaptive_battery_management_enabled without activating full Android Battery Saver.",
            expectedBenefit = "Longer standby battery life while keeping full performance for foreground apps.",
            namespace = SettingNamespace.GLOBAL,
            keyOrPath = "adaptive_battery_management_enabled",
            options = listOf(TweakOption("Enabled", "1"), TweakOption("Disabled", "0")),
            defaultValue = "1",
            minSdk = 28,
            requiredTier = PrivilegeTier.SHIZUKU,
            baseRisk = TweakRisk.SAFE,
            fallbackSettingsAction = Settings.ACTION_BATTERY_SAVER_SETTINGS
        ),
        TweakSpec(
            id = "app_standby",
            name = "App Standby Buckets",
            category = TweakCategory.BATTERY,
            summary = "Restricts background jobs and network sync for idle apps.",
            technicalDetail = "Toggles Settings.Global app_standby_enabled.",
            expectedBenefit = "Reduces overnight battery drain and background data usage.",
            namespace = SettingNamespace.GLOBAL,
            keyOrPath = "app_standby_enabled",
            options = listOf(TweakOption("Enabled", "1"), TweakOption("Disabled", "0")),
            defaultValue = "1",
            requiredTier = PrivilegeTier.SHIZUKU,
            baseRisk = TweakRisk.SAFE
        ),
        TweakSpec(
            id = "screen_timeout",
            name = "Screen Auto-Off Timeout",
            category = TweakCategory.BATTERY,
            summary = "Controls how quickly the display powers down when idle.",
            technicalDetail = "Modifies Settings.System screen_off_timeout in milliseconds.",
            expectedBenefit = "Saves screen-on power and protects AMOLED panels from burn-in.",
            namespace = SettingNamespace.SYSTEM,
            keyOrPath = "screen_off_timeout",
            options = listOf(TweakOption("30s", "30000"), TweakOption("1m", "60000"), TweakOption("2m", "120000")),
            defaultValue = "60000",
            requiredTier = PrivilegeTier.SHIZUKU,
            baseRisk = TweakRisk.SAFE,
            fallbackSettingsAction = Settings.ACTION_DISPLAY_SETTINGS
        ),
        TweakSpec(
            id = "wifi_scan_throttle",
            name = "Wi-Fi Scan Throttling",
            category = TweakCategory.NETWORK,
            summary = "Limits how frequently background apps can trigger Wi-Fi radio scans.",
            technicalDetail = "Configures Settings.Global wifi_scan_throttle_enabled.",
            expectedBenefit = "Lower Wi-Fi radio drain and steadier latency during online gaming.",
            namespace = SettingNamespace.GLOBAL,
            keyOrPath = "wifi_scan_throttle_enabled",
            options = listOf(TweakOption("Throttled", "1"), TweakOption("Unrestricted", "0")),
            defaultValue = "1",
            minSdk = 29,
            requiredTier = PrivilegeTier.SHIZUKU,
            baseRisk = TweakRisk.SAFE,
            fallbackSettingsAction = Settings.ACTION_WIFI_SETTINGS
        ),
        TweakSpec(
            id = "private_dns",
            name = "Private DNS Privacy Shield",
            category = TweakCategory.PRIVACY,
            summary = "Encrypts DNS queries and optionally filters ad/tracker domains system-wide.",
            technicalDetail = "Configures Settings.Global private_dns_mode & private_dns_specifier.",
            expectedBenefit = "Encrypted DNS lookups and reduced tracker traffic.",
            namespace = SettingNamespace.SHELL_CMD,
            keyOrPath = "private_dns",
            options = listOf(
                TweakOption("AdGuard DNS", "hostname:dns.adguard-dns.com"),
                TweakOption("Cloudflare 1.1.1.1", "hostname:one.one.one.one"),
                TweakOption("Auto", "opportunistic")
            ),
            defaultValue = "opportunistic",
            minSdk = 28,
            requiredTier = PrivilegeTier.SHIZUKU,
            baseRisk = TweakRisk.SAFE,
            fallbackSettingsAction = Settings.ACTION_WIRELESS_SETTINGS
        ),
        TweakSpec(
            id = "telemetry_optout",
            name = "System Error Telemetry Reduction",
            category = TweakCategory.PRIVACY,
            summary = "Disables automatic background app error reporting prompts.",
            technicalDetail = "Sets Settings.Global send_action_app_error to 0.",
            expectedBenefit = "Less background diagnostic chatter and cleaner privacy posture.",
            namespace = SettingNamespace.GLOBAL,
            keyOrPath = "send_action_app_error",
            options = listOf(TweakOption("Minimize (0)", "0"), TweakOption("Stock (1)", "1")),
            defaultValue = "1",
            requiredTier = PrivilegeTier.SHIZUKU,
            baseRisk = TweakRisk.SAFE
        ),
        TweakSpec(
            id = "cpu_governor",
            name = "CPU Scaling Governor (Cluster 0)",
            category = TweakCategory.ROOT_ONLY,
            summary = "Controls how aggressively the kernel scales CPU core frequencies.",
            technicalDetail = "Writes to /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor. Requires Superuser.",
            expectedBenefit = "Choose schedutil/walt for balance, performance for sustained clocks, or powersave for cooling.",
            namespace = SettingNamespace.SYSFS,
            keyOrPath = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
            options = listOf(
                TweakOption("schedutil", "schedutil"),
                TweakOption("performance", "performance"),
                TweakOption("powersave", "powersave")
            ),
            defaultValue = "schedutil",
            requiredTier = PrivilegeTier.ROOT,
            baseRisk = TweakRisk.ROOT
        ),
        TweakSpec(
            id = "vm_swappiness",
            name = "Kernel VM Swappiness",
            category = TweakCategory.ROOT_ONLY,
            summary = "Tunes how eagerly the Linux kernel swaps RAM pages to ZRAM.",
            technicalDetail = "Writes to /proc/sys/vm/swappiness (lower values prefer keeping active apps in physical RAM).",
            expectedBenefit = "Smoother multitasking on high-RAM phones or better compression on 4-6GB phones.",
            namespace = SettingNamespace.SYSFS,
            keyOrPath = "/proc/sys/vm/swappiness",
            options = listOf(TweakOption("10 (RAM First)", "10"), TweakOption("40 (Balanced)", "40"), TweakOption("60 (Stock)", "60")),
            defaultValue = "60",
            requiredTier = PrivilegeTier.ROOT,
            baseRisk = TweakRisk.ROOT
        )
    )

    fun evaluateRisk(spec: TweakSpec, deviceTier: PrivilegeTier): TweakRisk {
        if (Build.VERSION.SDK_INT < spec.minSdk) return TweakRisk.UNSUPPORTED
        if (spec.namespace == SettingNamespace.SYSFS && !File(spec.keyOrPath).exists() && deviceTier != PrivilegeTier.ROOT) {
            return TweakRisk.ROOT
        }
        return spec.baseRisk
    }

    fun detectConflicts(spec: TweakSpec, currentValues: Map<String, String>): String? {
        if (spec.id == "min_refresh") {
            val peak = currentValues["peak_refresh"]?.toFloatOrNull()
            if (peak != null && peak <= 60f) {
                return "Conflict: Peak Refresh Rate is capped at 60 Hz. Raising Minimum Refresh Rate requires raising Peak Refresh Rate first."
            }
        }
        if (spec.id == "peak_refresh") {
            val min = currentValues["min_refresh"]?.toFloatOrNull()
            if (min != null && min > 60f) {
                return "Conflict: Minimum Refresh Rate is set to ${min.toInt()} Hz. Lowering Peak below Min will conflict."
            }
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
                    else if (Shell.isAppGrantedRoot() == true) {
                        Shell.cmd("cat ${spec.keyOrPath}").exec().out.firstOrNull()?.trim() ?: "Restricted"
                    } else "Root Required"
                }
            }
        }.getOrDefault(spec.defaultValue)
    }

    suspend fun readAllValues(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        catalog.associate { it.id to readCurrentValue(context, it) }
    }

    suspend fun applyTweak(
        context: Context,
        spec: TweakSpec,
        targetValue: String
    ): TweakHistoryRecord = withContext(Dispatchers.IO) {
        val previous = readCurrentValue(context, spec)
        val cmd = when (spec.namespace) {
            SettingNamespace.GLOBAL -> "settings put global ${spec.keyOrPath} $targetValue"
            SettingNamespace.SYSTEM -> "settings put system ${spec.keyOrPath} $targetValue"
            SettingNamespace.SECURE -> "settings put secure ${spec.keyOrPath} $targetValue"
            SettingNamespace.SHELL_CMD -> {
                if (targetValue.startsWith("hostname:")) {
                    val host = targetValue.removePrefix("hostname:")
                    "settings put global private_dns_mode hostname && settings put global private_dns_specifier $host"
                } else {
                    "settings put global private_dns_mode $targetValue"
                }
            }
            SettingNamespace.SYSFS -> "echo '$targetValue' > ${spec.keyOrPath}"
        }

        val requireRoot = spec.requiredTier == PrivilegeTier.ROOT
        val (ok, msg) = PrivilegedRunner.runShell(cmd, requireRoot = requireRoot)
        val tierLabel = when {
            Shell.isAppGrantedRoot() == true -> "ROOT"
            PrivilegedRunner.isShizukuGranted() -> "SHIZUKU"
            else -> "STANDARD"
        }

        val record = TweakHistoryRecord(
            id = System.currentTimeMillis(),
            tweakId = spec.id,
            tweakName = spec.name,
            timestampMs = System.currentTimeMillis(),
            previousValue = previous,
            newValue = targetValue,
            tierUsed = tierLabel,
            succeeded = ok,
            message = msg
        )
        TweakStore.appendHistory(context, record)
        record
    }
}

// ---------- LOCAL PERSISTENCE (HISTORY & SYSTEM TIME MACHINE) ----------

object TweakStore {
    private const val HISTORY_FILE = "tweakora_history.json"
    private const val SNAPSHOT_FILE = "tweakora_snapshots.json"

    fun loadHistory(context: Context): List<TweakHistoryRecord> = runCatching {
        val file = File(context.filesDir, HISTORY_FILE)
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TweakHistoryRecord(
                id = o.optLong("id"),
                tweakId = o.optString("tweakId"),
                tweakName = o.optString("tweakName"),
                timestampMs = o.optLong("timestampMs"),
                previousValue = o.optString("previousValue"),
                newValue = o.optString("newValue"),
                tierUsed = o.optString("tierUsed"),
                succeeded = o.optBoolean("succeeded"),
                message = o.optString("message")
            )
        }.sortedByDescending { it.timestampMs }
    }.getOrDefault(emptyList())

    fun appendHistory(context: Context, record: TweakHistoryRecord) {
        val list = (listOf(record) + loadHistory(context)).take(80)
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("tweakId", r.tweakId)
                put("tweakName", r.tweakName)
                put("timestampMs", r.timestampMs)
                put("previousValue", r.previousValue)
                put("newValue", r.newValue)
                put("tierUsed", r.tierUsed)
                put("succeeded", r.succeeded)
                put("message", r.message)
            })
        }
        File(context.filesDir, HISTORY_FILE).writeText(arr.toString())
    }

    fun loadSnapshots(context: Context): List<TimeMachineSnapshot> = runCatching {
        val file = File(context.filesDir, SNAPSHOT_FILE)
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val valObj = o.optJSONObject("values") ?: JSONObject()
            val map = mutableMapOf<String, String>()
            valObj.keys().forEach { k -> map[k] = valObj.optString(k) }
            TimeMachineSnapshot(
                id = o.optLong("id"),
                label = o.optString("label"),
                createdAtMs = o.optLong("createdAtMs"),
                buildFingerprint = o.optString("buildFingerprint"),
                kernelVersion = o.optString("kernelVersion"),
                values = map
            )
        }.sortedByDescending { it.createdAtMs }
    }.getOrDefault(emptyList())

    fun saveSnapshots(context: Context, snapshots: List<TimeMachineSnapshot>) {
        val arr = JSONArray()
        snapshots.take(25).forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("label", s.label)
                put("createdAtMs", s.createdAtMs)
                put("buildFingerprint", s.buildFingerprint)
                put("kernelVersion", s.kernelVersion)
                put("values", JSONObject(s.values))
            })
        }
        File(context.filesDir, SNAPSHOT_FILE).writeText(arr.toString())
    }

    fun createSnapshot(context: Context, label: String, values: Map<String, String>): TimeMachineSnapshot {
        val kernel = runCatching { File("/proc/version").readText().trim().substringBefore(" (") }.getOrDefault("Unknown")
        val snap = TimeMachineSnapshot(
            id = System.currentTimeMillis(),
            label = label.ifBlank { "Snapshot ${SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date())}" },
            createdAtMs = System.currentTimeMillis(),
            buildFingerprint = Build.FINGERPRINT,
            kernelVersion = kernel,
            values = values
        )
        saveSnapshots(context, listOf(snap) + loadSnapshots(context))
        return snap
    }

    fun exportBackupJson(context: Context): String {
        val root = JSONObject()
        root.put("app", "Tweakora")
        root.put("exportedAt", System.currentTimeMillis())
        root.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        val snapsFile = File(context.filesDir, SNAPSHOT_FILE)
        root.put("snapshots", if (snapsFile.exists()) JSONArray(snapsFile.readText()) else JSONArray())
        return root.toString(2)
    }

    fun importBackupJson(context: Context, rawJson: String): Result<Int> = runCatching {
        val root = JSONObject(rawJson)
        val arr = root.getJSONArray("snapshots")
        val imported = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val valObj = o.optJSONObject("values") ?: JSONObject()
            val map = mutableMapOf<String, String>()
            valObj.keys().forEach { k -> map[k] = valObj.optString(k) }
            TimeMachineSnapshot(
                id = o.optLong("id", System.currentTimeMillis() + i),
                label = o.optString("label", "Imported Backup"),
                createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
                buildFingerprint = o.optString("buildFingerprint", Build.FINGERPRINT),
                kernelVersion = o.optString("kernelVersion", "Unknown"),
                values = map
            )
        }
        val merged = (imported + loadSnapshots(context)).distinctBy { it.id }
        saveSnapshots(context, merged)
        imported.size
    }
}

// ---------- TWEAKS & TIME MACHINE UI ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweaksAndTimeMachineScreen(
    deviceTier: PrivilegeTier,
    onTierRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeSection by remember { mutableIntStateOf(0) } // 0=Library, 1=Time Machine, 2=History
    var selectedCategory by remember { mutableStateOf(TweakCategory.ALL) }
    var currentValues by remember { mutableStateOf>(emptyMap()) }
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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("TWEAK ENGINE", style = MaterialTheme.typography.labelMedium, color = GlassPalette.CyberCyan)
                    Text("Control & Time Machine", style = MaterialTheme.typography.headlineSmall)
                }
                GlassStatusPill(
                    text = deviceTier.badge,
                    color = when (deviceTier) {
                        PrivilegeTier.ROOT -> GlassPalette.NeonPurple
                        PrivilegeTier.SHIZUKU -> GlassPalette.CyberCyan
                        PrivilegeTier.STANDARD -> GlassPalette.ElectricBlue
                    }
                )
            }
        }

        // Section switcher (Library / Time Machine / History)
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Library", "Time Machine", "History").forEachIndexed { idx, label ->
                    FilterChip(
                        selected = activeSection == idx,
                        onClick = { activeSection = idx },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Privilege helper banner if Standard mode
        if (deviceTier == PrivilegeTier.STANDARD) {
            item {
                GlassCard(tint = GlassPalette.WarmAmber, glowAccent = GlassPalette.WarmAmber) {
                    Text("STANDARD MODE ACTIVE", style = MaterialTheme.typography.labelMedium, color = GlassPalette.WarmAmber)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "You can inspect all live values & create Time Machine snapshots now. Enable Shizuku (no root needed) or Root to apply system tweaks directly inside Tweakora.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassPillButton(
                            text = "Grant Shizuku",
                            onClick = {
                                val started = PrivilegedRunner.requestShizuku()
                                statusBanner = if (started) "Requested Shizuku permission. Tap Refresh after allowing."
                                else "Shizuku service not running. Start Shizuku app first."
                            }
                        )
                        TextButton(onClick = {
                            onTierRefresh()
                            reloadAll()
                        }) {
                            Text("Refresh", color = GlassPalette.CyberCyan, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        statusBanner?.let { msg ->
            item {
                GlassCard(tint = GlassPalette.CyberCyan, glowAccent = GlassPalette.CyberCyan) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = GlassPalette.TextPrimary, modifier = Modifier.weight(1f))
                        IconButton(onClick = { statusBanner = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = GlassPalette.TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // TAB 0: TWEAK LIBRARY
        if (activeSection == 0) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TweakCategory.entries) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat.label, fontSize = 11.sp) }
                        )
                    }
                }
            }

            val filtered = TweakEngine.catalog.filter {
                selectedCategory == TweakCategory.ALL || it.category == selectedCategory
            }

            items(filtered, key = { it.id }) { spec ->
                val risk = TweakEngine.evaluateRisk(spec, deviceTier)
                val riskColor = when (risk) {
                    TweakRisk.SAFE -> GlassPalette.StatusGood
                    TweakRisk.ADVANCED -> GlassPalette.CyberCyan
                    TweakRisk.ROOT -> GlassPalette.NeonPurple
                    TweakRisk.UNSUPPORTED -> GlassPalette.StatusDanger
                }
                val liveVal = currentValues[spec.id] ?: "..."
                val conflict = TweakEngine.detectConflicts(spec, currentValues)

                GlassCard(
                    tint = Color.White,
                    glowAccent = riskColor,
                    onClick = { inspectedTweak = spec }
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(spec.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        GlassStatusPill(risk.badge, riskColor)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(spec.summary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Current: $liveVal",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = GlassPalette.CyberCyan
                        )
                        Text(
                            "${spec.category.label} • API ${spec.minSdk}+",
                            fontSize = 11.sp,
                            color = GlassPalette.TextMuted
                        )
                    }
                    if (conflict != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(conflict, fontSize = 11.sp, color = GlassPalette.WarmAmber)
                    }
                }
            }
        }

        // TAB 1: SYSTEM TIME MACHINE
        if (activeSection == 1) {
            item {
                GlassActionBanner(
                    title = "Capture System Snapshot",
                    subtitle = "Save all ${TweakEngine.catalog.size} current settings before tweaking",
                    icon = Icons.Rounded.Save,
                    onClick = {
                        scope.launch {
                            val fresh = TweakEngine.readAllValues(context)
                            withContext(Dispatchers.IO) {
                                TweakStore.createSnapshot(context, "", fresh)
                            }
                            reloadAll()
                            statusBanner = "System Time Machine snapshot saved."
                        }
                    }
                )
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassToolTile(
                        title = "Export JSON",
                        subtitle = "Copy backup",
                        icon = Icons.Rounded.ContentCopy,
                        accent = GlassPalette.CyberCyan,
                        onClick = {
                            val json = TweakStore.exportBackupJson(context)
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm?.setPrimaryClip(ClipData.newPlainText("Tweakora Backup", json))
                            statusBanner = "Backup JSON copied to clipboard (${json.length} chars)."
                        },
                        modifier = Modifier.weight(1f)
                    )
                    GlassToolTile(
                        title = "Import JSON",
                        subtitle = "Restore backup",
                        icon = Icons.Rounded.Upload,
                        accent = GlassPalette.NeonPurple,
                        onClick = { showImportDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (snapshots.isEmpty()) {
                item {
                    GlassCard {
                        Text("No snapshots yet. Tap 'Capture System Snapshot' above to record your stock baseline.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                items(snapshots, key = { it.id }) { snap ->
                    val diffCount = snap.values.count { (k, v) -> currentValues[k] != null && currentValues[k] != v }
                    val timeText = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.US).format(Date(snap.createdAtMs))
                    GlassCard(glowAccent = GlassPalette.NeonPurple) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(snap.label, style = MaterialTheme.typography.titleMedium)
                                Text(timeText, style = MaterialTheme.typography.bodySmall)
                            }
                            GlassStatusPill(
                                text = if (diffCount == 0) "MATCHES" else "$diffCount DIFF",
                                color = if (diffCount == 0) GlassPalette.StatusGood else GlassPalette.WarmAmber
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Kernel: ${snap.kernelVersion}", fontSize = 11.sp, color = GlassPalette.TextMuted, maxLines = 1)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassPillButton(
                                text = "Compare Diff",
                                onClick = { diffSnapshot = snap },
                                gradient = listOf(GlassPalette.NeonPurple, GlassPalette.ElectricBlue)
                            )
                            GlassPillButton(
                                text = "Restore All",
                                onClick = {
                                    scope.launch {
                                        var restored = 0
                                        var failed = 0
                                        snap.values.forEach { (id, oldVal) ->
                                            val spec = TweakEngine.catalog.firstOrNull { it.id == id } ?: return@forEach
                                            if (currentValues[id] != oldVal && oldVal != "Root Required" && oldVal != "Restricted") {
                                                val rec = TweakEngine.applyTweak(context, spec, oldVal)
                                                if (rec.succeeded) restored++ else failed++
                                            }
                                        }
                                        reloadAll()
                                        statusBanner = "Time Machine restore: $restored restored, $failed failed."
                                    }
                                },
                                gradient = listOf(GlassPalette.ElectricBlue, GlassPalette.CyberCyan)
                            )
                        }
                    }
                }
            }
        }

        // TAB 2: CHANGE HISTORY & UNDO
        if (activeSection == 2) {
            if (history.isEmpty()) {
                item {
                    GlassCard {
                        Text("No tweak operations recorded yet. Every tweak applied logs its previous value here for 1-tap Undo.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                items(history, key = { it.id }) { rec ->
                    val timeText = SimpleDateFormat("MMM dd HH:mm:ss", Locale.US).format(Date(rec.timestampMs))
                    val spec = TweakEngine.catalog.firstOrNull { it.id == rec.tweakId }
                    GlassCard(glowAccent = if (rec.succeeded) GlassPalette.StatusGood else GlassPalette.StatusDanger) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(rec.tweakName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            GlassStatusPill(
                                text = if (rec.succeeded) rec.tierUsed else "BLOCKED",
                                color = if (rec.succeeded) GlassPalette.StatusGood else GlassPalette.StatusDanger
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${rec.previousValue}  →  ${rec.newValue}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = GlassPalette.CyberCyan
                        )
                        Text("$timeText • ${rec.message}", style = MaterialTheme.typography.bodySmall)
                        if (rec.succeeded && spec != null) {
                            Spacer(Modifier.height(8.dp))
                            GlassPillButton(
                                text = "Undo (${rec.previousValue})",
                                onClick = {
                                    scope.launch {
                                        val undoRec = TweakEngine.applyTweak(context, spec, rec.previousValue)
                                        reloadAll()
                                        statusBanner = if (undoRec.succeeded) "Reverted ${spec.name} to ${rec.previousValue}"
                                        else "Undo failed: ${undoRec.message}"
                                    }
                                },
                                gradient = listOf(GlassPalette.NeonPurple, GlassPalette.NeonMagenta)
                            )
                        }
                    }
                }
            }
        }
    }

    // TWEAK DETAIL MODAL SHEET
    inspectedTweak?.let { spec ->
        val risk = TweakEngine.evaluateRisk(spec, deviceTier)
        val liveVal = currentValues[spec.id] ?: spec.defaultValue
        val conflict = TweakEngine.detectConflicts(spec, currentValues)
        var chosenVal by remember(spec.id) { mutableStateOf(liveVal) }

        ModalBottomSheet(
            onDismissRequest = { inspectedTweak = null },
            containerColor = Color(0xFF120E24)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.name, style = MaterialTheme.typography.headlineSmall, fontSize = 19.sp, modifier = Modifier.weight(1f))
                    GlassStatusPill(risk.badge, GlassPalette.NeonPurple)
                }
                Text(spec.summary, style = MaterialTheme.typography.bodySmall, color = GlassPalette.TextPrimary)
                GlassCard {
                    Text("TECHNICAL DETAIL", style = MaterialTheme.typography.labelMedium, color = GlassPalette.CyberCyan)
                    Spacer(Modifier.height(4.dp))
                    Text(spec.technicalDetail, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text("Expected Benefit: ${spec.expectedBenefit}", style = MaterialTheme.typography.bodySmall, color = GlassPalette.StatusGood)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Requires: ${spec.requiredTier.badge} • Android API ${spec.minSdk}+ • Live: $liveVal",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = GlassPalette.TextMuted
                    )
                }

                if (conflict != null) {
                    Text(conflict, fontSize = 12.sp, color = GlassPalette.WarmAmber)
                }

                Text("SELECT VALUE", style = MaterialTheme.typography.labelMedium, color = GlassPalette.TextMuted)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(spec.options) { opt ->
                        FilterChip(
                            selected = chosenVal == opt.value,
                            onClick = { chosenVal = opt.value },
                            label = { Text(opt.label, fontSize = 12.sp) }
                        )
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassPillButton(
                        text = "Apply Value",
                        onClick = {
                            scope.launch {
                                val rec = TweakEngine.applyTweak(context, spec, chosenVal)
                                reloadAll()
                                statusBanner = if (rec.succeeded) "Applied ${spec.name} = $chosenVal"
                                else rec.message
                                inspectedTweak = null
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    GlassPillButton(
                        text = "Reset Default",
                        onClick = {
                            scope.launch {
                                val rec = TweakEngine.applyTweak(context, spec, spec.defaultValue)
                                reloadAll()
                                statusBanner = if (rec.succeeded) "Reset ${spec.name} to ${spec.defaultValue}"
                                else rec.message
                                inspectedTweak = null
                            }
                        },
                        gradient = listOf(GlassPalette.NeonPurple, GlassPalette.NeonMagenta),
                        modifier = Modifier.weight(1f)
                    )
                }

                if (spec.fallbackSettingsAction != null) {
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(spec.fallbackSettingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Open Android System Settings (Manual Fallback)", fontSize = 12.sp, color = GlassPalette.CyberCyan)
                    }
                }
            }
        }
    }

    // SNAPSHOT DIFF DIALOG
    diffSnapshot?.let { snap ->
        val diffs = snap.values.entries.filter { (k, v) -> currentValues[k] != v }
        AlertDialog(
            onDismissRequest = { diffSnapshot = null },
            containerColor = Color(0xFF141028),
            title = { Text("Config Diff: ${snap.label}", fontSize = 16.sp) },
            text = {
                if (diffs.isEmpty()) {
                    Text("Current device configuration matches this snapshot 100%.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        diffs.forEach { (id, savedVal) ->
                            val title = TweakEngine.catalog.firstOrNull { it.id == id }?.name ?: id
                            Text("$title", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = GlassPalette.TextPrimary)
                            Text(
                                "Snapshot: $savedVal  |  Now: ${currentValues[id] ?: "?"}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = GlassPalette.CyberCyan
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { diffSnapshot = null }) { Text("Close") }
            }
        )
    }

    // IMPORT BACKUP DIALOG
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            containerColor = Color(0xFF141028),
            title = { Text("Import Tweakora Backup JSON", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    placeholder = { Text("Paste exported JSON here", fontSize = 12.sp) },
                    maxLines = 6
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val res = TweakStore.importBackupJson(context, importText)
                    statusBanner = res.fold(
                        onSuccess = { "Imported $it Time Machine snapshot(s)." },
                        onFailure = { "Invalid backup JSON: ${it.message}" }
                    )
                    importText = ""
                    showImportDialog = false
                    reloadAll()
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }
}
