package n7.kcalai.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор строки ввода: «что это» и «сколько».
 *
 * Здесь проверяются не регулярки, а решения: почему «2 яйца» — штуки, а «курица 150» —
 * граммы; почему «творог 5%» и «творог 3.2» не превращаются в количество; почему
 * порядок слов не важен. Каждое из них когда-то было спором и закреплено тестом.
 */
class TextParserTest {

    // --- Что уходит в поиск ---------------------------------------------------------

    @Test
    fun `порядок слов не важен`() {
        val a = parseSegment("200 г гречки")!!
        val b = parseSegment("гречки 200 г")!!

        assertEquals("гречки", a.name)
        assertEquals(a.name, b.name)
        assertEquals(Quantity.Weight(200), a.quantity)
        assertEquals(a.quantity, b.quantity)
    }

    @Test
    fun `жирность — часть названия, а не количество`() {
        val segment = parseSegment("творог 5%")!!

        assertEquals("творог", segment.name)
        assertEquals(Quantity.None, segment.quantity)
    }

    @Test
    fun `дробное число цифрами без единицы — не количество`() {
        // «творог 3.2» — почти наверняка жирность без знака процента.
        assertEquals(Quantity.None, parseSegment("творог 3.2")!!.quantity)
        // Числа в поиск не уходят: FTS по «3.2» отсёк бы всё по AND.
        assertEquals("творог", parseSegment("творог 3.2")!!.name)
    }

    @Test
    fun `словесная дробь количеством считается`() {
        assertEquals(Quantity.Bare(0.5), parseSegment("пол банана")!!.quantity)
        assertEquals(Quantity.Bare(1.5), parseSegment("полтора яблока")!!.quantity)
    }

    @Test
    fun `строка из одних чисел и единиц — не сегмент`() {
        assertNull(parseSegment("200 г"))
        assertNull(parseSegment("   "))
    }

    // --- Единицы ----------------------------------------------------------------------

    @Test
    fun `вес и объём сразу превращаются в граммы`() {
        assertEquals(Quantity.Weight(200), parseSegment("курица 200г")!!.quantity)
        assertEquals(Quantity.Weight(500), parseSegment("0.5 кг картошки")!!.quantity)
        assertEquals(Quantity.Weight(250), parseSegment("молоко 250 мл")!!.quantity)
        assertEquals(Quantity.Weight(1000), parseSegment("литр кефира")!!.quantity)
    }

    @Test
    fun `десятичная запятая не рвёт число`() {
        assertEquals(Quantity.Weight(1500), parseSegment("1,5 кг мяса")!!.quantity)
    }

    @Test
    fun `порционные единицы приводятся к канону`() {
        assertEquals(Quantity.Units(2.0, Units.PIECE), parseSegment("2 шт яйца")!!.quantity)
        assertEquals(Quantity.Units(1.0, Units.TABLESPOON), parseSegment("ложка мёда")!!.quantity)
        assertEquals(Quantity.Units(1.0, Units.TABLESPOON), parseSegment("столовая ложка масла")!!.quantity)
        assertEquals(Quantity.Units(2.0, Units.TEASPOON), parseSegment("2 ч.л. сахара")!!.quantity)
        assertEquals(Quantity.Units(1.0, Units.GLASS), parseSegment("кружка чая")!!.quantity)
        assertEquals(Quantity.Units(1.0, Units.PORTION), parseSegment("тарелка борща")!!.quantity)
    }

    @Test
    fun `сокращения единиц с точками не ломают токенизацию`() {
        val segment = parseSegment("масло 1 ст.л.")!!

        assertEquals("масло", segment.name)
        assertEquals(Quantity.Units(1.0, Units.TABLESPOON), segment.quantity)
    }

    @Test
    fun `слитное «полстакана» — половина стакана`() {
        assertEquals(Quantity.Units(0.5, Units.GLASS), parseSegment("полстакана молока")!!.quantity)
        assertEquals(Quantity.Units(0.5, Units.TABLESPOON), parseSegment("пол-ложки мёда")!!.quantity)
    }

    @Test
    fun `явный вес сильнее порционной единицы`() {
        // Иначе «340» стало бы счётом банок. Лишняя единица в поиск не идёт.
        val segment = parseSegment("банка кукурузы 340 г")!!

        assertEquals(Quantity.Weight(340), segment.quantity)
        assertEquals("кукурузы", segment.name)
        assertEquals(Quantity.Weight(250), parseSegment("стакан молока 250 мл")!!.quantity)
    }

