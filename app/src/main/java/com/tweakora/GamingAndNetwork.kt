package com.tweakora

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
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
    BATTERY("Battery-Friendly")
}

data class GameAppInfo(
    val packageName: String,
    val name: String,
    val profile: GameProfileType
)

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

data class NetworkInfoSnapshot(
    val transport: String,
    val linkSpeedMbps: Int?,
    val rssiDbm: Int?,
    val frequencyMhz: Int?
)

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

        val apps = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        apps.filter { app ->
            val launchable = pm.getLaunchIntentForPackage(app.packageName) != null
            if (!launchable || app.packageName == context.packageName) return@filter false
            val isCatGame = app.category == ApplicationInfo.CATEGORY_GAME
            val matchesName = knownGameKeywords.any { app.packageName.lowercase().contains(it) }
            isCatGame || matchesName || app.packageName in manualPkgs
        }.map { app ->
            val label = pm.getApplicationLabel(app).toString()
            val savedProfile = prefs.getString("profile_${app.packageName}", GameProfileType.BALANCED.name)
            val profile = runCatching { GameProfileType.valueOf(savedProfile ?: "") }.getOrDefault(GameProfileType.BALANCED)
            GameAppInfo(app.packageName, label, profile)
        }.sortedBy { it.name.lowercase() }
    }

    fun saveGameProfile(context: Context, pkg: String, profile: GameProfileType) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString("profile_$pkg", profile.name).apply()
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
                id = o.optLong("id"),
                gameName = o.optString("gameName"),
                profile = o.optString("profile"),
                timestampMs = o.optLong("timestampMs"),
                durationSec = o.optLong("durationSec"),
                startBattery = o.optInt("startBattery"),
                endBattery = o.optInt("endBattery"),
                startTempC = o.optDouble("startTempC").toFloat(),
                peakTempC = o.optDouble("peakTempC").toFloat(),
                endTempC = o.optDouble("endTempC").toFloat(),
                bottleneckNote = o.optString("bottleneckNote")
            )
        }.sortedByDescending { it.timestampMs }
    }.getOrDefault(emptyList())

    fun saveAutopsy(context: Context, record: SessionAutopsyRecord) {
        val all = (listOf(record) + loadAutopsies(context)).take(30)
        val arr = JSONArray()
        all.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("gameName", r.gameName)
                put("profile", r.profile)
                put("timestampMs", r.timestampMs)
                put("durationSec", r.durationSec)
                put("startBattery", r.startBattery)
                put("endBattery", r.endBattery)
                put("startTempC", r.startTempC.toDouble())
                put("peakTempC", r.peakTempC.toDouble())
                put("endTempC", r.endTempC.toDouble())
                put("bottleneckNote", r.bottleneckNote)
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
            transport = transport,
            linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 },
            rssiDbm = info?.rssi?.takeIf { it in -120..-10 },
            frequencyMhz = info?.frequency?.takeIf { it > 0 }
        )
    }

    /**
     * Real 10-packet TCP handshake latency, jitter, and packet loss test.
     */
    suspend fun runNetworkStabilityTest(
        host: String = "1.1.1.1",
        port: Int = 443,
        onProgress: (Int) -> Unit
    ): NetworkTestResult = withContext(Dispatchers.IO) {
        val samples = mutableListOf<Long>()
        var dropped = 0
        val totalPackets = 10

        for (i in 1..totalPackets) {
            val t0 = System.nanoTime()
            val ok = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 1500)
                }
                true
            }.getOrDefault(false)
            val rttMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
            if (ok) samples.add(rttMs) else dropped++
            withContext(Dispatchers.Main) { onProgress(i) }
        }

        val lossPct = (dropped * 100) / totalPackets
        if (samples.isEmpty()) {
            return@withContext NetworkTestResult(host, emptyList(), 0, 0, 0, 0, 100, "Unreachable — Check Internet")
        }

        val avg = samples.average().toInt()
        val min = samples.minOrNull()?.toInt() ?: 0
        val max = samples.maxOrNull()?.toInt() ?: 0
        val jitter = if (samples.size >= 2) {
            samples.zipWithNext { a, b -> abs(a - b) }.average().toInt()
        } else 0

        val grade = when {
            lossPct > 10 -> "Unstable ($lossPct% loss) — Switch Wi-Fi/5G"
            avg <= 45 && jitter <= 12 -> "Esports Ready — Ultra-low ping & steady jitter"
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

    var subTab by remember { mutableIntStateOf(0) } // 0=Games, 1=Autopsy, 2=Network Lab
    var games by remember { mutableStateOf<List<GameAppInfo>>(emptyList()) }
    var autopsies by remember { mutableStateOf<List<SessionAutopsyRecord>>(emptyList()) }
    var activeSession by remember { mutableStateOf<ActiveGameSession?>(null) }
    var showAddGameDialog by remember { mutableStateOf(false) }
    var allApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

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

    // Update peak temp while a gaming session is active
    LaunchedEffect(dna?.batteryTempC) {
        val s = activeSession
        val t = dna?.batteryTempC
        if (s != null && t != null && t > s.peakTempC) {
            activeSession = s.copy(peakTempC = t)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("GAMING CENTRE", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Text("Game Hub & Network Lab", style = MaterialTheme.typography.headlineSmall)
                }
                GlassStatusPill(
                    text = dna?.let { String.format(Locale.US, "%.1f°C", it.batteryTempC) } ?: "--",
                    color = if ((dna?.batteryTempC ?: 0f) >= 40f) GlassPalette.WarmAmber else GlassPalette.CyberCyan
                )
            }
        }

        item {
            GlassSegmentedTabs(
                items = listOf("Games", "Autopsy", "Network Lab"),
                selectedIndex = subTab,
                onSelect = {
                    subTab = it
                    if (it == 2) netSnapshot = GamingEngine.readNetworkSnapshot(context)
                }
            )
        }

        // Active Session Banner (visible across sub-tabs)
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
                                    s.peakTempC >= 42f -> "Thermal Bottleneck: Peak temp hit ${s.peakTempC}°C."
                                    (s.startBattery - endBat) >= 5 -> "High battery drain (${s.startBattery - endBat}% drop)."
                                    else -> "Clean session — thermals and battery stayed stable."
                                }
                                val rec = SessionAutopsyRecord(
                                    id = System.currentTimeMillis(),
                                    gameName = s.gameName,
                                    profile = s.profile.label,
                                    timestampMs = System.currentTimeMillis(),
                                    durationSec = dur,
                                    startBattery = s.startBattery,
                                    endBattery = endBat,
                                    startTempC = s.startTempC,
                                    peakTempC = s.peakTempC,
                                    endTempC = endTemp,
                                    bottleneckNote = note
                                )
                                GamingEngine.saveAutopsy(context, rec)
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

        // SUB-TAB 0: GAMES LIBRARY
        if (subTab == 0) {
            item {
                GlassActionBanner(
                    title = "Add Game Manually",
                    subtitle = "${games.size} game(s) detected • Tap to add any installed app",
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
                        Text(
                            "No games auto-detected yet. Tap 'Add Game Manually' above to add Free Fire, BGMI, Winlator, or any game installed on your device.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                items(games, key = { it.packageName }) { game ->
                    GlassCard(glowAccent = cfg.accent.primary) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(game.name, style = MaterialTheme.typography.titleMedium)
                                Text(game.packageName, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            GlassPillButton(
                                text = "Launch",
                                onClick = {
                                    activeSession = ActiveGameSession(
                                        gameName = game.name,
                                        profile = game.profile,
                                        startMs = System.currentTimeMillis(),
                                        startBattery = dna?.batteryPercent ?: 0,
                                        startTempC = dna?.batteryTempC ?: 0f,
                                        peakTempC = dna?.batteryTempC ?: 0f
                                    )
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(game.packageName)
                                    if (launchIntent != null) {
                                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(launchIntent)
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GameProfileType.entries.forEach { prof ->
                                FilterChip(
                                    selected = game.profile == prof,
                                    onClick = {
                                        GamingEngine.saveGameProfile(context, game.packageName, prof)
                                        refreshGames()
                                    },
                                    label = { Text(prof.label, fontSize = 10.5.sp) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // SUB-TAB 1: GAME SESSION AUTOPSY
        if (subTab == 1) {
            if (autopsies.isEmpty()) {
                item {
                    GlassCard {
                        Text("GAME SESSION AUTOPSY", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Launch any game from the Games tab to start recording a live session. When you return to Tweakora and tap 'Finish', a full battery & thermal autopsy report appears here.",
                            style = MaterialTheme.typography.bodySmall
                        )
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
                        Text(rep.bottleneckNote, fontSize = 11.5.sp, color = GlassPalette.CyberCyan)
                    }
                }
            }
        }

        // SUB-TAB 2: NETWORK STABILITY LAB
        if (subTab == 2) {
            item {
                GlassCard(glowAccent = cfg.accent.secondary) {
                    Text("ACTIVE CONNECTION", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Spacer(Modifier.height(8.dp))
                    GlassSpecRow("Transport", netSnapshot.transport)
                    netSnapshot.linkSpeedMbps?.let { GlassSpecRow("Wi-Fi Link Speed", "$it Mbps") }
                    netSnapshot.rssiDbm?.let { GlassSpecRow("Signal Strength", "$it dBm") }
                    netSnapshot.frequencyMhz?.let {
                        val band = if (it >= 5000) "5 GHz" else "2.4 GHz"
                        GlassSpecRow("Wi-Fi Band", "$it MHz ($band)")
                    }
                    Spacer(Modifier.height(12.dp))
                    if (netRunning) {
                        GlassGaugeRow("Probing Cloudflare 1.1.1.1 ($netProgress/10)", "${netProgress * 10}%", netProgress / 10f, GlassPalette.CyberCyan)
                    } else {
                        GlassPillButton(
                            text = "Run Ping, Jitter & Packet Loss Test",
                            onClick = {
                                scope.launch {
                                    netSnapshot = GamingEngine.readNetworkSnapshot(context)
                                    netRunning = true
                                    netProgress = 0
                                    netResult = GamingEngine.runNetworkStabilityTest { p -> netProgress = p }
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
                                Text("${r.avgMs} ms", fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = netColor)
                            }
                            GlassStatusPill("JITTER ${r.jitterMs}ms", cfg.accent.secondary)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (r.samplesMs.size >= 2) {
                            val cap = r.maxMs.coerceAtLeast(50).toFloat()
                            GlassWaveChart(r.samplesMs.map { (it / cap).coerceIn(0.05f, 1f) }, netColor)
                        }
                        Spacer(Modifier.height(8.dp))
                        GlassSpecRow("Min / Max RTT", "${r.minMs} ms / ${r.maxMs} ms")
                        GlassSpecRow("Packet Loss", "${r.packetLossPct}%")
                        GlassSpecRow("Target Server", "${r.host}:443")
                        Spacer(Modifier.height(6.dp))
                        Text(r.grade, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }

    if (showAddGameDialog) {
        AlertDialog(
            onDismissRequest = { showAddGameDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Add App to Game Library", fontSize = 16.sp) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(allApps) { (pkg, name) ->
                        TextButton(
                            onClick = {
                                GamingEngine.addManualGame(context, pkg)
                                showAddGameDialog = false
                                refreshGames()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(name, modifier = Modifier.fillMaxWidth(), fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddGameDialog = false }) { Text("Close") }
            }
        )
    }
}
