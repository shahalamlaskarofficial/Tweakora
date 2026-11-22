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
    MIDNIGHT("Midnight Glass"),
    AMOLED("AMOLED Glass"),
    LIGHT("Frosted Light")
}

enum class GlassAccent(
    val label: String,
    val primary: Color,
    val secondary: Color,
    val glow: Color
) {
    NEON_PURPLE("Neon Purple", Color(0xFFA855F7), Color(0xFF06B6D4), Color(0xFFEC4899)),
    CYBER_CYAN("Cyber Cyan", Color(0xFF06B6D4), Color(0xFF3B82F6), Color(0xFFA855F7)),
    ELECTRIC_BLUE("Electric Blue", Color(0xFF3B82F6), Color(0xFF06B6D4), Color(0xFFA855F7)),
    SOLAR_AMBER("Solar Amber", Color(0xFFF59E0B), Color(0xFFEC4899), Color(0xFFA855F7))
}

data class GlassThemeConfig(
    val mode: GlassThemeMode = GlassThemeMode.MIDNIGHT,
    val accent: GlassAccent = GlassAccent.NEON_PURPLE
)

val LocalGlassConfig = compositionLocalOf { GlassThemeConfig() }

object GlassPalette {
    val DeepNight = Color(0xFF060510)
    val MidnightPurple = Color(0xFF140B2D)
    val NeonPurple = Color(0xFFA855F7)
    val NeonMagenta = Color(0xFFEC4899)
    val ElectricBlue = Color(0xFF3B82F6)
    val CyberCyan = Color(0xFF06B6D4)
    val WarmAmber = Color(0xFFF59E0B)
    val StatusGood = Color(0xFF22C55E)
    val StatusWarn = Color(0xFFF59E0B)
    val StatusDanger = Color(0xFFEF4444)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextMuted = Color(0xFFAEB9D4)
}

@Composable
fun TweakoraGlassTheme(
    config: GlassThemeConfig = GlassThemeConfig(),
    content: @Composable () -> Unit
) {
    val isLight = config.mode == GlassThemeMode.LIGHT
    val colors = if (isLight) {
        lightColorScheme(
            primary = config.accent.primary,
            secondary = config.accent.secondary,
            tertiary = config.accent.glow,
            background = Color(0xFFEDF1FA),
            surface = Color(0xCCFFFFFF),
            surfaceVariant = Color(0xAAFFFFFF),
            outline = Color(0x330F172A),
            onBackground = Color(0xFF0F172A),
            onSurface = Color(0xFF0F172A),
            onSurfaceVariant = Color(0xFF475569)
        )
    } else {
        darkColorScheme(
            primary = config.accent.primary,
            secondary = config.accent.secondary,
            tertiary = config.accent.glow,
            background = if (config.mode == GlassThemeMode.AMOLED) Color.Black else GlassPalette.DeepNight,
            surface = Color(0xFF100C22),
            surfaceVariant = Color(0x20FFFFFF),
            outline = Color(0x33FFFFFF),
            onBackground = GlassPalette.TextPrimary,
            onSurface = GlassPalette.TextPrimary,
            onSurfaceVariant = GlassPalette.TextMuted
        )
    }

    val typo = Typography(
        headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-0.3).sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        labelMedium = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.5.sp,
            letterSpacing = 1.0.sp
        ),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
    )

    CompositionLocalProvider(LocalGlassConfig provides config) {
        MaterialTheme(colorScheme = colors, typography = typo, content = content)
    }
}

@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val cfg = LocalGlassConfig.current
    val bgBrush = when (cfg.mode) {
        GlassThemeMode.MIDNIGHT -> Brush.verticalGradient(
            listOf(Color(0xFF180C36), Color(0xFF090717), Color(0xFF050712))
        )
        GlassThemeMode.AMOLED -> Brush.verticalGradient(
            listOf(Color(0xFF080412), Color(0xFF000000), Color(0xFF000000))
        )
        GlassThemeMode.LIGHT -> Brush.verticalGradient(
            listOf(Color(0xFFE9EEFC), Color(0xFFF3F6FF), Color(0xFFE7EFFA))
        )
    }
    val orbAlpha = when (cfg.mode) {
        GlassThemeMode.MIDNIGHT -> 1.0f
        GlassThemeMode.AMOLED -> 0.55f
        GlassThemeMode.LIGHT -> 0.45f
    }

    Box(modifier = modifier.fillMaxSize().background(bgBrush)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cfg.accent.primary.copy(alpha = 0.34f * orbAlpha), Color.Transparent),
                    center = Offset(size.width * 0.14f, size.height * 0.10f),
                    radius = size.width * 0.78f
                ),
                radius = size.width * 0.78f,
                center = Offset(size.width * 0.14f, size.height * 0.10f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cfg.accent.secondary.copy(alpha = 0.26f * orbAlpha), Color.Transparent),
                    center = Offset(size.width * 0.92f, size.height * 0.34f),
                    radius = size.width * 0.68f
                ),
                radius = size.width * 0.68f,
                center = Offset(size.width * 0.92f, size.height * 0.34f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cfg.accent.glow.copy(alpha = 0.24f * orbAlpha), Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 0.78f),
                    radius = size.width * 0.70f
                ),
                radius = size.width * 0.70f,
                center = Offset(size.width * 0.18f, size.height * 0.78f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(GlassPalette.WarmAmber.copy(alpha = 0.14f * orbAlpha), Color.Transparent),
                    center = Offset(size.width * 0.85f, size.height * 0.90f),
                    radius = size.width * 0.52f
                ),
                radius = size.width * 0.52f,
                center = Offset(size.width * 0.85f, size.height * 0.90f)
            )
        }
        content()
    }
}

