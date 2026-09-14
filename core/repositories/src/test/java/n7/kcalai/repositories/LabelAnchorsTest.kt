package n7.kcalai.repositories

import n7.kcalai.model.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор по подписям — то, ради чего вводился кириллический OCR.
 *
 * Строки в тестах имитируют выдачу PP-OCRv5: текст плюс положение на снимке.
 * Раскладка взята с настоящей этикетки — подписи в левой колонке, числа справа,
 * каждая пара на своей высоте.
 */
class LabelAnchorsTest {

    /**
     * Творог 5%: белки 17,2 · жиры 5,0 · углеводы 1,8 · 121 ккал.
     *
     * Порядок печати нарочно не БЖУ, а ЖУБ — как на части упаковок.
     * Именно этот случай нынешняя арифметика разбирает неверно.
     */
    private fun label(): List<TextLine> = listOf(
        line("Творог 5%", y = 40f, height = 30f),
        line("Пищевая ценность на 100 г", y = 100f),
        line("Жиры", y = 140f), line("5,0", y = 140f, x = 300f),
        line("Углеводы", y = 180f), line("1,8", y = 180f, x = 300f),
        line("Белки", y = 220f), line("17,2", y = 220f, x = 300f),
        line("Энергетическая ценность", y = 260f),
        line("506 кДж / 121 ккал", y = 300f),
    )

    @Test
    fun `белки и углеводы берутся по подписям, а не по порядку`() {
        val reading = LabelParser.parseLines(label())

        assertTrue(reading.confident)
        assertEquals(1720, reading.draft.prot100)
        assertEquals(180, reading.draft.carb100)
        assertEquals(500, reading.draft.fat100)
    }

    @Test
    fun `арифметика на этой этикетке ошибается — с подписями нет`() {
        val byNumbers = LabelParser.parse(label().map { it.text })
        val byAnchors = LabelParser.parseLines(label())

        // Перебор по Этуотеру ставит белком то, что напечатано выше, — здесь жиры
        // и углеводы идут первыми, и он берёт «1,8» вместо «17,2».
        assertEquals(180, byNumbers.draft.prot100)
        assertEquals(1720, byAnchors.draft.prot100)
    }

    @Test
    fun `калории читаются из строки с кДж и ккал`() {
        assertEquals(121, LabelParser.parseLines(label()).draft.kcal100)
    }

    @Test
    fun `подпись «на 100 г» за значение не принимается`() {
        val lines = listOf(
            line("Белки на 100 г", y = 100f), line("17,2", y = 100f, x = 300f),
            line("Жиры", y = 140f), line("5,0", y = 140f, x = 300f),
            line("Углеводы", y = 180f), line("1,8", y = 180f, x = 300f),
            line("121 ккал", y = 220f),
        )

        // Без отсечения «100 г» белка вышло бы сто грамм в ста граммах.
        assertEquals(1720, LabelParser.parseLines(lines).draft.prot100)
    }

    @Test
    fun `перевранная распознавателем буква прощается`() {
        val lines = listOf(
            // «Белки» с латинской «B» и «Жиры» с потерянной буквой — типичный мусор OCR.
            line("Bелки", y = 100f), line("17,2", y = 100f, x = 300f),
            line("Жиры", y = 140f), line("5,0", y = 140f, x = 300f),
            line("Углеводьi", y = 180f), line("1,8", y = 180f, x = 300f),
            line("121 ккал", y = 220f),
        )

        val reading = LabelParser.parseLines(lines)
        assertEquals(1720, reading.draft.prot100)
        assertEquals(180, reading.draft.carb100)
    }

    /**
     * Напиток «Ягодный микс»: жиров на этикетке нет вовсе, зато в составе
     * «глюкозно-фруктозный сироп». «Сироп» отличается от «жиров» двумя
     * буквами, и нечёткое сравнение принимало его за подпись без значения —
     * форма вставала на «Ж —», хотя правильный ответ ноль.
     */
    @Test
    fun `слово из состава, похожее на подпись, подписью не считается`() {
        val lines = listOf(
            line("сахар, глюкозно-фруктозный сироп", y = 60f),
            line("Пищевая ценность:", y = 100f),
            line("углеводы, г/100 мл", y = 140f), line("6,0", y = 140f, x = 300f),
            line("Энергетическая ценность:", y = 180f),
            line("ккал/100 мл", y = 220f), line("25,0", y = 220f, x = 300f),
            line("кДж/100 мл", y = 260f), line("110,0", y = 260f, x = 300f),
        )

        val reading = LabelParser.parseLines(lines)
        assertTrue(reading.confident)
        assertEquals(0, reading.draft.fat100)
        assertEquals(0, reading.draft.prot100)
        assertEquals(600, reading.draft.carb100)
    }

    @Test
    fun `точная подпись без числа нулём не становится`() {
        val lines = listOf(
            line("Белки", y = 100f), line("17,2", y = 100f, x = 300f),
            // Число у жиров закрыл блик: подпись есть, значения нет.
            line("Жиры", y = 140f),
            line("Углеводы", y = 180f), line("1,8", y = 180f, x = 300f),
            line("121 ккал", y = 220f),
        )

        val reading = LabelParser.parseLines(lines)
        assertFalse(reading.confident)
        assertEquals(null, reading.draft.fat100)
    }

    @Test
    fun `берётся колонка, что ближе к подписи`() {
        // Слева «на 100 г», справа «на порцию 40 г».
        val lines = listOf(
            line("Белки", y = 100f), line("13,0", y = 100f, x = 300f), line("5,2", y = 100f, x = 500f),
            line("Жиры", y = 140f), line("5,0", y = 140f, x = 300f), line("2,0", y = 140f, x = 500f),
            line("Углеводы", y = 180f), line("69,0", y = 180f, x = 300f), line("27,6", y = 180f, x = 500f),
            line("373 ккал", y = 220f),
        )

        val reading = LabelParser.parseLines(lines)
        assertEquals(1300, reading.draft.prot100)
        assertEquals(6900, reading.draft.carb100)
    }

    @Test
    fun `название продукта предлагается, а таблица в кандидаты не идёт`() {
        val reading = LabelParser.parseLines(label())

        assertEquals("Творог 5%", reading.names.first())
        assertEquals("Творог 5%", reading.draft.name)
        assertTrue(
            "подписи таблицы не могут быть названием",
            reading.names.none { it.contains("Белки") || it.contains("ккал") },
        )
    }

    @Test
    fun `без подписей работает арифметика, но за неё расписывается человек`() {
        // Латинский распознаватель: цифры есть, слов нет.
        val lines = listOf(
            line("506", y = 100f), line("121", y = 140f),
            line("17,2", y = 180f), line("5,0", y = 220f), line("1,8", y = 260f),
        )

        val reading = LabelParser.parseLines(lines)

        // Форма заполняется — ради этого перебор и нужен.
        assertEquals(121, reading.draft.kcal100)
        assertEquals(1720, reading.draft.prot100)

        // А вот закрывать съёмку сама арифметика не вправе: сойтись с калориями
        // может и случайная тройка чисел, и отличить её от прочитанной нельзя.
        assertFalse("подобранное перебором подтверждает человек", reading.confident)
    }

    private fun line(text: String, y: Float, x: Float = 100f, height: Float = 20f) =
        TextLine(text = text, centerX = x, centerY = y, width = 120f, height = height)
}
