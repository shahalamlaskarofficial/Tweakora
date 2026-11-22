package com.tweakora

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Locale

enum class PrivilegeTier(val badge: String) { STANDARD("STANDARD"), SHIZUKU("SHIZUKU"), ROOT("ROOTED") }
enum class HealthStatus(val label: String) { GOOD("Good"), NEEDS_ATTENTION("Needs Attention"), CRITICAL("Critical") }
enum class PerformanceProfile(val label: String, val animScale: String) {
    BALANCED("Balanced", "0.75"), PERFORMANCE("Perf", "0.5"), BATTERY("Battery", "1.0"), GAMING("Gaming", "0.5")
}

data class DeviceDna(
    val manufacturer: String, val model: String, val deviceCodeName: String,
    val socName: String, val supportedAbis: List<String>, val cpuCores: Int,
    val coreFreqsMhz: List<Int?>, val peakLiveClockMhz: Int?,
    val gpuRenderer: String, val gpuVendor: String, val gpuGlVersion: String,
    val totalRamMb: Long, val usedRamMb: Long,
    val totalStorageGb: Float, val usedStorageGb: Float, val freeStorageGb: Float,
    val displayText: String, val sensorCount: Int,
    val androidVersion: String, val sdkInt: Int, val securityPatch: String,
    val buildId: String, val buildFingerprint: String, val kernelVersion: String,
    val batteryPercent: Int, val batteryTempC: Float, val batteryVoltageMv: Int,
    val batteryHealth: String, val isCharging: Boolean,
    val thermalStatus: String, val thermalHeadroom: Float?,
    val privilegeTier: PrivilegeTier, val healthScore: Int, val healthStatus: HealthStatus
) {
    val ramFraction: Float get() = if (totalRamMb > 0) (usedRamMb.toFloat() / totalRamMb).coerceIn(0f, 1f) else 0f
    val storageFraction: Float get() = if (totalStorageGb > 0f) (usedStorageGb / totalStorageGb).coerceIn(0f, 1f) else 0f
}

/**
 * Isolated telemetry state holder: keeps live updates completely decoupled from the Navigation Bar
 * so the sliding Liquid Glass dock NEVER recomposes when telemetry ticks.
 */
object TelemetryStore {
    var dna by mutableStateOf<DeviceDna?>(null)
    var ramHistory by mutableStateOf<List<Float>>(emptyList())
    var tempHistory by mutableStateOf<List<Float>>(emptyList())
    var ruleLogs by mutableStateOf<List<RuleEventLog>>(emptyList())
    var chargeAlert by mutableStateOf<String?>(null)
}

private data class StaticHardwareCache(
    val soc: String, val cores: Int, val gpu: Triple<String, String, String>,
    val displayText: String, val sensorCount: Int, val kernel: String, val abis: List<String>
)

object SystemInspector {
    @Volatile private var staticCache: StaticHardwareCache? = null
    @Volatile private var cachedTier: PrivilegeTier? = null

