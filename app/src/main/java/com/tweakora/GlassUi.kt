package com.tweakora

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

enum class GlassThemeMode(val label: String) {
    MIDNIGHT("Liquid Dark"), AMOLED("AMOLED"), LIGHT("Frosted Light")
}

enum class GlassAccent(val label: String, val primary: Color, val secondary: Color, val glow: Color) {
    NEON_PURPLE("Soft Violet", Color(0xFF9F7AEA), Color(0xFF38BDF8), Color(0xFF818CF8)),
    CYBER_CYAN("Ocean Cyan", Color(0xFF22B8CF), Color(0xFF60A5FA), Color(0xFF9F7AEA)),
    ELECTRIC_BLUE("Nordic Blue", Color(0xFF4F8FF7), Color(0xFF38BDF8), Color(0xFF818CF8)),
    SOLAR_AMBER("Warm Amber", Color(0xFFE5A93B), Color(0xFF9F7AEA), Color(0xFF38BDF8))
}

data class GlassThemeConfig(
    val mode: GlassThemeMode = GlassThemeMode.MIDNIGHT,
    val accent: GlassAccent = GlassAccent.NEON_PURPLE
)

val LocalGlassConfig = compositionLocalOf { GlassThemeConfig() }

object GlassPalette {
    val DeepNight = Color(0xFF0A0C12)
    val MidnightPurple = Color(0xFF101320)
    val NeonPurple = Color(0xFF9F7AEA)
    val NeonMagenta = Color(0xFFE879A9)
    val ElectricBlue = Color(0xFF4F8FF7)
    val CyberCyan = Color(0xFF38BDF8)
    val WarmAmber = Color(0xFFEAB308)
    val StatusGood = Color(0xFF34D399)
    val StatusWarn = Color(0xFFFBBF24)
    val StatusDanger = Color(0xFFF87171)
    val TextPrimary = Color(0xFFEAEFF7)
    val TextMuted = Color(0xFFA2AEC4)
}

@Composable
fun TweakoraGlassTheme(config: GlassThemeConfig = GlassThemeConfig(), content: @Composable () -> Unit) {
    val isLight = config.mode == GlassThemeMode.LIGHT
    val primaryText = if (isLight) Color(0xFF111827) else GlassPalette.TextPrimary
    val mutedText = if (isLight) Color(0xFF4B5563) else GlassPalette.TextMuted

    val colors = if (isLight) {
        lightColorScheme(
            primary = config.accent.primary, secondary = config.accent.secondary, tertiary = config.accent.glow,
            background = Color(0xFFEEF2F8), surface = Color(0xE6FFFFFF), surfaceVariant = Color(0xCCFFFFFF),
            outline = Color(0x28111827), onBackground = primaryText, onSurface = primaryText, onSurfaceVariant = mutedText
        )
    } else {
        darkColorScheme(
            primary = config.accent.primary, secondary = config.accent.secondary, tertiary = config.accent.glow,
            background = if (config.mode == GlassThemeMode.AMOLED) Color.Black else GlassPalette.DeepNight,
            surface = Color(0xFF131622), surfaceVariant = Color(0x20FFFFFF), outline = Color(0x2EFFFFFF),
            onBackground = primaryText, onSurface = primaryText, onSurfaceVariant = mutedText
        )
    }

    val typo = Typography(
        headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, color = primaryText),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, lineHeight = 21.sp, color = primaryText),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, color = primaryText),
        bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.5.sp, color = mutedText),
        labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.9.sp)
    )

    CompositionLocalProvider(LocalGlassConfig provides config, LocalContentColor provides primaryText) {
        MaterialTheme(colorScheme = colors, typography = typo, content = content)
    }
}

