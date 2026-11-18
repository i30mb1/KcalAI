package n7.kcalai.feature.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import n7.kcalai.model.TextLine
import n7.kcalai.repositories.LabelReading

/**
 * Путь кадра этикетки — общий для Android и iOS: распознают строки платформы
 * по-своему, а что делать со смазанным кадром, с отсутствием распознавания
 * и с прочитанными строками, решается здесь.
 */
class LabelPipelineTest {

    private val table = listOf(
        "Пищевая ценность на 100 г:",
        "белки 16 г, жиры 5 г, углеводы 3 г",
        "энергетическая ценность 121 ккал",
    ).mapIndexed { index, text -> TextLine(text, centerX = 480f, centerY = 40f + index * 30f, width = 900f, height = 20f) }

    @Test
    fun `мыльный кадр — ниже порога резкости`() {
        val pipeline = LabelPipeline()

        assertFalse(pipeline.isSharp(FrameSharpness.MIN - 1))
        assertTrue(pipeline.isSharp(FrameSharpness.MIN))
    }

    /** Карточка считает мыло подряд и через несколько секунд просит вписать руками. */
    @Test
    fun `смазанный кадр повторяет прошлый резкий с пометкой`() {
        val pipeline = LabelPipeline()
        val read = pipeline.read(table, elapsedMs = 400, size = "960×1280")!!

        val blurred = pipeline.blurred(elapsedMs = 5)

        assertFalse(blurred.sharp)
        assertEquals(5, blurred.elapsedMs)
        assertEquals(read.frame.reading, blurred.reading, "голоса и поля не дёргаются")
    }

    @Test
    fun `смазанный кадр до первого резкого — пустой`() {
        val blurred = LabelPipeline().blurred(elapsedMs = 5)

        assertFalse(blurred.sharp)
        assertEquals(LabelReading.EMPTY, blurred.reading)
    }

    /** Нет распознавания — это состояние, а не событие: о нём говорят один раз. */
    @Test
    fun `о недоступном распознавании сообщают один раз`() {
        val pipeline = LabelPipeline()

        assertEquals(false, pipeline.unavailable()?.available)
        assertNull(pipeline.unavailable())
    }

    @Test
    fun `прочитанные строки голосуют и становятся кадром`() {
        val read = LabelPipeline().read(table, elapsedMs = 400, size = "960×1280")!!

        assertEquals(121, read.frame.reading.draft.kcal100)
        assertEquals("960×1280", read.frame.size)
        assertEquals(400, read.frame.elapsedMs)
        assertEquals(1, read.frame.frames)
        assertTrue(read.frame.sharp)
    }
}
