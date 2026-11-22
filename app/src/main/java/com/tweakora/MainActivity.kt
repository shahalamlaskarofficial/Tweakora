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
import androidx.compose.ui.text.TextStyle
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

enum class PerformanceProfile(val label: String) {
    BALANCED("Balanced"),
    PERFORMANCE("Performance"),
    BATTERY("Battery Saver"),
    GAMING("Gaming")
}

data class DeviceDna(
    val manufacturer: String,
    val model: String,
    val socName: String,
    val cpuCores: Int,
    val peakLiveClockMhz: Int?,
    val gpuRenderer: String,
    val totalRamMb: Long,
    val usedRamMb: Long,
    val totalStorageGb: Float,
    val usedStorageGb: Float,
    val freeStorageGb: Float,
    val displayText: String,
    val sensorCount: Int,
    val androidVersion: String,
    val sdkInt: Int,
    val buildId: String,
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
    @Volatile private var cachedGpu: String? = null

    private fun readGpuRenderer(): String {
        cachedGpu?.let { return it }
        val renderer = runCatching {
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
            val cfg = configs[0] ?: return "Unavailable"
            val surface = EGL14.eglCreatePbufferSurface(
                display, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            val ctx = EGL14.eglCreateContext(
                display, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            EGL14.eglMakeCurrent(display, surface, surface, ctx)
            val name = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Unavailable"
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, ctx)
            EGL14.eglTerminate(display)
            name
        }.getOrDefault("Unavailable")
        cachedGpu = renderer
        return renderer
    }

    private fun detectTier(requestRoot: Boolean): PrivilegeTier {
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
            val peakMhz = (0 until cores).mapNotNull { i ->
                runCatching {
                    File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                        .readText().trim().toInt() / 1000
                }.getOrNull()
            }.maxOrNull()

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

            DeviceDna(
                manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
                model = Build.MODEL,
                socName = soc,
                cpuCores = cores,
                peakLiveClockMhz = peakMhz,
                gpuRenderer = readGpuRenderer(),
                totalRamMb = totalRamMb,
                usedRamMb = usedRamMb,
                totalStorageGb = totalStorageGb,
                usedStorageGb = usedStorageGb,
                freeStorageGb = freeStorageGb,
                displayText = displayText,
                sensorCount = sensorCount,
                androidVersion = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                buildId = Build.DISPLAY,
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

// ---------- THEME & UI COMPONENTS ----------

private val NeonPurple = Color(0xFFA855F7)
private val ElectricBlue = Color(0xFF3B82F6)
private val CyberCyan = Color(0xFF06B6D4)
private val StatusGoodColor = Color(0xFF22C55E)
private val StatusWarnColor = Color(0xFFF59E0B)
private val StatusDangerColor = Color(0xFFEF4444)

@Composable
private fun TweakoraTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = NeonPurple,
        secondary = ElectricBlue,
        tertiary = CyberCyan,
        background = Color(0xFF000000),
        surface = Color(0xFF0C0E14),
        surfaceVariant = Color(0xFF12151E),
        outline = Color(0xFF232838),
        onBackground = Color(0xFFF8FAFC),
        onSurface = Color(0xFFF8FAFC),
        onSurfaceVariant = Color(0xFF94A3B8)
    )
    val typo = Typography(
        headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.8.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
    )
    MaterialTheme(colorScheme = colors, typography = typo, content = content)
}

@Composable
private fun HudCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = if (glow) {
        Brush.linearGradient(listOf(NeonPurple.copy(alpha = 0.7f), CyberCyan.copy(alpha = 0.45f)))
    } else {
        Brush.linearGradient(listOf(MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.outline))
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, border)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun GaugeRow(label: String, value: String, progress: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50)),
            color = color,
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        )
    }
}

// ---------- ACTIVITY & SHELL ----------

private data class NavTab(val route: String, val label: String, val icon: ImageVector)

class MainActivity : ComponentActivity() {
    companion object {
        init {
            runCatching {
                Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(10))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TweakoraTheme {
                TweakoraAppShell()
            }
        }
    }
}

@Composable
private fun TweakoraAppShell() {
    val navController = rememberNavController()
    val tabs = listOf(
        NavTab("home", "Home", Icons.Rounded.Dashboard),
        NavTab("tweaks", "Tweaks", Icons.Rounded.Tune),
        NavTab("gaming", "Gaming", Icons.Rounded.SportsEsports),
        NavTab("monitor", "Monitor", Icons.Rounded.QueryStats),
        NavTab("settings", "Settings", Icons.Rounded.Settings)
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = { goTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") {
                HomeDashboard(
                    onOpenTab = { goTab(it) },
                    onOpenRootLab = { navController.navigate("rootlab") }
                )
            }
            composable("tweaks") { PlaceholderScreen("Tweak Library & Time Machine", "Next up in Step 2") }
            composable("gaming") { PlaceholderScreen("Gaming Centre & Network Lab", "Coming in Step 5") }
            composable("monitor") { PlaceholderScreen("System Monitor, Battery & Thermal", "Coming in Step 4") }
            composable("settings") { PlaceholderScreen("Settings, Hardware & Health", "Coming in Step 3") }
            composable("rootlab") { PlaceholderScreen("Root Laboratory", "Coming in Step 7") }
        }
    }
}

