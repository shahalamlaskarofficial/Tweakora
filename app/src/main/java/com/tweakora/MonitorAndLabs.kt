package com.tweakora

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class ThermalZoneReading(
    val zoneName: String,
    val type: String,
    val tempC: Float
)

data class BenchmarkResult(
    val score: Int,
    val consistencyPercent: Int,
    val passScores: List<Int>,
    val startTempC: Float,
    val endTempC: Float,
    val durationSec: Float,
    val verdict: String
)

object MonitorEngine {

    suspend fun readThermalZones(): List<ThermalZoneReading> = withContext(Dispatchers.IO) {
        val dir = File("/sys/class/thermal")
        val zones = dir.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return@withContext emptyList()
        zones.mapNotNull { z ->
            runCatching {
                val rawTemp = File(z, "temp").readText().trim().toFloat()
                val celsius = if (rawTemp > 1000f) rawTemp / 1000f else if (rawTemp > 150f) rawTemp / 10f else rawTemp
                if (celsius !in 5f..115f) return@runCatching null
                val type = runCatching { File(z, "type").readText().trim() }.getOrDefault(z.name)
                ThermalZoneReading(z.name, type, celsius)
            }.getOrNull()
        }.sortedByDescending { it.tempC }.take(12)
    }

    fun readBatteryCurrentMa(context: Context): Int? = runCatching {
        val bm = context.getSystemService(BatteryManager::class.java)
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Int.MIN_VALUE || raw == 0) return null
        if (kotlin.math.abs(raw) > 10000) raw / 1000 else raw
    }.getOrNull()

    fun readBatteryChargeCounterMah(context: Context): Int? = runCatching {
        val bm = context.getSystemService(BatteryManager::class.java)
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (raw <= 0) null else raw / 1000
    }.getOrNull()

    /**
     * Real multi-core sustained compute benchmark (6 sequential passes across all CPU cores).
     * Measures raw compute throughput, consistency/throttling drop-off, and thermal rise.
     */
    suspend fun runSustainedBenchmark(
        context: Context,
        onPassCompleted: (Int, Int) -> Unit
    ): BenchmarkResult = withContext(Dispatchers.Default) {
        val startDna = SystemInspector.capture(context)
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 16)
        val passScores = mutableListOf<Int>()
        val totalStart = System.nanoTime()

        for (pass in 1..6) {
            val t0 = System.nanoTime()
            val ops = coroutineScope {
                (0 until cores).map { worker ->
                    async {
                        val md = MessageDigest.getInstance("SHA-256")
                        var buf = "tweakora-pass-$pass-core-$worker".toByteArray()
                        var count = 0
                        val endAt = System.nanoTime() + 650_000_000L // 650ms per pass
                        while (System.nanoTime() < endAt) {
                            buf = md.digest(buf)
                            count++
                        }
                        count
                    }
                }.awaitAll().sum()
            }
            val elapsedSec = ((System.nanoTime() - t0) / 1_000_000_000.0).coerceAtLeast(0.1)
            val passScore = ((ops / elapsedSec) / 100.0).toInt().coerceAtLeast(1)
            passScores.add(passScore)
            withContext(Dispatchers.Main) { onPassCompleted(pass, passScore) }
        }

        val endDna = SystemInspector.capture(context)
        val totalSec = ((System.nanoTime() - totalStart) / 1_000_000_000f)
        val avg = passScores.average().toInt()
        val minScore = passScores.minOrNull() ?: avg
        val maxScore = passScores.maxOrNull()?.coerceAtLeast(1) ?: 1
        val consistency = ((minScore.toFloat() / maxScore.toFloat()) * 100f).toInt().coerceIn(0, 100)
        val tempDelta = endDna.batteryTempC - startDna.batteryTempC

        val verdict = when {
            consistency >= 88 && tempDelta < 3f -> "Excellent sustained stability. Minimal thermal throttling detected."
            consistency >= 72 -> "Good performance with mild clock scaling under sustained load."
            else -> "Noticeable performance drop-off (${100 - consistency}%). Thermal or power throttling active."
        }

        BenchmarkResult(
            score = avg,
            consistencyPercent = consistency,
            passScores = passScores,
            startTempC = startDna.batteryTempC,
            endTempC = endDna.batteryTempC,
            durationSec = totalSec,
            verdict = verdict
        )
    }
}

