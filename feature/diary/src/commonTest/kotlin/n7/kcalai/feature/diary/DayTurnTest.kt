package n7.kcalai.feature.diary

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Кто листает дни — палец или тап по неделе — и когда меняется открытый день. */
class DayTurnTest {

    private val today = 100L
    private val yesterday = 99L
    private val before = 98L

    /** Неделя наверху переключается, пока переписка ещё едет, а не после того как она защёлкнулась. */
    @Test
    fun `перевалили пальцем за середину — день выбран сразу`() {
        val turn = DayTurn()
        turn.grabbed()

        assertTrue(turn.crossed(yesterday, shown = today))
        assertFalse(turn.opened(yesterday), "страницы уже там — везти их не нужно")
    }

    @Test
    fun `страницы доезжают сами после тапа — молча`() {
        val turn = DayTurn()

        assertTrue(turn.opened(before))
        assertFalse(turn.crossed(yesterday, shown = before))
    }

    @Test
    fun `увели и тут же вернули — следующий тап по неделе всё равно листает`() {
        val turn = DayTurn()
        turn.grabbed()
        turn.crossed(yesterday, shown = today)
        // Вчерашний день ещё не успел открыться, а палец уже вернул страницу.
        assertTrue(turn.crossed(today, shown = today))
        turn.released()

        assertTrue(turn.opened(before))
    }

    /** Пока палец на экране, запоздавший день из базы страницу из-под него не выдёргивает. */
    @Test
    fun `пока листают, открывшийся день страницы не тянет`() {
        val turn = DayTurn()
        turn.grabbed()

        assertFalse(turn.opened(yesterday))
    }

    /** Палец уже отпустил, а страница ещё доезжает: тап по другому дню обязан увезти и переписку. */
    @Test
    fun `тапнули другой день, пока страница доезжает, — страницы едут к нему`() {
        val turn = DayTurn()
        turn.grabbed()
        turn.crossed(yesterday, shown = today)
        turn.opened(yesterday)
        turn.released()

        assertTrue(turn.opened(before))
        assertFalse(turn.crossed(yesterday, shown = before), "дальше везут сами — молча")
    }

    @Test
    fun `перелистнули через два дня — промежуточный назад не тянет`() {
        val turn = DayTurn()
        turn.grabbed()
        turn.crossed(yesterday, shown = today)
        turn.crossed(before, shown = today)
        turn.released()

        assertFalse(turn.opened(yesterday))
        assertFalse(turn.opened(before))
    }
}
