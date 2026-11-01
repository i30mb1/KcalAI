package n7.kcalai.feature.scanner

import androidx.compose.runtime.Composable
import n7.kcalai.model.Nutriments
import n7.kcalai.repositories.LabelReading

/*
 * Точки входа в сканер. Камера, распознавание штрих-кода и текста у каждой
 * платформы свои, а карточка, разбор этикетки и голосование по кадрам — общие.
 */

/**
 * Окно съёмки: от штрих-кода до карточки нового продукта.
 *
 * Шаг ведёт экранная модель — см. [ScanStep]. Окно показывает шаг и сообщает,
 * что произошло: код прочитан, карточка заполнена и подтверждена, окно закрыли.
 * Между шагами окно не закрывается и камера не гаснет.
 *
 * @param onScanned код прочитан камерой или набран руками — уже нормализованный
 *        до EAN-13. Отдаётся один раз за шаг кода.
 * @param onNoBarcode «Нет штрих-кода» на шаге кода: развесное, домашнее — дальше
 *        карточка без кода. Окно в дневнике одно, и других дверей к этикетке нет.
 * @param onSave карточка заполнена и человек подтвердил её. Окно само ничего
 *        не сохраняет: куда класть продукт, решает дневник.
 * @param frameStats над камерой — счётчик кадров и время разбора. Для
 *        разработчика: человеку из магазина приложений он ничего не говорит.
 */
@Composable
expect fun ScannerDialog(
    step: ScanStep,
    onScanned: (String) -> Unit,
    onNoBarcode: () -> Unit,
    onSave: (gtin: String?, name: String, nutriments: Nutriments) -> Unit,
    onDismiss: () -> Unit,
    frameStats: Boolean = false,
)

/** Отладка разбора этикетки: показывает, что и почему распознано. */
@Composable
expect fun LabelDebugDialog(onRead: (LabelReading) -> Unit, onDismiss: () -> Unit)