@Composable
private fun HomeDashboard(onOpenTab: (String) -> Unit, onOpenRootLab: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dna by remember { mutableStateOf<DeviceDna?>(null) }
    var activeProfile by remember { mutableStateOf(PerformanceProfile.BALANCED) }

    LaunchedEffect(Unit) {
        while (isActive) {
            dna = SystemInspector.capture(context, requestRoot = false)
            delay(2000L)
        }
    }

    val current = dna
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = NeonPurple)
        }
        return
    }

    val tierColor = when (current.privilegeTier) {
        PrivilegeTier.ROOT -> NeonPurple
        PrivilegeTier.SHIZUKU -> CyberCyan
        PrivilegeTier.STANDARD -> ElectricBlue
    }
    val healthColor = when (current.healthStatus) {
        HealthStatus.GOOD -> StatusGoodColor
        HealthStatus.NEEDS_ATTENTION -> StatusWarnColor
        HealthStatus.CRITICAL -> StatusDangerColor
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("TWEAKORA", style = MaterialTheme.typography.labelMedium, color = NeonPurple)
                    Text("${current.manufacturer} ${current.model}", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Android ${current.androidVersion} (API ${current.sdkInt}) • ${current.buildId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(current.privilegeTier.badge, tierColor)
            }
        }

        item {
            HudCard(glow = true) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("DEVICE HEALTH", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${current.healthScore}", fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = healthColor)
                            Text("/100", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        StatusPill(current.healthStatus.label.uppercase(), healthColor)
                        Spacer(Modifier.height(6.dp))
                        Text("Profile: ${activeProfile.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PerformanceProfile.entries) { p ->
                        FilterChip(
                            selected = activeProfile == p,
                            onClick = { activeProfile = p },
                            label = { Text(p.label, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        item {
            HudCard {
                Text("SYSTEM DNA", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                Spacer(Modifier.height(10.dp))
                SpecRow("Processor", "${current.socName} (${current.cpuCores} Cores)")
                SpecRow("Live Clock", current.peakLiveClockMhz?.let { "$it MHz peak" } ?: "Protected by SELinux")
                SpecRow("GPU", current.gpuRenderer)
                SpecRow("Display", current.displayText)
                SpecRow("Sensors", "${current.sensorCount} detected")
                SpecRow("Kernel", current.kernelVersion)
            }
        }

        item {
            HudCard {
                Text("MEMORY & STORAGE", style = MaterialTheme.typography.labelMedium, color = ElectricBlue)
                Spacer(Modifier.height(12.dp))
                GaugeRow("RAM Usage", "${current.usedRamMb} / ${current.totalRamMb} MB", current.ramFraction, NeonPurple)
                Spacer(Modifier.height(12.dp))
                GaugeRow(
                    "Storage",
                    String.format(Locale.US, "%.1f / %.1f GB (%.1f GB free)", current.usedStorageGb, current.totalStorageGb, current.freeStorageGb),
                    current.storageFraction,
                    CyberCyan
                )
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HudCard(Modifier.weight(1f)) {
                    Text("BATTERY", style = MaterialTheme.typography.labelMedium, color = StatusGoodColor)
                    Spacer(Modifier.height(6.dp))
                    Text("${current.batteryPercent}%", fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("${if (current.isCharging) "Charging" else "Discharging"} • ${current.batteryHealth}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${current.batteryVoltageMv} mV", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HudCard(Modifier.weight(1f)) {
                    val tColor = when {
                        current.batteryTempC >= 42f -> StatusDangerColor
                        current.batteryTempC >= 38f -> StatusWarnColor
                        else -> CyberCyan
                    }
                    Text("THERMAL", style = MaterialTheme.typography.labelMedium, color = tColor)
                    Spacer(Modifier.height(6.dp))
                    Text(String.format(Locale.US, "%.1f°C", current.batteryTempC), fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = tColor)
                    Text(current.thermalStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        current.thermalHeadroom?.let { String.format(Locale.US, "Headroom: %.0f%%", (it * 100f).coerceAtMost(100f)) } ?: "Headroom: N/A",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickTile("Tweak Library", "Safe & advanced", Icons.Rounded.Tune, NeonPurple, { onOpenTab("tweaks") }, Modifier.weight(1f))
                QuickTile("Root Lab", if (current.privilegeTier == PrivilegeTier.ROOT) "Unlocked" else "Check status", Icons.Rounded.Science, StatusWarnColor, onOpenRootLab, Modifier.weight(1f))
            }
        }

        item {
            HudCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("PRIVILEGE ACCESS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        scope.launch { dna = SystemInspector.capture(context, requestRoot = true) }
                    }) {
                        Text("Check Root", fontSize = 12.sp)
                    }
                }
                Text(
                    "Current mode: ${current.privilegeTier.badge}. More Control. More Power.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun QuickTile(title: String, subtitle: String, icon: ImageVector, color: Color, onClick: () -> Unit, modifier: Modifier) {
    HudCard(modifier = modifier, onClick = onClick) {
        Icon(icon, contentDescription = title, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaceholderScreen(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
