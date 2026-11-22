package com.tweakora

import android.content.Context
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RuleTrigger(val label: String) {
    THERMAL_HIGH("Temp ≥ 40°C"),
    BATTERY_LOW("Battery ≤ 20%"),
    CHARGING_STARTED("Charging Connected"),
    RAM_HIGH("RAM ≥ 85%")
}

enum class RuleAction(val label: String) {
    ALERT_COOLING("Cooling Warning Banner"),
    SWITCH_BATTERY_PROFILE("Recommend Battery Mode"),
    RECOMMEND_CLEANUP("Trigger Memory Alert"),
    LOG_EVENT("Log Telemetry Event")
}

data class SmartRule(
    val id: Long,
    val name: String,
    val trigger: RuleTrigger,
    val action: RuleAction,
    val enabled: Boolean
)

data class RuleEventLog(
    val timestampMs: Long,
    val ruleName: String,
    val detail: String
)

object SmartRulesEngine {
    private const val RULES_FILE = "tweakora_smart_rules.json"
    private val lastFiredMap = mutableMapOf<Long, Long>()

    private fun defaultRules(): List<SmartRule> = listOf(
        SmartRule(1L, "Thermal Guard", RuleTrigger.THERMAL_HIGH, RuleAction.ALERT_COOLING, true),
        SmartRule(2L, "Low Battery Shield", RuleTrigger.BATTERY_LOW, RuleAction.SWITCH_BATTERY_PROFILE, true),
        SmartRule(3L, "RAM Pressure Alert", RuleTrigger.RAM_HIGH, RuleAction.RECOMMEND_CLEANUP, false),
        SmartRule(4L, "Charge Session Tracker", RuleTrigger.CHARGING_STARTED, RuleAction.LOG_EVENT, true)
    )

    fun loadRules(context: Context): List<SmartRule> = runCatching {
        val f = File(context.filesDir, RULES_FILE)
        if (!f.exists()) return defaultRules()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SmartRule(
                id = o.optLong("id"),
                name = o.optString("name"),
                trigger = runCatching { RuleTrigger.valueOf(o.optString("trigger")) }.getOrDefault(RuleTrigger.THERMAL_HIGH),
                action = runCatching { RuleAction.valueOf(o.optString("action")) }.getOrDefault(RuleAction.LOG_EVENT),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }.getOrDefault(defaultRules())

    fun saveRules(context: Context, rules: List<SmartRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("trigger", r.trigger.name)
                put("action", r.action.name)
                put("enabled", r.enabled)
            })
        }
        File(context.filesDir, RULES_FILE).writeText(arr.toString())
    }

    fun evaluateLive(context: Context, dna: DeviceDna): List<RuleEventLog> {
        val now = System.currentTimeMillis()
        val fired = mutableListOf<RuleEventLog>()
        loadRules(context).filter { it.enabled }.forEach { rule ->
            val cooldownOk = now - (lastFiredMap[rule.id] ?: 0L) > 60_000L
            if (!cooldownOk) return@forEach

            val matched = when (rule.trigger) {
                RuleTrigger.THERMAL_HIGH -> dna.batteryTempC >= 40f
                RuleTrigger.BATTERY_LOW -> dna.batteryPercent in 1..20 && !dna.isCharging
                RuleTrigger.CHARGING_STARTED -> dna.isCharging
                RuleTrigger.RAM_HIGH -> dna.ramFraction >= 0.85f
            }
            if (matched) {
                lastFiredMap[rule.id] = now
                fired.add(
                    RuleEventLog(
                        timestampMs = now,
                        ruleName = rule.name,
                        detail = "${rule.trigger.label} → ${rule.action.label}"
                    )
                )
            }
        }
        return fired
    }
}

