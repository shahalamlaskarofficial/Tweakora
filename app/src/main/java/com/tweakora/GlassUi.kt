package com.tweakora

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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
    MIDNIGHT("Liquid Dark"), AMOLED("AMOLED Glass"), LIGHT("Frosted Light")
}

enum class GlassAccent(val label: String, val primary: Color, val secondary: Color, val glow: Color) {
    NEON_PURPLE("Neon Purple", Color(0xFFA855F7), Color(0xFF22D3EE), Color(0xFFF472B6)),
    CYBER_CYAN("Cyber Cyan", Color(0xFF06B6D4), Color(0xFF60A5FA), Color(0xFFA855F7)),
    ELECTRIC_BLUE("Electric Blue", Color(0xFF3B82F6), Color(0xFF22D3EE), Color(0xFFA855F7)),
    SOLAR_AMBER("Solar Amber", Color(0xFFF59E0B), Color(0xFFF472B6), Color(0xFFA855F7))
}

data class GlassThemeConfig(
    val mode: GlassThemeMode = GlassThemeMode.MIDNIGHT,
    val accent: GlassAccent = GlassAccent.NEON_PURPLE
)

val LocalGlassConfig = compositionLocalOf { GlassThemeConfig() }

object GlassPalette {
    val DeepNight = Color(0xFF06060D)
    val MidnightPurple = Color(0xFF120B26)
    val NeonPurple = Color(0xFFA855F7)
    val NeonMagenta = Color(0xFFF472B6)
    val ElectricBlue = Color(0xFF3B82F6)
    val CyberCyan = Color(0xFF22D3EE)
    val WarmAmber = Color(0xFFFBBF24)
    val StatusGood = Color(0xFF34D399)
    val StatusWarn = Color(0xFFFBBF24)
    val StatusDanger = Color(0xFFF87171)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextMuted = Color(0xFFC8D2E6)
}

@Composable
fun TweakoraGlassTheme(config: GlassThemeConfig = GlassThemeConfig(), content: @Composable () -> Unit) {
    val isLight = config.mode == GlassThemeMode.LIGHT
    val primaryText = if (isLight) Color(0xFF0F172A) else GlassPalette.TextPrimary
    val mutedText = if (isLight) Color(0xFF475569) else GlassPalette.TextMuted

    val colors = if (isLight) {
        lightColorScheme(
            primary = config.accent.primary, secondary = config.accent.secondary, tertiary = config.accent.glow,
            background = Color(0xFFECF0FA), surface = Color(0xD9FFFFFF), surfaceVariant = Color(0xB3FFFFFF),
            outline = Color(0x330F172A), onBackground = primaryText, onSurface = primaryText, onSurfaceVariant = mutedText
        )
    } else {
        darkColorScheme(
            primary = config.accent.primary, secondary = config.accent.secondary, tertiary = config.accent.glow,
            background = if (config.mode == GlassThemeMode.AMOLED) Color.Black else GlassPalette.DeepNight,
            surface = Color(0xFF12111D), surfaceVariant = Color(0x26FFFFFF), outline = Color(0x40FFFFFF),
            onBackground = primaryText, onSurface = primaryText, onSurfaceVariant = mutedText
        )
    }

    val typo = Typography(
        headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 30.sp, color = primaryText),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 16.5.sp, lineHeight = 22.sp, color = primaryText),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, color = primaryText),
        bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, lineHeight = 19.sp, color = mutedText),
        labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 1.1.sp)
    )

    CompositionLocalProvider(LocalGlassConfig provides config, LocalContentColor provides primaryText) {
        MaterialTheme(colorScheme = colors, typography = typo, content = content)
    }
}

