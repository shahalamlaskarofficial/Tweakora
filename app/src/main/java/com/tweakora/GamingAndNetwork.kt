package com.tweakora

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class GameProfileType(val label: String) {
    COMPETITIVE("Competitive"),
    BALANCED("Balanced"),
    BATTERY("Battery")
}

data class GameAppInfo(val packageName: String, val name: String, val profile: GameProfileType)

data class SessionAutopsyRecord(
    val id: Long,
    val gameName: String,
    val profile: String,
    val timestampMs: Long,
    val durationSec: Long,
    val startBattery: Int,
    val endBattery: Int,
    val startTempC: Float,
    val peakTempC: Float,
    val endTempC: Float,
    val bottleneckNote: String
)

data class ActiveGameSession(
    val gameName: String,
    val profile: GameProfileType,
    val startMs: Long,
    val startBattery: Int,
    val startTempC: Float,
    val peakTempC: Float
)

data class NetworkInfoSnapshot(val transport: String, val linkSpeedMbps: Int?, val rssiDbm: Int?, val frequencyMhz: Int?)

data class NetworkTestResult(
    val host: String,
    val samplesMs: List<Long>,
    val avgMs: Int,
    val minMs: Int,
    val maxMs: Int,
    val jitterMs: Int,
    val packetLossPct: Int,
    val grade: String
)

object GamingEngine {
    private const val AUTOPSY_FILE = "tweakora_autopsies.json"
    private const val PREF_NAME = "tweakora_games"

    private val knownGameKeywords = listOf(
        "freefire", "pubg", "bgmi", "codm", "callofduty", "clash", "genshin",
        "minecraft", "roblox", "mobilelegends", "brawlstars", "asphalt", "ea.gp",
        "supercell", "garena", "riotgames", "valorant", "winlator", "ppsspp", "dolphin"
    )

    suspend fun detectInstalledGames(context: Context): List<GameAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val manualPkgs = prefs.getStringSet("manual_games", emptySet()) ?: emptySet()
        val apps = runCatching { pm.getInstalledApplications(PackageManager.GET_META_DATA) }.getOrDefault(emptyList())

        apps.filter { app ->
            val launchable = pm.getLaunchIntentForPackage(app.packageName) != null
            if (!launchable || app.packageName == context.packageName) return@filter false
            app.category == ApplicationInfo.CATEGORY_GAME ||
                knownGameKeywords.any { app.packageName.lowercase().contains(it) } ||
                app.packageName in manualPkgs
        }.map { app ->
            val label = pm.getApplicationLabel(app).toString()
            val saved = prefs.getString("profile_${app.packageName}", GameProfileType.BALANCED.name)
            val profile = runCatching { GameProfileType.valueOf(saved ?: "") }.getOrDefault(GameProfileType.BALANCED)
            GameAppInfo(app.packageName, label, profile)
        }.sortedBy { it.name.lowercase() }
    }

    fun saveGameProfile(context: Context, pkg: String, profile: GameProfileType) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString("profile_$pkg", profile.name).apply()
    }

    fun addManualGame(context: Context, pkg: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet("manual_games", emptySet()) ?: emptySet()).toMutableSet()
        set.add(pkg)
        prefs.edit().putStringSet("manual_games", set).apply()
    }

    suspend fun listLaunchableUserApps(context: Context): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != context.packageName }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second.lowercase() }
    }

    fun loadAutopsies(context: Context): List<SessionAutopsyRecord> = runCatching {
        val file = File(context.filesDir, AUTOPSY_FILE)
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SessionAutopsyRecord(
                o.optLong("id"), o.optString("gameName"), o.optString("profile"),
                o.optLong("timestampMs"), o.optLong("durationSec"),
                o.optInt("startBattery"), o.optInt("endBattery"),
                o.optDouble("startTempC").toFloat(), o.optDouble("peakTempC").toFloat(),
                o.optDouble("endTempC").toFloat(), o.optString("bottleneckNote")
            )
        }.sortedByDescending { it.timestampMs }
    }.getOrDefault(emptyList())

    fun saveAutopsy(context: Context, record: SessionAutopsyRecord) {
        val all = (listOf(record) + loadAutopsies(context)).take(30)
        val arr = JSONArray()
        all.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("gameName", r.gameName); put("profile", r.profile)
                put("timestampMs", r.timestampMs); put("durationSec", r.durationSec)
                put("startBattery", r.startBattery); put("endBattery", r.endBattery)
                put("startTempC", r.startTempC.toDouble()); put("peakTempC", r.peakTempC.toDouble())
                put("endTempC", r.endTempC.toDouble()); put("bottleneckNote", r.bottleneckNote)
            })
        }
        File(context.filesDir, AUTOPSY_FILE).writeText(arr.toString())
    }

    @Suppress("DEPRECATION")
    fun readNetworkSnapshot(context: Context): NetworkInfoSnapshot {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val transport = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
        if (transport != "Wi-Fi") return NetworkInfoSnapshot(transport, null, null, null)
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val info = runCatching { wm?.connectionInfo }.getOrNull()
        return NetworkInfoSnapshot(
            transport,
            info?.linkSpeed?.takeIf { it > 0 },
            info?.rssi?.takeIf { it in -120..-10 },
            info?.frequency?.takeIf { it > 0 }
        )
    }

    suspend fun runNetworkStabilityTest(
        host: String = "1.1.1.1",
        port: Int = 443,
        onProgress: (Int) -> Unit
    ): NetworkTestResult = withContext(Dispatchers.IO) {
        val samples = mutableListOf<Long>()
        var dropped = 0
        for (i in 1..10) {
            val t0 = System.nanoTime()
            val ok = runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 1500) }; true }.getOrDefault(false)
            val rtt = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            if (ok) samples.add(rtt) else dropped++
            withContext(Dispatchers.Main) { onProgress(i) }
        }
        val lossPct = dropped * 10
        if (samples.isEmpty()) return@withContext NetworkTestResult(host, emptyList(), 0, 0, 0, 0, 100, "Unreachable — Check Internet")
        val avg = samples.average().toInt()
        val min = samples.minOrNull()?.toInt() ?: 0
        val max = samples.maxOrNull()?.toInt() ?: 0
        val jitter = if (samples.size >= 2) samples.zipWithNext { a, b -> abs(a - b) }.average().toInt() else 0
        val grade = when {
            lossPct > 10 -> "Unstable ($lossPct% loss) — Switch Wi-Fi/5G"
            avg <= 45 && jitter <= 12 -> "Esports Ready — Low ping & steady jitter"
            avg <= 90 && jitter <= 25 -> "Good — Smooth for online multiplayer"
            else -> "High Latency — Expect minor delay spikes"
        }
        NetworkTestResult(host, samples, avg, min, max, jitter, lossPct, grade)
    }
}

