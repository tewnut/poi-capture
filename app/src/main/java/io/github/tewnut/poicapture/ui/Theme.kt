package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's look: the street-green of the logo on a soft green-grey canvas, white cards with a hairline border rather
 * than shadows (they read better in sunlight on a handlebar), rounder corners, and tighter, bolder titles.
 */
@Composable
fun PoiTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Green, onPrimary = Color.White,
            primaryContainer = Palette.GreenLight, onPrimaryContainer = Color(0xFF053D2B),
            secondary = Color(0xFF35B385), onSecondary = Color.White,
            secondaryContainer = Palette.GreenLight, onSecondaryContainer = Color(0xFF053D2B),
            tertiary = Palette.Blue,
            background = Palette.Canvas, onBackground = Palette.Ink,
            surface = Color.White, onSurface = Palette.Ink,
            surfaceVariant = Color(0xFFEEF2F0), onSurfaceVariant = Palette.Muted,
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FAF9),
            surfaceContainer = Color(0xFFF2F5F3), surfaceContainerHigh = Color(0xFFECF0EE),
            surfaceContainerHighest = Color(0xFFE6EBE8),
            outline = Color(0xFFD0D5DD), outlineVariant = Palette.Line,
            error = Palette.Red,
        ),
        typography = base.copy(
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
            bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

/** A screen title's style: large, bold, slightly tightened. */
val ScreenTitle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp, color = Palette.Ink)