@Composable
fun GlassBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val cfg = LocalGlassConfig.current
    val orbStrength = when (cfg.mode) { GlassThemeMode.MIDNIGHT -> 0.50f; GlassThemeMode.AMOLED -> 0.20f; GlassThemeMode.LIGHT -> 0.28f }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val bgBrush = when (cfg.mode) {
                    GlassThemeMode.MIDNIGHT -> Brush.verticalGradient(listOf(Color(0xFF101422), Color(0xFF0A0C12), Color(0xFF07090E)))
                    GlassThemeMode.AMOLED -> Brush.verticalGradient(listOf(Color(0xFF05070B), Color.Black, Color.Black))
                    GlassThemeMode.LIGHT -> Brush.verticalGradient(listOf(Color(0xFFEAF0F9), Color(0xFFF4F7FC), Color(0xFFEAF1FA)))
                }
                val c1 = Offset(size.width * 0.16f, size.height * 0.10f)
                val c2 = Offset(size.width * 0.88f, size.height * 0.42f)
                val c3 = Offset(size.width * 0.22f, size.height * 0.84f)
                val b1 = Brush.radialGradient(listOf(cfg.accent.primary.copy(0.20f * orbStrength), Color.Transparent), c1, size.width * 0.78f)
                val b2 = Brush.radialGradient(listOf(cfg.accent.secondary.copy(0.16f * orbStrength), Color.Transparent), c2, size.width * 0.72f)
                val b3 = Brush.radialGradient(listOf(cfg.accent.glow.copy(0.14f * orbStrength), Color.Transparent), c3, size.width * 0.65f)
                onDrawBehind {
                    drawRect(bgBrush)
                    drawCircle(b1, size.width * 0.78f, c1)
                    drawCircle(b2, size.width * 0.72f, c2)
                    drawCircle(b3, size.width * 0.65f, c3)
                }
            },
        content = content
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    glowAccent: Color? = null,
    cornerRadius: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cfg = LocalGlassConfig.current
    val isLight = cfg.mode == GlassThemeMode.LIGHT
    val shape = RoundedCornerShape(cornerRadius)
    val accent = glowAccent ?: cfg.accent.primary
    val fill = remember(isLight) {
        if (isLight) Brush.verticalGradient(listOf(Color.White.copy(0.86f), Color.White.copy(0.72f)))
        else Brush.verticalGradient(listOf(Color(0xFF1A1E2E).copy(0.78f), Color(0xFF111420).copy(0.70f)))
    }
    val border = remember(isLight, accent) {
        Brush.verticalGradient(listOf(Color.White.copy(if (isLight) 0.9f else 0.18f), accent.copy(0.14f), Color.White.copy(if (isLight) 0.4f else 0.08f)))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .background(tint.copy(0.03f))
            .border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content
    )
}

