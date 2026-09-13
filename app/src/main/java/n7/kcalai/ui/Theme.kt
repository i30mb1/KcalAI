package n7.kcalai.ui

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

private val Green = Color(0xFF3F6B3F)
private val GreenLight = Color(0xFFA8D5A2)

private val LightColors = lightColorScheme(
    primary = Green,
    secondary = Color(0xFF52634F),
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    secondary = Color(0xFFB9CCB4),
)

@Composable
fun KcalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        // На Android 12+ берём палитру обоев: приложение выглядит родным, и это бесплатно.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
