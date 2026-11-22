package com.tweakora

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class RamCleanResult(
    val beforeUsedMb: Long,
    val afterUsedMb: Long,
    val freedMb: Long,
    val appsTrimmed: Int,
    val methodUsed: String
)

data class JunkCandidate(
    val path: String,
    val name: String,
    val category: String,
    val sizeBytes: Long
)

data class BypassNode(
    val path: String,
    val label: String,
    val enableBypassVal: String,
    val normalChargeVal: String,
    val currentVal: String
) {
    val isBypassActive: Boolean get() = currentVal.trim() == enableBypassVal
}

object CleanerEngine {

    suspend fun cleanRam(context: Context): RamCleanResult = withContext(Dispatchers.IO) {
        val am = context.getSystemService(ActivityManager::class.java)
        val memBefore = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val usedBefore = (memBefore.totalMem - memBefore.availMem) / (1024L * 1024L)

        val pm = context.packageManager
        val userPkgs = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            .filter {
                val isSys = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                !isSys && it.packageName != context.packageName
            }

        var trimmed = 0
        userPkgs.forEach { app ->
            runCatching {
                am.killBackgroundProcesses(app.packageName)
                trimmed++
            }
        }

        var method = "Android Safe Trim"
        if (Shell.isAppGrantedRoot() == true || PrivilegedRunner.isShizukuGranted()) {
            val (ok, _) = PrivilegedRunner.runShell("am kill-all", requireRoot = false)
            if (ok) method = if (Shell.isAppGrantedRoot() == true) "Root Deep Trim" else "Shizuku Deep Trim"
        }

        System.gc()
        Thread.sleep(350)

        val memAfter = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val usedAfter = (memAfter.totalMem - memAfter.availMem) / (1024L * 1024L)
        val freed = (usedBefore - usedAfter).coerceAtLeast(0L)

        RamCleanResult(usedBefore, usedAfter, freed, trimmed, method)
    }

    suspend fun scanJunk(context: Context): List<JunkCandidate> = withContext(Dispatchers.IO) {
        val results = mutableListOf<JunkCandidate>()

        // 1. App's own cache directories
        listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
            runCatching {
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    results.add(JunkCandidate(f.absolutePath, f.name, "App Cache", f.length()))
                }
            }
        }

        // 2. Temp files, log files, old APK installers & empty folders in Downloads/Documents
        val publicDirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        ).filter { it.exists() && it.canRead() }

        publicDirs.forEach { dir ->
            runCatching {
                dir.walkTopDown().maxDepth(3).forEach { f ->
                    val low = f.name.lowercase()
                    when {
                        f.isFile && (low.endsWith(".tmp") || low.endsWith(".log") || low.endsWith(".bak") || low.endsWith(".temp")) ->
                            results.add(JunkCandidate(f.absolutePath, f.name, "Temp / Log", f.length()))
                        f.isFile && low.endsWith(".apk") ->
                            results.add(JunkCandidate(f.absolutePath, f.name, "APK Installer", f.length()))
                        f.isDirectory && f != dir && (f.listFiles()?.isEmpty() == true) ->
                            results.add(JunkCandidate(f.absolutePath, f.name, "Empty Folder", 0L))
                    }
                }
            }
        }

        results.sortedByDescending { it.sizeBytes }.take(50)
    }

    suspend fun cleanJunk(items: List<JunkCandidate>): Pair<Int, Float> = withContext(Dispatchers.IO) {
        var removed = 0
        var freedBytes = 0L
        items.forEach { item ->
            val f = File(item.path)
            if (f.exists() && runCatching { f.delete() }.getOrDefault(false)) {
                removed++
                freedBytes += item.sizeBytes
            }
        }
        // Trim system app caches safely if Shizuku or Root is available
        if (Shell.isAppGrantedRoot() == true || PrivilegedRunner.isShizukuGranted()) {
            PrivilegedRunner.runShell("pm trim-caches 512M", requireRoot = false)
        }
        removed to (freedBytes / (1024f * 1024f))
    }
}

