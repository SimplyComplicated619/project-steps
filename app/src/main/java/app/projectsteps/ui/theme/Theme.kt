package app.projectsteps.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.projectsteps.data.ThemeMode

private val Blue = Color(0xFF1D4ED8)
private val BlueLight = Color(0xFF93C5FD)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0A1F5C),
    secondary = Color(0xFF475569),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFF7F9FC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F4F9),
    surfaceContainer = Color(0xFFEBEFF5),
    surfaceContainerHigh = Color(0xFFE4E9F0),
    surfaceContainerHighest = Color(0xFFDDE3EB),
    outline = Color(0xFF6B7280),
    outlineVariant = Color(0xFFCBD2DC),
    error = Color(0xFFB91C1C),
)

/** Standard dark: deep grey surfaces. */
private val DarkColors = darkColorScheme(
    primary = BlueLight,
    onPrimary = Color(0xFF0A1F5C),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFCBD5E1),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF181B21),
    surfaceContainer = Color(0xFF1D2027),
    surfaceContainerHigh = Color(0xFF262A32),
    surfaceContainerHighest = Color(0xFF30343D),
    outline = Color(0xFF9CA3AF),
    outlineVariant = Color(0xFF3A3F49),
    error = Color(0xFFF87171),
)

/**
 * AMOLED dark: true black background (pixels off on the S23 Ultra's OLED panel), but cards and
 * sections keep slightly raised, distinguishable surfaces so hierarchy is not lost.
 */
private val AmoledColors = DarkColors.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0E0F12),
    surfaceContainer = Color(0xFF15171B),
    surfaceContainerHigh = Color(0xFF1D2025),
    surfaceContainerHighest = Color(0xFF262A30),
    outlineVariant = Color(0xFF34383F),
)

/** Status colours. Always paired with a text label: colour is never the only signal. */
@Immutable
data class StatusColors(
    val overdue: Color,
    val done: Color,
    val blocked: Color,
    val approval: Color,
    val destructive: Color,
    val waiting: Color,
    val dueToday: Color,
)

private val LightStatus = StatusColors(
    overdue = Color(0xFFB91C1C), done = Color(0xFF15803D), blocked = Color(0xFFB45309),
    approval = Color(0xFF7E22CE), destructive = Color(0xFFB91C1C), waiting = Color(0xFF0369A1), dueToday = Color(0xFF1D4ED8),
)
private val DarkStatus = StatusColors(
    overdue = Color(0xFFF87171), done = Color(0xFF4ADE80), blocked = Color(0xFFFBBF24),
    approval = Color(0xFFD8B4FE), destructive = Color(0xFFF87171), waiting = Color(0xFF7DD3FC), dueToday = Color(0xFF93C5FD),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun ProjectStepsTheme(
    mode: ThemeMode,
    amoled: Boolean,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    var scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark && amoled -> AmoledColors
        dark -> DarkColors
        else -> LightColors
    }
    if (dark && amoled && dynamicColor) {
        scheme = scheme.copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
