package n7.kcalai.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Где мы на дороге дня: цель шагов делится между землями маршрута поровну, дальше они идут по кругу. */
class RouteTest {

    @Test
    fun `внутри дня — текущая земля и сколько до следующей`() {
        assertEquals(RouteSpot(land = 0, next = 1, stepsToNext = 1_600), routeSpot(lands = 5, goal = 8_000, steps = 0))
        assertEquals(RouteSpot(land = 2, next = 3, stepsToNext = 1_350), routeSpot(lands = 5, goal = 8_000, steps = 3_450))
        assertEquals(RouteSpot(land = 3, next = 4, stepsToNext = 1_600), routeSpot(lands = 5, goal = 8_000, steps = 4_800))
    }

    @Test
    fun `прошли цель — земли идут по кругу`() {
        assertEquals(RouteSpot(land = 4, next = 0, stepsToNext = 100), routeSpot(lands = 5, goal = 8_000, steps = 7_900))
        assertEquals(RouteSpot(land = 0, next = 1, stepsToNext = 1_500), routeSpot(lands = 5, goal = 8_000, steps = 8_100))
    }

    @Test
    fun `одна земля или нет данных — показывать нечего`() {
        assertNull(routeSpot(lands = 1, goal = 8_000, steps = 100))
        assertNull(routeSpot(lands = 5, goal = 0, steps = 100))
    }
}
