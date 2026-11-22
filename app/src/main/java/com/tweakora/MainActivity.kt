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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// ---------- MODELS ----------

enum class PrivilegeTier(val badge: String) {
    STANDARD("STANDARD"),
    SHIZUKU("SHIZUKU"),
    ROOT("ROOTED")
}

enum class HealthStatus(val label: String) {
    GOOD("Good"),
    NEEDS_ATTENTION("Needs Attention"),
    CRITICAL("Critical")
}

enum class PerformanceProfile(val label: String, val animScale: String) {
    BALANCED("Balanced", "0.75"),
    PERFORMANCE("Performance", "0.5"),
    BATTERY("Battery", "1.0"),
    GAMING("Gaming", "0.5")
}

data class DeviceDna(
    val manufacturer: String,
    val model: String,
    val deviceCodeName: String,
    val socName: String,
    val supportedAbis: List<String>,
    val cpuCores: Int,
    val coreFreqsMhz: List<Int?>,
    val peakLiveClockMhz: Int?,
    val gpuRenderer: String,
    val gpuVendor: String,
    val gpuGlVersion: String,
    val totalRamMb: Long,
    val usedRamMb: Long,
    val totalStorageGb: Float,
    val usedStorageGb: Float,
    val freeStorageGb: Float,
    val displayText: String,
    val sensorCount: Int,
    val androidVersion: String,
    val sdkInt: Int,
    val securityPatch: String,
    val buildId: String,
    val buildFingerprint: String,
    val kernelVersion: String,
    val batteryPercent: Int,
    val batteryTempC: Float,
    val batteryVoltageMv: Int,
    val batteryHealth: String,
    val isCharging: Boolean,
    val thermalStatus: String,
    val thermalHeadroom: Float?,
    val privilegeTier: PrivilegeTier,
    val healthScore: Int,
    val healthStatus: HealthStatus
) {
    val ramFraction: Float get() = if (totalRamMb > 0) (usedRamMb.toFloat() / totalRamMb).coerceIn(0f, 1f) else 0f
    val storageFraction: Float get() = if (totalStorageGb > 0f) (usedStorageGb / totalStorageGb).coerceIn(0f, 1f) else 0f
}

// ---------- SYSTEM DNA PROFILER ----------

object SystemInspector {
    @Volatile private var cachedGpu: Triple<String, String, String>? = null

