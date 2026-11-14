package n7.kcalai.feature.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Название с пачки — в запрос к справочнику.
 *
 * Строки здесь такие, какими их отдаёт распознаватель или разбор этикетки:
 * капсом, с массой нетто, кавычками бренда, обрывками состава и адреса.
 */
class LabelNameQueryTest {

    @Test
    fun `масса нетто из запроса уходит жирность остаётся`() {
        assertEquals("ТВОРОГ 5% Простоквашино", LabelNameQuery.of("ТВОРОГ 5% Простоквашино 180 г"))
        assertEquals("Масло сливочное 82,5%", LabelNameQuery.of("Масло сливочное 82,5% 180г"))
        assertEquals("Молоко питьевое 3,2%", LabelNameQuery.of("Молоко питьевое 3,2% 0,9 л"))
        assertEquals("Кефир 1%", LabelNameQuery.of("Кефир 1% 930 мл."))
        assertEquals("Яйцо куриное С1", LabelNameQuery.of("Яйцо куриное С1 10 шт"))
        assertEquals("Гречка ядрица", LabelNameQuery.of("Гречка ядрица 0,8 кг"))
    }

    /** «г» распознаватель путает с латинской «r»: «180r» — та же масса. */
    @Test
    fun `масса с латинской r вместо г — тоже масса`() {
        assertEquals("Сырок глазированный", LabelNameQuery.of("Сырок глазированный 40r"))
    }

    @Test
    fun `кавычки бренда снимаются а сам бренд остаётся`() {
        assertEquals("Творог 5% простоквашино", LabelNameQuery.of("Творог 5% \"простоквашино"))
        assertEquals("Сыр Российский", LabelNameQuery.of("Сыр «Российский»"))
    }

    /** Служебные пометки упаковки ищутся как слова и топят поиск: «м», «д», «ж» в названии нет. */
    @Test
    fun `БЗМЖ и м·д·ж — не слова названия`() {
        assertEquals("Сметана 20%", LabelNameQuery.of("Сметана 20% БЗМЖ"))
        assertEquals("Молоко 2,5%", LabelNameQuery.of("Молоко м.д.ж. 2,5%"))
        assertEquals("Ряженка 4%", LabelNameQuery.of("Ряженка мдж 4%"))
    }

    /**
     * Латинские двойники посреди русского слова. На глаз «TBOPOГ» — творог,
     * а для поиска по словам это другое слово, и он не находит ничего.
     */
    @Test
    fun `латинские буквы в русском слове — к русским`() {
        assertEquals("ТВОРОГ 5%", LabelNameQuery.of("TBOPOГ 5%"))
        assertEquals("Молоко", LabelNameQuery.of("Mолоко"))
        // Целиком латинское слово законно: бренд так и напечатан.
        assertEquals("Alpen Gold Молочный", LabelNameQuery.of("Alpen Gold Молочный"))
    }

    @Test
    fun `ноль посреди русского слова — буква о`() {
        assertEquals("ТВОРОГ 9%", LabelNameQuery.of("ТВ0Р0Г 9%"))
        // А в числе ноль остаётся нулём.
        assertEquals("Кефир 1,0%", LabelNameQuery.of("Кефир 1,0%"))
    }

    @Test
    fun `строки таблицы пищевой ценности — не название`() {
        assertNull(LabelNameQuery.of("Белки 16 г"))
        assertNull(LabelNameQuery.of("Жиры 5,0 г, углеводы 3,0 г"))
        assertNull(LabelNameQuery.of("Энергетическая ценность 121 ккал / 506 кДж"))
        assertNull(LabelNameQuery.of("Пищевая ценность в 100 г продукта"))
    }

    @Test
    fun `состав — не название даже без слова «состав»`() {
        assertNull(LabelNameQuery.of("Состав: творог, закваска"))
        // Продолжение состава на следующей строке: перечисление через запятую.
        assertNull(LabelNameQuery.of("Молоко нормализованное, закваска, сычужный фермент"))
    }

    /** Запятая между цифрами — дробь, а не перечисление. */
    @Test
    fun `дробная жирность перечислением не считается`() {
        assertEquals("Молоко 2,5%, ультрапастеризованное", LabelNameQuery.of("Молоко 2,5%, ультрапастеризованное"))
    }

    @Test
    fun `масса даты сроки и ГОСТ — не название`() {
        assertNull(LabelNameQuery.of("Масса нетто 180 г"))
        assertNull(LabelNameQuery.of("Годен до 12.05.2026"))
        assertNull(LabelNameQuery.of("Изготовлено 01.11.26"))
        assertNull(LabelNameQuery.of("Дата 01/11/2026 партия"))
        assertNull(LabelNameQuery.of("Хранить при t +2…+6 °C"))
        assertNull(LabelNameQuery.of("ГОСТ 31453-2013"))
    }

    @Test
    fun `адрес изготовителя — не название`() {
        assertNull(LabelNameQuery.of("ООО «Данон Россия», 127015, г. Москва"))
        assertNull(LabelNameQuery.of("Россия, 143421 Московская"))
        assertNull(LabelNameQuery.of("ул. Ленина, д. 5"))
        assertNull(LabelNameQuery.of("Московская обл., Ступино"))
        assertNull(LabelNameQuery.of("Сделано в России"))
    }

    @Test
    fun `цифры штрих-кода и одни пометки — искать нечего`() {
        assertNull(LabelNameQuery.of("4607001771234"))
        assertNull(LabelNameQuery.of("БЗМЖ"))
        assertNull(LabelNameQuery.of("180 г"))
        assertNull(LabelNameQuery.of("5%"))
        assertNull(LabelNameQuery.of(""))
        assertNull(LabelNameQuery.of("   "))
    }

    /** «Российский» — сыр, а не адрес: страна в адресе ловится индексом и «сделано в». */
    @Test
    fun `российский сыр адресом не считается`() {
        assertEquals("Сыр Российский 50%", LabelNameQuery.of("Сыр Российский 50%"))
    }

    @Test
    fun `из кандидатов берётся первый из которого выходит запрос`() {
        assertEquals(
            "ТВОРОГ 5%",
            LabelNameQuery.pick(listOf("", "Масса нетто 180 г", "ТВОРОГ 5% 180 г", "Простоквашино")),
        )
        assertNull(LabelNameQuery.pick(listOf("Белки 16 г", "БЗМЖ")))
        assertNull(LabelNameQuery.pick(emptyList()))
    }
}
