package n7.kcalai.feature.scanner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import n7.kcalai.ui.KcalTheme

/**
 * Диалог по умолчанию отступает от системных полос, и над камерой висит глухая
 * полоса под часами. Отсюда `decorFitsSystemWindows = false` и перекраска значков
 * статуса: иначе белые часы оказались бы на бежевом фоне светлой темы.
 */
@Composable
actual fun ScanDialog(onDismiss: () -> Unit, onCamera: Boolean, content: @Composable () -> Unit) {
    val lightBars = !onCamera && !KcalTheme.colors.isDark

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
        content()
    }
}