    @Test
    fun `слитное «200г» читается как вес`() {
        assertEquals(Quantity.Weight(200), parseSegment("курица 200г")!!.quantity)
        assertEquals(Quantity.Units(2.0, Units.PIECE), parseSegment("2шт яйца")!!.quantity)
    }

    // --- Расплывчатые объёмы ------------------------------------------------------------

    @Test
    fun `«немного» — множитель к порции`() {
        assertEquals(Quantity.Vague(0.5), parseSegment("немного орехов")!!.quantity)
    }

    @Test
    fun `повторённое «чуть» не попадает в название`() {
        // «чуть-чуть соли» искалось как «чуть соли» и не находило ничего.
        val segment = parseSegment("чуть-чуть соли")!!

        assertEquals("соли", segment.name)
        assertEquals(Quantity.Vague(0.4), segment.quantity)
    }

    // --- Фраза ------------------------------------------------------------------------

    @Test
    fun `фраза делится по запятой, «и» и плюсу`() {
        val names = parsePhrase("гречка 200, курица и салат + хлеб").map { it.name }

        assertEquals(listOf("гречка", "курица", "салат", "хлеб"), names)
    }

    @Test
    fun `десятичная запятая не считается разделителем фразы`() {
        val segments = parsePhrase("1,5 стакана молока")

        assertEquals(1, segments.size)
        assertEquals(Quantity.Units(1.5, Units.GLASS), segments.single().quantity)
    }

    // --- Граммы для конкретного продукта ------------------------------------------------

    private val egg = mapOf(Units.PIECE to 60)

    @Test
    fun `голое число у штучного продукта — штуки`() {
        val grams = Quantity.Bare(2.0).toGrams(egg, defaultPortionG = 120)

        assertEquals(120, grams.value)
        assertFalse(grams.guessed)
        assertEquals(Units.PIECE, grams.unit)
        assertEquals(2.0, grams.count, 0.0)
    }

    @Test
    fun `голое число у нештучного продукта — граммы`() {
        val grams = Quantity.Bare(150.0).toGrams(emptyMap(), defaultPortionG = 200)

        assertEquals(150, grams.value)
        assertFalse(grams.guessed)
    }

    @Test
    fun `маленькое число у нештучного продукта — не граммы, а порция`() {
        // «молоко 3» — ни штуки, ни граммы. Три грамма молока честнее не записывать.
        val grams = Quantity.Bare(3.0).toGrams(emptyMap(), defaultPortionG = 200)

        assertEquals(200, grams.value)
        assertTrue(grams.guessed)
    }

    @Test
    fun `дюжина — ещё штуки, сотня — уже граммы`() {
        assertEquals(12 * 60, Quantity.Bare(12.0).toGrams(egg, 60).value)
        assertEquals(100, Quantity.Bare(100.0).toGrams(egg, 60).value)
    }

    @Test
    fun `единица без перевода у этого продукта — типичная порция с пометкой`() {
        // «стакан котлет»: единица названа, но у котлет нет граммовки стакана.
        val grams = Quantity.Units(1.0, Units.GLASS).toGrams(emptyMap(), defaultPortionG = 90)

        assertEquals(90, grams.value)
        assertTrue(grams.guessed)
        assertNull("догадку памяти порций отдавать нельзя", grams.unit)
    }

    @Test
    fun `явный вес запоминается как порция`() {
        val grams = Quantity.Weight(250).toGrams(emptyMap(), defaultPortionG = 100)

        assertEquals(250, grams.value)
        assertEquals(Units.PORTION, grams.unit)
        assertFalse(grams.guessed)
    }

    @Test
    fun `без порции у продукта подставляется сто грамм`() {
        assertEquals(100, Quantity.None.toGrams(emptyMap(), defaultPortionG = null).value)
        assertEquals(100, Quantity.None.toGrams(emptyMap(), defaultPortionG = 0).value)
    }

    @Test
    fun `«немного» масштабирует порцию, но не до нуля`() {
        assertEquals(50, Quantity.Vague(0.5).toGrams(emptyMap(), 100).value)
        assertEquals(1, Quantity.Vague(0.1).toGrams(emptyMap(), 4).value)
    }
}
