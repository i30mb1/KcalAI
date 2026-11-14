package n7.kcalai.feature.scanner

import androidx.compose.runtime.Composable

/**
 * Окно съёмки уже во весь экран: контроллер приложения стоит без отступов
 * от системных полос, и просить об этом отдельно, как на Android, не нужно.
 * Значки статуса над камерой светлые всегда — под ними затемнение верхней строки.
 */
@Composable
actual fun ScanDialog(onDismiss: () -> Unit, onCamera: Boolean, content: @Composable () -> Unit) {
    content()
}
