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
    MIDNIGHT("Liquid Dark"),
    AMOLED("AMOLED Glass"),
    LIGHT("Frosted Light")
}

enum class GlassAccent(
    val label: String,
    val primary: Color,
    val secondary: Color,
    val glow: Color
) {
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
fun TweakoraGlassTheme(
    config: GlassThemeConfig = GlassThemeConfig(),
    content: @Composable () -> Unit
) {
    val isLight = config.mode == GlassThemeMode.LIGHT
    val primaryText = if (isLight) Color(0xFF0F172A) else GlassPalette.TextPrimary
    val mutedText = if (isLight) Color(0xFF475569) else GlassPalette.TextMuted

    val colors = if (isLight) {
        lightColorScheme(
            primary = config.accent.primary,
            secondary = config.accent.secondary,
            tertiary = config.accent.glow,
            background = Color(0xFFECF0FA),
            surface = Color(0xD9FFFFFF),
            surfaceVariant = Color(0xB3FFFFFF),
            outline = Color(0x330F172A),
            onBackground = primaryText,
            onSurface = primaryText,
            onSurfaceVariant = mutedText
        )
    } else {
        darkColorScheme(
            primary = config.accent.primary,
            secondary = config.accent.secondary,
            tertiary = config.accent.glow,
            background = if (config.mode == GlassThemeMode.AMOLED) Color.Black else GlassPalette.DeepNight,
            surface = Color(0xFF12111D),
            surfaceVariant = Color(0x26FFFFFF),
            outline = Color(0x40FFFFFF),
            onBackground = primaryText,
            onSurface = primaryText,
            onSurfaceVariant = mutedText
        )
    }

    val typo = Typography(
        headlineSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            letterSpacing = (-0.4).sp,
            color = primaryText
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 16.5.sp,
            lineHeight = 22.sp,
            color = primaryText
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = primaryText
        ),
        bodySmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            color = mutedText
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 11.5.sp,
            letterSpacing = 1.1.sp
        )
    )

    CompositionLocalProvider(
        LocalGlassConfig provides config,
        LocalContentColor provides primaryText
    ) {
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
            listOf(Color(0xFF150C2E), Color(0xFF090913), Color(0xFF04050A))
        )
        GlassThemeMode.AMOLED -> Brush.verticalGradient(
            listOf(Color(0xFF070510), Color(0xFF000000), Color(0xFF000000))
        )
        GlassThemeMode.LIGHT -> Brush.verticalGradient(
            listOf(Color(0xFFE7EEFC), Color(0xFFF4F7FF), Color(0xFFE6EFFB))
        )
    }
    val orbAlpha = when (cfg.mode) {
        GlassThemeMode.MIDNIGHT -> 0.92f
        GlassThemeMode.AMOLED -> 0.45f
        GlassThemeMode.LIGHT -> 0.40f
    }

    Box(modifier = modifier.fillMaxSize().background(bgBrush)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cfg.accent.primary.copy(alpha = 0.30f * orbAlpha), Color.Transparent),
                    center = Offset(size.width * 0.14f, size.height * 0.08f),
                    radius = size.width * 0.80f
                ),
                radius = size.width * 0.80f,
                center = Offset(size.width * 0.14f, size.height * 0.08f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cfg.accent.secondary.copy(alpha = 0.24f * orbAlpha), Color.Transparent),
                    center = Offset(size.width * 0.92f, size.height * 0.36f),
                    radius = size.width * 0.70f
                ),
                radius = size.width * 0.70f,
                center = Offset(size.width * 0.92f, size.height * 0.36f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cfg.accent.glow.copy(alpha = 0.20f * orbAlpha), Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 0.82f),
                    radius = size.width * 0.68f
                ),
                radius = size.width * 0.68f,
                center = Offset(size.width * 0.18f, size.height * 0.82f)
            )
        }
        content()
    }
}

/**
 * Liquid Glass card: smoky translucent frost with luminous white top specular edge.
 */
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

    val fillBrush = if (isLight) {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.78f), Color.White.copy(alpha = 0.52f))
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xFF28263A).copy(alpha = 0.50f),
                Color(0xFF12111C).copy(alpha = 0.42f)
            )
        )
    }

    val specularSheen = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (isLight) 0.40f else 0.10f),
            Color.Transparent,
            tint.copy(alpha = if (isLight) 0.04f else 0.05f)
        )
    )

    val borderBrush = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (isLight) 0.92f else 0.36f),
            accent.copy(alpha = 0.18f),
            Color.White.copy(alpha = if (isLight) 0.45f else 0.14f)
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fillBrush)
            .background(specularSheen)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, borderBrush)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                content = content
            )
        }
    }
}

/**
 * Floating Liquid Glass Capsule Bottom Navigation Bar (content scrolls visibly underneath).
 */
@Composable
fun LiquidGlassNavBar(
    items: List<Triple<String, String, ImageVector>>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val cfg = LocalGlassConfig.current
    val isLight = cfg.mode == GlassThemeMode.LIGHT
    val capsuleShape = RoundedCornerShape(50)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(capsuleShape)
                .background(
                    if (isLight) {
                        Brush.verticalGradient(listOf(Color(0xD6FFFFFF), Color(0xB8EEF2FA)))
                    } else {
                        Brush.verticalGradient(listOf(Color(0xB3272634), Color(0xCC0D0D15)))
                    }
                )
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = capsuleShape,
                color = Color.Transparent,
                border = BorderStroke(
                    1.2.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (isLight) 0.95f else 0.46f),
                            Color.White.copy(alpha = if (isLight) 0.40f else 0.12f),
                            Color.White.copy(alpha = if (isLight) 0.65f else 0.24f)
                        )
                    )
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEach { (route, label, icon) ->
                        val selected = currentRoute == route
                        val pillModifier = if (selected) {
                            Modifier
                                .weight(1f)
                                .clip(capsuleShape)
                                .background(
                                    if (isLight) Color(0xFF0F172A).copy(alpha = 0.10f)
                                    else Color.White.copy(alpha = 0.19f)
                                )
                        } else {
                            Modifier.weight(1f).clip(capsuleShape)
                        }

                        Column(
                            modifier = pillModifier
                                .clickable { onSelect(route) }
                                .padding(vertical = 8.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = when {
                                    selected && isLight -> Color(0xFF0F172A)
                                    selected -> Color.White
                                    isLight -> Color(0xFF64748B)
                                    else -> Color(0xFFB8C2D8)
                                },
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = when {
                                    selected && isLight -> Color(0xFF0F172A)
                                    selected -> Color.White
                                    isLight -> Color(0xFF64748B)
                                    else -> Color(0xFFB8C2D8)
                                },
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GlassSegmentedTabs(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val cfg = LocalGlassConfig.current
    val isLight = cfg.mode == GlassThemeMode.LIGHT
