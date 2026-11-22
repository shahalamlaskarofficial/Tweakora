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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
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

private data class StaticHardwareCache(
    val soc: String, val cores: Int, val gpu: Triple<String, String, String>,
    val displayText: String, val sensorCount: Int, val kernel: String, val abis: List<String>
)

/**
 * Battery-optimized System DNA Profiler: caches static hardware specs once at startup
 * so live telemetry ticks only read lightweight RAM, Battery & Thermal sensors.
 */
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
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()

    var dna by remember { mutableStateOf<DeviceDna?>(null) }
    var ramHistory by remember { mutableStateOf(listOf<Float>()) }
    var tempHistory by remember { mutableStateOf(listOf<Float>()) }
    var isPaused by remember { mutableStateOf(false) }
    var isAppForeground by remember { mutableStateOf(true) }
    var ruleLogs by remember { mutableStateOf(listOf<RuleEventLog>()) }
    var chargeAlertText by remember { mutableStateOf<String?>(null) }
    var lastChargeAlarmMs by remember { mutableLongStateOf(0L) }

    // Automatically pause polling when app goes to background (0% background battery drain)
    DisposableEffect(context) {
        val owner = context as? LifecycleOwner
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isAppForeground = true
            else if (event == Lifecycle.Event.ON_PAUSE) isAppForeground = false
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }

    LaunchedEffect(isPaused, isAppForeground, settings.pollIntervalSec, settings.chargeGuardEnabled, settings.chargeLimitPct) {
        while (isActive) {
            if (isAppForeground && (!isPaused || dna == null)) {
                val snap = SystemInspector.capture(context, requestRoot = false)
                dna = snap
                ramHistory = (ramHistory + snap.ramFraction).takeLast(14)
                tempHistory = (tempHistory + ((snap.batteryTempC - 20f) / 30f).coerceIn(0.05f, 1f)).takeLast(14)

                val fired = SmartRulesEngine.evaluateLive(context, snap)
                if (fired.isNotEmpty()) ruleLogs = (fired + ruleLogs).take(20)

                // Live Smart Charge Guard Alert
                if (settings.chargeGuardEnabled && snap.isCharging && (snap.batteryPercent >= settings.chargeLimitPct || snap.batteryTempC >= 41f)) {
                    chargeAlertText = "Charge Guard: ${snap.batteryPercent}% (${String.format(Locale.US, "%.1f°C", snap.batteryTempC)}) — Unplug charger to protect battery health."
                    val now = System.currentTimeMillis()
                    if (now - lastChargeAlarmMs > 120_000L) {
                        lastChargeAlarmMs = now
                        BypassChargingEngine.playChargeGuardTone(context)
                    }
                } else {
                    chargeAlertText = null
                }
            }
            if (settings.pollIntervalSec <= 0) break // Manual mode
            delay(settings.pollIntervalSec * 1000L)
        }
    }

    val navTabs = listOf(
        Triple("home", "Home", Icons.Rounded.Dashboard),
        Triple("tweaks", "Tweaks", Icons.Rounded.Tune),
        Triple("gaming", "Gaming", Icons.Rounded.SportsEsports),
        Triple("monitor", "Monitor", Icons.Rounded.QueryStats),
        Triple("settings", "Tools", Icons.Rounded.Settings)
    )
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route

    fun goTab(route: String) {
        if (currentRoute == route) return
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    GlassBackdrop {
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn(tween(180)) },
                exitTransition = { fadeOut(tween(130)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(130)) }
            ) {
                composable("home") {
                    GlassHomeDashboard(
                        dna = dna,
                        ramHistory = ramHistory,
                        tempHistory = tempHistory,
                        latestRuleLog = ruleLogs.firstOrNull(),
                        chargeAlertText = chargeAlertText,
                        onOpenTab = { goTab(it) },
                        onOpenCleaner = { navController.navigate("cleaner") },
                        onOpenRootLab = { navController.navigate("rootlab") },
                        onOpenSmartRules = { navController.navigate("rules") },
                        onCheckRoot = { scope.launch { dna = SystemInspector.capture(context, requestRoot = true) } }
                    )
                }
                composable("tweaks") {
                    TweaksAndTimeMachineScreen(
                        deviceTier = dna?.privilegeTier ?: PrivilegeTier.STANDARD,
                        onTierRefresh = { scope.launch { dna = SystemInspector.capture(context, requestRoot = true) } }
                    )
                }
                composable("gaming") {
                    Box(Modifier.fillMaxSize().padding(bottom = 86.dp)) { GamingHubScreen(dna = dna) }
                }
                composable("monitor") {
                    Box(Modifier.fillMaxSize().padding(bottom = 86.dp)) {
                        MonitorHubScreen(dna = dna, ramHistory = ramHistory, tempHistory = tempHistory, isMonitoringPaused = isPaused, onToggleMonitoring = { isPaused = !isPaused })
                    }
                }
                composable("settings") {
                    SettingsAndToolsScreen(
                        dna = dna, themeConfig = themeConfig, settings = settings,
                        onUpdateTheme = onUpdateTheme, onUpdateSettings = onUpdateSettings, onResetAll = onResetAll,
                        onOpenCleaner = { navController.navigate("cleaner") },
                        onOpenRootLab = { navController.navigate("rootlab") },
                        onOpenSmartRules = { navController.navigate("rules") },
                        onRefreshDna = { scope.launch { dna = SystemInspector.capture(context, requestRoot = true) } }
                    )
                }
                composable("cleaner") {
                    CleanerAndBypassScreen(
                        dna = dna, settings = settings, onUpdateSettings = onUpdateSettings,
                        onRefreshDna = { scope.launch { dna = SystemInspector.capture(context, requestRoot = false) } },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("rootlab") {
                    RootLabScreen(dna = dna, onBack = { navController.popBackStack() }, onRefreshDna = { scope.launch { dna = SystemInspector.capture(context, requestRoot = true) } })
                }
                composable("rules") {
                    SmartRulesAndSimScreen(dna = dna, recentLogs = ruleLogs, onBack = { navController.popBackStack() })
                }
            }

            if (currentRoute !in listOf("rootlab", "rules", "cleaner")) {
                LiquidGlassNavBar(
                    items = navTabs,
                    currentRoute = currentRoute,
                    onSelect = { goTab(it) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun GlassHomeDashboard(
    dna: DeviceDna?,
    ramHistory: List<Float>,
    tempHistory: List<Float>,
    latestRuleLog: RuleEventLog?,
    chargeAlertText: String?,
    onOpenTab: (String) -> Unit,
    onOpenCleaner: () -> Unit,
    onOpenRootLab: () -> Unit,
    onOpenSmartRules: () -> Unit,
    onCheckRoot: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = LocalGlassConfig.current

    var activeProfile by remember { mutableStateOf(PerformanceProfile.BALANCED) }
    var profileFeedback by remember { mutableStateOf<String?>(null) }
    var chartTab by remember { mutableIntStateOf(0) }
    var recentTweaks by remember { mutableStateOf<List<TweakHistoryRecord>>(emptyList()) }

    // Load recent tweaks once when Home opens (not every tick -> saves battery & prevents UI stutter)
    LaunchedEffect(Unit) {
        recentTweaks = withContext(Dispatchers.IO) { TweakStore.loadHistory(context).take(3) }
    }

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
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TWEAKORA", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Spacer(Modifier.height(2.dp))
                    Text("${dna.manufacturer} ${dna.model}", style = MaterialTheme.typography.headlineSmall)
                    Text("Android ${dna.androidVersion} (API ${dna.sdkInt}) • ${dna.buildId}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(8.dp))
                GlassStatusPill(dna.privilegeTier.badge, tierColor)
            }
        }

        chargeAlertText?.let { alert ->
            item {
                GlassCard(tint = GlassPalette.StatusGood, glowAccent = GlassPalette.StatusGood, onClick = onOpenCleaner) {
                    Text("CHARGE GUARD ALERT", style = MaterialTheme.typography.labelMedium, color = GlassPalette.StatusGood)
                    Spacer(Modifier.height(2.dp))
                    Text(alert, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        latestRuleLog?.let { log ->
            item {
                GlassCard(tint = GlassPalette.WarmAmber, glowAccent = GlassPalette.WarmAmber, onClick = onOpenSmartRules) {
                    Text("SMART RULE: ${log.ruleName.uppercase()}", style = MaterialTheme.typography.labelMedium, color = GlassPalette.WarmAmber)
                    Text(log.detail, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            GlassCard(tint = cfg.accent.primary, glowAccent = cfg.accent.primary) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    GlassRingGauge(dna.healthScore / 100f, "${dna.healthScore}", dna.healthStatus.label, "${dna.batteryPercent}% BAT")
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassStatusPill(dna.healthStatus.label.uppercase(), healthColor)
                        Text(String.format(Locale.US, "%.1f°C • %s", dna.batteryTempC, dna.thermalStatus), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("${if (dna.isCharging) "Charging" else "On Battery"} • ${dna.batteryHealth} (${dna.batteryVoltageMv} mV)", style = MaterialTheme.typography.bodySmall)
                        Text("Profile: ${activeProfile.label}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cfg.accent.secondary)
                    }
                }
                Spacer(Modifier.height(14.dp))
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
                                profileFeedback = if (rec.succeeded) "${p.label} active (${p.animScale}x transitions)." else "${p.label} selected."
                            }
                        } else profileFeedback = "${p.label} selected. Grant Shizuku in Tools to auto-apply."
                    }
                )
                profileFeedback?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 12.5.sp, color = cfg.accent.secondary) }
            }
        }

        // Quick Action Banner for Cleaner & Bypass Charging
        item {
            GlassActionBanner(
                title = "RAM Cleaner, Junk & Bypass Charging",
                subtitle = "Free memory safely, clean leftover files & protect battery charging",
                icon = Icons.Rounded.CleaningServices,
                onClick = onOpenCleaner
            )
        }

        item {
            Text("CONTROL CENTRE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassToolTile("Tweaks", "24 controls", Icons.Rounded.Tune, cfg.accent.primary, { onOpenTab("tweaks") }, Modifier.weight(1f))
                GlassToolTile("Gaming", "Autopsy & Hz", Icons.Rounded.SportsEsports, cfg.accent.secondary, { onOpenTab("gaming") }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassToolTile("Perf Labs", "Bench & Temp", Icons.Rounded.Speed, GlassPalette.WarmAmber, { onOpenTab("monitor") }, Modifier.weight(1f))
                GlassToolTile("Smart Rules", "Auto & Sim", Icons.Rounded.Bolt, GlassPalette.NeonMagenta, onOpenSmartRules, Modifier.weight(1f))
            }
        }

        item {
            GlassCard(glowAccent = cfg.accent.secondary) {
                Text("LIVE TELEMETRY", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                Spacer(Modifier.height(10.dp))
                GlassSegmentedTabs(listOf("RAM ${(dna.ramFraction * 100).toInt()}%", String.format(Locale.US, "Thermal %.1f°C", dna.batteryTempC)), chartTab, { chartTab = it })
                Spacer(Modifier.height(10.dp))
                GlassWaveChart(if (chartTab == 0) ramHistory else tempHistory, if (chartTab == 0) cfg.accent.secondary else GlassPalette.NeonMagenta)
                Spacer(Modifier.height(12.dp))
                GlassGaugeRow("RAM Usage", "${dna.usedRamMb} / ${dna.totalRamMb} MB", dna.ramFraction, cfg.accent.primary)
                Spacer(Modifier.height(10.dp))
                GlassGaugeRow("Storage", String.format(Locale.US, "%.1f / %.1f GB (%.1f GB free)", dna.usedStorageGb, dna.totalStorageGb, dna.freeStorageGb), dna.storageFraction, cfg.accent.secondary)
            }
        }

        item {
            GlassCard(onClick = { onOpenTab("settings") }) {
                Text("SYSTEM DNA", style = MaterialTheme.typography.labelMedium, color = cfg.accent.primary)
                Spacer(Modifier.height(6.dp))
                GlassSpecRow("Processor", "${dna.socName} (${dna.cpuCores} Cores)")
                GlassSpecRow("Live Clock", dna.peakLiveClockMhz?.let { "$it MHz peak" } ?: "SELinux Protected")
                GlassSpecRow("GPU", dna.gpuRenderer)
                GlassSpecRow("Display", dna.displayText)
                GlassSpecRow("Sensors", "${dna.sensorCount} detected")
            }
        }

        item {
            GlassCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("RECENT TWEAKS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        TextButton(onClick = onCheckRoot) { Text("Check Root", fontSize = 13.sp, color = cfg.accent.secondary) }
                        TextButton(onClick = onOpenRootLab) { Text("Root Lab", fontSize = 13.sp, color = GlassPalette.WarmAmber) }
                    }
                }
                if (recentTweaks.isEmpty()) Text("No tweaks applied yet. Open Tweaks to explore 24 controls & Time Machine.", style = MaterialTheme.typography.bodySmall)
                else recentTweaks.forEach { rec ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(rec.tweakName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("${rec.previousValue} → ${rec.newValue}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (rec.succeeded) GlassPalette.StatusGood else GlassPalette.StatusDanger)
                    }
                }
            }
        }
    }
}
// END OF MAINACTIVITY
