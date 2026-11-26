package n7.kcalai.webapi

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Мир прогулки главный: клик по миру убирает дневник, ещё клик — возвращает. */
class CalmPageTest {

    @Test
    fun `клик по миру убирает дневник, а клик по дневнику — нет`() {
        assertFalse(diaryAfterClick(world = true, shown = true, onDiary = false))
        assertTrue(diaryAfterClick(world = true, shown = true, onDiary = true))
    }

    @Test
    fun `задвинутый возвращается любым кликом`() {
        assertTrue(diaryAfterClick(world = true, shown = false, onDiary = false))
        assertTrue(diaryAfterClick(world = true, shown = false, onDiary = true))
    }

    @Test
    fun `мира нет — прятать не от чего`() {
        assertTrue(diaryAfterClick(world = false, shown = true, onDiary = false))
    }
}
