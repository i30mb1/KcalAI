package n7.kcalai.feature.diary

import kotlin.math.roundToInt
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.model.FiberTotal
import n7.kcalai.model.Mascot
import n7.kcalai.model.Sex
import n7.kcalai.ui.Macro

/*
 * Цель для новичка по росту, весу и возрасту.
 *
 * Расход по факту (модель 6) появляется только через две недели взвешиваний,
 * а до тех пор новичку не на что опереться, кроме собственной догадки. Формула
 * Миффлина — Сан Жеора ошибается на ±20%, и поэтому она здесь отправная точка,
 * а не ответ: цифры встают в поля шита, человек их правит, а через две недели
 * карточка расхода по факту предложит его собственное число.
 */

/** Подпись выбора в помощнике цели. */
val Sex.title: String
    get() = when (this) {
        Sex.MALE -> "Мужчина"
        Sex.FEMALE -> "Женщина"
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

/**
 * Клетчатка по калориям цели: 14 г на каждую 1000 ккал, но не меньше 25 г.
 *
 * Нижняя граница — норма ВОЗ и EFSA для взрослых. Она же и без цели калорий:
 * клетчатка нужна и тому, кто худеет, — ему, при меньших калориях, особенно.
 */
fun suggestedFiber(kcal: Int?): Int =
    maxOf(FIBER_MIN_G, ((kcal ?: 0) * FIBER_PER_1000_KCAL / 1000.0).roundToInt())

/**
 * Цель в граммах. Калории из неё считаются, а не вводятся отдельно.
 *
 * Отдельное поле калорий давало сохранить невозможное: «200 ккал и 120 г белка»,
 * хотя 120 г белка — уже 480 ккал. Из граммов калории складываются всегда верно.
 */
data class GoalMacros(val prot: Int, val fat: Int, val carb: Int) {
    val kcal: Int get() = prot * KCAL_PER_PROT + fat * KCAL_PER_FAT + carb * KCAL_PER_CARB
}

/**
 * Что встаёт в поля шита цели.
 *
 * Граммы сохранённой цели — как есть. Цель из одних калорий, заведённая до того,
 * как калории стали считаться из граммов, раскладывается 20/30/50: иначе в шите
 * стояли бы нули, и прежние калории пропали бы при первом же сохранении.
 */
fun goalMacros(goal: DailyGoalEntity?): GoalMacros? = when {
    goal == null -> null
    goal.prot + goal.fat + goal.carb > 0 -> GoalMacros(goal.prot, goal.fat, goal.carb)
    goal.kcal > 0 -> GoalMacros(
        prot = (goal.kcal * KCAL_SPLIT_PROT / KCAL_PER_PROT).roundToInt(),
        fat = (goal.kcal * KCAL_SPLIT_FAT / KCAL_PER_FAT).roundToInt(),
        carb = (goal.kcal * KCAL_SPLIT_CARB / KCAL_PER_CARB).roundToInt(),
    )
    else -> null
}

/** Углеводы, с которыми цель даёт [kcal]: белок и жир человек выбрал сам, углеводы — остаток. */
fun carbsFor(kcal: Int, prot: Int, fat: Int): Int =
    ((kcal - prot * KCAL_PER_PROT - fat * KCAL_PER_FAT) / KCAL_PER_CARB.toDouble()).roundToInt().coerceAtLeast(0)

/** Кто говорит в шите цели и что. */
data class SheetLine(val mascot: Mascot, val text: String)

/**
 * Реплика в шите цели.
 *
 * Без неё шит — три пустых поля и ноль, и непонятно, что от человека хотят.
 * Объясняет Яйцо: оно главное в знакомстве. Тап по полю — слово берёт хозяин
 * макроса и говорит про своё, а помощнику по росту и весу — своя строка.
 *
 * @param focus поле, в котором сейчас курсор
 * @param kcal сколько калорий уже складывают граммы в полях
 */
fun goalSheetLine(focus: Macro?, helperOpen: Boolean, kcal: Int): SheetLine = when {
    focus == Macro.PROTEIN -> SheetLine(Mascot.EGG, "Белок — 1,6–1,8 г на килограмм веса. Бережёт мышцы.")
    focus == Macro.FAT -> SheetLine(Mascot.BUTTER, "Жиры — около трети калорий дня. Спокойно, без перегибов.")
    focus == Macro.CARB -> SheetLine(Mascot.GRAIN, "Углеводы — всё остальное. Топливо на день!")
    helperOpen -> SheetLine(Mascot.EGG, "Расскажи о себе — посчитаю по формуле, а ты поправишь.")
    kcal == 0 -> SheetLine(Mascot.EGG, "Сколько белков, жиров и углеводов в день? Калории сложу сам.")
    else -> SheetLine(Mascot.EGG, "Это твоя цель на день. Поправь граммы — калории пересчитаю.")
}

/** Место у реплики в шите цели — туда перебегает тот, кто говорит. */
const val SHEET_SPEECH_ANCHOR = "sheet-speech"

/**
 * Кто где стоит в шите цели: говорящий — у реплики, остальные — дома, в своих полях.
 *
 * Та же расстановка, что на главном экране: персонаж один и перебегает с места
 * на место, а пустое место в поле подписано буквой.
 */
fun goalSheetPlaces(line: SheetLine): Map<Mascot, MascotPlace> =
    Mascot.entries.associateWith { mascot ->
        if (mascot == line.mascot) MascotPlace.Reply(SHEET_SPEECH_ANCHOR) else MascotPlace.Home
    }

/** Цель клетчатки дня: заданная руками, а без неё — посчитанная по калориям. */
fun fiberGoal(goal: DailyGoalEntity?): Int = goal?.fiber ?: suggestedFiber(goal?.kcal)

/**
 * Реплика Зёрнышка по тапу на клетчатку в шапке: что это за число и сколько её за день.
 * Неизвестная клетчатка — не ноль: тогда он честно говорит, что посчитать не из чего.
 */
fun fiberSpeech(fiber: FiberTotal, goalGrams: Int): String {
    val shown = formatFiber(fiber)
        ?: return "Это клетчатка! У сегодняшних записей её не указано — посчитать пока не из чего."
    return "Это клетчатка! Сегодня $shown из $goalGrams г. Её добавят овощи, крупы и отруби."
}

private fun roundToTen(kcal: Double): Int = (kcal / 10).roundToInt() * 10

private const val MIN_KCAL = 1200
private const val FIBER_MIN_G = 25
private const val KCAL_SPLIT_PROT = 0.2
private const val KCAL_SPLIT_FAT = 0.3
private const val KCAL_SPLIT_CARB = 0.5
private const val FIBER_PER_1000_KCAL = 14
private const val PROT_KEEP = 1.6
private const val PROT_CHANGE = 1.8
private const val FAT_SHARE = 0.3
private const val KCAL_PER_PROT = 4
private const val KCAL_PER_FAT = 9
private const val KCAL_PER_CARB = 4

private val AGE_RANGE = 14..100
private val HEIGHT_RANGE = 120..230
private val WEIGHT_RANGE = 30.0..300.0
