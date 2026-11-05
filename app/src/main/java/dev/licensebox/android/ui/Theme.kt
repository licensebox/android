package dev.licensebox.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.licensebox.android.R

// The console's look (licensebox.dev's site.css): paper and ink, one blue,
// IBM Plex Sans.

val Plex = FontFamily(
    Font(R.font.plex_sans_400, FontWeight.Normal),
    Font(R.font.plex_sans_500, FontWeight.Medium),
    Font(R.font.plex_sans_600, FontWeight.SemiBold),
    Font(R.font.plex_sans_700, FontWeight.Bold),
)
val PlexMono = FontFamily(Font(R.font.plex_mono_400, FontWeight.Normal))

private val Blue = Color(0xFF1F45D8)

private val Light = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E9FB),
    onPrimaryContainer = Color(0xFF0E2477),
    secondary = Color(0xFF333333),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECECEC),
    onSecondaryContainer = Color(0xFF2B2B2B),
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF2B2B2B),
    surface = Color(0xFFF5F5F5),
    onSurface = Color(0xFF2B2B2B),
    surfaceVariant = Color(0xFFF3F3F3),
    onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFECECEC),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE2E2E2),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFBE4E2),
    onErrorContainer = Color(0xFF5F120D),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DB0FF),
    onPrimary = Color(0xFF0E2477),
    primaryContainer = Color(0xFF233A99),
    onPrimaryContainer = Color(0xFFE4E9FB),
    secondary = Color(0xFFD6D6D6),
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = Color(0xFF2E2E2E),
    onSecondaryContainer = Color(0xFFE6E6E6),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceContainerLowest = Color(0xFF1B1B1B),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF222222),
    surfaceContainerHighest = Color(0xFF2A2A2A),
    outline = Color(0xFF5A5A5A),
    outlineVariant = Color(0xFF2E2E2E),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5F120D),
    onErrorContainer = Color(0xFFFBE4E2),
)

/** Colours the console uses beyond Material's: for module kinds and states. */
@Immutable
data class Extra(val free: Color, val trial: Color, val hidden: Color, val warn: Color, val good: Color)

val LocalExtra = staticCompositionLocalOf { Extra(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified) }

private val LightExtra = Extra(free = Color(0xFF1B7F3B), trial = Color(0xFF8A5A00), hidden = Color(0xFF6B6B6B), warn = Color(0xFFB3261E), good = Color(0xFF1B7F3B))
private val DarkExtra = Extra(free = Color(0xFF7FD69A), trial = Color(0xFFF2C46B), hidden = Color(0xFF9E9E9E), warn = Color(0xFFFFB4AB), good = Color(0xFF7FD69A))

private fun type(): Typography {
    val t = Typography()
    fun TextStyle.plex() = copy(fontFamily = Plex)
    // Sizes kept a step smaller than Material's: a square phone screen is
    // about 480dp a side, and a list should show a screenful.
    return Typography(
        displayLarge = t.displayLarge.plex(),
        displayMedium = t.displayMedium.plex(),
        displaySmall = t.displaySmall.plex(),
        headlineLarge = t.headlineLarge.plex(),
        headlineMedium = t.headlineMedium.plex(),
        headlineSmall = t.headlineSmall.plex().copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.plex().copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.plex().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.plex().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.plex().copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodyMedium = t.bodyMedium.plex(),
        bodySmall = t.bodySmall.plex(),
        labelLarge = t.labelLarge.plex().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.plex(),
        labelSmall = t.labelSmall.plex(),
    )
}

@Composable
fun LicenseboxTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors: ColorScheme = if (dark) Dark else Light
    androidx.compose.runtime.CompositionLocalProvider(LocalExtra provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = colors,
            typography = type(),
            shapes = Shapes(
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(20.dp),
            ),
            content = content,
        )
    }
}
