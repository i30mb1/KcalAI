package n7.kcalai.webapi

import n7.kcalai.model.FiberTotal
import n7.kcalai.model.Mascot
import n7.kcalai.model.fiberSpeech
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Тап по плитке — хозяин отвечает: кто он и как дела у его макроса. Тапают снова —
 * говорит другое, по кругу: одна и та же фраза на каждый тап надоедает с третьего раза.
 */
class TileSpeechTest {

    @Test
    fun `хозяин плитки представляется и называет свои граммы`() {
        val egg = tileSpeech(WebTile.PROTEIN, eatenCg = 3_500, goalGrams = 120, fiber = FiberTotal.ZERO, tap = 0)

        assertEquals(Mascot.EGG, egg.mascot)
        assertTrue("35 из 120" in egg.text, egg.text)
        assertEquals(Mascot.BUTTER, tileSpeech(WebTile.FAT, 500, 70, FiberTotal.ZERO, 0).mascot)
        assertEquals(Mascot.GRAIN, tileSpeech(WebTile.CARB, 5_800, 230, FiberTotal.ZERO, 0).mascot)
    }

    @Test
    fun `без цели — просто граммы`() {
        val text = tileSpeech(WebTile.PROTEIN, eatenCg = 3_500, goalGrams = null, fiber = FiberTotal.ZERO, tap = 0).text

        assertTrue("35 г" in text, text)
    }

    /** Клетчатка говорит то же, что по тапу в шапке приложения, — и про себя: «я клетчатка». */
    @Test
    fun `клетчатка — та же фраза, что в приложении, и о себе`() {
        val fiber = FiberTotal(cg = 600, unknown = 0)

        assertEquals(fiberSpeech(fiber, 28), tileSpeech(WebTile.FIBER, 600, 28, fiber, tap = 0).text)
        assertTrue("клетчатка" in tileSpeech(WebTile.FIBER, 600, 28, fiber, tap = 1).text.lowercase())
    }

    @Test
    fun `тапают снова — другая фраза, по кругу`() {
        WebTile.entries.forEach { tile ->
            val said = (0 until TILE_LINES).map { tileSpeech(tile, 1_000, 50, FiberTotal(1_000, 0), it).text }
            assertEquals(TILE_LINES, said.toSet().size, "$tile повторяется")
            assertEquals(said.first(), tileSpeech(tile, 1_000, 50, FiberTotal(1_000, 0), TILE_LINES).text)
        }
        assertNotEquals(
            tileSpeech(WebTile.FAT, 500, 70, FiberTotal.ZERO, 0).text,
            tileSpeech(WebTile.FAT, 500, 70, FiberTotal.ZERO, 1).text,
        )
    }
}