@Composable
fun SmartRulesAndSimScreen(
    dna: DeviceDna?,
    recentLogs: List<RuleEventLog>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val cfg = LocalGlassConfig.current

    var subTab by remember { mutableIntStateOf(0) } // 0=Smart Rules, 1=Optimization Simulator
    var rules by remember { mutableStateOf(SmartRulesEngine.loadRules(context)) }
    var showAddDialog by remember { mutableStateOf(false) }

    // Simulator states (clearly labeled ESTIMATED vs MEASURED)
    var simAnimFast by remember { mutableStateOf(true) }
    var simRefreshCap by remember { mutableIntStateOf(90) }
    var simFreezer by remember { mutableStateOf(true) }
    var simPrivateDns by remember { mutableStateOf(true) }

    val estResponsivenessBoost = (if (simAnimFast) 18 else 0) + (if (simRefreshCap >= 90) 12 else 0)
    val estBatteryGainPct = (if (simRefreshCap == 60) 14 else if (simRefreshCap == 90) 6 else 0) +
        (if (simFreezer) 8 else 0)
    val estThermalReliefC = (if (simRefreshCap == 60) 1.8f else if (simRefreshCap == 90) 0.7f else 0f) +
        (if (simFreezer) 0.5f else 0f)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("AUTOMATION & PREVIEW", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                    Text("Smart Rules & Simulator", style = MaterialTheme.typography.headlineSmall)
                }
                GlassPillButton(text = "Back", onClick = onBack, gradient = listOf(cfg.accent.primary, cfg.accent.secondary))
            }
        }

        item {
            GlassSegmentedTabs(
                items = listOf("Smart Rules", "Simulator"),
                selectedIndex = subTab,
                onSelect = { subTab = it }
            )
        }

        if (subTab == 0) {
            item {
                GlassActionBanner(
                    title = "Create Automation Rule",
                    subtitle = "${rules.count { it.enabled }} active rule(s) monitoring live telemetry",
                    icon = Icons.Rounded.Add,
                    onClick = { showAddDialog = true }
                )
            }

            items(rules, key = { it.id }) { rule ->
                GlassCard(glowAccent = if (rule.enabled) cfg.accent.secondary else GlassPalette.TextMuted) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.name, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text("IF: ${rule.trigger.label}", fontSize = 13.sp, color = cfg.accent.secondary, fontWeight = FontWeight.SemiBold)
                            Text("THEN: ${rule.action.label}", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { on ->
                                rules = rules.map { if (it.id == rule.id) it.copy(enabled = on) else it }
                                SmartRulesEngine.saveRules(context, rules)
                            }
                        )
                    }
                }
            }

            item {
                GlassCard {
                    Text("LIVE RULE TRIGGER LOG", style = MaterialTheme.typography.labelMedium, color = GlassPalette.WarmAmber)
                    Spacer(Modifier.height(8.dp))
                    if (recentLogs.isEmpty()) {
                        Text("No rules triggered in this session yet. Active rules evaluate automatically against live telemetry.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        recentLogs.forEach { log ->
                            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(log.timestampMs))
                            Text("[$ts] ${log.ruleName}: ${log.detail}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        } else {
            // OPTIMIZATION SIMULATOR (ESTIMATED vs MEASURED)
            item {
                GlassCard(tint = cfg.accent.primary, glowAccent = cfg.accent.primary) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("MEASURED BASELINE", style = MaterialTheme.typography.labelMedium, color = GlassPalette.StatusGood)
                        GlassStatusPill("MEASURED", GlassPalette.StatusGood)
                    }
                    Spacer(Modifier.height(8.dp))
                    GlassSpecRow("Current Temp", dna?.let { String.format(Locale.US, "%.1f°C (%s)", it.batteryTempC, it.thermalStatus) } ?: "--")
                    GlassSpecRow("Current RAM", dna?.let { "${(it.ramFraction * 100).toInt()}% used" } ?: "--")
                    GlassSpecRow("Battery Level", dna?.let { "${it.batteryPercent}% (${it.batteryVoltageMv} mV)" } ?: "--")
                }
            }

            item {
                GlassCard(glowAccent = cfg.accent.secondary) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("SIMULATED PROJECTION", style = MaterialTheme.typography.labelMedium, color = cfg.accent.secondary)
                        GlassStatusPill("ESTIMATED", GlassPalette.WarmAmber)
                    }
                    Spacer(Modifier.height(10.dp))
                    GlassGaugeRow("Est. UI Snappiness Gain", "+$estResponsivenessBoost%", estResponsivenessBoost / 35f, cfg.accent.primary)
                    Spacer(Modifier.height(8.dp))
                    GlassGaugeRow("Est. Standby Battery Gain", "+$estBatteryGainPct%", estBatteryGainPct / 25f, GlassPalette.StatusGood)
                    Spacer(Modifier.height(8.dp))
                    GlassSpecRow("Est. Thermal Reduction", String.format(Locale.US, "-%.1f°C under load", estThermalReliefC))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Note: Values above are model estimates to preview trade-offs before applying real tweaks in the Tweaks tab.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            item {
                GlassCard {
                    Text("TOGGLE CONFIGURATION PREVIEW", style = MaterialTheme.typography.labelMedium, color = cfg.accent.primary)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("0.5x Fast UI Animations", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = simAnimFast, onCheckedChange = { simAnimFast = it })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Cached Apps Freezer", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = simFreezer, onCheckedChange = { simFreezer = it })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Encrypted Private DNS", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = simPrivateDns, onCheckedChange = { simPrivateDns = it })
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Peak Refresh Rate Ceiling", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    GlassSegmentedTabs(
                        items = listOf("60 Hz", "90 Hz", "120 Hz"),
                        selectedIndex = when (simRefreshCap) { 60 -> 0; 90 -> 1; else -> 2 },
                        onSelect = { idx -> simRefreshCap = when (idx) { 0 -> 60; 1 -> 90; else -> 120 } }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        var newName by remember { mutableStateOf("Custom Guard") }
        var chosenTrigger by remember { mutableStateOf(RuleTrigger.THERMAL_HIGH) }
        var chosenAction by remember { mutableStateOf(RuleAction.ALERT_COOLING) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("New Smart Rule", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Rule Name") },
                        singleLine = true
                    )
                    Text("Trigger Condition", style = MaterialTheme.typography.labelMedium)
                    RuleTrigger.entries.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = chosenTrigger == t, onClick = { chosenTrigger = t })
                            Text(t.label, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val rule = SmartRule(System.currentTimeMillis(), newName.ifBlank { "Custom Rule" }, chosenTrigger, chosenAction, true)
                    rules = listOf(rule) + rules
                    SmartRulesEngine.saveRules(context, rules)
                    showAddDialog = false
                }) { Text("Save Rule") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}
