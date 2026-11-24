package n7.kcalai.model

import kotlin.math.roundToInt

/**
 * Цель в граммах. Калории из неё считаются, а не вводятся отдельно.
 *
 * Отдельное поле калорий давало сохранить невозможное: «200 ккал и 120 г белка»,
 * хотя 120 г белка — уже 480 ккал. Из граммов калории складываются всегда верно.
 */
data class GoalMacros(val prot: Int, val fat: Int, val carb: Int) {
    val kcal: Int get() = prot * KCAL_PER_PROT + fat * KCAL_PER_FAT + carb * KCAL_PER_CARB
}

/** Калорий в грамме белка, жира и углеводов — по ним цель складывается из граммов. */
const val KCAL_PER_PROT = 4
const val KCAL_PER_FAT = 9
const val KCAL_PER_CARB = 4

/**
 * Клетчатка по калориям цели: 14 г на каждую 1000 ккал, но не меньше 25 г.
 *
 * Нижняя граница — норма ВОЗ и EFSA для взрослых. Она же и без цели калорий:
 * клетчатка нужна и тому, кто худеет, — ему, при меньших калориях, особенно.
 */
fun suggestedFiber(kcal: Int?): Int =
    maxOf(FIBER_MIN_G, ((kcal ?: 0) * FIBER_PER_1000_KCAL / 1000.0).roundToInt())

private const val FIBER_MIN_G = 25

private const val FIBER_PER_1000_KCAL = 14
