package n7.kcalai.feature.diary

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** «Дорожка» в строке ввода — просьба подключить беговую дорожку, а не еда. */
class TreadmillAskTest {

    @Test
    fun `просьба о дорожке — словами, а не еда`() {
        listOf("дорожка", "Дорожку!", "беговая дорожка", "подключи дорожку").forEach {
            assertTrue(asksForTreadmill(it), it)
        }
        listOf("гречка", "дорожка из моркови", "", "дорога").forEach {
            assertFalse(asksForTreadmill(it), it)
        }
    }
}
