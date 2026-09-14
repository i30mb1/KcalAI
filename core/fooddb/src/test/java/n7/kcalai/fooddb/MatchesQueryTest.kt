package n7.kcalai.fooddb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поиск по своим продуктам.
 *
 * Тест написан по живому промаху: продукт, заведённый со сканера этикетки,
 * не находился по набранному в композере — совсем, ни при каком вводе. Причина
 * была не в поиске как таковом, а в `LIKE '%запрос%'`, которым он делался
 * в SQL: русский регистр встроенный в SQLite `LIKE` не складывает.
 *
 * Поэтому здесь проверяется не «функция работает», а именно те три свойства,
 * которых подстрочный поиск дать не может в принципе, — [findsRegardlessOfCase],
 * [findsRegardlessOfWordOrder] и [findsAcrossWordEndings]. Сломать их обратно
 * можно только вернувшись к подстроке, и тест на это и поставлен.
 */
class MatchesQueryTest {

    /**
     * Тот самый случай.
     *
     * Названия приходят с заглавной не случайно: разбор этикетки ставит её сам,
     * чтобы в ленте «Начинка» стояла рядом с «Гречка», а не кричала капсом.
     * Человек же набирает строчными — и до починки не находил ничего.
     */
    @Test
    fun findsRegardlessOfCase() {
        assertTrue(matchesQuery("Начинка фруктовая", "начинка фрук"))
        assertTrue(matchesQuery("начинка фруктовая", "НАЧИНКА"))
        assertTrue(matchesQuery("ТВОРОГ 5%", "творог"))
    }

    /** «Фруктовая начинка» и «начинка фруктовая» — одно и то же, и человек это знает. */
    @Test
    fun findsRegardlessOfWordOrder() {
        assertTrue(matchesQuery("Фруктовая начинка", "начинка фрук"))
        assertTrue(matchesQuery("Молоко Простоквашино", "простоквашино молоко"))
    }

    /** Падеж запроса и падеж названия совпадать не обязаны. */
    @Test
    fun findsAcrossWordEndings() {
        assertTrue(matchesQuery("Начинка фруктовая", "начинку фруктовую"))
        assertTrue(matchesQuery("Гречка", "гречки"))
        assertTrue(matchesQuery("Творог мягкий", "творога"))
    }

    /** Человек ещё печатает — находить надо уже сейчас, а не по последней букве. */
    @Test
    fun findsByPrefix() {
        assertTrue(matchesQuery("Начинка фруктовая", "нач"))
        assertTrue(matchesQuery("Начинка фруктовая", "начинка ф"))
    }

    /**
     * Слова соединяются «и», а не «или».
     *
     * Иначе одно случайно совпавшее слово вытаскивало бы в подсказки половину
     * списка, и чипсы перестали бы что-либо значить.
     */
    @Test
    fun requiresEveryWord() {
        assertFalse(matchesQuery("Начинка фруктовая", "начинка творожная"))
        assertFalse(matchesQuery("Молоко", "молоко простоквашино"))
    }

    @Test
    fun rejectsUnrelated() {
        assertFalse(matchesQuery("Начинка фруктовая", "гречка"))
        assertFalse(matchesQuery("Гречка", "греч ка"))
    }

    /** Пустой запрос не значит «подходит всё»: подсказывать при пустом поле нечего. */
    @Test
    fun emptyQueryMatchesNothing() {
        assertFalse(matchesQuery("Начинка фруктовая", ""))
        assertFalse(matchesQuery("Начинка фруктовая", "   "))
        assertFalse(matchesQuery("Начинка фруктовая", "!!!"))
    }

    /** «ё» и «е» человек набирает как придётся, а на клавиатуре «ё» ещё и спрятана. */
    @Test
    fun foldsYo() {
        assertTrue(matchesQuery("Тёртый сыр", "тертый"))
        assertTrue(matchesQuery("Тертый сыр", "тёртый"))
    }
}
