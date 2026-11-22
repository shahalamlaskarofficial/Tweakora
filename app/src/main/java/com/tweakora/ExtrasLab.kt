package com.tweakora

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

data class LiveSensorValues(
    val accel: String = "Listening...",
    val gyro: String = "Unavailable",
    val lightLux: String = "Unavailable",
    val magneticUt: String = "Unavailable",
    val proximityCm: String = "Unavailable",
    val pressureHpa: String = "Unavailable"
)

data class PresetBundle(
    val id: String,
    val title: String,
    val subtitle: String,
    val accent: Color,
    val tweaks: List<Pair<String, String>>
)

object HardwareExtrasEngine {

    val presetBundles = listOf(
        PresetBundle(
            id = "esports",
            title = "Esports Boost",
            subtitle = "0.5x transitions, 120Hz peak, Wi-Fi scan throttle",
            accent = GlassPalette.CyberCyan,
            tweaks = listOf(
                "anim_window" to "0.5",
                "anim_transition" to "0.5",
                "anim_duration" to "0.5",
                "peak_refresh" to "120.0",
                "wifi_scan_throttle" to "1"
            )
        ),
        PresetBundle(
            id = "eco",
            title = "Max Battery Eco",
            subtitle = "60Hz ceiling, Apps Freezer, Adaptive Battery, 30s timeout",
            accent = GlassPalette.StatusGood,
            tweaks = listOf(
                "peak_refresh" to "60.0",
                "min_refresh" to "60.0",
                "cached_freezer" to "enabled",
                "adaptive_battery" to "1",
                "screen_timeout" to "30000",
                "mobile_data_always_on" to "0"
            )
        ),
        PresetBundle(
            id = "cool",
            title = "Cool Down",
            subtitle = "60Hz display, freeze cached apps, stay-awake off",
            accent = GlassPalette.NeonPurple,
            tweaks = listOf(
                "peak_refresh" to "60.0",
                "cached_freezer" to "enabled",
                "thermal_stay_awake" to "0"
            )
        ),
        PresetBundle(
            id = "stock",
            title = "Stock Defaults",
            subtitle = "Restore standard 1.0x animations & system defaults",
            accent = GlassPalette.WarmAmber,
            tweaks = listOf(
                "anim_window" to "1.0",
                "anim_transition" to "1.0",
                "anim_duration" to "1.0",
                "cached_freezer" to "default",
                "screen_timeout" to "60000"
            )
        )
    )

    fun readLocalIpv4(): String = runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return "Unavailable"
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses.toList()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress ?: "Unavailable"
                }
            }
        }
        "Offline"
    }.getOrDefault("Unavailable")

    /**
     * Generates a real PCM sine tone for Left, Right, Stereo, or 165Hz Speaker Dust/Water Ejector.
     */
    suspend fun playSpeakerTone(freqHz: Double, durationMs: Int, leftVol: Float, rightVol: Float) =
        withContext(Dispatchers.Default) {
            runCatching {
                val sampleRate = 44100
                val frameCount = (sampleRate * (durationMs / 1000.0)).toInt().coerceAtLeast(1000)
                val pcm = ShortArray(frameCount * 2)
                for (i in 0 until frameCount) {
                    val sample = sin(2.0 * PI * freqHz * i / sampleRate)
                    // Smooth fade-in/out envelope to prevent speaker clicks
                    val env = when {
                        i < 400 -> i / 400.0
                        i > frameCount - 400 -> (frameCount - i) / 400.0
                        else -> 1.0
                    }
                    val v = sample * env * 28000.0
                    pcm[i * 2] = (v * leftVol).toInt().toShort()
                    pcm[i * 2 + 1] = (v * rightVol).toInt().toShort()
                }

                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcm, 0, pcm.size)
                track.play()
                delay(durationMs.toLong() + 120L)
                track.stop()
                track.release()
            }
        }
}

