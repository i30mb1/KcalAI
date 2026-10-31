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
 * Экран съёмки во весь экран, включая полосы статуса и навигации.
 *
 * Диалог по умолчанию отступает от системных полос, и над камерой висит
 * глухая полоса под часами. Живая картинка должна идти до самого края —
 * как в любой камере; свои отступы верхняя строка и панель делают сами.
 *
 * @param onCamera под значками статуса превью: они светлые, затемнение под
 *        ними есть всегда. Без камеры — ручной ввод кода, отказ в доступе —
 *        под ними фон темы, и в светлой теме белые часы на бежевом не видны.
 */
@Composable
fun ScanDialog(onDismiss: () -> Unit, onCamera: Boolean = true, content: @Composable () -> Unit) {
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