@Composable
fun GlassBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val cfg = LocalGlassConfig.current
    val bgBrush = when (cfg.mode) {
        GlassThemeMode.MIDNIGHT -> Brush.verticalGradient(listOf(Color(0xFF150C2E), Color(0xFF090913), Color(0xFF04050A)))
        GlassThemeMode.AMOLED -> Brush.verticalGradient(listOf(Color(0xFF070510), Color.Black, Color.Black))
        GlassThemeMode.LIGHT -> Brush.verticalGradient(listOf(Color(0xFFE7EEFC), Color(0xFFF4F7FF), Color(0xFFE6EFFB)))
    }
    val alpha = when (cfg.mode) { GlassThemeMode.MIDNIGHT -> 0.9f; GlassThemeMode.AMOLED -> 0.45f; GlassThemeMode.LIGHT -> 0.4f }

    Box(modifier = modifier.fillMaxSize().background(bgBrush)) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(cfg.accent.primary.copy(alpha = 0.30f * alpha), Color.Transparent), Offset(size.width * 0.14f, size.height * 0.08f), size.width * 0.8f), size.width * 0.8f, Offset(size.width * 0.14f, size.height * 0.08f))
            drawCircle(Brush.radialGradient(listOf(cfg.accent.secondary.copy(alpha = 0.24f * alpha), Color.Transparent), Offset(size.width * 0.92f, size.height * 0.36f), size.width * 0.7f), size.width * 0.7f, Offset(size.width * 0.92f, size.height * 0.36f))
            drawCircle(Brush.radialGradient(listOf(cfg.accent.glow.copy(alpha = 0.20f * alpha), Color.Transparent), Offset(size.width * 0.18f, size.height * 0.82f), size.width * 0.68f), size.width * 0.68f, Offset(size.width * 0.18f, size.height * 0.82f))
        }
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    glowAccent: Color? = null,
    cornerRadius: Dp = 24.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cfg = LocalGlassConfig.current
    val isLight = cfg.mode == GlassThemeMode.LIGHT
    val shape = RoundedCornerShape(cornerRadius)
    val accent = glowAccent ?: cfg.accent.primary
    val fill = if (isLight) Brush.verticalGradient(listOf(Color.White.copy(0.78f), Color.White.copy(0.54f)))
    else Brush.verticalGradient(listOf(Color(0xFF28263A).copy(0.52f), Color(0xFF12111C).copy(0.44f)))
    val border = Brush.verticalGradient(listOf(Color.White.copy(if (isLight) 0.9f else 0.36f), accent.copy(0.20f), Color.White.copy(if (isLight) 0.45f else 0.14f)))

    Box(modifier.fillMaxWidth().clip(shape).background(fill).background(tint.copy(if (isLight) 0.04f else 0.05f)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Surface(Modifier.fillMaxWidth(), shape = shape, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, border)) {
            Column(Modifier.padding(18.dp), content = content)
        }
    }
}

