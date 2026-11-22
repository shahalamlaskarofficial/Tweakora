package com.tweakora

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
        headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 29.sp, color = primaryText),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, color = primaryText),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, color = primaryText),
        bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp, color = mutedText),
        labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.9.sp)
    )

    CompositionLocalProvider(LocalGlassConfig provides config, LocalContentColor provides primaryText) {
        MaterialTheme(colorScheme = colors, typography = typo, content = content)
    }
}

/**
 * GPU-cached backdrop: allocates radial brushes once per layout size for zero scroll lag & low battery usage.
 */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val cfg = LocalGlassConfig.current
    val orbStrength = when (cfg.mode) { GlassThemeMode.MIDNIGHT -> 0.55f; GlassThemeMode.AMOLED -> 0.22f; GlassThemeMode.LIGHT -> 0.30f }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val bgBrush = when (cfg.mode) {
                    GlassThemeMode.MIDNIGHT -> Brush.verticalGradient(listOf(Color(0xFF111524), Color(0xFF0A0C12), Color(0xFF07090E)))
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
    cornerRadius: Dp = 22.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cfg = LocalGlassConfig.current
    val isLight = cfg.mode == GlassThemeMode.LIGHT
    val shape = RoundedCornerShape(cornerRadius)
    val accent = glowAccent ?: cfg.accent.primary
    val fill = if (isLight) Brush.verticalGradient(listOf(Color.White.copy(0.86f), Color.White.copy(0.70f)))
    else Brush.verticalGradient(listOf(Color(0xFF1A1E2E).copy(0.78f), Color(0xFF111420).copy(0.70f)))
    val border = Brush.verticalGradient(listOf(Color.White.copy(if (isLight) 0.9f else 0.20f), accent.copy(0.14f), Color.White.copy(if (isLight) 0.4f else 0.08f)))

    Box(modifier.fillMaxWidth().clip(shape).background(fill).background(tint.copy(0.03f)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Surface(Modifier.fillMaxWidth(), shape = shape, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, border)) {
            Column(Modifier.padding(17.dp), content = content)
        }
    }
}

/**
 * Pure GPU-accelerated sliding Liquid Glass Capsule Dock (uses graphicsLayer.translationX for 120fps motion).
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

    val dockBg = if (isLight) Brush.verticalGradient(listOf(Color(0xE0FFFFFF), Color(0xC8EAF0FA)))
    else Brush.verticalGradient(listOf(Color(0xC81E2232), Color(0xE00D1019)))
    val dockRim = Brush.verticalGradient(listOf(Color.White.copy(if (isLight) 0.95f else 0.38f), Color.White.copy(0.10f), Color.White.copy(if (isLight) 0.6f else 0.22f)))

    val sliderBg = if (isLight) Brush.verticalGradient(listOf(Color(0x260F172A), Color(0x140F172A)))
    else Brush.verticalGradient(listOf(Color(0x3DFFFFFF), Color(0x1CFFFFFF)))
    val sliderRim = Brush.verticalGradient(listOf(Color.White.copy(if (isLight) 0.9f else 0.55f), Color.White.copy(0.10f)))

    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box(Modifier.fillMaxWidth().clip(capsule).background(dockBg)) {
            Surface(Modifier.fillMaxWidth(), shape = capsule, color = Color.Transparent, border = BorderStroke(1.2.dp, dockRim)) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(6.dp)) {
                    val slotWidth = maxWidth / items.size.coerceAtLeast(1)
                    val targetPx = with(density) { (slotWidth * selectedIdx).toPx() }
                    val slidePx by animateFloatAsState(
                        targetValue = targetPx,
                        animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f),
                        label = "gpuNavSlide"
                    )

                    Box(
                        modifier = Modifier
                            .graphicsLayer { translationX = slidePx }
                            .width(slotWidth)
                            .height(54.dp)
                            .clip(capsule)
                            .background(sliderBg)
                            .border(1.dp, sliderRim, capsule)
                    )

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
    }
}

/**
 * Pure GPU-accelerated sliding segmented glass tabs.
 */
@Composable
fun GlassSegmentedTabs(items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cfg = LocalGlassConfig.current
    val isLight = cfg.mode == GlassThemeMode.LIGHT
    val shape = RoundedCornerShape(50)
    val safeIdx = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val density = LocalDensity.current

    Surface(modifier.fillMaxWidth(), shape = shape, color = if (isLight) Color.White.copy(0.70f) else Color(0x88151926), border = BorderStroke(1.dp, Color.White.copy(if (isLight) 0.85f else 0.18f))) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(4.dp)) {
            val slotW = maxWidth / items.size.coerceAtLeast(1)
            val targetPx = with(density) { (slotW * safeIdx).toPx() }
            val slidePx by animateFloatAsState(
                targetValue = targetPx,
                animationSpec = spring(dampingRatio = 0.84f, stiffness = 500f),
                label = "gpuSegSlide"
            )
            Box(
                Modifier
                    .graphicsLayer { translationX = slidePx }
                    .width(slotW)
                    .height(36.dp)
                    .clip(shape)
                    .background(Brush.horizontalGradient(listOf(cfg.accent.primary.copy(0.90f), cfg.accent.secondary.copy(0.88f))))
            )
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
}

@Composable
fun GlassRingGauge(progress: Float, centerTitle: String, centerSubtitle: String, pillLabel: String, modifier: Modifier = Modifier, size: Dp = 132.dp) {
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
            Text(centerTitle, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(centerSubtitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(cfg.accent.primary, cfg.accent.secondary))).padding(horizontal = 11.dp, vertical = 3.dp)) {
            Text(pillLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun GlassActionBanner(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cfg = LocalGlassConfig.current
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.fillMaxWidth().clip(shape).background(Brush.horizontalGradient(listOf(cfg.accent.primary.copy(0.32f), cfg.accent.secondary.copy(0.24f)))).clickable(onClick = onClick)) {
        Surface(Modifier.fillMaxWidth(), shape = shape, color = Color.Transparent, border = BorderStroke(1.dp, Color.White.copy(0.22f))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(0.14f)), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = title, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = cfg.accent.secondary)
            }
        }
    }
}

@Composable
fun GlassToolTile(title: String, subtitle: String, icon: ImageVector, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, tint = accent, glowAccent = accent, cornerRadius = 20.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(0.18f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun GlassWaveChart(values: List<Float>, lineColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(94.dp)) {
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
        drawCircle(lineColor.copy(0.35f), 6.5.dp.toPx(), coords.last())
        drawCircle(Color.White, 3.dp.toPx(), coords.last())
    }
}

@Composable
fun GlassStatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(color.copy(0.18f)).padding(horizontal = 11.dp, vertical = 4.dp)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
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
