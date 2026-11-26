package n7.kcalai.model

/**
 * Где мы на дороге дня.
 *
 * @param land номер земли маршрута, на которой стоим
 * @param next номер следующей
 * @param stepsToNext сколько шагов до неё
 */
data class RouteSpot(val land: Int, val next: Int, val stepsToNext: Int)

/**
 * Дорога дня в прогулке: цель шагов делится между [lands] землями поровну; прошли цель — земли идут по кругу.
 * Та же раскладка, что строит мир (walk/src/core/biomes.js). Одна земля или цели нет — показывать нечего.
 */
fun routeSpot(lands: Int, goal: Int, steps: Int): RouteSpot? {
    if (lands < 2 || goal <= 0) return null
    val length = (goal / lands).coerceAtLeast(1)
    val passed = steps.coerceAtLeast(0) / length
    return RouteSpot(land = passed % lands, next = (passed + 1) % lands, stepsToNext = (passed + 1) * length - steps.coerceAtLeast(0))
}