/**
 * Refined translucent frosted glass card with luminous top-left rim and readable inner tint.
 */
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

    val fillBrush = if (isLight) {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.76f), Color.White.copy(alpha = 0.54f))
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xFF1F1B38).copy(alpha = 0.58f),
                Color(0xFF110E22).copy(alpha = 0.46f)
            )
        )
    }

    val tintOverlay = Brush.linearGradient(
        listOf(
            tint.copy(alpha = if (isLight) 0.08f else 0.11f),
            Color.Transparent,
            accent.copy(alpha = if (isLight) 0.06f else 0.08f)
        )
    )

    val borderBrush = Brush.linearGradient(
        listOf(
            (if (isLight) Color.White else Color.White).copy(alpha = if (isLight) 0.85f else 0.28f),
            accent.copy(alpha = 0.22f),
            cfg.accent.secondary.copy(alpha = 0.24f)
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fillBrush)
            .background(tintOverlay)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, borderBrush)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                content = content
            )
        }
    }
}

/**
 * Sleek segmented glass pill bar for switching sub-sections conveniently with one thumb.
 */
@Composable
fun GlassSegmentedTabs(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val cfg = LocalGlassConfig.current
    val isLight = cfg.mode == GlassThemeMode.LIGHT
    val shape = RoundedCornerShape(50)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = if (isLight) Color.White.copy(alpha = 0.55f) else Color(0x66120D26),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (isLight) 0.7f else 0.16f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEachIndexed { idx, label ->
                val selected = idx == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(
                            if (selected) {
                                Brush.horizontalGradient(listOf(cfg.accent.primary, cfg.accent.secondary))
                            } else {
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                            }
                        )
                        .clickable { onSelect(idx) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 11.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun GlassRingGauge(
    progress: Float,
    centerTitle: String,
    centerSubtitle: String,
    pillLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp
) {
    val cfg = LocalGlassConfig.current
    val clamped = progress.coerceIn(0f, 1f)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val strokePx = 8.dp.toPx()
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)

            drawArc(
                color = Color.White.copy(alpha = 0.14f),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            val sweep = 270f * clamped
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        cfg.accent.secondary,
                        cfg.accent.primary,
                        GlassPalette.WarmAmber,
                        cfg.accent.glow,
                        cfg.accent.secondary
                    )
                ),
                startAngle = 135f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            val angleRad = Math.toRadians((135f + sweep).toDouble())
            val radius = arcSize.width / 2f
            val cx = this.size.width / 2f + (radius * cos(angleRad)).toFloat()
            val cy = this.size.height / 2f + (radius * sin(angleRad)).toFloat()
            drawCircle(color = cfg.accent.secondary.copy(alpha = 0.55f), radius = 7.dp.toPx(), center = Offset(cx, cy))
            drawCircle(color = Color.White, radius = 3.2.dp.toPx(), center = Offset(cx, cy))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerTitle,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = centerSubtitle,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(listOf(cfg.accent.primary, cfg.accent.secondary))
                )
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text(
                text = pillLabel,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color.White
            )
        }
    }
}

@Composable
fun GlassActionBanner(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cfg = LocalGlassConfig.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        cfg.accent.primary.copy(alpha = 0.38f),
                        cfg.accent.glow.copy(alpha = 0.30f),
                        GlassPalette.WarmAmber.copy(alpha = 0.32f)
                    )
                )
            )
            .clickable(onClick = onClick)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = Color.Transparent,
            border = BorderStroke(
                1.dp,
                Brush.horizontalGradient(
                    listOf(Color.White.copy(alpha = 0.36f), GlassPalette.WarmAmber.copy(alpha = 0.55f))
                )
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = title, tint = Color.White, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.84f))
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = GlassPalette.WarmAmber)
            }
        }
    }
}

@Composable
fun GlassToolTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        tint = accent,
        glowAccent = accent,
        cornerRadius = 20.dp,
        onClick = onClick
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 13.5.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun GlassWaveChart(
    values: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(94.dp)
    ) {
        val points = if (values.size >= 2) values else listOf(0.35f, 0.42f, 0.39f, 0.48f)
        val w = size.width
        val h = size.height
        val padY = 10.dp.toPx()

        for (i in 1..4) {
            val gx = w * (i / 5f)
            drawLine(
                color = Color.White.copy(alpha = 0.08f),
                start = Offset(gx, 0f),
                end = Offset(gx, h),
                strokeWidth = 1.dp.toPx()
            )
        }

        val stepX = w / (points.size - 1).coerceAtLeast(1)
        val coords = points.mapIndexed { idx, v ->
            val clamped = v.coerceIn(0f, 1f)
            Offset(idx * stepX, h - padY - clamped * (h - padY * 2f))
        }

        val linePath = Path().apply {
            moveTo(coords.first().x, coords.first().y)
            for (i in 0 until coords.size - 1) {
                val p0 = coords[i]
                val p1 = coords[i + 1]
                val cx = (p0.x + p1.x) / 2f
                cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
            }
        }

        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(coords.last().x, h)
            lineTo(coords.first().x, h)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.32f), Color.Transparent))
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
        )

        val last = coords.last()
        drawCircle(color = lineColor.copy(alpha = 0.38f), radius = 7.dp.toPx(), center = last)
        drawCircle(color = Color.White, radius = 3.dp.toPx(), center = last)
    }
}

@Composable
fun GlassStatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.20f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun GlassPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: List<Color> = listOf(GlassPalette.ElectricBlue, GlassPalette.CyberCyan)
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(gradient))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun GlassGaugeRow(label: String, value: String, progress: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.72f), color)))
            )
        }
    }
}

@Composable
fun GlassSpecRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
