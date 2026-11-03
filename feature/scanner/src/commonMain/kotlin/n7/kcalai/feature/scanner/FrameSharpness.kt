package n7.kcalai.feature.scanner

import kotlin.math.max

/**
 * Резкость кадра: дисперсия лапласиана в самой резкой плитке сетки 4×4.
 *
 * Лапласиан — вторая производная, и на резкой границе буквы он даёт всплеск,
 * на размытой — пологий холмик. Дисперсия по площади собирает это в одно
 * число: у смазанного кадра всплесков нет, и она мала.
 *
 * Смаз от движения портит кадр целиком, а размытый фон — только фон. Этикетку
 * снимают вплотную, глубина резкости там в сантиметры, и рука с кухней за пачкой
 * всегда не в фокусе. Поэтому судит самое резкое место кадра, а не заранее
 * выбранное: центральная четверть, которая мерила резкость раньше, на пачке
 * у края кадра мерила как раз фон. Две съёмки подряд ушли в пропуск целиком,
 * хотя дата и штрих-код на кадрах читались глазом, — а код из пропущенного
 * кадра не ищется вовсе.
 *
 * Плитка, а не весь кадр и не отдельные точки: на весь кадр пустое поле
 * разбавляет дисперсию так же, как фон, а на участке меньше пары строк текста
 * метрика считает уже не резкость, а отдельные штрихи.
 *
 * Полное разрешение, без уменьшения: метрика калибровалась по сохранённым
 * кадрам как есть, а уменьшение сглаживает границы и сдвигает шкалу.
 */
internal object FrameSharpness {

    /**
     * Ниже этой резкости кадр пропускается.
     *
     * Порог режет только явный смаз, и это принципиально: метрика зависит
     * от контраста не меньше, чем от фокуса. Чёрный текст на белом при хорошем
     * свете даёт сотни и тысячи, а коричневый на бежевом при тусклом — 82–120
     * в полном фокусе. Чистой границы нет: кадр на самом начале смаза даёт
     * и 91. Но лишний такой кадр стоит одного чтения, а потерянный резкий —
     * голоса, поэтому порог стоит под резкими: явный смаз на записанных
     * сессиях не поднимается выше 78.
     */
    const val MIN = 80.0

    private const val GRID = 4

    /** Плитка уже этого — не плитка: лапласиану нужна рамка в точку с каждой стороны. */
    private const val MIN_TILE = 3

    /** @param luma яркость 0..255, построчно, [width] × [height] */
    fun of(luma: IntArray, width: Int, height: Int): Double {
        val tileWidth = width / GRID
        val tileHeight = height / GRID
        if (tileWidth < MIN_TILE || tileHeight < MIN_TILE) return 0.0

        var sharpest = 0.0
        for (row in 0 until GRID) {
            for (column in 0 until GRID) {
                val left = column * tileWidth
                val top = row * tileHeight
                sharpest = max(sharpest, variance(luma, width, left, top, tileWidth, tileHeight))
            }
        }
        return sharpest
    }

    private fun variance(luma: IntArray, stride: Int, left: Int, top: Int, w: Int, h: Int): Double {
        var sum = 0L
        var squares = 0L
        for (y in top + 1 until top + h - 1) {
            for (x in left + 1 until left + w - 1) {
                val i = y * stride + x
                val lap = 4 * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - stride] - luma[i + stride]
                sum += lap
                squares += lap.toLong() * lap
            }
        }
        val count = ((w - 2) * (h - 2)).toDouble()
        val mean = sum / count
        return squares / count - mean * mean
    }
}
