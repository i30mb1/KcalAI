package n7.kcalai.model

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
