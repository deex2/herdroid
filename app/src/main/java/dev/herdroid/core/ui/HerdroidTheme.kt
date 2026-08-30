package dev.herdroid.core.ui

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.herdroid.R

object HerdrColors {
    val Background = Color(0xff181825)
    val SurfaceDim = Color(0xff1e1e2e)
    val Selected = Color(0xff313244)
    val Elevated = Color(0xff45475a)
    val Text = Color(0xffcdd6f4)
    val Subtext = Color(0xffa6adc8)
    val Muted = Color(0xff6c7086)
    val Blue = Color(0xff89b4fa)
    val Mauve = Color(0xffcba6f7)
    val Green = Color(0xffa6e3a1)
    val Yellow = Color(0xfff9e2af)
    val Red = Color(0xfff38ba8)
    val Teal = Color(0xff94e2d5)
    val Ink = Color(0xff11111b)
}

fun herdrDarkColorScheme(action: Color): ColorScheme = darkColorScheme(
    primary = action,
    onPrimary = HerdrColors.Ink,
    primaryContainer = action,
    onPrimaryContainer = HerdrColors.Ink,
    secondary = HerdrColors.Mauve,
    onSecondary = HerdrColors.Ink,
    tertiary = HerdrColors.Teal,
    onTertiary = HerdrColors.Ink,
    background = HerdrColors.Background,
    onBackground = HerdrColors.Text,
    surface = HerdrColors.Background,
    onSurface = HerdrColors.Text,
    surfaceVariant = HerdrColors.Selected,
    onSurfaceVariant = HerdrColors.Subtext,
    surfaceContainer = HerdrColors.SurfaceDim,
    surfaceContainerHigh = HerdrColors.Selected,
    outline = HerdrColors.Elevated,
    outlineVariant = HerdrColors.Selected,
    error = HerdrColors.Red,
    onError = HerdrColors.Ink,
    errorContainer = HerdrColors.Red,
    onErrorContainer = HerdrColors.Ink,
)

val HerdrFontFamily = FontFamily(
    Font(R.font.cascadia_mono, FontWeight.Normal),
    Font(R.font.cascadia_mono, FontWeight.Bold),
)

private fun herdrText(size: Int, bold: Boolean = false) = TextStyle(
    fontFamily = HerdrFontFamily,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = (size + 5).sp,
)

private val HerdrTypography = Typography(
    displayLarge = herdrText(24, true),
    displayMedium = herdrText(24, true),
    displaySmall = herdrText(24, true),
    headlineLarge = herdrText(20, true),
    headlineMedium = herdrText(20, true),
    headlineSmall = herdrText(20, true),
    titleLarge = herdrText(24, true),
    titleMedium = herdrText(16, true),
    titleSmall = herdrText(14, true),
    bodyLarge = herdrText(15),
    bodyMedium = herdrText(13),
    bodySmall = herdrText(12),
    labelLarge = herdrText(13, true),
    labelMedium = herdrText(12, true),
    labelSmall = herdrText(12),
)

private val HerdrShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

@Composable
fun HerdroidTheme(content: @Composable () -> Unit) {
    val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current).primary
    } else {
        HerdrColors.Blue
    }
    MaterialTheme(
        colorScheme = herdrDarkColorScheme(action),
        typography = HerdrTypography,
        shapes = HerdrShapes,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}

@Composable
fun HerdrPanel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(12.dp),
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

@Composable
fun HerdrSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.lowercase(),
        modifier = modifier,
        color = HerdrColors.Mauve,
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 0.8.sp,
    )
}

@Composable
fun HerdrStatusChip(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}