@Composable
fun GamingHubScreen(dna: DeviceDna?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = LocalGlassConfig.current

    var subTab by remember { mutableIntStateOf(0) } // 0=Games, 1=Autopsy, 2=Touch+FPS, 3=Network
    var games by remember { mutableStateOf<List<GameAppInfo>>(emptyList()) }
    var autopsies by remember { mutableStateOf<List<SessionAutopsyRecord>>(emptyList()) }
    var activeSession by remember { mutableStateOf<ActiveGameSession?>(null) }
    var showAddGameDialog by remember { mutableStateOf(false) }
    var allApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // Touch Response Inspector state (real pointer sampling)
    var liveTouchHz by remember { mutableIntStateOf(0) }
    var peakTouchHz by remember { mutableIntStateOf(0) }
    var touchLatencyMs by remember { mutableFloatStateOf(0f) }

    // Live Choreographer FPS & Frame-Time Analyzer
    var fpsTestActive by remember { mutableStateOf(false) }
    var frameTimesMs by remember { mutableStateOf<List<Float>>(emptyList()) }
    var avgFps by remember { mutableIntStateOf(0) }
    var low1PctFps by remember { mutableIntStateOf(0) }

    // Network Stability Lab state
    var netSnapshot by remember { mutableStateOf(GamingEngine.readNetworkSnapshot(context)) }
    var netRunning by remember { mutableStateOf(false) }
    var netProgress by remember { mutableIntStateOf(0) }
    var netResult by remember { mutableStateOf<NetworkTestResult?>(null) }

    fun refreshGames() {
        scope.launch {
            games = GamingEngine.detectInstalledGames(context)
            autopsies = withContext(Dispatchers.IO) { GamingEngine.loadAutopsies(context) }
        }
    }

    LaunchedEffect(Unit) { refreshGames() }

    LaunchedEffect(dna?.batteryTempC) {
        val s = activeSession
        val t = dna?.batteryTempC
        if (s != null && t != null && t > s.peakTempC) activeSession = s.copy(peakTempC = t)
    }

    // Real Choreographer frame-time sampler
    DisposableEffect(fpsTestActive) {
        if (!fpsTestActive) return@DisposableEffect onDispose {}
        val choreographer = Choreographer.getInstance()
        var lastNs = 0L
        val collected = mutableListOf<Float>()
        val startMs = System.currentTimeMillis()
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastNs != 0L) {
                    val deltaMs = (frameTimeNanos - lastNs) / 1_000_000f
                    if (deltaMs in 2f..100f) {
                        collected.add(deltaMs)
                        frameTimesMs = collected.takeLast(24)
                        val avgMs = collected.average().toFloat().coerceAtLeast(1f)
                        avgFps = (1000f / avgMs).toInt()
                        val worst = collected.sortedDescending().take((collected.size / 10).coerceAtLeast(1)).average().toFloat().coerceAtLeast(1f)
                        low1PctFps = (1000f / worst).toInt()
                    }
                }
                lastNs = frameTimeNanos
                if (fpsTestActive && System.currentTimeMillis() - startMs < 5000L) {
                    choreographer.postFrameCallback(this)
                } else {
                    fpsTestActive = false
                }
            }
        }
        choreographer.postFrameCallback(cb)
        onDispose { choreographer.removeFrameCallback(cb) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("GAMING CENTRE", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Text("Game Hub & Labs", style = MaterialTheme.typography.headlineSmall)
                }
                GlassStatusPill(
                    text = dna?.let { String.format(Locale.US, "%.1f°C", it.batteryTempC) } ?: "--",
                    color = if ((dna?.batteryTempC ?: 0f) >= 40f) GlassPalette.WarmAmber else cfg.accent.secondary
                )
            }
        }

        item {
            GlassSegmentedTabs(
                items = listOf("Games", "Autopsy", "Touch+FPS", "Network"),
                selectedIndex = subTab,
                onSelect = {
                    subTab = it
                    if (it == 3) netSnapshot = GamingEngine.readNetworkSnapshot(context)
                }
            )
        }

        // Active Recording Session Banner
        activeSession?.let { s ->
            item {
                GlassCard(tint = GlassPalette.NeonMagenta, glowAccent = GlassPalette.NeonMagenta) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("RECORDING SESSION", style = MaterialTheme.typography.labelMedium, color = GlassPalette.NeonMagenta)
                            Text("${s.gameName} (${s.profile.label})", style = MaterialTheme.typography.titleMedium)
                            val elapsed = ((System.currentTimeMillis() - s.startMs) / 1000L).coerceAtLeast(1L)
                            Text("Elapsed: ${elapsed}s • Peak: ${String.format(Locale.US, "%.1f°C", s.peakTempC)}", style = MaterialTheme.typography.bodySmall)
                        }
                        GlassPillButton(
                            text = "Finish",
                            onClick = {
                                val endBat = dna?.batteryPercent ?: s.startBattery
                                val endTemp = dna?.batteryTempC ?: s.peakTempC
                                val dur = ((System.currentTimeMillis() - s.startMs) / 1000L).coerceAtLeast(1L)
                                val note = when {
                                    s.peakTempC >= 42f -> "Thermal Bottleneck: Peak hit ${s.peakTempC}°C."
                                    (s.startBattery - endBat) >= 5 -> "High drain (${s.startBattery - endBat}% drop)."
                                    else -> "Stable session — thermals & battery healthy."
                                }
                                GamingEngine.saveAutopsy(
                                    context,
                                    SessionAutopsyRecord(System.currentTimeMillis(), s.gameName, s.profile.label, System.currentTimeMillis(), dur, s.startBattery, endBat, s.startTempC, s.peakTempC, endTemp, note)
                                )
                                activeSession = null
                                subTab = 1
                                refreshGames()
                            },
                            gradient = listOf(GlassPalette.NeonMagenta, GlassPalette.WarmAmber)
                        )
                    }
                }
            }
        }

        // TAB 0: GAMES
        if (subTab == 0) {
            item {
                GlassActionBanner(
                    title = "Add Game Manually",
                    subtitle = "${games.size} game(s) ready • Tap to add any installed app",
                    icon = Icons.Rounded.SportsEsports,
                    onClick = {
                        scope.launch {
                            allApps = GamingEngine.listLaunchableUserApps(context)
                            showAddGameDialog = true
                        }
                    }
                )
            }
            if (games.isEmpty()) {
                item {
                    GlassCard {
                        Text("No games auto-detected yet. Tap 'Add Game Manually' to add Free Fire, BGMI, Winlator, or any game.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                items(games, key = { it.packageName }) { game ->
                    GlassCard(glowAccent = cfg.accent.primary) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(game.name, style = MaterialTheme.typography.titleMedium)
                                Text(game.packageName, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            GlassPillButton(
                                text = "Launch",
                                onClick = {
                                    activeSession = ActiveGameSession(game.name, game.profile, System.currentTimeMillis(), dna?.batteryPercent ?: 0, dna?.batteryTempC ?: 0f, dna?.batteryTempC ?: 0f)
                                    context.packageManager.getLaunchIntentForPackage(game.packageName)?.let {
                                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(it)
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        GlassSegmentedTabs(
                            items = GameProfileType.entries.map { it.label },
                            selectedIndex = GameProfileType.entries.indexOf(game.profile),
                            onSelect = { idx ->
                                GamingEngine.saveGameProfile(context, game.packageName, GameProfileType.entries[idx])
                                refreshGames()
                            }
                        )
                    }
                }
            }
        }

        // TAB 1: GAME SESSION AUTOPSY + COMPARISON
        if (subTab == 1) {
            if (autopsies.size >= 2) {
                val a = autopsies[0]
                val b = autopsies[1]
                item {
                    GlassCard(glowAccent = cfg.accent.primary) {
                        Text("SESSION COMPARISON (LATEST 2)", style = MaterialTheme.typography.labelMedium, color = cfg.accent.primary)
                        Spacer(Modifier.height(6.dp))
                        GlassSpecRow("Latest (${a.gameName})", "${a.profile} • Peak ${String.format(Locale.US, "%.1f°C", a.peakTempC)} • ${a.endBattery - a.startBattery}%")
                        GlassSpecRow("Previous (${b.gameName})", "${b.profile} • Peak ${String.format(Locale.US, "%.1f°C", b.peakTempC)} • ${b.endBattery - b.startBattery}%")
                    }
                }
            }
            if (autopsies.isEmpty()) {
                item {
                    GlassCard {
                        Text("GAME SESSION AUTOPSY", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                        Spacer(Modifier.height(6.dp))
                        Text("Launch a game from the Games tab to record battery drain, thermal peak, and bottlenecks automatically.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                items(autopsies, key = { it.id }) { rep ->
                    val timeStr = SimpleDateFormat("MMM dd • HH:mm", Locale.US).format(Date(rep.timestampMs))
                    GlassCard(glowAccent = cfg.accent.secondary) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(rep.gameName, style = MaterialTheme.typography.titleMedium)
                            GlassStatusPill(rep.profile.uppercase(), cfg.accent.primary)
                        }
                        Text("$timeStr • Duration: ${rep.durationSec}s", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        GlassSpecRow("Battery Delta", "${rep.startBattery}% → ${rep.endBattery}% (${rep.endBattery - rep.startBattery}%)")
                        GlassSpecRow("Thermal Curve", String.format(Locale.US, "%.1f°C → Peak %.1f°C → %.1f°C", rep.startTempC, rep.peakTempC, rep.endTempC))
                        Spacer(Modifier.height(6.dp))
                        Text(rep.bottleneckNote, fontSize = 12.5.sp, color = cfg.accent.secondary)
                    }
                }
            }
        }

        // TAB 2: TOUCH RESPONSE INSPECTOR & LIVE FPS/FRAME-TIME ANALYZER
        if (subTab == 2) {
            item {
                GlassCard(glowAccent = cfg.accent.secondary) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("TOUCH RESPONSE INSPECTOR", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                        GlassStatusPill(if (peakTouchHz > 0) "PEAK $peakTouchHz Hz" else "READY", cfg.accent.primary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(128.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White.copy(0.06f))
                            .border(1.dp, cfg.accent.secondary.copy(0.45f), RoundedCornerShape(18.dp))
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    var lastEventNs = 0L
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val now = System.nanoTime()
                                        if (event.changes.any { it.pressed }) {
                                            if (lastEventNs != 0L) {
                                                val diffMs = (now - lastEventNs) / 1_000_000f
                                                if (diffMs in 1.5f..80f) {
                                                    val hz = (1000f / diffMs).toInt().coerceIn(30, 1000)
                                                    liveTouchHz = hz
                                                    if (hz > peakTouchHz) peakTouchHz = hz
                                                    touchLatencyMs = diffMs
                                                }
                                            }
                                            lastEventNs = now
                                        } else {
                                            lastEventNs = 0L
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (liveTouchHz > 0) "$liveTouchHz Hz" else "Swipe Rapidly Here",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = cfg.accent.secondary
                            )
                            Text(
                                text = if (touchLatencyMs > 0f) String.format(Locale.US, "Input Interval: %.1f ms (Real Digitizer)", touchLatencyMs)
                                else "Measures real hardware touch sampling rate",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            item {
                GlassCard(glowAccent = cfg.accent.primary) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("FPS & FRAME-TIME ANALYZER", style = MaterialTheme.typography.labelMedium, color = cfg.accent.primary)
                            Text(if (avgFps > 0) "$avgFps FPS avg • $low1PctFps FPS 1% low" else "Display pipeline frame pacing", style = MaterialTheme.typography.bodySmall)
                        }
                        GlassPillButton(
                            text = if (fpsTestActive) "Sampling..." else "Run 5s Test",
                            onClick = {
                                if (!fpsTestActive) {
                                    frameTimesMs = emptyList()
                                    fpsTestActive = true
                                }
                            }
                        )
                    }
                    if (frameTimesMs.size >= 2) {
                        Spacer(Modifier.height(10.dp))
                        GlassWaveChart(frameTimesMs.map { (it / 33.3f).coerceIn(0.08f, 1f) }, cfg.accent.primary)
                        Spacer(Modifier.height(6.dp))
                        GlassSpecRow("Avg Frame Time", String.format(Locale.US, "%.1f ms", frameTimesMs.average()))
                        GlassSpecRow("Display Panel", dna?.displayText ?: "Unknown")
                    }
                }
            }
        }

        // TAB 3: NETWORK STABILITY LAB
        if (subTab == 3) {
            item {
                GlassCard(glowAccent = cfg.accent.secondary) {
                    Text("ACTIVE CONNECTION", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Spacer(Modifier.height(8.dp))
                    GlassSpecRow("Transport", netSnapshot.transport)
                    netSnapshot.linkSpeedMbps?.let { GlassSpecRow("Wi-Fi Link Speed", "$it Mbps") }
                    netSnapshot.rssiDbm?.let { GlassSpecRow("Signal Strength", "$it dBm") }
                    netSnapshot.frequencyMhz?.let { GlassSpecRow("Wi-Fi Band", "$it MHz (${if (it >= 5000) "5 GHz" else "2.4 GHz"})") }
                    Spacer(Modifier.height(12.dp))
                    if (netRunning) {
                        GlassGaugeRow("Probing 1.1.1.1 ($netProgress/10)", "${netProgress * 10}%", netProgress / 10f, cfg.accent.secondary)
                    } else {
                        GlassPillButton(
                            text = "Run Ping, Jitter & Packet Loss Test",
                            onClick = {
                                scope.launch {
                                    netSnapshot = GamingEngine.readNetworkSnapshot(context)
                                    netRunning = true; netProgress = 0
                                    netResult = GamingEngine.runNetworkStabilityTest { netProgress = it }
                                    netRunning = false
                                }
                            }
                        )
                    }
                }
            }

            netResult?.let { r ->
                item {
                    val netColor = when {
                        r.packetLossPct > 10 -> GlassPalette.StatusDanger
                        r.avgMs <= 60 && r.jitterMs <= 15 -> GlassPalette.StatusGood
                        else -> GlassPalette.WarmAmber
                    }
                    GlassCard(tint = netColor, glowAccent = netColor) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("AVG LATENCY", style = MaterialTheme.typography.labelMedium, color = netColor)
                                Text("${r.avgMs} ms", fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, color = netColor)
                            }
                            GlassStatusPill("JITTER ${r.jitterMs}ms", cfg.accent.secondary)
                        }
                        if (r.samplesMs.size >= 2) {
                            Spacer(Modifier.height(8.dp))
                            val cap = r.maxMs.coerceAtLeast(50).toFloat()
                            GlassWaveChart(r.samplesMs.map { (it / cap).coerceIn(0.05f, 1f) }, netColor)
                        }
                        Spacer(Modifier.height(8.dp))
                        GlassSpecRow("Min / Max RTT", "${r.minMs} ms / ${r.maxMs} ms")
                        GlassSpecRow("Packet Loss", "${r.packetLossPct}%")
                        Spacer(Modifier.height(6.dp))
                        Text(r.grade, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (showAddGameDialog) {
        AlertDialog(
            onDismissRequest = { showAddGameDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Add App to Game Library", style = MaterialTheme.typography.titleMedium) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(allApps) { (pkg, name) ->
                        TextButton(
                            onClick = { GamingEngine.addManualGame(context, pkg); showAddGameDialog = false; refreshGames() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(name, modifier = Modifier.fillMaxWidth(), fontSize = 14.sp) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAddGameDialog = false }) { Text("Close") } }
        )
    }
}
// END OF GAMING
