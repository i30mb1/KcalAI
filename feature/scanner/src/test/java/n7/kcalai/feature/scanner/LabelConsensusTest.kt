package n7.kcalai.feature.scanner

import n7.kcalai.model.ProductDraft
import n7.kcalai.repositories.LabelReading
import n7.kcalai.repositories.LabelTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Согласие кадров при съёмке этикетки.
 *
 * Один кадр может сойтись по Этуотеру и при этом ошибиться — «80» прочитано
 * как «388». Поэтому в дневник уходит не первый сошедшийся разбор, а тот,
 * который подтвердили несколько кадров, причём поле за полем.
 */
class LabelConsensusTest {

    /** Творог 5%: 121 ккал, Б 16 / Ж 5 / У 3. */
    private fun frame(
        kcal: Int? = 121,
        prot: Int? = 1_600,
        fat: Int? = 500,
        carb: Int? = 300,
        route: LabelTrace.Route = LabelTrace.Route.LABELS,
        names: List<String> = emptyList(),
    ) = LabelReading(
        draft = ProductDraft(name = names.firstOrNull(), kcal100 = kcal, prot100 = prot, fat100 = fat, carb100 = carb),
        numbers = emptyList(),
        confident = true,
        names = names,
        trace = LabelTrace(route = route),
    )

    @Test
    fun `одного сошедшегося кадра мало`() {
        val consensus = LabelConsensus()

        val verdict = consensus.add(frame())

        assertFalse(verdict.settled)
        assertEquals(1, verdict.kcal.votes)
        // Но показывать уже есть что: человек должен видеть, что камера читает.
        assertEquals(121, verdict.reading.draft.kcal100)
    }

    @Test
    fun `три совпавших кадра — согласие`() {
        val consensus = LabelConsensus()

        consensus.add(frame())
        consensus.add(frame())
        val verdict = consensus.add(frame())

        assertTrue(verdict.settled)
        assertEquals(121, verdict.reading.draft.kcal100)
        assertEquals(1_600, verdict.reading.draft.prot100)
    }

    @Test
    fun `блик на одной строке не портит остальные поля`() {
        val consensus = LabelConsensus()

        consensus.add(frame(fat = 5_000))
        consensus.add(frame(fat = null))
        val verdict = consensus.add(frame())

        assertTrue("калории набрали три голоса своим чередом", verdict.kcal.settled)
        assertFalse("жиры — нет: прочитаны по-разному", verdict.fat.settled)
        assertFalse(verdict.settled)
    }

    @Test
    fun `близкие прочтения одного числа голосуют вместе`() {
        val consensus = LabelConsensus()

        // 87,6 и 87 — одно и то же число: 2% допуска плюс единица.
        consensus.add(frame(kcal = 88))
        consensus.add(frame(kcal = 87))
        val verdict = consensus.add(frame(kcal = 88))

        assertTrue(verdict.kcal.settled)
        assertEquals("представитель кучи — самое частое", 88, verdict.kcal.value)
    }

    @Test
    fun `два далёких числа поровну — выбирать за человека нечего`() {
        val consensus = LabelConsensus(minVotes = 2)

        consensus.add(frame(kcal = 80))
        consensus.add(frame(kcal = 388))
        consensus.add(frame(kcal = 80))
        val verdict = consensus.add(frame(kcal = 388))

        assertNull(verdict.kcal.value)
        assertFalse(verdict.settled)
    }

    @Test
    fun `разбор арифметикой по числам не голосует`() {
        val consensus = LabelConsensus()

        repeat(3) { consensus.add(frame(route = LabelTrace.Route.NUMBERS)) }
        val verdict = consensus.add(frame(route = LabelTrace.Route.NUMBERS))

        assertEquals(0, verdict.kcal.votes)
        assertFalse(verdict.settled)
    }

    @Test
    fun `сошедшиеся голоса обязаны сходиться и по Этуотеру`() {
        val consensus = LabelConsensus()

        // Три кадра подряд одинаково прочли невозможную тройку: 121 ккал при Б 60 / Ж 5 / У 3.
        repeat(2) { consensus.add(frame(prot = 6_000)) }
        val verdict = consensus.add(frame(prot = 6_000))

        assertTrue(verdict.prot.settled)
        assertFalse("сошлись все четыре поля, но между собой они не сходятся", verdict.settled)
    }

    @Test
    fun `старые кадры уходят из окна`() {
        val consensus = LabelConsensus(window = 3)

        repeat(3) { consensus.add(frame(kcal = 80)) }
        // Камеру перевели на другую пачку.
        repeat(3) { consensus.add(frame(kcal = 250)) }
        val verdict = consensus.add(frame(kcal = 250))

        assertEquals(250, verdict.kcal.value)
        assertEquals(3, verdict.kcal.votes)
    }

    @Test
    fun `название подтверждается вторым кадром`() {
        val consensus = LabelConsensus()

        val first = consensus.add(frame(names = listOf("Тврг", "Творог")))
        assertNull("по одному кадру название — догадка", first.name)
        assertEquals(listOf("Тврг", "Творог"), first.names)

        val second = consensus.add(frame(names = listOf("Творог", "Тваpог")))
        assertEquals("Творог", second.name)
        assertEquals("догадки, не пережившие смену кадра, из чипсов уходят", listOf("Творог"), second.names)
    }
}
