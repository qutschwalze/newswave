package com.wavenews.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback-Palette (dunkles Indigoblau, passend zum Launcher-Icon)
private val Indigo = Color(0xFF3949AB)
private val IndigoDark = Color(0xFF1A237E)

private val LightColors = lightColorScheme(
    primary = Indigo,
    primaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFF5C6BC0),
    surface = Color(0xFFFEFBFF),
    background = Color(0xFFF3F1F7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    primaryContainer = Color(0xFF2E3A9E),
    secondary = Color(0xFF9FA8DA),
    surface = Color(0xFF15151A),
    background = Color(0xFF0C0C10),
)

/**
 * darkTheme = null → folgt dem System; true/false → erzwungen hell/dunkel.
 * Nutzt Material You dynamische Farben (Android 12+), sonst die Wave-Palette.
 */
@Composable
fun NewsWaveTheme(darkTheme: Boolean? = null, content: @Composable () -> Unit) {
    val useDark = darkTheme ?: isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        useDark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
