package com.ghidroid.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.ghidroid.data.ThemeMode

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410001),
    secondary = Color(0xFF775651),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD5),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF705C2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBDFA6),
    onTertiaryContainer = Color(0xFF251A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    outline = Color(0xFF857371),
    outlineVariant = Color(0xFFD8C2BF),
    inverseSurface = Color(0xFF392E2C),
    inverseOnSurface = Color(0xFFFFEDEA),
    inversePrimary = Color(0xFFFFB4A9),
    surfaceDim = Color(0xFFE8D6D3),
    surfaceBright = Color(0xFFFFF8F6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0EE),
    surfaceContainer = Color(0xFFFCEAE7),
    surfaceContainerHigh = Color(0xFFF6E4E1),
    surfaceContainerHighest = Color(0xFFF1DEDC),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB4A9),
    onPrimary = Color(0xFF690003),
    primaryContainer = Color(0xFF8C1D18),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFE7BDB6),
    onSecondary = Color(0xFF442925),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFFDEC48C),
    onTertiary = Color(0xFF3E2E04),
    tertiaryContainer = Color(0xFF564419),
    onTertiaryContainer = Color(0xFFFBDFA6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1110),
    onBackground = Color(0xFFF1DEDC),
    surface = Color(0xFF1A1110),
    onSurface = Color(0xFFF1DEDC),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BF),
    outline = Color(0xFFA08C8A),
    outlineVariant = Color(0xFF534341),
    inverseSurface = Color(0xFFF1DEDC),
    inverseOnSurface = Color(0xFF392E2C),
    inversePrimary = Color(0xFFB3261E),
    surfaceDim = Color(0xFF1A1110),
    surfaceBright = Color(0xFF423735),
    surfaceContainerLowest = Color(0xFF140C0B),
    surfaceContainerLow = Color(0xFF231918),
    surfaceContainer = Color(0xFF271D1C),
    surfaceContainerHigh = Color(0xFF322826),
    surfaceContainerHighest = Color(0xFF3D3231),
)

/** Syntax colours for listings and decompiled C; fixed hues so code reads the same with any wallpaper. */
@Immutable
data class CodeColors(
    val keyword: Color,
    val comment: Color,
    val type: Color,
    val function: Color,
    val variable: Color,
    val constant: Color,
    val parameter: Color,
    val global: Color,
    val error: Color,
    val special: Color,
    val address: Color,
    val bytes: Color,
    val mnemonic: Color,
    val call: Color,
    val jump: Color,
    val ret: Color,
    val register: Color,
    val string: Color,
    val highlight: Color,
    val currentLine: Color,
)

private val LightCode = CodeColors(
    keyword = Color(0xFF8E24AA),
    comment = Color(0xFF6B7A70),
    type = Color(0xFF00796B),
    function = Color(0xFF1565C0),
    variable = Color(0xFF2A2221),
    constant = Color(0xFFC2410C),
    parameter = Color(0xFFAD1457),
    global = Color(0xFF2E7D32),
    error = Color(0xFFBA1A1A),
    special = Color(0xFF9A6B00),
    address = Color(0xFF857371),
    bytes = Color(0xFFA08C8A),
    mnemonic = Color(0xFF1565C0),
    call = Color(0xFF8E24AA),
    jump = Color(0xFFC2410C),
    ret = Color(0xFFAD1457),
    register = Color(0xFF00796B),
    string = Color(0xFF2E7D32),
    highlight = Color(0x40FFC107),
    currentLine = Color(0x1AB3261E),
)

private val DarkCode = CodeColors(
    keyword = Color(0xFFD59CF4),
    comment = Color(0xFF8C9A90),
    type = Color(0xFF5FD8C6),
    function = Color(0xFF8AB4FF),
    variable = Color(0xFFF1DEDC),
    constant = Color(0xFFFFB074),
    parameter = Color(0xFFFF8FB8),
    global = Color(0xFF94D99A),
    error = Color(0xFFFFB4AB),
    special = Color(0xFFE6C35C),
    address = Color(0xFFA08C8A),
    bytes = Color(0xFF7D6B69),
    mnemonic = Color(0xFF8AB4FF),
    call = Color(0xFFD59CF4),
    jump = Color(0xFFFFB074),
    ret = Color(0xFFFF8FB8),
    register = Color(0xFF5FD8C6),
    string = Color(0xFF94D99A),
    highlight = Color(0x55FFC107),
    currentLine = Color(0x26FFB4A9),
)

val LocalCodeColors = staticCompositionLocalOf { LightCode }

val CodeFont: FontFamily = FontFamily.Monospace

object CodeTheme {
    val colors: CodeColors
        @Composable get() = LocalCodeColors.current
}

@Composable
fun GhidroidTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    val code = if (dark) DarkCode else LightCode
    CompositionLocalProvider(LocalCodeColors provides code.copy(variable = colorScheme.onSurface)) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

@Composable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.surface.luminanceApprox() < 0.5f

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
