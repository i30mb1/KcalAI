package n7.kcalai.model

/**
 * Распознанная строка вместе с тем, где она лежала на снимке.
 *
 * Геометрия здесь не украшение, а главное. Без неё разбор этикетки видит мешок
 * строк и вынужден гадать: на таблице пищевой ценности подпись «Белки» и её число
 * связаны тем, что напечатаны на одной высоте, а колонки «на 100 г» и «на порцию»
 * различаются только положением по горизонтали.
 *
 * Живёт в `:core:model`, потому что нужна двоим по разные стороны графа зависимостей:
 * `:core:ocr` её производит, `:core:repositories` разбирает. Ни один из них
 * не должен знать о другом.
 *
 * Координаты — в пикселях исходного снимка, начало отсчёта в левом верхнем углу.
 *
 * @param confidence уверенность распознавателя, 0..1
 */
data class TextLine(
    val text: String,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val confidence: Float = 1f,
) {
    val top: Float get() = centerY - height / 2f
    val bottom: Float get() = centerY + height / 2f
    val left: Float get() = centerX - width / 2f
    val right: Float get() = centerX + width / 2f

    /**
     * Лежат ли строки на одной строке таблицы.
     *
     * Допуск задаётся долей от высоты самой строки, а не абсолютной величиной:
     * снимки приходят разного разрешения, и порог в пикселях был бы верен
     * ровно для одного из них.
     */
    fun sameRow(other: TextLine, tolerance: Float = 0.6f): Boolean {
        val allowed = maxOf(height, other.height) * tolerance
        return kotlin.math.abs(centerY - other.centerY) <= allowed
    }
}