@Composable
fun GlassBox(
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
    badge: String? = null,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val cfg = LocalGlassConfig.current
    val isLight = cfg.mode == GlassThemeMode.LIGHT
    val shape = RoundedCornerShape(22.dp)
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }

    val boxBg = remember(isLight) {
        if (isLight) Brush.verticalGradient(listOf(Color.White.copy(0.88f), Color.White.copy(0.74f)))
        else Brush.verticalGradient(listOf(Color(0xFF1B2030).copy(0.76f), Color(0xFF10131E).copy(0.70f)))
    }
    val boxBorder = remember(isLight, accent) {
        Brush.verticalGradient(listOf(Color.White.copy(if (isLight) 0.95f else 0.24f), accent.copy(0.20f), Color.White.copy(0.08f)))
    }
    val headerBg = if (isLight) accent.copy(0.07f) else Color.White.copy(0.035f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(boxBg)
            .border(1.dp, boxBorder, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(9.dp))
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                badge?.let { GlassStatusPill(it, accent) }
                if (collapsible) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        HorizontalDivider(color = Color.White.copy(if (isLight) 0.35f else 0.08f), thickness = 1.dp)
        AnimatedVisibility(visible = !collapsible || expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

@Composable
fun GlassBoxTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLight = LocalGlassConfig.current.mode == GlassThemeMode.LIGHT
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (isLight) accent.copy(0.08f) else Color.White.copy(0.05f))
            .border(1.dp, if (isLight) accent.copy(0.18f) else Color.White.copy(0.10f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(accent.copy(0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Clean stat cell for 2x2 Live Vitals matrices inside a GlassBox.
 */
@Composable
fun GlassStatCell(
    label: String,
    value: String,
    detail: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLight = LocalGlassConfig.current.mode == GlassThemeMode.LIGHT
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (isLight) accent.copy(0.07f) else Color.White.copy(0.045f))
            .border(1.dp, if (isLight) accent.copy(0.18f) else Color.White.copy(0.09f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label.uppercase(), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = accent, letterSpacing = 0.7.sp)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(detail, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Zero-Recomposition 120fps Sliding Liquid Glass Dock:
 * Reads Animatable.value ONLY inside graphicsLayer so the UI thread never recomposes during slides.
 */
@Composable
fun LiquidGlassNavBar(
    items: List<Triple<String, String, ImageVector>>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isLight = LocalGlassConfig.current.mode == GlassThemeMode.LIGHT
    val capsule = RoundedCornerShape(50)
    val selectedIdx = items.indexOfFirst { it.first == currentRoute }.coerceAtLeast(0)
    val density = LocalDensity.current
    val count = items.size.coerceAtLeast(1)

    var innerWidthPx by remember { mutableIntStateOf(0) }
    val animIndex = remember { Animatable(selectedIdx.toFloat()) }

    LaunchedEffect(selectedIdx) {
        animIndex.animateTo(
            targetValue = selectedIdx.toFloat(),
            animationSpec = spring(dampingRatio = 0.80f, stiffness = 520f)
        )
    }

    val dockBg = remember(isLight) {
        if (isLight) Brush.verticalGradient(listOf(Color(0xE6FFFFFF), Color(0xD0EAF0FA)))
        else Brush.verticalGradient(listOf(Color(0xD41E2232), Color(0xEB0C0F18)))
    }
    val dockRim = remember(isLight) {
        Brush.verticalGradient(listOf(Color.White.copy(if (isLight) 0.95f else 0.36f), Color.White.copy(0.10f), Color.White.copy(if (isLight) 0.6f else 0.22f)))
    }
    val sliderBg = remember(isLight) {
        if (isLight) Brush.verticalGradient(listOf(Color(0x220F172A), Color(0x120F172A)))
        else Brush.verticalGradient(listOf(Color(0x3BFFFFFF), Color(0x18FFFFFF)))
    }
    val sliderRim = remember(isLight) {
        Brush.verticalGradient(listOf(Color.White.copy(if (isLight) 0.85f else 0.52f), Color.White.copy(0.08f)))
    }

    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(capsule)
                .background(dockBg)
                .border(1.2.dp, dockRim, capsule)
                .padding(6.dp)
                .onSizeChanged { innerWidthPx = it.width }
        ) {
            if (innerWidthPx > 0) {
                val slotWidthPx = innerWidthPx.toFloat() / count
                val slotWidthDp = with(density) { slotWidthPx.toDp() }
                Box(
                    modifier = Modifier
                        .graphicsLayer { translationX = animIndex.value * slotWidthPx }
                        .width(slotWidthDp)
                        .height(54.dp)
                        .clip(capsule)
                        .background(sliderBg)
                        .border(1.dp, sliderRim, capsule)
                )
            }

            Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                items.forEach { (route, label, icon) ->
                    val selected = currentRoute == route
                    val itemTint = when {
                        selected && isLight -> Color(0xFF0F172A)
                        selected -> Color.White
                        isLight -> Color(0xFF64748B)
                        else -> Color(0xFF9AA6BE)
                    }
                    val interaction = remember { MutableInteractionSource() }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(interactionSource = interaction, indication = null) { onSelect(route) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(icon, contentDescription = label, tint = itemTint, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = itemTint, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Zero-Recomposition 120fps Sliding Segmented Tabs.
 */
@Composable
fun GlassSegmentedTabs(items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cfg = LocalGlassConfig.current
    val isLight = cfg.mode == GlassThemeMode.LIGHT
    val shape = RoundedCornerShape(50)
    val count = items.size.coerceAtLeast(1)
    val safeIdx = selectedIndex.coerceIn(0, count - 1)
    val density = LocalDensity.current

    var innerWidthPx by remember { mutableIntStateOf(0) }
    val animIndex = remember { Animatable(safeIdx.toFloat()) }

    LaunchedEffect(safeIdx) {
        animIndex.animateTo(
            targetValue = safeIdx.toFloat(),
            animationSpec = spring(dampingRatio = 0.84f, stiffness = 560f)
        )
    }

    val pillBrush = remember(cfg.accent) {
        Brush.horizontalGradient(listOf(cfg.accent.primary.copy(0.90f), cfg.accent.secondary.copy(0.88f)))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isLight) Color.White.copy(0.72f) else Color(0x88141825))
            .border(1.dp, Color.White.copy(if (isLight) 0.85f else 0.16f), shape)
            .padding(4.dp)
            .onSizeChanged { innerWidthPx = it.width }
    ) {
        if (innerWidthPx > 0) {
            val slotPx = innerWidthPx.toFloat() / count
            val slotDp = with(density) { slotPx.toDp() }
            Box(
                Modifier
                    .graphicsLayer { translationX = animIndex.value * slotPx }
                    .width(slotDp)
                    .height(36.dp)
                    .clip(shape)
                    .background(pillBrush)
            )
        }
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { idx, label ->
                val sel = idx == safeIdx
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = interaction, indication = null) { onSelect(idx) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 12.5.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun GlassRingGauge(progress: Float, centerTitle: String, centerSubtitle: String, pillLabel: String, modifier: Modifier = Modifier, size: Dp = 128.dp) {
    val cfg = LocalGlassConfig.current
    val clamped = progress.coerceIn(0f, 1f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val strokePx = 8.dp.toPx()
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)
            drawArc(Color.White.copy(0.12f), 135f, 270f, false, topLeft, arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
            val sweep = 270f * clamped
            drawArc(Brush.sweepGradient(listOf(cfg.accent.secondary, cfg.accent.primary, cfg.accent.glow, cfg.accent.secondary)), 135f, sweep, false, topLeft, arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
            val rad = Math.toRadians((135f + sweep).toDouble())
            val r = arcSize.width / 2f
            val pt = Offset(this.size.width / 2f + (r * cos(rad)).toFloat(), this.size.height / 2f + (r * sin(rad)).toFloat())
            drawCircle(cfg.accent.secondary.copy(0.45f), 6.5.dp.toPx(), pt)
            drawCircle(Color.White, 3.dp.toPx(), pt)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTitle, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(centerSubtitle, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(cfg.accent.primary, cfg.accent.secondary))).padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text(pillLabel, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun GlassActionBanner(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cfg = LocalGlassConfig.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(cfg.accent.primary.copy(0.30f), cfg.accent.secondary.copy(0.22f))))
            .border(1.dp, Color.White.copy(0.20f), shape)
            .clickable(onClick = onClick)
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = title, tint = Color.White, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 12.5.sp, lineHeight = 17.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = cfg.accent.secondary)
    }
}

@Composable
fun GlassToolTile(title: String, subtitle: String, icon: ImageVector, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassBoxTile(title, subtitle, icon, accent, onClick, modifier)
}

@Composable
fun GlassWaveChart(values: List<Float>, lineColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(90.dp)) {
        val pts = if (values.size >= 2) values else listOf(0.35f, 0.42f, 0.39f, 0.48f)
        val padY = 10.dp.toPx()
        for (i in 1..4) drawLine(Color.White.copy(0.08f), Offset(size.width * (i / 5f), 0f), Offset(size.width * (i / 5f), size.height), 1.dp.toPx())
        val stepX = size.width / (pts.size - 1).coerceAtLeast(1)
        val coords = pts.mapIndexed { i, v -> Offset(i * stepX, size.height - padY - v.coerceIn(0f, 1f) * (size.height - padY * 2f)) }
        val line = Path().apply {
            moveTo(coords.first().x, coords.first().y)
            for (i in 0 until coords.size - 1) {
                val cx = (coords[i].x + coords[i + 1].x) / 2f
                cubicTo(cx, coords[i].y, cx, coords[i + 1].y, coords[i + 1].x, coords[i + 1].y)
            }
        }
        val fill = Path().apply { addPath(line); lineTo(coords.last().x, size.height); lineTo(coords.first().x, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(lineColor.copy(0.24f), Color.Transparent)))
        drawPath(line, lineColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(lineColor.copy(0.35f), 6.dp.toPx(), coords.last())
        drawCircle(Color.White, 3.dp.toPx(), coords.last())
    }
}

@Composable
fun GlassStatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(color.copy(0.18f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
    }
}

@Composable
fun GlassPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, gradient: List<Color> = listOf(GlassPalette.ElectricBlue, GlassPalette.CyberCyan)) {
    Box(modifier.clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(gradient)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun GlassGaugeRow(label: String, value: String, progress: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Box(Modifier.fillMaxWidth().height(7.5.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(0.10f))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(color.copy(0.75f), color))))
        }
    }
}

@Composable
fun GlassSpecRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
// END OF GLASSUI
