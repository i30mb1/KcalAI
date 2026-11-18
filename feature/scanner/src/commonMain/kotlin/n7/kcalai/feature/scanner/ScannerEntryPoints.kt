package n7.kcalai.feature.scanner

import androidx.compose.runtime.Composable
import n7.kcalai.repositories.LabelReading

/**
 * Отладка разбора этикетки: показывает, что и почему распознано.
 *
 * Своя у платформы: на Android она показывает голоса своего движка распознавания,
 * на iOS читает Vision, и показывать нечего.
 */
@Composable
expect fun LabelDebugDialog(onRead: (LabelReading) -> Unit, onDismiss: () -> Unit)