    private fun readGpu(): Triple<String, String, String> = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2); EGL14.eglInitialize(display, ver, 0, ver, 1)
        val attribs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        val cfg = configs[0] ?: return Triple("Unavailable", "Unavailable", "Unavailable")
        val surface = EGL14.eglCreatePbufferSurface(display, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        val ctx = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surface, surface, ctx)
        val info = Triple(GLES20.glGetString(GLES20.GL_RENDERER) ?: "Unavailable", GLES20.glGetString(GLES20.GL_VENDOR) ?: "Unavailable", GLES20.glGetString(GLES20.GL_VERSION) ?: "Unavailable")
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, ctx); EGL14.eglTerminate(display)
        info
    }.getOrDefault(Triple("Unavailable", "Unavailable", "Unavailable"))

    private fun getStatic(context: Context): StaticHardwareCache {
        staticCache?.let { return it }
        val dm = context.getSystemService(DisplayManager::class.java)
        val mode = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
        val displayText = if (mode != null) "${mode.physicalWidth}x${mode.physicalHeight} @ ${mode.refreshRate.toInt()}Hz" else "Unknown"
        val sensors = context.getSystemService(SensorManager::class.java)?.getSensorList(Sensor.TYPE_ALL)?.size ?: 0
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL.isNotBlank()) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else Build.HARDWARE
        val kernel = runCatching { File("/proc/version").readText().trim().substringBefore(" (") }.getOrElse { System.getProperty("os.version") ?: "Unknown" }
        return StaticHardwareCache(soc, Runtime.getRuntime().availableProcessors(), readGpu(), displayText, sensors, kernel, Build.SUPPORTED_ABIS.toList())
            .also { staticCache = it }
    }

    fun detectTier(requestRoot: Boolean): PrivilegeTier {
        if (!requestRoot) cachedTier?.let { return it }
        if (Shell.isAppGrantedRoot() == true) return PrivilegeTier.ROOT.also { cachedTier = it }
        if (requestRoot) {
            if (runCatching { Shell.getShell().isRoot }.getOrDefault(false)) return PrivilegeTier.ROOT.also { cachedTier = it }
        }
        val shizukuOk = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return (if (shizukuOk) PrivilegeTier.SHIZUKU else PrivilegeTier.STANDARD).also { cachedTier = it }
    }

    suspend fun capture(context: Context, requestRoot: Boolean = false): DeviceDna = withContext(Dispatchers.IO) {
        val st = getStatic(context)
        val am = context.getSystemService(ActivityManager::class.java)
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalRamMb = memInfo.totalMem / (1024L * 1024L)
        val usedRamMb = ((memInfo.totalMem - memInfo.availMem) / (1024L * 1024L)).coerceAtLeast(0L)

        val statFs = StatFs(Environment.getDataDirectory().path)
        val totalStorageGb = (statFs.blockCountLong * statFs.blockSizeLong) / 1_073_741_824f
        val freeStorageGb = (statFs.availableBlocksLong * statFs.blockSizeLong) / 1_073_741_824f
        val usedStorageGb = (totalStorageGb - freeStorageGb).coerceAtLeast(0f)

        val bat = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (lvl >= 0 && scale > 0) (lvl * 100) / scale else 0
        val tempC = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val voltageMv = bat?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val statusInt = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING || statusInt == BatteryManager.BATTERY_STATUS_FULL
        val healthLabel = when (bat?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Degraded"; BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"; else -> "Unknown"
        }

        val pm = context.getSystemService(PowerManager::class.java)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Nominal"; PowerManager.THERMAL_STATUS_LIGHT -> "Warm"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"; PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"; else -> "High"
            }
        } else "Android 10+"
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getThermalHeadroom(10).takeUnless { it.isNaN() || it < 0f } else null

        val freqs = (0 until st.cores).map { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toInt() / 1000 }.getOrNull()
        }

        var score = 100
        if (totalRamMb > 0 && usedRamMb.toFloat() / totalRamMb > 0.85f) score -= 15
        if (totalStorageGb > 0f && usedStorageGb / totalStorageGb > 0.88f) score -= 20
        if (tempC >= 42f) score -= 25 else if (tempC >= 38f) score -= 10
        if (healthLabel != "Good" && healthLabel != "Unknown") score -= 20
        score = score.coerceIn(0, 100)

        DeviceDna(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, model = Build.MODEL, deviceCodeName = Build.DEVICE,
            socName = st.soc, supportedAbis = st.abis, cpuCores = st.cores, coreFreqsMhz = freqs, peakLiveClockMhz = freqs.filterNotNull().maxOrNull(),
            gpuRenderer = st.gpu.first, gpuVendor = st.gpu.second, gpuGlVersion = st.gpu.third,
            totalRamMb = totalRamMb, usedRamMb = usedRamMb, totalStorageGb = totalStorageGb, usedStorageGb = usedStorageGb, freeStorageGb = freeStorageGb,
            displayText = st.displayText, sensorCount = st.sensorCount, androidVersion = Build.VERSION.RELEASE, sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH, buildId = Build.DISPLAY, buildFingerprint = Build.FINGERPRINT, kernelVersion = st.kernel,
            batteryPercent = percent, batteryTempC = tempC, batteryVoltageMv = voltageMv, batteryHealth = healthLabel, isCharging = isCharging,
            thermalStatus = thermalStatus, thermalHeadroom = headroom, privilegeTier = detectTier(requestRoot), healthScore = score,
            healthStatus = when { score >= 80 -> HealthStatus.GOOD; score >= 55 -> HealthStatus.NEEDS_ATTENTION; else -> HealthStatus.CRITICAL }
        )
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        init { runCatching { Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(10)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            var themeConfig by remember { mutableStateOf(AppPrefs.loadThemeConfig(ctx)) }
            var appSettings by remember { mutableStateOf(AppPrefs.loadSettings(ctx)) }

            TweakoraGlassTheme(config = themeConfig) {
                TweakoraLiquidShell(
                    themeConfig = themeConfig,
                    settings = appSettings,
                    onUpdateTheme = { themeConfig = it; AppPrefs.saveThemeConfig(ctx, it) },
                    onUpdateSettings = { appSettings = it; AppPrefs.saveSettings(ctx, it) },
                    onResetAll = {
                        AppPrefs.resetAll(ctx)
                        themeConfig = GlassThemeConfig()
                        appSettings = TweakoraSettings()
                    }
                )
            }
        }
    }
}

