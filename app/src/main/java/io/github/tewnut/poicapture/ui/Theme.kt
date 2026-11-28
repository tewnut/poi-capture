package io.github.tewnut.poicapture.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.tewnut.poicapture.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Be Vietnam Pro (SIL OFL 1.1, res/font): made for Vietnamese diacritics, close to the VMAP wordmark's geometric style. */
val AppFont = FontFamily(
    Font(R.font.be_vietnam_pro_regular, FontWeight.Normal),
    Font(R.font.be_vietnam_pro_medium, FontWeight.Medium),
    Font(R.font.be_vietnam_pro_semibold, FontWeight.SemiBold),
    Font(R.font.be_vietnam_pro_bold, FontWeight.Bold),
    Font(R.font.be_vietnam_pro_extrabold, FontWeight.ExtraBold),
)

private fun Typography.withFont(f: FontFamily) = Typography(
    displayLarge.copy(fontFamily = f), displayMedium.copy(fontFamily = f), displaySmall.copy(fontFamily = f),
    headlineLarge.copy(fontFamily = f), headlineMedium.copy(fontFamily = f), headlineSmall.copy(fontFamily = f),
    titleLarge.copy(fontFamily = f), titleMedium.copy(fontFamily = f), titleSmall.copy(fontFamily = f),
    bodyLarge.copy(fontFamily = f), bodyMedium.copy(fontFamily = f), bodySmall.copy(fontFamily = f),
    labelLarge.copy(fontFamily = f), labelMedium.copy(fontFamily = f), labelSmall.copy(fontFamily = f),
)

/**
 * The app's look: VMAP's black and lime (primary buttons black with lime text, as the brand's dark lockup) on a soft
 * lime-grey canvas, white cards with a hairline border rather
 * than shadows (they read better in sunlight on a handlebar), rounder corners, and tighter, bolder titles.
 */
@Composable
fun PoiTheme(content: @Composable () -> Unit) {
    val base = Typography().withFont(AppFont)
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Black, onPrimary = Palette.Lime,
            primaryContainer = Palette.GreenLight, onPrimaryContainer = Color(0xFF1E3600),
            secondary = Palette.Green, onSecondary = Color.White,
            secondaryContainer = Palette.GreenLight, onSecondaryContainer = Color(0xFF1E3600),
            tertiary = Palette.Blue,
            background = Palette.Canvas, onBackground = Palette.Ink,
            surface = Color.White, onSurface = Palette.Ink,
            surfaceVariant = Color(0xFFEFF2E8), onSurfaceVariant = Palette.Muted,
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF9FAF6),
            surfaceContainer = Color(0xFFF3F5EE), surfaceContainerHigh = Color(0xFFEDF0E6),
            surfaceContainerHighest = Color(0xFFE7EBDF),
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
val ScreenTitle = TextStyle(fontFamily = AppFont, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp, color = Palette.Ink)
