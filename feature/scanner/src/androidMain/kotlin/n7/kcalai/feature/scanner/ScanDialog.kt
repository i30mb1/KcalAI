package n7.kcalai.feature.scanner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Экран съёмки во весь экран, включая полосы статуса и навигации.
 *
 * Диалог по умолчанию отступает от системных полос, и над камерой висит
 * глухая полоса под часами. Живая картинка должна идти до самого края —
 * как в любой камере; свои отступы верхняя строка и панель делают сами.
 * Значки статуса — светлые: под ними всегда затемнённое превью, а не фон темы.
 */
@Composable
fun ScanDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
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
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        content()
    }
}
