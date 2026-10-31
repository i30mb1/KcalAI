package n7.kcalai.feature.diary

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

/**
 * Цель для новичка по росту, весу и возрасту.
 *
 * Расход по факту появляется только через две недели взвешиваний, а до тех пор
 * новичку не на что опереться, кроме собственной догадки. Формула Миффлина —
 * Сан Жеора — честная отправная точка: её показывают как оценку и правят руками.
 */
class GoalHelperTest {

    /** 30 лет, 180 см, 80 кг, спорт 3–5 раз: обмен 1780, расход ×1,55 ≈ 2760. */
    @Test
    fun `держать вес — расход по формуле, белок 1,6 г на кило`() {
        val goal = suggestGoal(Body(Sex.MALE, age = 30, heightCm = 180, weightKg = 80.0, Activity.ACTIVE, Aim.KEEP))

        assertEquals(GoalSuggestion(kcal = 2760, prot = 128, fat = 92, carb = 355), goal)
    }

    /** 25 лет, 165 см, 60 кг, хожу: обмен 1345, расход ×1,375 ≈ 1850, минус 15% ≈ 1570. */
    @Test
    fun `похудеть — минус пятнадцать процентов и белка больше`() {
        val goal = suggestGoal(Body(Sex.FEMALE, age = 25, heightCm = 165, weightKg = 60.0, Activity.LIGHT, Aim.LOSE))

        assertEquals(GoalSuggestion(kcal = 1570, prot = 108, fat = 52, carb = 168), goal)
    }

    /** Ниже 1200 ккал без врача не опускаются — и подсказка не опускает. */
    @Test
    fun `цель не опускается ниже 1200 ккал`() {
        val goal = suggestGoal(Body(Sex.FEMALE, age = 70, heightCm = 150, weightKg = 40.0, Activity.LOW, Aim.LOSE))

        assertEquals(1200, goal.kcal)
    }

    @Test
    fun `данные читаются как их пишут — с запятой и пробелами`() {
        assertEquals(
            Body(Sex.MALE, age = 30, heightCm = 180, weightKg = 82.4, Activity.LOW, Aim.KEEP),
            bodyOrNull(Sex.MALE, age = " 30", height = "180", weight = "82,4", Activity.LOW, Aim.KEEP),
        )
    }

    /** Опечатка в росте даёт цель, по которой нельзя жить. Кнопка ждёт правдоподобного. */
    @Test
    fun `неправдоподобные данные не считаются`() {
        assertNull(bodyOrNull(Sex.MALE, age = "5", height = "180", weight = "80", Activity.LOW, Aim.KEEP))
        assertNull(bodyOrNull(Sex.MALE, age = "30", height = "18", weight = "80", Activity.LOW, Aim.KEEP))
        assertNull(bodyOrNull(Sex.MALE, age = "30", height = "180", weight = "800", Activity.LOW, Aim.KEEP))
        assertNull(bodyOrNull(null, age = "30", height = "180", weight = "80", Activity.LOW, Aim.KEEP))
    }
}
