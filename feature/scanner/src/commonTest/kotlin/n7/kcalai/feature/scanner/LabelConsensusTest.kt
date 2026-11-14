package n7.kcalai.feature.scanner

import n7.kcalai.model.ProductDraft
import n7.kcalai.model.TextLine
import n7.kcalai.repositories.LabelParser
import n7.kcalai.repositories.LabelReading
import n7.kcalai.repositories.LabelTrace
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

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

    /** Кадр, в котором макрос прочитан, не прочитан вовсе или досчитан из остальных. */
    private fun traced(carb: Int?, read: Boolean = false, zeroed: Boolean = false, derived: Boolean = false) =
        frame(carb = carb).let { reading ->
            reading.copy(
                trace = reading.trace.copy(
                    readLabels = if (read) listOf("У") else emptyList(),
                    zeroedLabels = if (zeroed) listOf("У") else emptyList(),
                    derivedLabels = if (derived) listOf("У") else emptyList(),
                ),
            )
        }

    /**
     * Балтика: на банке 6,5 г, а досчитано из калорий 8,7. Такого числа на пачке нет,
     * и голосовать за него нельзя: кадры повторяют одну и ту же арифметику, а не правду.
     */
    @Test
    fun `досчитанное число не голосует`() {
        val consensus = LabelConsensus()

        val verdict = List(4) { consensus.add(traced(carb = 870, derived = true)) }.last()

        assertEquals(null, verdict.carb.settledValue)
    }

    /**
     * Уксус: подпись то влезает в кадр, то нет. «Подписи не видно» — не прочтение, и пока
     * хоть один кадр её прочитал, такие кадры голоса не имеют: нули поля уже не занимают.
     */
    @Test
    fun `прочитанное значение важнее кадров без подписи`() {
        val consensus = LabelConsensus()

        repeat(4) { consensus.add(traced(carb = 0, zeroed = true)) }
        val verdict = List(3) { consensus.add(traced(carb = 4_800, read = true)) }.last()

        assertEquals(4_800, verdict.carb.settledValue)
    }

    /** Подпись не прочитана ни разу, и кадры сходятся на нуле: у сахара белка и правда нет. */
    @Test
    fun `ноль без подписи голосует пока никто её не прочитал`() {
        val consensus = LabelConsensus()

        val verdict = List(3) { consensus.add(traced(carb = 0, zeroed = true)) }.last()

        assertEquals(0, verdict.carb.settledValue)
    }

    /**
     * Пастила, строки с живой съёмки: три кадра подряд читают один и тот же абзац,
     * а в двух над таблицей лежит чужое число — обрезок «НА 10» и «96поки».
     * Белок раскалывался на 10, 96 и 2,5, и на третьем кадре согласия не было,
     * хотя настоящая строка прочитана во всех трёх.
     */
    @Test
    fun `числа вокруг слова «белок» не раскалывают голоса белка`() {
        val paragraph = listOf(
            "белки - 2,5г,углеводы -76,0 г. ЭНЕРГЕТИЧЕСКАЯЦЕННОСТЬ",
            "ПРОДУКТА: 1330 кДЖ/310 ккал. *Аналитические данные",
        )
        val frames = listOf(
            listOf("НЕПЕРЕНОСИМОСТИЯИЧНОГО БЕЛКА. ПИЩЕВАЯ ЦЕННОСТЬ (СРЕДНИЕ ЗНАЧЕНИЯ*) НА 10"),
            listOf("COCТАВ:96поки свежие.малина,яичный белок.ПРОТИВОНЮNASR"),
            listOf("НЕПЕРЕНОСИМОСТИЯИЧНОГО БЕЛКА. ПИЩЕВАЯ ЦЕННОСТЬ (СРЕДНИЕ"),
        ).map { head ->
            (head + paragraph).mapIndexed { index, text ->
                TextLine(text, centerX = 480f, centerY = 40f + index * 30f, width = 900f, height = 20f)
            }
        }
        val consensus = LabelConsensus()

        val verdict = frames.map { consensus.add(LabelParser.parseLines(it)) }.last()

        assertTrue(verdict.settled)
        assertEquals(250, verdict.reading.draft.prot100)
        assertEquals(310, verdict.reading.draft.kcal100)
    }

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

        assertTrue(verdict.kcal.settled, "калории набрали три голоса своим чередом")
        assertFalse(verdict.fat.settled, "жиры — нет: прочитаны по-разному")
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
        assertEquals(88, verdict.kcal.value, "представитель кучи — самое частое")
    }

    /**
     * Допуск пропорциональный, а не «плюс-минус единица».
     *
     * У калорий разброс распознавания — единицы, у макросов — сотни сотых грамма,
     * и одна абсолютная константа была бы верна ровно для одного из них.
     * На крупных числах два процента это уже десятки: 2400 и 2440 сотых грамма
     * жира — одно прочтение, и голосовать они обязаны вместе.
     */
    @Test
    fun `на крупных числах допуск растёт вместе с числом`() {
        val consensus = LabelConsensus()

        // Жиры 24,00 / 24,40 / 24,00 г — внутри двух процентов от 2400.
        consensus.add(frame(kcal = 486, prot = 750, fat = 2_400, carb = 6_000))
        consensus.add(frame(kcal = 486, prot = 750, fat = 2_440, carb = 6_000))
        val verdict = consensus.add(frame(kcal = 486, prot = 750, fat = 2_400, carb = 6_000))

        assertTrue(verdict.fat.settled, "три прочтения одного числа дают согласие")
        assertEquals(2_400, verdict.fat.value)
    }

    @Test
    fun `далёкие прочтения вместе не голосуют`() {
        val consensus = LabelConsensus()

        // 2400 и 3000 расходятся на четверть — это разные числа, а не разброс.
        consensus.add(frame(fat = 2_400))
        consensus.add(frame(fat = 3_000))
        val verdict = consensus.add(frame(fat = 3_000))

        assertFalse(verdict.fat.settled, "двух голосов из трёх не хватает")
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
        assertFalse(verdict.settled, "сошлись все четыре поля, но между собой они не сходятся")
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
        assertNull(first.name, "по одному кадру название — догадка")
        assertEquals(listOf("Тврг", "Творог"), first.names)

        val second = consensus.add(frame(names = listOf("Творог", "Тваpог")))
        assertEquals("Творог", second.name)
        assertEquals(listOf("Творог"), second.names, "догадки, не пережившие смену кадра, из чипсов уходят")
    }
}
