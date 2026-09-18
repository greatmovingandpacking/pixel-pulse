package app.pixelpulse.ui.theme

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

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CFFC4),
    onPrimary = Color(0xFF003826),
    primaryContainer = Color(0xFF005238),
    onPrimaryContainer = Color(0xFFA6FFD6),
    secondary = Color(0xFFB4CCCE),
    background = Color(0xFF0B1211),
    surface = Color(0xFF0B1211),
    surfaceVariant = Color(0xFF3A4A46),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C4C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF7CFFC4),
    onPrimaryContainer = Color(0xFF002114),
    background = Color(0xFFF4FBF7),
    surface = Color(0xFFF4FBF7),
)

@Composable
fun PixelPulseTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val followSystem = isSystemInDarkTheme()
    val useDark = darkTheme || followSystem
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useDark ->
            dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        useDark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
