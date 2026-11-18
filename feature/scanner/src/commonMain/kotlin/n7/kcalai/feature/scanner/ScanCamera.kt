package n7.kcalai.feature.scanner

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import n7.kcalai.model.ProductDraft

/**
 * Камера платформы для окна съёмки — всё, чего общим не бывает.
 *
 * Доступ, превью, чтение штрих-кода и строк этикетки у каждой платформы свои:
 * CameraX, ML Kit и свой движок на Android, AVFoundation и Vision на iOS. Окно
 * вокруг камеры — рамка, карточка, персонажи, отклик ладони — одно, общее, см. [ScannerDialog].
 */
internal interface ScanCamera {

    /** Доступ к камере есть. */
    val granted: Boolean

    /** Человек ответил на запрос доступа: до ответа говорить «доступа нет» рано. */
    val answered: Boolean

    /** Нейросеть читает последний резкий кадр этикетки; `null` — читать нечем. */
    val secondOpinion: (suspend () -> ProductDraft?)?

    /**
     * Живая картинка. Сессия камеры и разбор кадров живут, пока она на экране,
     * и смену шага переживают: окно одно — от штрих-кода до карточки.
     *
     * @param zoom во сколько раз приблизить
     * @param onPinch щипок по превью — во сколько раз развели пальцы; `null` — жест ничего не меняет
     * @param fit 0 — кадр на весь экран, как у штрих-кода; 1 — по ширине от верха, как у этикетки
     */
    @Composable
    fun Preview(modifier: Modifier, zoom: Float, onPinch: ((Float) -> Unit)?, fit: Float)
}

/**
 * Камера окна съёмки. Обработчики можно передавать новыми на каждой перерисовке:
 * камера собирается один раз и зовёт всегда свежие. Зовёт — на главном потоке.
 *
 * @param reading читать этикетку; `false` — искать только штрих-код
 * @param onBarcode код с кадра — уже приведённый к EAN-13
 * @param onFrame разобранный кадр этикетки
 * @param outcome чем кончилась съёмка — для записи кадров на диск в сборке разработчика;
 *        `null` — карточки не было
 */
@Composable
internal expect fun rememberScanCamera(
    reading: () -> Boolean,
    onBarcode: (String) -> Unit,
    onFrame: (LabelFrame) -> Unit,
    outcome: () -> String?,
): ScanCamera