    private fun readGpu(): Triple<String, String, String> {
        cachedGpu?.let { return it }
        val res = runCatching {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
            val cfg = configs[0] ?: return Triple("Unavailable", "Unavailable", "Unavailable")
            val surface = EGL14.eglCreatePbufferSurface(
                display, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            val ctx = EGL14.eglCreateContext(
                display, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            EGL14.eglMakeCurrent(display, surface, surface, ctx)
            val info = Triple(
                GLES20.glGetString(GLES20.GL_RENDERER) ?: "Unavailable",
                GLES20.glGetString(GLES20.GL_VENDOR) ?: "Unavailable",
                GLES20.glGetString(GLES20.GL_VERSION) ?: "Unavailable"
            )
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, ctx)
            EGL14.eglTerminate(display)
            info
        }.getOrDefault(Triple("Unavailable", "Unavailable", "Unavailable"))
        cachedGpu = res
        return res
    }

    fun detectTier(requestRoot: Boolean): PrivilegeTier {
        if (Shell.isAppGrantedRoot() == true) return PrivilegeTier.ROOT
        if (requestRoot) {
            val shell = runCatching { Shell.getShell() }.getOrNull()
            if (shell?.isRoot == true) return PrivilegeTier.ROOT
        }
        val shizukuOk = runCatching {
            Shizuku.pingBinder() && !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (shizukuOk) PrivilegeTier.SHIZUKU else PrivilegeTier.STANDARD
    }

    suspend fun capture(context: Context, requestRoot: Boolean = false): DeviceDna =
        withContext(Dispatchers.IO) {
            val am = context.getSystemService(ActivityManager::class.java)
            val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val totalRamMb = memInfo.totalMem / (1024L * 1024L)
            val availRamMb = memInfo.availMem / (1024L * 1024L)
            val usedRamMb = (totalRamMb - availRamMb).coerceAtLeast(0L)

            val statFs = StatFs(Environment.getDataDirectory().path)
            val totalStorageGb = (statFs.blockCountLong * statFs.blockSizeLong) / 1_073_741_824f
            val freeStorageGb = (statFs.availableBlocksLong * statFs.blockSizeLong) / 1_073_741_824f
            val usedStorageGb = (totalStorageGb - freeStorageGb).coerceAtLeast(0f)

            val dm = context.getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display?.mode
            val displayText = if (mode != null) {
                "${mode.physicalWidth}x${mode.physicalHeight} @ ${mode.refreshRate.toInt()}Hz"
            } else "Unknown"

            val sm = context.getSystemService(SensorManager::class.java)
            val sensorCount = sm?.getSensorList(Sensor.TYPE_ALL)?.size ?: 0

            val bat = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val percent = if (lvl >= 0 && scale > 0) (lvl * 100) / scale else 0
            val tempC = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            val voltageMv = bat?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val statusInt = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
                statusInt == BatteryManager.BATTERY_STATUS_FULL
            val healthLabel = when (bat?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Degraded"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                else -> "Unknown"
            }

            val pm = context.getSystemService(PowerManager::class.java)
            val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when (pm.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "Nominal"
                    PowerManager.THERMAL_STATUS_LIGHT -> "Warm"
                    PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                    PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                    else -> "High"
                }
            } else "Android 10+"

            val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getThermalHeadroom(10).takeUnless { it.isNaN() || it < 0f }
            } else null

            val cores = Runtime.getRuntime().availableProcessors()
            val freqs = (0 until cores).map { i ->
                runCatching {
                    File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                        .readText().trim().toInt() / 1000
                }.getOrNull()
            }
            val peakMhz = freqs.filterNotNull().maxOrNull()

            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL.isNotBlank()) {
                "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim()
            } else Build.HARDWARE

            val kernel = runCatching {
                File("/proc/version").readText().trim().substringBefore(" (")
            }.getOrElse { System.getProperty("os.version") ?: "Unknown" }

            var score = 100
            if (totalRamMb > 0 && usedRamMb.toFloat() / totalRamMb > 0.85f) score -= 15
            if (totalStorageGb > 0f && usedStorageGb / totalStorageGb > 0.88f) score -= 20
            if (tempC >= 42f) score -= 25 else if (tempC >= 38f) score -= 10
            if (healthLabel != "Good" && healthLabel != "Unknown") score -= 20
            score = score.coerceIn(0, 100)

            val healthStatus = when {
                score >= 80 -> HealthStatus.GOOD
                score >= 55 -> HealthStatus.NEEDS_ATTENTION
                else -> HealthStatus.CRITICAL
            }
            val gpu = readGpu()

            DeviceDna(
                manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
                model = Build.MODEL,
                deviceCodeName = Build.DEVICE,
                socName = soc,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                cpuCores = cores,
                coreFreqsMhz = freqs,
                peakLiveClockMhz = peakMhz,
                gpuRenderer = gpu.first,
                gpuVendor = gpu.second,
                gpuGlVersion = gpu.third,
                totalRamMb = totalRamMb,
                usedRamMb = usedRamMb,
                totalStorageGb = totalStorageGb,
                usedStorageGb = usedStorageGb,
                freeStorageGb = freeStorageGb,
                displayText = displayText,
                sensorCount = sensorCount,
                androidVersion = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                securityPatch = Build.VERSION.SECURITY_PATCH,
                buildId = Build.DISPLAY,
                buildFingerprint = Build.FINGERPRINT,
                kernelVersion = kernel,
                batteryPercent = percent,
                batteryTempC = tempC,
                batteryVoltageMv = voltageMv,
                batteryHealth = healthLabel,
                isCharging = isCharging,
                thermalStatus = thermalStatus,
                thermalHeadroom = headroom,
                privilegeTier = detectTier(requestRoot),
                healthScore = score,
                healthStatus = healthStatus
            )
        }
}

