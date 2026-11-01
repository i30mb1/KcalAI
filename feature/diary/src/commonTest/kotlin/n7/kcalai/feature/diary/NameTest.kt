package n7.kcalai.feature.diary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Имя, которое человек назвал персонажам. */
class NameTest {

    @Test
    fun `имя чистится от лишних пробелов`() {
        assertEquals("Женя", cleanName("  Женя  "))
        assertEquals("Анна Мария", cleanName("Анна   Мария"))
    }

    @Test
    fun `пусто и без букв — не имя`() {
        assertNull(cleanName(""))
        assertNull(cleanName("   "))
        assertNull(cleanName("123"))
    }

    /** Имя стоит в начале фразы уведомления: длинное съело бы всё остальное. */
    @Test
    fun `длиннее двадцати знаков — не имя`() {
        assertEquals("Александра-Виктория", cleanName("Александра-Виктория"))
        assertNull(cleanName("Александра-Виктория-Мария"))
    }
}
