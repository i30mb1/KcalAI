package n7.kcalai.feature.scanner

import androidx.compose.runtime.Composable
import n7.kcalai.model.Nutriments
import n7.kcalai.repositories.LabelReading

/*
 * Точки входа в сканер. Камера, распознавание штрих-кода и текста у каждой
 * платформы свои, а экран-оверлей, разбор этикетки и голосование по кадрам — общие.
 */

/** Штрих-код с камеры. */
@Composable
expect fun ScannerDialog(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
)

/**
 * Этикетка с камеры: название и КБЖУ на 100 г.
 *
 * @param frameStats над камерой — счётчик кадров и время разбора. Для
 *        разработчика: человеку из магазина приложений он ничего не говорит.
 */
@Composable
expect fun LabelScannerDialog(
    onSave: (gtin: String?, name: String, nutriments: Nutriments) -> Unit,
    onDismiss: () -> Unit,
    gtin: String? = null,
    frameStats: Boolean = false,
)

/** Отладка разбора этикетки: показывает, что и почему распознано. */
@Composable
expect fun LabelDebugDialog(onRead: (LabelReading) -> Unit, onDismiss: () -> Unit)