// ---------- ACTIVITY & GLASS NAVIGATION SHELL ----------

private data class NavTab(val route: String, val label: String, val icon: ImageVector)

class MainActivity : ComponentActivity() {
    companion object {
        init {
            runCatching { Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(10)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            var themeConfig by remember { mutableStateOf(AppPrefs.loadThemeConfig(ctx)) }

            TweakoraGlassTheme(config = themeConfig) {
                TweakoraGlassShell(
                    themeConfig = themeConfig,
                    onUpdateTheme = { newCfg ->
                        themeConfig = newCfg
                        AppPrefs.saveThemeConfig(ctx, newCfg)
                    }
                )
            }
        }
    }
}

@Composable
private fun TweakoraGlassShell(
    themeConfig: GlassThemeConfig,
    onUpdateTheme: (GlassThemeConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()

    var dna by remember { mutableStateOf<DeviceDna?>(null) }
    var ramHistory by remember { mutableStateOf(listOf<Float>()) }
    var tempHistory by remember { mutableStateOf(listOf<Float>()) }
    var isPaused by remember { mutableStateOf(false) }

    LaunchedEffect(isPaused) {
        while (isActive) {
            if (!isPaused || dna == null) {
                val snap = SystemInspector.capture(context, requestRoot = false)
                dna = snap
                ramHistory = (ramHistory + snap.ramFraction).takeLast(14)
                tempHistory = (tempHistory + ((snap.batteryTempC - 20f) / 30f).coerceIn(0.05f, 1f)).takeLast(14)
            }
            delay(2000L)
        }
    }

    val tabs = listOf(
        NavTab("home", "Home", Icons.Rounded.Dashboard),
        NavTab("tweaks", "Tweaks", Icons.Rounded.Tune),
        NavTab("gaming", "Gaming", Icons.Rounded.SportsEsports),
        NavTab("monitor", "Monitor", Icons.Rounded.QueryStats),
        NavTab("settings", "Tools", Icons.Rounded.Settings)
    )
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route

    fun goTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    GlassBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (currentRoute != "rootlab") {
                    GlassBottomNavBar(
                        tabs = tabs,
                        currentRoute = currentRoute,
                        onSelect = { goTab(it) }
                    )
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier.padding(padding)
            ) {
                composable("home") {
                    GlassHomeDashboard(
                        dna = dna,
                        ramHistory = ramHistory,
                        tempHistory = tempHistory,
                        onOpenTab = { goTab(it) },
                        onOpenRootLab = { navController.navigate("rootlab") },
                        onCheckRoot = {
                            scope.launch { dna = SystemInspector.capture(context, requestRoot = true) }
                        }
                    )
                }
                composable("tweaks") {
                    TweaksAndTimeMachineScreen(
                        deviceTier = dna?.privilegeTier ?: PrivilegeTier.STANDARD,
                        onTierRefresh = {
                            scope.launch { dna = SystemInspector.capture(context, requestRoot = true) }
                        }
                    )
                }
                composable("gaming") {
                    GamingHubScreen(dna = dna)
                }
                composable("monitor") {
                    MonitorHubScreen(
                        dna = dna,
                        ramHistory = ramHistory,
                        tempHistory = tempHistory,
                        isMonitoringPaused = isPaused,
                        onToggleMonitoring = { isPaused = !isPaused }
                    )
                }
                composable("settings") {
                    SettingsAndToolsScreen(
                        dna = dna,
                        themeConfig = themeConfig,
                        onUpdateTheme = onUpdateTheme,
                        onOpenRootLab = { navController.navigate("rootlab") },
                        onRefreshDna = {
                            scope.launch { dna = SystemInspector.capture(context, requestRoot = true) }
                        }
                    )
                }
                composable("rootlab") {
                    RootLabScreen(
                        dna = dna,
                        onBack = { navController.popBackStack() },
                        onRefreshDna = {
                            scope.launch { dna = SystemInspector.capture(context, requestRoot = true) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassBottomNavBar(
    tabs: List<NavTab>,
    currentRoute: String?,
    onSelect: (String) -> Unit
) {
    val cfg = LocalGlassConfig.current
    val isLight = cfg.mode == GlassThemeMode.LIGHT
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        val shape = RoundedCornerShape(28.dp)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = if (isLight) Color(0xCCFFFFFF) else Color(0xBB110D24),
            border = BorderStroke(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        Color.White.copy(alpha = if (isLight) 0.9f else 0.24f),
                        cfg.accent.primary.copy(alpha = 0.45f),
                        cfg.accent.secondary.copy(alpha = 0.38f)
                    )
                )
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val selected = currentRoute == tab.route
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (selected) {
                                    Brush.horizontalGradient(
                                        listOf(
                                            cfg.accent.primary.copy(alpha = 0.30f),
                                            cfg.accent.secondary.copy(alpha = 0.26f)
                                        )
                                    )
                                } else {
                                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                                }
                            )
                            .clickable { onSelect(tab.route) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) cfg.accent.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            tab.label,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ---------- TRANSPARENT GLASS HOME DASHBOARD ----------

@Composable
private fun GlassHomeDashboard(
    dna: DeviceDna?,
    ramHistory: List<Float>,
    tempHistory: List<Float>,
    onOpenTab: (String) -> Unit,
    onOpenRootLab: () -> Unit,
    onCheckRoot: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = LocalGlassConfig.current

    var activeProfile by remember { mutableStateOf(PerformanceProfile.BALANCED) }
    var profileFeedback by remember { mutableStateOf<String?>(null) }
    var chartTab by remember { mutableIntStateOf(0) }
    var recentTweaks by remember { mutableStateOf<List<TweakHistoryRecord>>(emptyList()) }

    LaunchedEffect(dna) {
        recentTweaks = withContext(Dispatchers.IO) { TweakStore.loadHistory(context).take(3) }
    }

    if (dna == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = cfg.accent.secondary)
        }
        return
    }

    val tierColor = when (dna.privilegeTier) {
        PrivilegeTier.ROOT -> cfg.accent.primary
        PrivilegeTier.SHIZUKU -> cfg.accent.secondary
        PrivilegeTier.STANDARD -> GlassPalette.ElectricBlue
    }
    val healthColor = when (dna.healthStatus) {
        HealthStatus.GOOD -> GlassPalette.StatusGood
        HealthStatus.NEEDS_ATTENTION -> GlassPalette.StatusWarn
        HealthStatus.CRITICAL -> GlassPalette.StatusDanger
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Header
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("TWEAKORA", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Text("${dna.manufacturer} ${dna.model}", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Android ${dna.androidVersion} (API ${dna.sdkInt}) • ${dna.buildId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GlassStatusPill(dna.privilegeTier.badge, tierColor)
            }
        }

        // 2. Hero Glass Ring Card
        item {
            GlassCard(tint = cfg.accent.primary, glowAccent = cfg.accent.primary) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    GlassRingGauge(
                        progress = dna.healthScore / 100f,
                        centerTitle = "${dna.healthScore}",
                        centerSubtitle = dna.healthStatus.label,
                        pillLabel = "${dna.batteryPercent}% BAT"
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassStatusPill(dna.healthStatus.label.uppercase(), healthColor)
                        Text(
                            text = String.format(Locale.US, "%.1f°C • %s", dna.batteryTempC, dna.thermalStatus),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${if (dna.isCharging) "Charging" else "On Battery"} • ${dna.batteryHealth} (${dna.batteryVoltageMv} mV)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Profile: ${activeProfile.label}",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cfg.accent.secondary
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PerformanceProfile.entries) { p ->
                        FilterChip(
                            selected = activeProfile == p,
                            onClick = {
                                activeProfile = p
                                if (dna.privilegeTier != PrivilegeTier.STANDARD) {
                                    scope.launch {
                                        val anim = TweakEngine.catalog.first { it.id == "anim_transition" }
                                        val rec = TweakEngine.applyTweak(context, anim, p.animScale)
                                        profileFeedback = if (rec.succeeded) {
                                            "${p.label} profile active (Transition scale ${p.animScale}x applied)."
                                        } else {
                                            "${p.label} profile selected."
                                        }
                                    }
                                } else {
                                    profileFeedback = "${p.label} profile selected. Enable Shizuku/Root in Tools to auto-apply system scales."
                                }
                            },
                            label = { Text(p.label, fontSize = 11.sp) }
                        )
                    }
                }
                profileFeedback?.let { msg ->
                    Spacer(Modifier.height(6.dp))
                    Text(msg, fontSize = 11.sp, color = cfg.accent.secondary)
                }
            }
        }

        // 3. Gradient Action Banner
        item {
            GlassActionBanner(
                title = "Full Device Scan & Tweakora AI",
                subtitle = "Check bottlenecks, hardware DNA, storage & privacy score",
                icon = Icons.Rounded.AutoAwesome,
                onClick = { onOpenTab("settings") }
            )
        }

        // 4. Translucent Glass Tool Grid (All Working)
        item {
            Text("CONTROL CENTRE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassToolTile(
                    title = "Tweaks",
                    subtitle = "14 controls",
                    icon = Icons.Rounded.Tune,
                    accent = cfg.accent.primary,
                    onClick = { onOpenTab("tweaks") },
                    modifier = Modifier.weight(1f)
                )
                GlassToolTile(
                    title = "Gaming",
                    subtitle = "Autopsy + Ping",
                    icon = Icons.Rounded.SportsEsports,
                    accent = cfg.accent.secondary,
                    onClick = { onOpenTab("gaming") },
                    modifier = Modifier.weight(1f)
                )
                GlassToolTile(
                    title = "Bench & Labs",
                    subtitle = "CPU + Thermal",
                    icon = Icons.Rounded.Speed,
                    accent = GlassPalette.WarmAmber,
                    onClick = { onOpenTab("monitor") },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 5. Live Glowing Wave Telemetry Card
        item {
            GlassCard(glowAccent = cfg.accent.secondary) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("LIVE TELEMETRY", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = chartTab == 0,
                            onClick = { chartTab = 0 },
                            label = { Text("RAM ${(dna.ramFraction * 100).toInt()}%", fontSize = 10.sp) }
                        )
                        FilterChip(
                            selected = chartTab == 1,
                            onClick = { chartTab = 1 },
                            label = { Text(String.format(Locale.US, "%.1f°C", dna.batteryTempC), fontSize = 10.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                GlassWaveChart(
                    values = if (chartTab == 0) ramHistory else tempHistory,
                    lineColor = if (chartTab == 0) cfg.accent.secondary else GlassPalette.NeonMagenta
                )
                Spacer(Modifier.height(10.dp))
                GlassGaugeRow("RAM", "${dna.usedRamMb} / ${dna.totalRamMb} MB", dna.ramFraction, cfg.accent.primary)
                Spacer(Modifier.height(8.dp))
                GlassGaugeRow(
                    "Storage",
                    String.format(Locale.US, "%.1f / %.1f GB (%.1f GB free)", dna.usedStorageGb, dna.totalStorageGb, dna.freeStorageGb),
                    dna.storageFraction,
                    cfg.accent.secondary
                )
            }
        }

        // 6. System DNA Summary
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

        // 7. Root Lab & Recent Tweaks
        item {
            GlassCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("RECENT TWEAKS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        TextButton(onClick = onCheckRoot) {
                            Text("Check Root", fontSize = 11.5.sp, color = cfg.accent.secondary)
                        }
                        TextButton(onClick = onOpenRootLab) {
                            Text("Root Lab", fontSize = 11.5.sp, color = GlassPalette.WarmAmber)
                        }
                    }
                }
                if (recentTweaks.isEmpty()) {
                    Text(
                        "No tweaks applied yet. Open Tweaks to run system controls or capture a Time Machine snapshot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    recentTweaks.forEach { rec ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(rec.tweakName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text(
                                "${rec.previousValue} → ${rec.newValue}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (rec.succeeded) GlassPalette.StatusGood else GlassPalette.StatusDanger
                            )
                        }
                    }
                }
            }
        }
    }
}