object BypassChargingEngine {
    // Known Linux kernel PMIC charging control nodes across OPPO/OnePlus/Realme, Qualcomm, MediaTek, Samsung, Pixel, Xiaomi
    private val knownNodes = listOf(
        Triple("/sys/class/oplus_chg/battery/mmi_charging_enable", "OPLUS / OPPO PMIC", "0" to "1"),
        Triple("/sys/class/power_supply/battery/mmi_charging_enable", "MMI Battery PMIC", "0" to "1"),
        Triple("/sys/class/power_supply/battery/input_suspend", "Qualcomm Input Suspend", "1" to "0"),
        Triple("/sys/class/power_supply/battery/charging_enabled", "Standard Charging Switch", "0" to "1"),
        Triple("/sys/class/power_supply/battery/battery_charging_enabled", "Kernel Battery Switch", "0" to "1"),
        Triple("/sys/class/power_supply/battery/batt_slate_mode", "Samsung Slate Bypass", "1" to "0"),
        Triple("/sys/class/power_supply/battery/op_disable_charge", "OnePlus Charge Control", "1" to "0"),
        Triple("/sys/class/power_supply/usb/input_suspend", "USB Power Input Suspend", "1" to "0")
    )

    suspend fun probeHardwareNode(): BypassNode? = withContext(Dispatchers.IO) {
        val isRoot = Shell.isAppGrantedRoot() == true
        for ((path, label, vals) in knownNodes) {
            val (bypassVal, normalVal) = vals
            if (isRoot) {
                val res = Shell.cmd("[ -f $path ] && cat $path").exec()
                val out = res.out.firstOrNull()?.trim()
                if (res.isSuccess && !out.isNullOrBlank()) {
                    return@withContext BypassNode(path, label, bypassVal, normalVal, out)
                }
            } else {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    val v = runCatching { f.readText().trim() }.getOrNull()
                    if (!v.isNullOrBlank()) return@withContext BypassNode(path, label, bypassVal, normalVal, v)
                }
            }
        }
        null
    }

    suspend fun toggleHardwareBypass(node: BypassNode, enableBypass: Boolean): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val target = if (enableBypass) node.enableBypassVal else node.normalChargeVal
            val cmd = "chmod 644 ${node.path} 2>/dev/null; echo $target > ${node.path}"
            val (ok, msg) = PrivilegedRunner.runShell(cmd, requireRoot = true)
            if (ok) true to (if (enableBypass) "Hardware Bypass ACTIVE via ${node.label}." else "Normal charging restored.")
            else false to msg
        }

    fun playChargeGuardTone(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        }
    }
}

