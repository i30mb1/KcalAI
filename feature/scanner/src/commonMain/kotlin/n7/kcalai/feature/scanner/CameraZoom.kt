package n7.kcalai.feature.scanner

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlin.math.roundToInt
import n7.kcalai.ui.KcalChip
import n7.kcalai.ui.KcalTheme

/**
 * Приближение в верхней строке: «2×». Тап переводит на следующую ступень.
 *
 * Показывается не ради самого числа, а чтобы приближение было видно как
 * возможность: щипок по превью никто не ищет, пока не знает, что он есть.
 */
@Composable
internal fun ZoomPill(zoom: Float, onClick: () -> Unit) {
    KcalChip(onClick = onClick, background = KcalTheme.colors.surface.copy(alpha = 0.92f)) {
        Text(zoom.zoomLabel(), style = KcalTheme.type.time, color = KcalTheme.colors.text)
    }
}

/** «2×» для круглого, «2,5×» для щипка. */
private fun Float.zoomLabel(): String {
    val tenths = (this * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}×" else "${tenths / 10},${tenths % 10}×"
}

/** Следующая ступень по тапу: 1× → 2× → 3× → 1×. Щипком можно встать между ними. */
internal fun nextZoomStop(zoom: Float): Float =
    ZOOM_STOPS.firstOrNull { it > zoom + ZOOM_STOP_SLACK } ?: ZOOM_STOPS.first()

/** Новое приближение после щипка: во сколько раз развели пальцы. */
internal fun pinchedZoom(zoom: Float, factor: Float): Float = (zoom * factor).coerceIn(ZOOM_MIN, ZOOM_MAX)

private val ZOOM_STOPS = listOf(1f, 2f, 3f)
private const val ZOOM_STOP_SLACK = 0.05f

/**
 * Двукратное приближение для этикетки.
 *
 * Строка таблицы пищевой ценности с расстояния, на котором камера ещё
 * фокусируется (ближе восьми-десяти сантиметров основная камера не наводится
 * и размывает), занимает в кадре 13–16 пикселей; детектор ужимает кадр
 * в 640×640 и видит 6–8 — и не находит строку вовсе. Вдвое крупнее —
 * 13–16 в детекторе, читается. Больше по умолчанию не нужно: таблица
 * перестаёт влезать, и человеку приходится водить камерой по строкам.
 *
 * Штрих-коду приближение не нужно — он читается и издалека, и на шаге кода
 * камера стоит на 1×, а к этикетке приближается вместе с переходом окна.
 */
internal const val LABEL_ZOOM = 2f

private const val ZOOM_MIN = 1f

/** Дальше сенсору уже нечего отдавать: кадр из четверти поля — это лупа над шумом. */
private const val ZOOM_MAX = 4f
