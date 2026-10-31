package n7.kcalai.feature.diary

import kotlin.math.roundToInt

/*
 * Цель для новичка по росту, весу и возрасту.
 *
 * Расход по факту (модель 6) появляется только через две недели взвешиваний,
 * а до тех пор новичку не на что опереться, кроме собственной догадки. Формула
 * Миффлина — Сан Жеора ошибается на ±20%, и поэтому она здесь отправная точка,
 * а не ответ: цифры встают в поля шита, человек их правит, а через две недели
 * карточка расхода по факту предложит его собственное число.
 */

enum class Sex(val title: String) {
    MALE("Мужчина"),
    FEMALE("Женщина"),
}

/** Сколько человек двигается: множитель к обмену покоя. */
enum class Activity(val factor: Double, val title: String) {
    LOW(1.2, "Мало двигаюсь"),
    LIGHT(1.375, "Хожу, иногда спорт"),
    ACTIVE(1.55, "Спорт 3–5 раз в неделю"),
}

/** Чего человек хочет от веса: доля от расхода. */
enum class Aim(val factor: Double, val title: String) {
    LOSE(0.85, "Похудеть"),
    KEEP(1.0, "Держать вес"),
    GAIN(1.1, "Набрать"),
}

data class Body(
    val sex: Sex,
    val age: Int,
    val heightCm: Int,
    val weightKg: Double,
    val activity: Activity,
    val aim: Aim,
)

/** Цель в тех же единицах, что и шит: ккал и граммы. */
data class GoalSuggestion(val kcal: Int, val prot: Int, val fat: Int, val carb: Int)

/**
 * Калории — расход по формуле с поправкой на цель, белок — от веса, жиры —
 * треть калорий, углеводы — остаток.
 *
 * Белка при похудении и наборе больше: при дефиците он бережёт мышцы,
 * при наборе — строит их. Ниже 1200 ккал без врача не опускаются, и подсказка
 * не опускает.
 */
fun suggestGoal(body: Body): GoalSuggestion {
    val sexTerm = if (body.sex == Sex.MALE) 5.0 else -161.0
    val restingKcal = 10 * body.weightKg + 6.25 * body.heightCm - 5 * body.age + sexTerm
    val kcal = roundToTen(maxOf(restingKcal * body.activity.factor * body.aim.factor, MIN_KCAL.toDouble()))

    val protPerKg = if (body.aim == Aim.KEEP) PROT_KEEP else PROT_CHANGE
    val prot = (body.weightKg * protPerKg).roundToInt()
    val fat = (kcal * FAT_SHARE / KCAL_PER_FAT).roundToInt()
    val carb = ((kcal - prot * KCAL_PER_PROT - fat * KCAL_PER_FAT) / KCAL_PER_CARB.toDouble())
        .roundToInt()
        .coerceAtLeast(0)

    return GoalSuggestion(kcal = kcal, prot = prot, fat = fat, carb = carb)
}

/**
 * Данные из полей — или `null`, пока они неправдоподобны.
 *
 * Опечатка в росте («18» вместо «180») даёт цель, по которой нельзя жить;
 * кнопка «Посчитать» ждёт правдоподобного, а не пересчитывает всё подряд.
 */
fun bodyOrNull(
    sex: Sex?,
    age: String,
    height: String,
    weight: String,
    activity: Activity,
    aim: Aim,
): Body? {
    val years = age.trim().toIntOrNull()?.takeIf { it in AGE_RANGE } ?: return null
    val cm = height.trim().toIntOrNull()?.takeIf { it in HEIGHT_RANGE } ?: return null
    val kg = weight.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in WEIGHT_RANGE } ?: return null
    return Body(sex ?: return null, years, cm, kg, activity, aim)
}

private fun roundToTen(kcal: Double): Int = (kcal / 10).roundToInt() * 10

private const val MIN_KCAL = 1200
private const val PROT_KEEP = 1.6
private const val PROT_CHANGE = 1.8
private const val FAT_SHARE = 0.3
private const val KCAL_PER_PROT = 4
private const val KCAL_PER_FAT = 9
private const val KCAL_PER_CARB = 4

private val AGE_RANGE = 14..100
private val HEIGHT_RANGE = 120..230
private val WEIGHT_RANGE = 30.0..300.0