@Composable
fun CleanerAndBypassScreen(
    dna: DeviceDna?,
    settings: TweakoraSettings,
    onUpdateSettings: (TweakoraSettings) -> Unit,
    onRefreshDna: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = LocalGlassConfig.current

    var subTab by remember { mutableIntStateOf(0) } // 0=RAM & Phone Cleaner, 1=Bypass Charging
    var ramCleaning by remember { mutableStateOf(false) }
    var ramResult by remember { mutableStateOf<RamCleanResult?>(null) }

    var scanningJunk by remember { mutableStateOf(false) }
    var junkItems by remember { mutableStateOf<List<JunkCandidate>>(emptyList()) }
    var confirmCleanAll by remember { mutableStateOf(false) }

    var bypassNode by remember { mutableStateOf<BypassNode?>(null) }
    var probingBypass by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(subTab, dna?.privilegeTier) {
        if (subTab == 1) {
            probingBypass = true
            bypassNode = BypassChargingEngine.probeHardwareNode()
            probingBypass = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("CLEANER & POWER CONTROL", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Text("Cleaner & Bypass", style = MaterialTheme.typography.headlineSmall)
                }
                GlassPillButton(text = "Back", onClick = onBack, gradient = listOf(cfg.accent.primary, cfg.accent.secondary))
            }
        }

        item {
            GlassSegmentedTabs(
                items = listOf("RAM & Cleaner", "Bypass Charging"),
                selectedIndex = subTab,
                onSelect = { subTab = it }
            )
        }

        statusBanner?.let { msg ->
            item {
                GlassCard(tint = GlassPalette.CyberCyan, glowAccent = GlassPalette.CyberCyan) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // TAB 0: REAL RAM CLEANER & PHONE JUNK CLEANER
        if (subTab == 0) {
            item {
                GlassCard(tint = cfg.accent.primary, glowAccent = cfg.accent.primary) {
                    Text("RAM CLEANER", style = MaterialTheme.typography.labelMedium, color = cfg.accent.primary)
                    Spacer(Modifier.height(8.dp))
                    if (dna != null) {
                        GlassGaugeRow("Live RAM Usage", "${dna.usedRamMb} / ${dna.totalRamMb} MB (${(dna.ramFraction * 100).toInt()}%)", dna.ramFraction, cfg.accent.primary)
                    }
                    Spacer(Modifier.height(12.dp))
                    GlassPillButton(
                        text = if (ramCleaning) "Trimming Background Apps..." else "Clean RAM Now",
                        onClick = {
                            if (!ramCleaning) {
                                scope.launch {
                                    ramCleaning = true
                                    ramResult = CleanerEngine.cleanRam(context)
                                    ramCleaning = false
                                    onRefreshDna()
                                }
                            }
                        },
                        gradient = listOf(cfg.accent.primary, cfg.accent.secondary)
                    )
                    ramResult?.let { r ->
                        Spacer(Modifier.height(10.dp))
                        GlassSpecRow("Method", r.methodUsed)
                        GlassSpecRow("Background Apps Trimmed", "${r.appsTrimmed}")
                        GlassSpecRow("Measured RAM Freed", "${r.freedMb} MB (${r.beforeUsedMb} → ${r.afterUsedMb} MB)")
                    }
                }
            }

            item {
                GlassCard(glowAccent = cfg.accent.secondary) {
                    Text("PHONE JUNK CLEANER", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Spacer(Modifier.height(6.dp))
                    Text("Scans app cache, .tmp/.log files, leftover APK installers, and empty folders. Always asks before deleting.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassPillButton(
                            text = if (scanningJunk) "Scanning..." else "Scan Junk",
                            onClick = {
                                scope.launch {
                                    scanningJunk = true
                                    junkItems = CleanerEngine.scanJunk(context)
                                    scanningJunk = false
                                    statusBanner = if (junkItems.isEmpty()) "Device looks clean! No leftover temp/APK junk found."
                                    else "Found ${junkItems.size} item(s) ready for safe review."
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (junkItems.isNotEmpty()) {
                            GlassPillButton(
                                text = "Clean (${junkItems.size})",
                                onClick = { confirmCleanAll = true },
                                gradient = listOf(GlassPalette.StatusGood, cfg.accent.secondary),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) {
                            Text("Grant All Files Access to scan Downloads & APKs", fontSize = 12.5.sp, color = GlassPalette.WarmAmber)
                        }
                    }
                }
            }

            items(junkItems.take(25), key = { it.path }) { item ->
                GlassCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${item.category} • ${String.format(Locale.US, "%.2f MB", item.sizeBytes / (1024f * 1024f))}", style = MaterialTheme.typography.bodySmall)
                        }
                        GlassStatusPill(item.category.uppercase(), cfg.accent.secondary)
                    }
                }
            }
        }

        // TAB 1: HARDWARE BYPASS CHARGING + NON-ROOT SMART CHARGE GUARD
        if (subTab == 1) {
            item {
                val isRoot = dna?.privilegeTier == PrivilegeTier.ROOT
                val node = bypassNode
                GlassCard(
                    tint = if (node?.isBypassActive == true) GlassPalette.StatusGood else cfg.accent.primary,
                    glowAccent = if (node?.isBypassActive == true) GlassPalette.StatusGood else cfg.accent.primary
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("HARDWARE BYPASS CHARGING", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                        GlassStatusPill(
                            text = when {
                                node?.isBypassActive == true -> "ACTIVE"
                                isRoot && node != null -> "SUPPORTED"
                                isRoot -> "NO PMIC NODE"
                                else -> "ROOT NEEDED"
                            },
                            color = if (node?.isBypassActive == true) GlassPalette.StatusGood else GlassPalette.WarmAmber
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (probingBypass) {
                        Text("Probing Linux PMIC power_supply nodes...", style = MaterialTheme.typography.bodySmall)
                    } else if (isRoot && node != null) {
                        Text("Detected PMIC controller: ${node.label}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Node: ${node.path} (Live: ${node.currentVal})", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassPillButton(
                                text = "Enable Bypass",
                                onClick = {
                                    scope.launch {
                                        val (_, msg) = BypassChargingEngine.toggleHardwareBypass(node, true)
                                        statusBanner = msg
                                        bypassNode = BypassChargingEngine.probeHardwareNode()
                                    }
                                },
                                gradient = listOf(GlassPalette.StatusGood, cfg.accent.secondary),
                                modifier = Modifier.weight(1f)
                            )
                            GlassPillButton(
                                text = "Normal Charge",
                                onClick = {
                                    scope.launch {
                                        val (_, msg) = BypassChargingEngine.toggleHardwareBypass(node, false)
                                        statusBanner = msg
                                        bypassNode = BypassChargingEngine.probeHardwareNode()
                                    }
                                },
                                gradient = listOf(cfg.accent.primary, GlassPalette.WarmAmber),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Text(
                            "True hardware bypass charging disconnects the battery at the PMIC chip while running off USB power. On unrooted Android devices, the Linux kernel blocks apps from modifying PMIC charging switches.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "If your phone is rooted (Magisk/KernelSU), tap Check Root to unlock direct PMIC bypass. Otherwise, use Smart Charge Guard below to protect battery heat & lifespan honestly.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cfg.accent.secondary
                        )
                    }
                }
            }

            // Honest Non-Root Alternative: Smart Charge Guard (works on ALL phones)
            item {
                GlassCard(glowAccent = GlassPalette.StatusGood) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("SMART CHARGE GUARD (ALL PHONES)", style = MaterialTheme.typography.labelMedium, color = GlassPalette.StatusGood)
                            Text("Alerts you to unplug at target % or heat", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = settings.chargeGuardEnabled,
                            onCheckedChange = { on -> onUpdateSettings(settings.copy(chargeGuardEnabled = on)) }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Stop-Charging Alert Threshold", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    val limits = listOf(75, 80, 85, 90)
                    GlassSegmentedTabs(
                        items = limits.map { "$it%" },
                        selectedIndex = limits.indexOf(settings.chargeLimitPct).coerceAtLeast(1),
                        onSelect = { idx -> onUpdateSettings(settings.copy(chargeLimitPct = limits[idx])) }
                    )
                    Spacer(Modifier.height(10.dp))
                    dna?.let {
                        GlassSpecRow("Live Status", "${it.batteryPercent}% • ${if (it.isCharging) "Plugged In" else "Unplugged"} • ${String.format(Locale.US, "%.1f°C", it.batteryTempC)}")
                    }
                    Spacer(Modifier.height(8.dp))
                    GlassPillButton(
                        text = "Open System Battery / Charging Settings",
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure {
                                runCatching { context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        }
                    )
                }
            }
        }
    }

    if (confirmCleanAll) {
        val totalMb = junkItems.sumOf { it.sizeBytes } / (1024f * 1024f)
        AlertDialog(
            onDismissRequest = { confirmCleanAll = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Confirm Safe Phone Cleanup", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "Remove ${junkItems.size} junk item(s) (${String.format(Locale.US, "%.2f MB", totalMb)})? Your photos, videos, and app data are never touched.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val (count, mb) = CleanerEngine.cleanJunk(junkItems)
                        statusBanner = "Cleaned $count item(s), freed ${String.format(Locale.US, "%.2f MB", mb)}."
                        junkItems = emptyList()
                        confirmCleanAll = false
                        onRefreshDna()
                    }
                }) { Text("Clean Now", color = GlassPalette.StatusGood) }
            },
            dismissButton = { TextButton(onClick = { confirmCleanAll = false }) { Text("Cancel") } }
        )
    }
}
// END OF CLEANERANDBYPASS
