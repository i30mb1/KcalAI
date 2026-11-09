package n7.kcalai.feature.diary

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.model.Mascot
import n7.kcalai.model.Sex
import n7.kcalai.ui.Macro

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

    /**
     * Клетчатка — минимум: 14 г на каждую 1000 ккал цели, но не меньше 25 г (ВОЗ, EFSA).
     * Без цели калорий — те же 25: норма клетчатки от похудения не зависит.
     */
    @Test
    fun `клетчатка по калориям цели, но не меньше 25 г`() {
        assertEquals(29, suggestedFiber(2100))
        assertEquals(39, suggestedFiber(2800))
        assertEquals(25, suggestedFiber(1500))
        assertEquals(25, suggestedFiber(null))
    }

    /** Заданная руками цель важнее посчитанной. */
    @Test
    fun `своя цель клетчатки важнее посчитанной`() {
        assertEquals(35, fiberGoal(DailyGoalEntity(0, kcal = 2100, prot = 120, fat = 70, carb = 240, fiber = 35)))
        assertEquals(29, fiberGoal(DailyGoalEntity(0, kcal = 2100, prot = 120, fat = 70, carb = 240)))
        assertEquals(25, fiberGoal(null))
    }

    /**
     * Калории цели не вводятся, а складываются из граммов: «200 ккал и 120 г белка»
     * больше не сохранить — 120 г белка это уже 480 ккал.
     */
    @Test
    fun `калории цели — сумма граммов по Этуотеру`() {
        assertEquals(480 + 630 + 960, GoalMacros(prot = 120, fat = 70, carb = 240).kcal)
        assertEquals(0, GoalMacros(0, 0, 0).kcal)
    }

    /** Цель из одних калорий, сохранённая раньше, встаёт в поля граммами, которые дают те же калории. */
    @Test
    fun `в поля встают граммы цели, а цель из одних калорий раскладывается`() {
        assertEquals(GoalMacros(120, 70, 240), goalMacros(DailyGoalEntity(0, kcal = 2070, prot = 120, fat = 70, carb = 240)))
        assertEquals(GoalMacros(prot = 100, fat = 67, carb = 250), goalMacros(DailyGoalEntity(0, kcal = 2000, prot = 0, fat = 0, carb = 0)))
        assertNull(goalMacros(null))
    }

    /** Расход по весу подставляется в углеводы: белок и жир человек выбрал сам. */
    @Test
    fun `расход по весу подставляется в углеводы`() {
        assertEquals(310, carbsFor(kcal = 2350, prot = 120, fat = 70))
        assertEquals(0, carbsFor(kcal = 900, prot = 120, fat = 70))
    }

    /** Опечатка в росте даёт цель, по которой нельзя жить. Кнопка ждёт правдоподобного. */
    @Test
    fun `неправдоподобные данные не считаются`() {
        assertNull(bodyOrNull(Sex.MALE, age = "5", height = "180", weight = "80", Activity.LOW, Aim.KEEP))
        assertNull(bodyOrNull(Sex.MALE, age = "30", height = "18", weight = "80", Activity.LOW, Aim.KEEP))
        assertNull(bodyOrNull(Sex.MALE, age = "30", height = "180", weight = "800", Activity.LOW, Aim.KEEP))
        assertNull(bodyOrNull(null, age = "30", height = "180", weight = "80", Activity.LOW, Aim.KEEP))
    }

    /**
     * В шите цели говорит персонаж: пустой шит без строки непонятен. Пока поле
     * не выбрано, Яйцо объясняет, что здесь делать; тап по полю — слово берёт
     * хозяин макроса.
     */
    @Test
    fun `в шите цели говорит хозяин выбранного поля`() {
        assertEquals(Mascot.EGG, goalSheetLine(focus = null, helperOpen = false, kcal = 0).mascot)
        assertEquals(Mascot.EGG, goalSheetLine(focus = Macro.PROTEIN, helperOpen = false, kcal = 0).mascot)
        assertEquals(Mascot.BUTTER, goalSheetLine(focus = Macro.FAT, helperOpen = false, kcal = 0).mascot)
        assertEquals(Mascot.GRAIN, goalSheetLine(focus = Macro.CARB, helperOpen = false, kcal = 0).mascot)
    }

    /** Новичку — что делать, тому, у кого цель есть, — что её можно поправить; помощнику — своя строка. */
    @Test
    fun `строка шита меняется вместе с тем, что происходит`() {
        val empty = goalSheetLine(focus = null, helperOpen = false, kcal = 0).text
        val filled = goalSheetLine(focus = null, helperOpen = false, kcal = 2070).text
        val helper = goalSheetLine(focus = null, helperOpen = true, kcal = 0).text

        assertEquals(3, setOf(empty, filled, helper).size)
    }

    /**
     * В шите говорящий перебегает к реплике, остальные стоят в своих полях — как
     * на главном экране: персонаж один и переходит с места на место.
     */
    @Test
    fun `в шите говорящий у реплики, остальные дома в полях`() {
        val places = goalSheetPlaces(goalSheetLine(focus = Macro.FAT, helperOpen = false, kcal = 0))

        assertEquals(MascotPlace.Reply(SHEET_SPEECH_ANCHOR), places[Mascot.BUTTER])
        assertEquals(MascotPlace.Home, places[Mascot.EGG])
        assertEquals(MascotPlace.Home, places[Mascot.GRAIN])
        assertEquals(setOf(Mascot.BUTTER), emptyHomes(places))
    }
}