@Composable
fun MonitorHubScreen(
    dna: DeviceDna?,
    ramHistory: List<Float>,
    tempHistory: List<Float>,
    isMonitoringPaused: Boolean,
    onToggleMonitoring: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = LocalGlassConfig.current

    var subTab by remember { mutableIntStateOf(0) } // 0=Live, 1=Perf Lab, 2=Battery, 3=Thermal
    var zones by remember { mutableStateOf<List<ThermalZoneReading>>(emptyList()) }
    var benchmarkRunning by remember { mutableStateOf(false) }
    var benchmarkProgress by remember { mutableIntStateOf(0) }
    var benchmarkResult by remember { mutableStateOf<BenchmarkResult?>(null) }
    var previousBenchScore by remember { mutableStateOf<Int?>(null) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(subTab, dna?.batteryTempC) {
        if (subTab == 3) {
            zones = MonitorEngine.readThermalZones()
        }
    }

    if (dna == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = cfg.accent.secondary)
        }
        return
    }

    val currentMa = remember(dna.batteryPercent, dna.batteryVoltageMv, dna.isCharging) {
        MonitorEngine.readBatteryCurrentMa(context)
    }
    val chargeMah = remember(dna.batteryPercent) {
        MonitorEngine.readBatteryChargeCounterMah(context)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("TELEMETRY LABS", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Text("Monitor & Intelligence", style = MaterialTheme.typography.headlineSmall)
                }
                GlassPillButton(
                    text = if (isMonitoringPaused) "Resume" else "Live",
                    onClick = onToggleMonitoring,
                    gradient = if (isMonitoringPaused) {
                        listOf(GlassPalette.WarmAmber, GlassPalette.NeonMagenta)
                    } else {
                        listOf(GlassPalette.StatusGood, GlassPalette.CyberCyan)
                    }
                )
            }
        }

        item {
            GlassSegmentedTabs(
                items = listOf("Live", "Perf Lab", "Battery", "Thermal"),
                selectedIndex = subTab,
                onSelect = { subTab = it }
            )
        }

        feedbackMsg?.let { msg ->
            item {
                GlassCard(tint = GlassPalette.CyberCyan, glowAccent = GlassPalette.CyberCyan) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        // SUB-TAB 0: LIVE SYSTEM MONITOR
        if (subTab == 0) {
            item {
                GlassCard(glowAccent = cfg.accent.secondary) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("RAM PRESSURE WAVE", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                        Text("${(dna.ramFraction * 100).toInt()}%", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    GlassWaveChart(ramHistory, cfg.accent.secondary)
                    Spacer(Modifier.height(8.dp))
                    GlassGaugeRow("Active Memory", "${dna.usedRamMb} / ${dna.totalRamMb} MB", dna.ramFraction, cfg.accent.primary)
                }
            }

            item {
                GlassCard(glowAccent = cfg.accent.primary) {
                    Text("CPU CORES (${dna.cpuCores} CORES)", style = MaterialTheme.typography.labelMedium, color = cfg.accent.primary)
                    Spacer(Modifier.height(8.dp))
                    val readable = dna.coreFreqsMhz.filterNotNull()
                    if (readable.isEmpty()) {
                        Text(
                            "Per-core frequency sysfs nodes are restricted by SELinux on this Android build. Processor: ${dna.socName}.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        val peak = readable.maxOrNull()?.coerceAtLeast(1000) ?: 2400
                        dna.coreFreqsMhz.forEachIndexed { i, mhz ->
                            val v = mhz ?: 0
                            GlassGaugeRow(
                                label = "Core $i",
                                value = if (mhz != null) "$mhz MHz" else "Offline",
                                progress = v.toFloat() / peak.toFloat(),
                                color = if (i >= dna.cpuCores - 2) GlassPalette.NeonMagenta else cfg.accent.secondary
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            item {
                GlassActionBanner(
                    title = "Export Live Telemetry Snapshot",
                    subtitle = "Copy current CPU, RAM, Battery & Thermal readings to clipboard",
                    icon = Icons.Rounded.ContentCopy,
                    onClick = {
                        val report = buildString {
                            appendLine("=== TWEAKORA LIVE MONITOR REPORT ===")
                            appendLine("Device: ${dna.manufacturer} ${dna.model} (Android ${dna.androidVersion})")
                            appendLine("SoC: ${dna.socName} (${dna.cpuCores} cores) | GPU: ${dna.gpuRenderer}")
                            appendLine("RAM: ${dna.usedRamMb}/${dna.totalRamMb} MB (${(dna.ramFraction * 100).toInt()}%)")
                            appendLine("Storage: ${String.format(Locale.US, "%.1f/%.1f GB", dna.usedStorageGb, dna.totalStorageGb)}")
                            appendLine("Battery: ${dna.batteryPercent}% (${dna.batteryTempC}C, ${dna.batteryVoltageMv}mV)")
                            appendLine("Thermal: ${dna.thermalStatus}")
                        }
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("Tweakora Monitor Report", report))
                        feedbackMsg = "Live telemetry report copied to clipboard."
                    }
                )
            }
        }

        // SUB-TAB 1: PERFORMANCE LAB & SUSTAINED BENCHMARK
        if (subTab == 1) {
            item {
                GlassCard(tint = cfg.accent.primary, glowAccent = cfg.accent.primary) {
                    Text("PERFORMANCE CONSISTENCY TEST", style = MaterialTheme.typography.labelMedium, color = cfg.accent.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Runs a real 6-pass multi-core SHA-256 workload across all ${dna.cpuCores} cores to measure sustained speed, throttling drop-off, and thermal rise.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    if (benchmarkRunning) {
                        GlassGaugeRow("Running Pass $benchmarkProgress / 6", "${(benchmarkProgress * 100) / 6}%", benchmarkProgress / 6f, GlassPalette.WarmAmber)
                    } else {
                        GlassPillButton(
                            text = if (benchmarkResult == null) "Start Real Benchmark (~4s)" else "Re-Run Benchmark",
                            onClick = {
                                scope.launch {
                                    previousBenchScore = benchmarkResult?.score
                                    benchmarkRunning = true
                                    benchmarkProgress = 0
                                    benchmarkResult = MonitorEngine.runSustainedBenchmark(context) { pass, _ ->
                                        benchmarkProgress = pass
                                    }
                                    benchmarkRunning = false
                                }
                            },
                            gradient = listOf(cfg.accent.primary, cfg.accent.secondary)
                        )
                    }
                }
            }

            benchmarkResult?.let { res ->
                item {
                    GlassCard(glowAccent = GlassPalette.StatusGood) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("COMPUTE SCORE", style = MaterialTheme.typography.labelMedium, color = GlassPalette.CyberCyan)
                                Text("${res.score} pts", fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                GlassStatusPill(
                                    text = "${res.consistencyPercent}% STABLE",
                                    color = if (res.consistencyPercent >= 80) GlassPalette.StatusGood else GlassPalette.WarmAmber
                                )
                                previousBenchScore?.let { prev ->
                                    val diff = res.score - prev
                                    Text(
                                        text = "vs prev: ${if (diff >= 0) "+" else ""}$diff pts",
                                        fontSize = 11.sp,
                                        color = if (diff >= 0) GlassPalette.StatusGood else GlassPalette.StatusDanger
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        val maxP = res.passScores.maxOrNull()?.coerceAtLeast(1)?.toFloat() ?: 1f
                        GlassWaveChart(res.passScores.map { it / maxP }, cfg.accent.primary)
                        Spacer(Modifier.height(8.dp))
                        GlassSpecRow("Thermal Rise", String.format(Locale.US, "%.1f°C → %.1f°C", res.startTempC, res.endTempC))
                        GlassSpecRow("Test Duration", String.format(Locale.US, "%.1f sec", res.durationSec))
                        Spacer(Modifier.height(6.dp))
                        Text(res.verdict, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // Real Bottleneck Detector
            item {
                val bottlenecks = buildList {
                    if (dna.ramFraction > 0.82f) add("RAM Pressure (${(dna.ramFraction * 100).toInt()}% used) — Background apps may cause app reloads.")
                    if (dna.storageFraction > 0.85f) add("Storage Pressure (${String.format(Locale.US, "%.1f GB", dna.freeStorageGb)} free) — Low flash space slows I/O speeds.")
                    if (dna.batteryTempC >= 39f) add("Thermal Bottleneck (${String.format(Locale.US, "%.1f°C", dna.batteryTempC)}) — SoC clock throttling likely active.")
                    if ((dna.thermalHeadroom ?: 0f) > 0.75f) add("Thermal Headroom Tight — Sustained gaming may dip frame rates.")
                    if (isEmpty()) add("No active bottlenecks detected. RAM, storage, and thermals are operating cleanly.")
                }
                GlassCard {
                    Text("BOTTLENECK DETECTOR", style = MaterialTheme.typography.labelMedium, color = GlassPalette.WarmAmber)
                    Spacer(Modifier.height(8.dp))
                    bottlenecks.forEach { b ->
                        Text("• $b", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // SUB-TAB 2: BATTERY INTELLIGENCE
        if (subTab == 2) {
            item {
                GlassCard(tint = GlassPalette.StatusGood, glowAccent = GlassPalette.StatusGood) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        GlassRingGauge(
                            progress = dna.batteryPercent / 100f,
                            centerTitle = "${dna.batteryPercent}%",
                            centerSubtitle = if (dna.isCharging) "Charging" else "Discharging",
                            pillLabel = dna.batteryHealth.uppercase()
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("BATTERY TELEMETRY", style = MaterialTheme.typography.labelMedium, color = GlassPalette.StatusGood)
                            GlassSpecRow("Temperature", String.format(Locale.US, "%.1f°C", dna.batteryTempC))
                            GlassSpecRow("Voltage", "${dna.batteryVoltageMv} mV")
                            GlassSpecRow("Live Current", currentMa?.let { "$it mA" } ?: "N/A")
                            val watts = currentMa?.let { (kotlin.math.abs(it) * dna.batteryVoltageMv) / 1_000_000f }
                            GlassSpecRow("Power Flow", watts?.let { String.format(Locale.US, "%.2f W", it) } ?: "N/A")
                            chargeMah?.let { GlassSpecRow("Charge Counter", "$it mAh") }
                        }
                    }
                }
            }

            item {
                val insights = buildList {
                    if (dna.batteryTempC >= 40f) add("High battery temperature (${dna.batteryTempC}°C) accelerates lithium aging. Avoid fast charging while gaming.")
                    if (currentMa != null && !dna.isCharging && kotlin.math.abs(currentMa) > 900) {
                        add("Elevated active draw (${kotlin.math.abs(currentMa)} mA). Lower peak refresh rate or brightness to extend screen-on time.")
                    }
                    if (dna.batteryPercent in 20..80) add("Battery is inside the optimal 20–80% longevity window.")
                    if (isEmpty()) add("Battery health (${dna.batteryHealth}) and voltage (${dna.batteryVoltageMv} mV) look completely normal.")
                }
                GlassCard {
                    Text("BATTERY DETECTIVE", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Spacer(Modifier.height(8.dp))
                    insights.forEach { tip ->
                        Text("• $tip", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    GlassPillButton(
                        text = "Open Android Battery Usage",
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                        }
                    )
                }
            }
        }

        // SUB-TAB 3: THERMAL INTELLIGENCE
        if (subTab == 3) {
            item {
                val tColor = when {
                    dna.batteryTempC >= 42f -> GlassPalette.StatusDanger
                    dna.batteryTempC >= 38f -> GlassPalette.WarmAmber
                    else -> GlassPalette.CyberCyan
                }
                GlassCard(tint = tColor, glowAccent = tColor) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("THERMAL STATUS", style = MaterialTheme.typography.labelMedium, color = tColor)
                            Text(String.format(Locale.US, "%.1f°C", dna.batteryTempC), fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = tColor)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            GlassStatusPill(dna.thermalStatus.uppercase(), tColor)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                dna.thermalHeadroom?.let { String.format(Locale.US, "Headroom: %.0f%%", (it * 100f).coerceAtMost(100f)) } ?: "Headroom: N/A",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    GlassWaveChart(tempHistory, tColor)
                }
            }

            item {
                GlassCard {
                    Text("THERMAL ZONES", style = MaterialTheme.typography.labelMedium, color = cfg.accent.primary)
                    Spacer(Modifier.height(8.dp))
                    if (zones.isEmpty()) {
                        Text(
                            "Direct /sys/class/thermal nodes are restricted by SELinux on unrooted Android. Using official PowerManager thermal headroom & battery sensor (${dna.batteryTempC}°C).",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        zones.forEach { z ->
                            val zColor = when {
                                z.tempC >= 55f -> GlassPalette.StatusDanger
                                z.tempC >= 42f -> GlassPalette.WarmAmber
                                else -> GlassPalette.CyberCyan
                            }
                            GlassGaugeRow(
                                label = z.type,
                                value = String.format(Locale.US, "%.1f°C", z.tempC),
                                progress = ((z.tempC - 20f) / 65f).coerceIn(0.05f, 1f),
                                color = zColor
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            item {
                val coolingTip = when {
                    dna.batteryTempC >= 42f -> "Remove thick case, stop charging, lower brightness to 40%, and pause heavy 3D gaming for 5 minutes."
                    dna.batteryTempC >= 38f -> "Device is warming up. Cap display refresh rate to 60–90Hz if playing extended sessions."
                    else -> "Thermals are cool and optimal for sustained peak CPU/GPU boost clocks."
                }
                GlassCard {
                    Text("COOLING ADVISOR", style = MaterialTheme.typography.labelMedium, color = GlassPalette.CyberCyan)
                    Spacer(Modifier.height(6.dp))
                    Text(coolingTip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
