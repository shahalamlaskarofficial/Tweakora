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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

object GlassPalette {
    val DeepNight = Color(0xFF070612)
    val MidnightPurple = Color(0xFF130B29)
    val NeonPurple = Color(0xFFA855F7)
    val NeonMagenta = Color(0xFFEC4899)
    val ElectricBlue = Color(0xFF3B82F6)
    val CyberCyan = Color(0xFF06B6D4)
    val WarmAmber = Color(0xFFF59E0B)
    val StatusGood = Color(0xFF22C55E)
    val StatusWarn = Color(0xFFF59E0B)
    val StatusDanger = Color(0xFFEF4444)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextMuted = Color(0xFFA3AEC6)
}

@Composable
fun TweakoraGlassTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = GlassPalette.NeonPurple,
        secondary = GlassPalette.ElectricBlue,
        tertiary = GlassPalette.CyberCyan,
        background = GlassPalette.DeepNight,
        surface = Color(0xFF0E0B1E),
        surfaceVariant = Color(0x22FFFFFF),
        outline = Color(0x33FFFFFF),
        onBackground = GlassPalette.TextPrimary,
        onSurface = GlassPalette.TextPrimary,
        onSurfaceVariant = GlassPalette.TextMuted
    )
    val typo = Typography(
        headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, color = GlassPalette.TextPrimary),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = GlassPalette.TextPrimary),
        labelMedium = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 0.9.sp
        ),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = GlassPalette.TextMuted)
    )
    MaterialTheme(colorScheme = colors, typography = typo, content = content)
}

/**
 * Deep midnight ambient canvas with glowing radial glassmorphism orbs behind translucent cards.
 */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(GlassPalette.MidnightPurple, GlassPalette.DeepNight, Color(0xFF050711))
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(GlassPalette.NeonPurple.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(size.width * 0.12f, size.height * 0.14f),
                    radius = size.width * 0.72f
                ),
                radius = size.width * 0.72f,
                center = Offset(size.width * 0.12f, size.height * 0.14f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(GlassPalette.CyberCyan.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.90f, size.height * 0.38f),
                    radius = size.width * 0.65f
                ),
                radius = size.width * 0.65f,
                center = Offset(size.width * 0.90f, size.height * 0.38f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(GlassPalette.NeonMagenta.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(size.width * 0.25f, size.height * 0.82f),
                    radius = size.width * 0.68f
                ),
                radius = size.width * 0.68f,
                center = Offset(size.width * 0.25f, size.height * 0.82f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(GlassPalette.WarmAmber.copy(alpha = 0.13f), Color.Transparent),
                    center = Offset(size.width * 0.82f, size.height * 0.88f),
                    radius = size.width * 0.55f
                ),
                radius = size.width * 0.55f,
                center = Offset(size.width * 0.82f, size.height * 0.88f)
            )
        }
        content()
    }
}

/**
 * Translucent frosted glass container with luminous top-left specular border.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    glowAccent: Color? = null,
    cornerRadius: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val fillBrush = Brush.linearGradient(
        colors = listOf(
            tint.copy(alpha = 0.12f),
            tint.copy(alpha = 0.05f),
            (glowAccent ?: GlassPalette.NeonPurple).copy(alpha = 0.06f)
        )
    )
    val borderBrush = Brush.linearGradient(
        colors = listOf(
            (glowAccent ?: Color.White).copy(alpha = 0.36f),
            Color.White.copy(alpha = 0.10f),
            GlassPalette.CyberCyan.copy(alpha = 0.22f)
        )
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fillBrush)
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
 * Circular luminous ring gauge with gradient sweep & bottom percentage pill.
 */
@Composable
fun GlassRingGauge(
    progress: Float,
    centerTitle: String,
    centerSubtitle: String,
    pillLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = 128.dp
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val strokePx = 7.dp.toPx()
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)

            // Outer translucent track
            drawArc(
                color = Color.White.copy(alpha = 0.12f),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            // Gradient progress arc
            val sweep = 270f * clamped
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        GlassPalette.CyberCyan,
                        GlassPalette.NeonPurple,
                        GlassPalette.WarmAmber,
                        GlassPalette.NeonMagenta,
                        GlassPalette.CyberCyan
                    )
                ),
                startAngle = 135f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            // Glowing indicator dot at arc tip
            val angleRad = Math.toRadians((135f + sweep).toDouble())
            val radius = arcSize.width / 2f
            val cx = this.size.width / 2f + (radius * cos(angleRad)).toFloat()
            val cy = this.size.height / 2f + (radius * sin(angleRad)).toFloat()
            drawCircle(color = Color.White.copy(alpha = 0.45f), radius = 6.dp.toPx(), center = Offset(cx, cy))
            drawCircle(color = Color.White, radius = 3.dp.toPx(), center = Offset(cx, cy))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerTitle,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = GlassPalette.TextPrimary
            )
            Text(
                text = centerSubtitle,
                fontSize = 10.sp,
                color = GlassPalette.TextMuted
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(
                        listOf(GlassPalette.ElectricBlue.copy(alpha = 0.85f), GlassPalette.CyberCyan.copy(alpha = 0.85f))
                    )
                )
                .padding(horizontal = 10.dp, vertical = 2.dp)
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

/**
 * Translucent warm-magenta-to-amber glass action banner with chevron arrow.
 */
@Composable
fun GlassActionBanner(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        GlassPalette.NeonPurple.copy(alpha = 0.30f),
                        GlassPalette.NeonMagenta.copy(alpha = 0.24f),
                        GlassPalette.WarmAmber.copy(alpha = 0.28f)
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
                    listOf(Color.White.copy(alpha = 0.30f), GlassPalette.WarmAmber.copy(alpha = 0.45f))
                )
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = title, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.78f))
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = GlassPalette.WarmAmber)
            }
        }
    }
}

/**
 * Compact tinted translucent glass tile for dashboard tool shortcuts.
 */
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
        cornerRadius = 18.dp,
        onClick = onClick
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontSize = 14.sp, maxLines = 1)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

/**
 * Smooth glowing wave spline chart for live RAM / Thermal / Battery telemetry.
 */
@Composable
fun GlassWaveChart(
    values: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(92.dp)
    ) {
        val points = if (values.size >= 2) values else listOf(0.4f, 0.45f, 0.42f, 0.5f)
        val w = size.width
        val h = size.height
        val padY = 10.dp.toPx()

        // Vertical dashed/faint glass gridlines
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
            brush = Brush.verticalGradient(
                listOf(lineColor.copy(alpha = 0.28f), Color.Transparent)
            )
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        val last = coords.last()
        drawCircle(color = lineColor.copy(alpha = 0.35f), radius = 7.dp.toPx(), center = last)
        drawCircle(color = Color.White, radius = 3.dp.toPx(), center = last)
    }
}

@Composable
fun GlassStatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
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
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
fun GlassGaugeRow(label: String, value: String, progress: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = GlassPalette.TextPrimary)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.10f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color))
                    )
            )
        }
    }
}