@Composable
fun LiquidGlassNavBar(
    items: List<Triple<String, String, ImageVector>>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isLight = LocalGlassConfig.current.mode == GlassThemeMode.LIGHT
    val capsule = RoundedCornerShape(50)
    val bg = if (isLight) Brush.verticalGradient(listOf(Color(0xD6FFFFFF), Color(0xB8EEF2FA)))
    else Brush.verticalGradient(listOf(Color(0xB3272634), Color(0xCC0D0D15)))
    val rim = Brush.verticalGradient(listOf(Color.White.copy(if (isLight) 0.95f else 0.46f), Color.White.copy(if (isLight) 0.40f else 0.12f), Color.White.copy(if (isLight) 0.65f else 0.24f)))

    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box(Modifier.fillMaxWidth().clip(capsule).background(bg)) {
            Surface(Modifier.fillMaxWidth(), shape = capsule, color = Color.Transparent, border = BorderStroke(1.2.dp, rim)) {
                Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    items.forEach { (route, label, icon) ->
                        val selected = currentRoute == route
                        val itemTint = when {
                            selected && isLight -> Color(0xFF0F172A)
                            selected -> Color.White
                            isLight -> Color(0xFF64748B)
                            else -> Color(0xFFB8C2D8)
                        }
                        Column(
                            modifier = Modifier.weight(1f).clip(capsule)
                                .background(if (selected) (if (isLight) Color(0x1A0F172A) else Color(0x30FFFFFF)) else Color.Transparent)
                                .clickable { onSelect(route) }.padding(vertical = 8.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(icon, contentDescription = label, tint = itemTint, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(2.dp))
                            Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = itemTint, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GlassSegmentedTabs(items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cfg = LocalGlassConfig.current
    val isLight = cfg.mode == GlassThemeMode.LIGHT
    val shape = RoundedCornerShape(50)
    Surface(modifier.fillMaxWidth(), shape = shape, color = if (isLight) Color.White.copy(0.65f) else Color(0x73181726), border = BorderStroke(1.dp, Color.White.copy(if (isLight) 0.85f else 0.24f))) {
        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { idx, label ->
                val sel = idx == selectedIndex
                Box(
                    Modifier.weight(1f).clip(shape)
                        .background(if (sel) Brush.horizontalGradient(listOf(cfg.accent.primary, cfg.accent.secondary)) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
                        .clickable { onSelect(idx) }.padding(vertical = 9.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 12.5.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun GlassRingGauge(progress: Float, centerTitle: String, centerSubtitle: String, pillLabel: String, modifier: Modifier = Modifier, size: Dp = 136.dp) {
    val cfg = LocalGlassConfig.current
    val clamped = progress.coerceIn(0f, 1f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val strokePx = 8.5.dp.toPx()
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)
            drawArc(Color.White.copy(0.16f), 135f, 270f, false, topLeft, arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
            val sweep = 270f * clamped
            drawArc(Brush.sweepGradient(listOf(cfg.accent.secondary, cfg.accent.primary, GlassPalette.WarmAmber, cfg.accent.glow, cfg.accent.secondary)), 135f, sweep, false, topLeft, arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
            val rad = Math.toRadians((135f + sweep).toDouble())
            val r = arcSize.width / 2f
            val pt = Offset(this.size.width / 2f + (r * cos(rad)).toFloat(), this.size.height / 2f + (r * sin(rad)).toFloat())
            drawCircle(cfg.accent.secondary.copy(0.55f), 7.dp.toPx(), pt)
            drawCircle(Color.White, 3.4.dp.toPx(), pt)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTitle, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            Text(centerSubtitle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(cfg.accent.primary, cfg.accent.secondary))).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(pillLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun GlassActionBanner(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cfg = LocalGlassConfig.current
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.fillMaxWidth().clip(shape).background(Brush.horizontalGradient(listOf(cfg.accent.primary.copy(0.42f), cfg.accent.glow.copy(0.30f), GlassPalette.WarmAmber.copy(0.34f)))).clickable(onClick = onClick)) {
        Surface(Modifier.fillMaxWidth(), shape = shape, color = Color.Transparent, border = BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(0.45f), GlassPalette.WarmAmber.copy(0.35f))))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(0.18f)), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = title, tint = Color.White, modifier = Modifier.size(23.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = Color.White.copy(0.90f))
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.White)
            }
        }
    }
}

@Composable
fun GlassToolTile(title: String, subtitle: String, icon: ImageVector, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, tint = accent, glowAccent = accent, cornerRadius = 22.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(accent.copy(0.24f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun GlassWaveChart(values: List<Float>, lineColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        val pts = if (values.size >= 2) values else listOf(0.35f, 0.42f, 0.39f, 0.48f)
        val padY = 10.dp.toPx()
        for (i in 1..4) drawLine(Color.White.copy(0.10f), Offset(size.width * (i / 5f), 0f), Offset(size.width * (i / 5f), size.height), 1.dp.toPx())
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
        drawPath(fill, Brush.verticalGradient(listOf(lineColor.copy(0.34f), Color.Transparent)))
        drawPath(line, lineColor, style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(lineColor.copy(0.42f), 7.dp.toPx(), coords.last())
        drawCircle(Color.White, 3.5.dp.toPx(), coords.last())
    }
}

@Composable
fun GlassStatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(color.copy(0.22f)).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    }
}

@Composable
fun GlassPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, gradient: List<Color> = listOf(GlassPalette.ElectricBlue, GlassPalette.CyberCyan)) {
    Box(modifier.clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(gradient)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun GlassGaugeRow(label: String, value: String, progress: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(0.14f))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(color.copy(0.78f), color))))
        }
    }
}

@Composable
fun GlassSpecRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
// END OF GLASSUI
