package n7.kcalai.webapi

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Мир прогулки главный: дневник поверх него уступает место, пока им не пользуются. */
class CalmPageTest {

    @Test
    fun `мира нет — дневник на месте всегда`() {
        assertTrue(diaryShown(world = false, overPanels = false, sinceMoveMs = 60_000, sinceTypingMs = 60_000))
    }

    @Test
    fun `курсор на дневнике и им двигают — на месте`() {
        assertTrue(diaryShown(world = true, overPanels = true, sinceMoveMs = 1_000, sinceTypingMs = 60_000))
    }

    @Test
    fun `курсор ушёл с дневника или замер — задвигается`() {
        assertFalse(diaryShown(world = true, overPanels = false, sinceMoveMs = 500, sinceTypingMs = 60_000))
        assertFalse(diaryShown(world = true, overPanels = true, sinceMoveMs = 7_000, sinceTypingMs = 60_000))
    }

    @Test
    fun `печатают — на месте, где бы ни был курсор`() {
        assertTrue(diaryShown(world = true, overPanels = false, sinceMoveMs = 60_000, sinceTypingMs = 3_000))
    }
}
