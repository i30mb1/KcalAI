package n7.kcalai.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор таблицы пищевой ценности.
 *
 * Строки в тестах — то, что реально отдаёт распознаватель на русской этикетке:
 * цифры читаются, кириллица превращается в мусор или пропадает. Проверяется
 * ровно это состояние, а не идеальный текст, которого на входе никогда не будет.
 */
class LabelParserTest {

    @Test
    fun `калории берутся из пары кДж и ккал`() {
        // Творог 5%: 121 ккал = 506 кДж. Отношение 4.18 — опознаётся без единой буквы.
        val reading = LabelParser.parse(
            listOf("506", "121", "17,2", "5,0", "1,8")
        )

        assertEquals(121, reading.draft.kcal100)
    }

    @Test
    fun `белки жиры и углеводы расставляются по Этуотеру`() {
        // Творог 5%: белки 17,2 · жиры 5,0 · углеводы 1,8.
        // 4·17,2 + 9·5,0 + 4·1,8 = 68,8 + 45 + 7,2 = 121 — сходится в точку.
        val reading = LabelParser.parse(
            listOf("506", "121", "17,2", "5,0", "1,8")
        )

        assertTrue(reading.confident)
        assertEquals(1720, reading.draft.prot100)
        assertEquals(500, reading.draft.fat100)
        assertEquals(180, reading.draft.carb100)
    }

    @Test
    fun `жир находится где угодно в строке`() {
        // Коэффициент 9 выделяет жир из тройки независимо от того,
        // в каком порядке числа напечатаны.
        val reading = LabelParser.parse(
            listOf("5,0", "1,8", "17,2", "121", "506")
        )

        assertTrue(reading.confident)
        assertEquals(500, reading.draft.fat100)
    }

    @Test
    fun `белок и углеводы различаются только порядком чтения`() {
        // У белка и углеводов один коэффициент — 4 ккал на грамм, — поэтому
        // энергия их перестановку не замечает: оба набора дают ровно 121 ккал.
        // Различить их арифметикой нельзя, и разбор честно берёт то, что выше:
        // русские этикетки печатают «Белки, Жиры, Углеводы».
        val bzhu = LabelParser.parse(listOf("506", "121", "17,2", "5,0", "1,8"))
        assertEquals(1720, bzhu.draft.prot100)
        assertEquals(180, bzhu.draft.carb100)

        // Та же этикетка с переставленными местами Б и У читается зеркально —
        // и это не дефект разбора, а предел того, что вообще даёт формула.
        val uzhb = LabelParser.parse(listOf("506", "121", "1,8", "5,0", "17,2"))
        assertEquals(180, uzhb.draft.prot100)
        assertEquals(1720, uzhb.draft.carb100)

        // Калории при любой перестановке одни и те же, а дневник считает именно их.
        assertEquals(bzhu.draft.kcal100, uzhb.draft.kcal100)
    }

    @Test
    fun `жиры и углеводы не путаются местами`() {
        // Печенье: белки 7,5 · жиры 24 · углеводы 60.
        // Перестановка жиров с углеводами дала бы 4·7,5+9·60+4·24 = 666 ккал
        // вместо 486 — разница вдвое, коэффициенты 9 и 4 разводят набор уверенно.
        val reading = LabelParser.parse(
            listOf("2034", "486", "7,5", "24", "60")
        )

        assertTrue(reading.confident)
        assertEquals(2400, reading.draft.fat100)
        assertEquals(6000, reading.draft.carb100)
    }

    @Test
    fun `колонка на порцию не подменяет колонку на 100 грамм`() {
        // Слева «на 100 г», справа «на порцию 40 г» — вдвое с лишним меньше.
        // Макросы порции с калориями ста грамм не сходятся и проигрывают.
        val reading = LabelParser.parse(
            listOf(
                "1560", "373", "13,0", "5,0", "69,0",
                "624", "149", "5,2", "2,0", "27,6",
            )
        )

        assertEquals(373, reading.draft.kcal100)
        assertEquals(1300, reading.draft.prot100)
        assertEquals(500, reading.draft.fat100)
        assertEquals(6900, reading.draft.carb100)
    }

    @Test
    fun `масса нетто и срок годности в макросы не попадают`() {
        // Рядом с таблицей всегда напечатано что-то ещё: вес пачки, дата, доля жира.
        val reading = LabelParser.parse(
            listOf(
                "450",
                "12.09.2026",
                "506", "121", "17,2", "5,0", "1,8",
                "4607036")
        )

        assertTrue(reading.confident)
        assertEquals(121, reading.draft.kcal100)
        assertEquals(1720, reading.draft.prot100)
    }

    @Test
    fun `этикетка без килоджоулей разбирается по наибольшему числу`() {
        // Импорт из США: кДж не печатают. Калориями становится самое крупное
        // правдоподобное число, и набор всё равно обязан сойтись по Этуотеру.
        val reading = LabelParser.parse(
            listOf("121", "17,2", "5,0", "1,8")
        )

        assertTrue(reading.confident)
        assertEquals(121, reading.draft.kcal100)
        assertEquals(1720, reading.draft.prot100)
    }

    @Test
    fun `не сошлось — разбор молчит, но числа отдаёт`() {
        // Блик съел строку с белками. Врать нельзя: человек получит чипсы
        // с тем, что видно, и назначит сам.
        val reading = LabelParser.parse(listOf("506", "121", "5,0"))

        assertFalse(reading.confident)
        assertNull(reading.draft.prot100)
        assertTrue(reading.numbers.containsAll(listOf("506", "121", "5,0")))
    }

    @Test
    fun `на этикетке без чисел разбор пуст`() {
        val reading = LabelParser.parse(listOf("Coca-Cola", "zero"))

        assertFalse(reading.confident)
        assertTrue(reading.numbers.isEmpty())
        assertNull(reading.draft.kcal100)
    }

    @Test
    fun `точка и запятая равноправны`() {
        // Какой разделитель напечатает типография, предсказать нельзя.
        val comma = LabelParser.parse(listOf("506", "121", "17,2", "5,0", "1,8"))
        val dot = LabelParser.parse(listOf("506", "121", "17.2", "5.0", "1.8"))

        assertEquals(comma.draft, dot.draft)
    }

    @Test
    fun `одинокие килоджоули пересчитываются в калории`() {
        // 2034 ккал на 100 г не бывает — это килоджоули, оставшиеся без пары.
        // Делим на 4.184 и получаем 486, с которыми макросы сходятся точно.
        val reading = LabelParser.parse(listOf("2034", "7,5", "24", "60"))

        assertTrue(reading.confident)
        assertEquals(486, reading.draft.kcal100)
        assertEquals(2400, reading.draft.fat100)
    }

    @Test
    fun `год из срока годности не принимается за килоджоули`() {
        // «2026» и настоящие «506» кДж дают отношение 4,00 — в двух процентах
        // от 4,184 оно уже не помещается, а в пяти помещалось бы.
        val reading = LabelParser.parse(
            listOf("12.09.2026", "506", "121", "17,2", "5,0", "1,8")
        )

        assertEquals(121, reading.draft.kcal100)
        assertTrue(reading.confident)
    }
}
