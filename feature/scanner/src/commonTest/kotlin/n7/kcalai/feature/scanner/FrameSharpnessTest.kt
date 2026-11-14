package n7.kcalai.feature.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Порог смаза на кадрах, устроенных как снятые.
 *
 * Снимков в репозитории нет — на них чужая кухня, — поэтому кадр здесь
 * нарисован: строки «букв» на бежевом и размытый фон. Сам порог откалиброван
 * по всем записанным кадрам, а тест стережёт устройство метрики.
 */
class FrameSharpnessTest {

    private fun frame(ink: (x: Int, y: Int) -> Boolean): IntArray =
        IntArray(WIDTH * HEIGHT) { i -> if (ink(i % WIDTH, i / WIDTH)) INK else PAPER }

    /** Буквы высотой в восемь точек с шагом в двадцать: столбики разной ширины. */
    private fun text(x: Int, y: Int): Boolean = y % 20 < 8 && (x * 7 / 3) % 11 < 5

    /** Смаз: каждая точка усредняется с соседями по обеим осям, как у кадра в движении. */
    private fun IntArray.smeared(reach: Int): IntArray = along(reach, 1, WIDTH).along(reach, WIDTH, HEIGHT)

    private fun IntArray.along(reach: Int, step: Int, length: Int): IntArray = IntArray(size) { i ->
        val at = i / step % length
        val from = maxOf(at - reach, 0)
        val to = minOf(at + reach, length - 1)
        (from..to).sumOf { this[i + (it - at) * step] } / (to - from + 1)
    }

    private fun sharpness(luma: IntArray) = FrameSharpness.of(luma, WIDTH, HEIGHT)

    @Test
    fun `текст на весь кадр — резкий`() {
        assertTrue(sharpness(frame(::text)) >= FrameSharpness.MIN)
    }

    /**
     * Съёмка 1789417527058: штрих-код и дата резкие, а этикетка занимает верх
     * кадра. Ниже — рука и кухня, размытые глубиной резкости, и центральная
     * четверть мерила их: резкость 73 при читаемом коде, и кадр в пропуск.
     */
    @Test
    fun `резкая этикетка у края кадра над размытым фоном — резкая`() {
        val luma = frame { x, y -> y < HEIGHT / 5 && text(x, y) }

        assertTrue(sharpness(luma) >= FrameSharpness.MIN)
    }

    @Test
    fun `кадр смазанный целиком — смазан`() {
        assertTrue(sharpness(frame(::text).smeared(reach = 12)) < FrameSharpness.MIN)
    }

    @Test
    fun `однотонный кадр — ноль`() {
        assertEquals(0.0, sharpness(IntArray(WIDTH * HEIGHT) { PAPER }))
    }

    @Test
    fun `кадр меньше сетки — ноль — не падение`() {
        assertEquals(0.0, FrameSharpness.of(IntArray(4), 2, 2))
    }

    private companion object {
        const val WIDTH = 240
        const val HEIGHT = 320
        const val INK = 70
        const val PAPER = 170
    }
}