@Composable
private fun TweakoraLiquidShell(
    themeConfig: GlassThemeConfig,
    settings: TweakoraSettings,
    onUpdateTheme: (GlassThemeConfig) -> Unit,
    onUpdateSettings: (TweakoraSettings) -> Unit,
    onResetAll: () -> Unit
) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf("home") }
    var overlayScreen by remember { mutableStateOf<String?>(null) }
    var isPaused by remember { mutableStateOf(false) }
    var isAppForeground by remember { mutableStateOf(true) }
    var lastChargeAlarmMs by remember { mutableLongStateOf(0L) }

    BackHandler(enabled = overlayScreen != null || activeTab != "home") {
        if (overlayScreen != null) overlayScreen = null
        else activeTab = "home"
    }

    DisposableEffect(context) {
        val owner = context as? LifecycleOwner
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isAppForeground = true
            else if (event == Lifecycle.Event.ON_PAUSE) isAppForeground = false
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }

    // Background-safe telemetry loop writing to TelemetryStore (never recomposes the bottom dock!)
    LaunchedEffect(isPaused, isAppForeground, settings.pollIntervalSec, settings.chargeGuardEnabled, settings.chargeLimitPct) {
        while (isActive) {
            if (isAppForeground && (!isPaused || TelemetryStore.dna == null)) {
                val snap = SystemInspector.capture(context, requestRoot = false)
                TelemetryStore.dna = snap
                TelemetryStore.ramHistory = (TelemetryStore.ramHistory + snap.ramFraction).takeLast(14)
                TelemetryStore.tempHistory = (TelemetryStore.tempHistory + ((snap.batteryTempC - 20f) / 30f).coerceIn(0.05f, 1f)).takeLast(14)

                val fired = SmartRulesEngine.evaluateLive(context, snap)
                if (fired.isNotEmpty()) TelemetryStore.ruleLogs = (fired + TelemetryStore.ruleLogs).take(20)

                if (settings.chargeGuardEnabled && snap.isCharging && (snap.batteryPercent >= settings.chargeLimitPct || snap.batteryTempC >= 41f)) {
                    TelemetryStore.chargeAlert = "Charge Guard: ${snap.batteryPercent}% (${String.format(Locale.US, "%.1f°C", snap.batteryTempC)}) — Unplug to protect battery."
                    val now = System.currentTimeMillis()
                    if (now - lastChargeAlarmMs > 120_000L) {
                        lastChargeAlarmMs = now
                        BypassChargingEngine.playChargeGuardTone(context)
                    }
                } else {
                    TelemetryStore.chargeAlert = null
                }
            }
            if (settings.pollIntervalSec <= 0) break
            delay(settings.pollIntervalSec * 1000L)
        }
    }

    val navTabs = remember {
        listOf(
            Triple("home", "Home", Icons.Rounded.Dashboard),
            Triple("tweaks", "Tweaks", Icons.Rounded.Tune),
            Triple("gaming", "Gaming", Icons.Rounded.SportsEsports),
            Triple("monitor", "Monitor", Icons.Rounded.QueryStats),
            Triple("settings", "Tools", Icons.Rounded.Settings)
        )
    }

    GlassBackdrop {
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // Isolated content host so telemetry recompositions stay inside active screen only
            ActiveScreenHost(
                activeTab = activeTab,
                overlayScreen = overlayScreen,
                themeConfig = themeConfig,
                settings = settings,
                isPaused = isPaused,
                onSelectTab = { activeTab = it },
                onOpenOverlay = { overlayScreen = it },
                onCloseOverlay = { overlayScreen = null },
                onTogglePause = { isPaused = !isPaused },
                onUpdateTheme = onUpdateTheme,
                onUpdateSettings = onUpdateSettings,
                onResetAll = onResetAll
            )

            // Isolated GPU-sliding dock (never touched by telemetry ticks!)
            if (overlayScreen == null) {
                LiquidGlassNavBar(
                    items = navTabs,
                    currentRoute = activeTab,
                    onSelect = { activeTab = it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun ActiveScreenHost(
    activeTab: String,
    overlayScreen: String?,
    themeConfig: GlassThemeConfig,
    settings: TweakoraSettings,
    isPaused: Boolean,
    onSelectTab: (String) -> Unit,
    onOpenOverlay: (String) -> Unit,
    onCloseOverlay: () -> Unit,
    onTogglePause: () -> Unit,
    onUpdateTheme: (GlassThemeConfig) -> Unit,
    onUpdateSettings: (TweakoraSettings) -> Unit,
    onResetAll: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dna = TelemetryStore.dna

    fun refreshNow(requestRoot: Boolean) {
        scope.launch { TelemetryStore.dna = SystemInspector.capture(context, requestRoot = requestRoot) }
    }

    when (overlayScreen) {
        "sources" -> SourcesAndLabScreen(dna = dna, onBack = onCloseOverlay)
        "cleaner" -> CleanerAndBypassScreen(
            dna = dna, settings = settings, onUpdateSettings = onUpdateSettings,
            onRefreshDna = { refreshNow(false) }, onBack = onCloseOverlay
        )
        "rootlab" -> RootLabScreen(dna = dna, onBack = onCloseOverlay, onRefreshDna = { refreshNow(true) })
        "rules" -> SmartRulesAndSimScreen(dna = dna, recentLogs = TelemetryStore.ruleLogs, onBack = onCloseOverlay)
        else -> when (activeTab) {
            "home" -> ExecutiveHomeDashboard(
                dna = dna,
                ramHistory = TelemetryStore.ramHistory,
                tempHistory = TelemetryStore.tempHistory,
                latestRuleLog = TelemetryStore.ruleLogs.firstOrNull(),
                chargeAlertText = TelemetryStore.chargeAlert,
                onOpenTab = onSelectTab,
                onOpenOverlay = onOpenOverlay,
                onCheckRoot = { refreshNow(true) }
            )
            "tweaks" -> TweaksAndTimeMachineScreen(
                deviceTier = dna?.privilegeTier ?: PrivilegeTier.STANDARD,
                onTierRefresh = { refreshNow(true) }
            )
            "gaming" -> Box(Modifier.fillMaxSize().padding(bottom = 86.dp)) { GamingHubScreen(dna = dna) }
            "monitor" -> Box(Modifier.fillMaxSize().padding(bottom = 86.dp)) {
                MonitorHubScreen(
                    dna = dna, ramHistory = TelemetryStore.ramHistory, tempHistory = TelemetryStore.tempHistory,
                    isMonitoringPaused = isPaused, onToggleMonitoring = onTogglePause
                )
            }
            "settings" -> SettingsAndToolsScreen(
                dna = dna, themeConfig = themeConfig, settings = settings,
                onUpdateTheme = onUpdateTheme, onUpdateSettings = onUpdateSettings, onResetAll = onResetAll,
                onOpenCleaner = { onOpenOverlay("cleaner") },
                onOpenSources = { onOpenOverlay("sources") },
                onOpenRootLab = { onOpenOverlay("rootlab") },
                onOpenSmartRules = { onOpenOverlay("rules") },
                onRefreshDna = { refreshNow(true) }
            )
        }
    }
}

// ---------- CLEAN EXECUTIVE HOME DASHBOARD (ZERO DUPLICATES, ALL GLASS BOXES) ----------

@Composable
private fun ExecutiveHomeDashboard(
    dna: DeviceDna?,
    ramHistory: List<Float>,
    tempHistory: List<Float>,
    latestRuleLog: RuleEventLog?,
    chargeAlertText: String?,
    onOpenTab: (String) -> Unit,
    onOpenOverlay: (String) -> Unit,
    onCheckRoot: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = LocalGlassConfig.current

    var activeProfile by remember { mutableStateOf(PerformanceProfile.BALANCED) }
    var profileFeedback by remember { mutableStateOf<String?>(null) }
    var chartTab by remember { mutableIntStateOf(0) }

    if (dna == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = cfg.accent.secondary) }
        return
    }

    val tierColor = when (dna.privilegeTier) {
        PrivilegeTier.ROOT -> cfg.accent.primary; PrivilegeTier.SHIZUKU -> cfg.accent.secondary; PrivilegeTier.STANDARD -> GlassPalette.ElectricBlue
    }
    val healthColor = when (dna.healthStatus) {
        HealthStatus.GOOD -> GlassPalette.StatusGood; HealthStatus.NEEDS_ATTENTION -> GlassPalette.StatusWarn; HealthStatus.CRITICAL -> GlassPalette.StatusDanger
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Clean Top Header
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TWEAKORA", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Spacer(Modifier.height(2.dp))
                    Text("${dna.manufacturer} ${dna.model}", style = MaterialTheme.typography.headlineSmall)
                    Text("Android ${dna.androidVersion} (API ${dna.sdkInt}) • ${dna.socName}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                GlassStatusPill(dna.privilegeTier.badge, tierColor)
            }
        }

        // Active Alerts (Only shown when triggered)
        chargeAlertText?.let { alert ->
            item {
                GlassCard(tint = GlassPalette.StatusGood, glowAccent = GlassPalette.StatusGood, onClick = { onOpenOverlay("cleaner") }) {
                    Text("CHARGE GUARD ALERT", style = MaterialTheme.typography.labelMedium, color = GlassPalette.StatusGood)
                    Text(alert, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        latestRuleLog?.let { log ->
            item {
                GlassCard(tint = GlassPalette.WarmAmber, glowAccent = GlassPalette.WarmAmber, onClick = { onOpenOverlay("rules") }) {
                    Text("SMART RULE: ${log.ruleName.uppercase()}", style = MaterialTheme.typography.labelMedium, color = GlassPalette.WarmAmber)
                    Text(log.detail, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // 2. GLASS BOX 1 — SYSTEM HEALTH & PERFORMANCE PROFILE
        item {
            GlassBox(title = "System Health & Profile", accent = healthColor, badge = dna.healthStatus.label.uppercase()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlassRingGauge(dna.healthScore / 100f, "${dna.healthScore}", dna.healthStatus.label, "${dna.batteryPercent}% BAT")
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(String.format(Locale.US, "%.1f°C • %s", dna.batteryTempC, dna.thermalStatus), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("${if (dna.isCharging) "Charging" else "On Battery"} • ${dna.batteryHealth}", style = MaterialTheme.typography.bodySmall)
                        Text("${dna.batteryVoltageMv} mV • ${dna.cpuCores} Cores", style = MaterialTheme.typography.bodySmall)
                        Text("Mode: ${activeProfile.label}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cfg.accent.secondary)
                    }
                }
                GlassSegmentedTabs(
                    items = PerformanceProfile.entries.map { it.label },
                    selectedIndex = PerformanceProfile.entries.indexOf(activeProfile),
                    onSelect = { idx ->
                        val p = PerformanceProfile.entries[idx]
                        activeProfile = p
                        if (dna.privilegeTier != PrivilegeTier.STANDARD) {
                            scope.launch {
                                val anim = TweakEngine.catalog.first { it.id == "anim_transition" }
                                val rec = TweakEngine.applyTweak(context, anim, p.animScale)
                                profileFeedback = if (rec.succeeded) "${p.label} applied (${p.animScale}x transitions)." else "${p.label} selected."
                            }
                        } else profileFeedback = "${p.label} selected. Grant Shizuku in Tools to auto-apply."
                    }
                )
                profileFeedback?.let { Text(it, fontSize = 12.5.sp, color = cfg.accent.secondary) }
            }
        }

        // 3. GLASS BOX 2 — LIVE VITALS 2x2 MATRIX & WAVEFORM
        item {
            GlassBox(title = "Live Vitals", accent = cfg.accent.secondary, badge = "ECO LIVE", collapsible = true, initiallyExpanded = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassStatCell("RAM", "${(dna.ramFraction * 100).toInt()}%", "${dna.usedRamMb}/${dna.totalRamMb} MB", cfg.accent.primary, { onOpenTab("monitor") }, Modifier.weight(1f))
                    GlassStatCell("Storage", String.format(Locale.US, "%.1f GB", dna.freeStorageGb), "Free of ${String.format(Locale.US, "%.0f GB", dna.totalStorageGb)}", cfg.accent.secondary, { onOpenTab("settings") }, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassStatCell("Battery", "${dna.batteryPercent}%", "${dna.batteryHealth} • ${dna.batteryVoltageMv}mV", GlassPalette.StatusGood, { onOpenOverlay("cleaner") }, Modifier.weight(1f))
                    GlassStatCell("Thermal", String.format(Locale.US, "%.1f°C", dna.batteryTempC), dna.thermalStatus, if (dna.batteryTempC >= 39f) GlassPalette.WarmAmber else GlassPalette.NeonMagenta, { onOpenTab("monitor") }, Modifier.weight(1f))
                }
                GlassSegmentedTabs(listOf("RAM Wave", "Thermal Wave"), chartTab, { chartTab = it })
                GlassWaveChart(if (chartTab == 0) ramHistory else tempHistory, if (chartTab == 0) cfg.accent.secondary else GlassPalette.NeonMagenta)
            }
        }

        // 4. GLASS BOX 3 — SPECIALIZED UTILITIES (Dedicated tools not duplicated from bottom tabs)
        item {
            GlassBox(title = "Utilities & Hardware Labs", accent = cfg.accent.primary, badge = "DIRECT") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassBoxTile("RAM Cleaner", "Junk & Bypass", Icons.Rounded.CleaningServices, GlassPalette.StatusGood, { onOpenOverlay("cleaner") }, Modifier.weight(1f))
                    GlassBoxTile("Sources Lab", "Sensors+Audio", Icons.Rounded.Sensors, cfg.accent.secondary, { onOpenOverlay("sources") }, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassBoxTile("Smart Rules", "Auto & Sim", Icons.Rounded.Bolt, GlassPalette.NeonMagenta, { onOpenOverlay("rules") }, Modifier.weight(1f))
                    GlassBoxTile("Root Lab", dna.privilegeTier.badge, Icons.Rounded.Science, GlassPalette.WarmAmber, { onOpenOverlay("rootlab") }, Modifier.weight(1f))
                }
            }
        }

        // 5. GLASS BOX 4 — SYSTEM DNA (Collapsible)
        item {
            GlassBox(title = "System DNA", accent = cfg.accent.primary, badge = "${dna.sensorCount} SENSORS", collapsible = true, initiallyExpanded = false) {
                GlassSpecRow("Processor", "${dna.socName} (${dna.cpuCores} Cores)")
                GlassSpecRow("Live Clock", dna.peakLiveClockMhz?.let { "$it MHz peak" } ?: "SELinux Protected")
                GlassSpecRow("GPU Renderer", dna.gpuRenderer)
                GlassSpecRow("Display Panel", dna.displayText)
                GlassSpecRow("Kernel", dna.kernelVersion)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassPillButton("Check Root", onClick = onCheckRoot, modifier = Modifier.weight(1f))
                    GlassPillButton("All Hardware", onClick = { onOpenTab("settings") }, gradient = listOf(cfg.accent.primary, cfg.accent.secondary), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
// END OF MAINACTIVITY