@Composable
fun SourcesAndLabScreen(
    dna: DeviceDna?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = LocalGlassConfig.current

    var subTab by remember { mutableIntStateOf(0) } // 0=Sources Box, 1=Presets, 2=Display & Audio
    var liveSensors by remember { mutableStateOf(LiveSensorValues()) }
    var thermalCount by remember { mutableIntStateOf(0) }
    var localIp by remember { mutableStateOf("Reading...") }
    var netSnap by remember { mutableStateOf(GamingEngine.readNetworkSnapshot(context)) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    // Audio & Display Test state
    var audioBusy by remember { mutableStateOf(false) }
    var pixelTestColorIdx by remember { mutableIntStateOf(-1) }
    val pixelColors = listOf(
        Color.Red to "RED — Check sub-pixels",
        Color.Green to "GREEN — Check sub-pixels",
        Color.Blue to "BLUE — Check sub-pixels",
        Color.White to "WHITE — Check AMOLED burn-in",
        Color.Black to "BLACK — Check backlight bleed",
        Color(0xFF404040) to "GRAY — Check panel uniformity"
    )

    // Register live hardware sensors ONLY while tab 0 is open (0% drain when closed)
    DisposableEffect(subTab) {
        if (subTab != 0) return@DisposableEffect onDispose {}
        val sm = context.getSystemService(SensorManager::class.java)
        val listener = object : SensorEventListener {
            var lastUiUpdate = 0L
            override fun onSensorChanged(ev: SensorEvent?) {
                val e = ev ?: return
                val now = System.currentTimeMillis()
                if (now - lastUiUpdate < 250L) return // Throttle to 4Hz for low battery draw
                lastUiUpdate = now
                liveSensors = when (e.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> liveSensors.copy(
                        accel = String.format(Locale.US, "X:%.1f  Y:%.1f  Z:%.1f m/s²", e.values[0], e.values[1], e.values[2])
                    )
                    Sensor.TYPE_GYROSCOPE -> liveSensors.copy(
                        gyro = String.format(Locale.US, "X:%.2f  Y:%.2f  Z:%.2f rad/s", e.values[0], e.values[1], e.values[2])
                    )
                    Sensor.TYPE_LIGHT -> liveSensors.copy(
                        lightLux = String.format(Locale.US, "%.0f lux", e.values[0])
                    )
                    Sensor.TYPE_MAGNETIC_FIELD -> liveSensors.copy(
                        magneticUt = String.format(Locale.US, "%.1f µT", kotlin.math.sqrt(e.values[0]*e.values[0] + e.values[1]*e.values[1] + e.values[2]*e.values[2]))
                    )
                    Sensor.TYPE_PROXIMITY -> liveSensors.copy(
                        proximityCm = String.format(Locale.US, "%.1f cm", e.values[0])
                    )
                    Sensor.TYPE_PRESSURE -> liveSensors.copy(
                        pressureHpa = String.format(Locale.US, "%.1f hPa", e.values[0])
                    )
                    else -> liveSensors
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val types = listOf(
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LIGHT,
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_PROXIMITY, Sensor.TYPE_PRESSURE
        )
        types.forEach { t ->
            sm?.getDefaultSensor(t)?.let { s -> sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_UI) }
        }
        onDispose { sm?.unregisterListener(listener) }
    }

    LaunchedEffect(subTab) {
        if (subTab == 0) {
            localIp = withContext(Dispatchers.IO) { HardwareExtrasEngine.readLocalIpv4() }
            netSnap = GamingEngine.readNetworkSnapshot(context)
            thermalCount = MonitorEngine.readThermalZones().size
        }
    }

    // Full-screen Dead Pixel / AMOLED Burn-in Overlay
    if (pixelTestColorIdx in pixelColors.indices) {
        val (bgColor, label) = pixelColors[pixelTestColorIdx]
        val textCol = if (bgColor == Color.White) Color.Black else Color.White
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .clickable {
                    if (pixelTestColorIdx < pixelColors.lastIndex) pixelTestColorIdx++
                    else pixelTestColorIdx = -1
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = textCol.copy(0.75f), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text("Tap anywhere for next color (${pixelTestColorIdx + 1}/${pixelColors.size})", color = textCol.copy(0.6f), fontSize = 12.sp)
            }
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
                    Text("ORGANIZED GLASS HUB", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Text("Sources, Presets & Lab", style = MaterialTheme.typography.headlineSmall)
                }
                GlassPillButton("Back", onClick = onBack, gradient = listOf(cfg.accent.primary, cfg.accent.secondary))
            }
        }

        item {
            GlassSegmentedTabs(
                items = listOf("Sources", "Presets", "Audio+Screen"),
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

        // TAB 0: ALL TELEMETRY & SENSOR SOURCES ORGANIZED INSIDE COLLAPSIBLE GLASS BOXES
        if (subTab == 0) {
            item {
                GlassBox(
                    title = "Live Hardware Sensors",
                    accent = cfg.accent.secondary,
                    badge = "LIVE 4HZ",
                    collapsible = true,
                    initiallyExpanded = true
                ) {
                    GlassSpecRow("Accelerometer", liveSensors.accel)
                    GlassSpecRow("Gyroscope", liveSensors.gyro)
                    GlassSpecRow("Ambient Light", liveSensors.lightLux)
                    GlassSpecRow("Magnetometer", liveSensors.magneticUt)
                    GlassSpecRow("Proximity", liveSensors.proximityCm)
                    GlassSpecRow("Barometer", liveSensors.pressureHpa)
                }
            }

            item {
                GlassBox(
                    title = "Compute & Memory Sources",
                    accent = cfg.accent.primary,
                    badge = dna?.socName?.take(12) ?: "SOC",
                    collapsible = true,
                    initiallyExpanded = true
                ) {
                    GlassSpecRow("SoC / Cores", "${dna?.socName ?: "--"} (${dna?.cpuCores ?: 0}c)")
                    GlassSpecRow("Peak Live Clock", dna?.peakLiveClockMhz?.let { "$it MHz" } ?: "SELinux Protected")
                    GlassSpecRow("GPU Renderer", dna?.gpuRenderer ?: "--")
                    GlassSpecRow("Memory Source", "${dna?.usedRamMb ?: 0} / ${dna?.totalRamMb ?: 0} MB")
                    GlassSpecRow("Storage Source", String.format(Locale.US, "%.1f GB free / %.1f GB", dna?.freeStorageGb ?: 0f, dna?.totalStorageGb ?: 0f))
                }
            }

            item {
                GlassBox(
                    title = "Power & Thermal Sources",
                    accent = GlassPalette.StatusGood,
                    badge = "${dna?.batteryPercent ?: 0}%",
                    collapsible = true,
                    initiallyExpanded = true
                ) {
                    val ma = remember(dna?.batteryPercent, dna?.batteryVoltageMv) { MonitorEngine.readBatteryCurrentMa(context) }
                    GlassSpecRow("Battery Level", "${dna?.batteryPercent ?: 0}% (${if (dna?.isCharging == true) "Charging" else "Discharging"})")
                    GlassSpecRow("Voltage / Health", "${dna?.batteryVoltageMv ?: 0} mV • ${dna?.batteryHealth ?: "--"}")
                    GlassSpecRow("Live Current", ma?.let { "$it mA" } ?: "Not exposed")
                    GlassSpecRow("Battery Sensor", String.format(Locale.US, "%.1f°C (%s)", dna?.batteryTempC ?: 0f, dna?.thermalStatus ?: "--"))
                    GlassSpecRow("Sysfs Thermal Zones", if (thermalCount > 0) "$thermalCount readable" else "SELinux restricted")
                }
            }

            item {
                GlassBox(
                    title = "Network & Connectivity Sources",
                    accent = GlassPalette.WarmAmber,
                    badge = netSnap.transport.uppercase(),
                    collapsible = true,
                    initiallyExpanded = false
                ) {
                    GlassSpecRow("Active Transport", netSnap.transport)
                    GlassSpecRow("Local IPv4", localIp)
                    netSnap.linkSpeedMbps?.let { GlassSpecRow("Wi-Fi Link Speed", "$it Mbps") }
                    netSnap.rssiDbm?.let { GlassSpecRow("Wi-Fi RSSI", "$it dBm") }
                    netSnap.frequencyMhz?.let { GlassSpecRow("Radio Band", "$it MHz") }
                }
            }
        }

        // TAB 1: ONE-TAP TWEAK PRESET BUNDLES INSIDE A GLASS BOX
        if (subTab == 1) {
            item {
                GlassBox(
                    title = "One-Tap System Presets",
                    accent = cfg.accent.primary,
                    badge = dna?.privilegeTier?.badge ?: "STANDARD"
                ) {
                    Text(
                        "Apply a tested multi-tweak bundle in one tap. Requires Shizuku or Root; every change is logged for 1-tap Undo in Tweaks → History.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    HardwareExtrasEngine.presetBundles.forEach { bundle ->
                        GlassBoxTile(
                            title = bundle.title,
                            subtitle = bundle.subtitle,
                            icon = Icons.Rounded.FlashOn,
                            accent = bundle.accent,
                            onClick = {
                                scope.launch {
                                    var applied = 0
                                    var skipped = 0
                                    bundle.tweaks.forEach { (id, target) ->
                                        val spec = TweakEngine.catalog.firstOrNull { it.id == id } ?: return@forEach
                                        val rec = TweakEngine.applyTweak(context, spec, target)
                                        if (rec.succeeded) applied++ else skipped++
                                    }
                                    statusBanner = if (applied > 0) {
                                        "${bundle.title}: Applied $applied tweak(s) ($skipped skipped)."
                                    } else {
                                        "Grant Shizuku or Root first to apply '${bundle.title}' automatically."
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // TAB 2: SPEAKER & DISPLAY DIAGNOSTIC LAB
        if (subTab == 2) {
            item {
                GlassBox(
                    title = "Speaker Stereo & Dust Ejector",
                    accent = cfg.accent.secondary,
                    badge = if (audioBusy) "PLAYING" else "READY"
                ) {
                    Text(
                        "Verify stereo speaker channels or run a 165Hz low-frequency acoustic pulse to clear dust and moisture from speaker grilles.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassBoxTile(
                            title = "Left Speaker",
                            subtitle = "520 Hz Left",
                            icon = Icons.Rounded.VolumeUp,
                            accent = cfg.accent.secondary,
                            onClick = {
                                if (!audioBusy) scope.launch {
                                    audioBusy = true; statusBanner = "Testing LEFT speaker channel..."
                                    HardwareExtrasEngine.playSpeakerTone(520.0, 1200, 1f, 0f)
                                    audioBusy = false; statusBanner = "Left speaker test complete."
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        GlassBoxTile(
                            title = "Right Speaker",
                            subtitle = "660 Hz Right",
                            icon = Icons.Rounded.VolumeUp,
                            accent = cfg.accent.primary,
                            onClick = {
                                if (!audioBusy) scope.launch {
                                    audioBusy = true; statusBanner = "Testing RIGHT speaker channel..."
                                    HardwareExtrasEngine.playSpeakerTone(660.0, 1200, 0f, 1f)
                                    audioBusy = false; statusBanner = "Right speaker test complete."
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    GlassPillButton(
                        text = if (audioBusy) "Emitting Acoustic Pulse..." else "Run 165Hz Speaker Dust & Water Ejector (4s)",
                        onClick = {
                            if (!audioBusy) scope.launch {
                                audioBusy = true; statusBanner = "Playing 165Hz speaker clearing pulse..."
                                HardwareExtrasEngine.playSpeakerTone(165.0, 4000, 1f, 1f)
                                audioBusy = false; statusBanner = "Speaker pulse finished."
                            }
                        },
                        gradient = listOf(cfg.accent.primary, cfg.accent.secondary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                GlassBox(
                    title = "Display & AMOLED Panel Lab",
                    accent = GlassPalette.WarmAmber,
                    badge = dna?.displayText?.substringAfter("@ ") ?: "PANEL"
                ) {
                    GlassSpecRow("Resolution & Rate", dna?.displayText ?: "Unknown")
                    Text(
                        "Inspect your display across 6 full-screen solid reference colors to check for dead pixels, tinting, or AMOLED burn-in.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    GlassPillButton(
                        text = "Launch Dead Pixel & Burn-In Tester",
                        onClick = { pixelTestColorIdx = 0 },
                        gradient = listOf(GlassPalette.WarmAmber, GlassPalette.NeonMagenta),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
// END OF EXTRASLAB
