package n7.kcalai.notify

import n7.kcalai.mascot.Suggestion
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.Nutriments
import n7.kcalai.model.parseFoodRef
import n7.kcalai.model.serialize

/**
 * Подсказка «Записать: гречка» — строками для extras PendingIntent.
 *
 * Кнопка в шторке срабатывает, когда процесса с подсказкой в памяти может уже
 * не быть: всё, что нужно для записи в дневник, едет вместе с нажатием.
 * Строки, а не Parcelable: их читает и чистый тест, без Android.
 */
object SuggestionExtras {

    /** Что приехало с нажатием: что записать, на какое сообщение это ответ и кто его писал. */
    data class Decoded(val suggestion: Suggestion, val messageId: Long, val mascot: Mascot)

    fun encode(suggestion: Suggestion, messageId: Long, mascot: Mascot): Map<String, String> {
        val candidate = suggestion.candidate
        return buildMap {
            put(REF, candidate.ref.serialize())
            put(NAME, candidate.displayName)
            put(KCAL, candidate.nutriments.kcal100.toString())
            put(PROT, candidate.nutriments.prot100.toString())
            put(FAT, candidate.nutriments.fat100.toString())
            put(CARB, candidate.nutriments.carb100.toString())
            candidate.servingG?.let { put(GRAMS, it.toString()) }
            put(MEAL, suggestion.meal.name)
            put(MESSAGE, messageId.toString())
            put(MASCOT, mascot.name)
        }
    }

    /** @return подсказка или `null`, если extras битые или порции нет: без веса записывать нечего */
    fun decode(extras: Map<String, String>): Decoded? {
        val ref = parseFoodRef(extras[REF]) ?: return null
        val grams = extras[GRAMS]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val candidate = FoodCandidate(
            ref = ref,
            displayName = extras[NAME]?.takeIf { it.isNotBlank() } ?: return null,
            nutriments = Nutriments(
                kcal100 = extras[KCAL]?.toIntOrNull() ?: return null,
                prot100 = extras[PROT]?.toIntOrNull() ?: return null,
                fat100 = extras[FAT]?.toIntOrNull() ?: return null,
                carb100 = extras[CARB]?.toIntOrNull() ?: return null,
            ),
            servingG = grams,
        )
        val meal = MealType.entries.find { it.name == extras[MEAL] } ?: return null
        val messageId = extras[MESSAGE]?.toLongOrNull() ?: return null
        val mascot = Mascot.entries.find { it.name == extras[MASCOT] } ?: return null
        return Decoded(Suggestion(candidate, meal), messageId, mascot)
    }

    private const val REF = "ref"
    private const val NAME = "name"
    private const val KCAL = "kcal"
    private const val PROT = "prot"
    private const val FAT = "fat"
    private const val CARB = "carb"
    private const val GRAMS = "grams"
    private const val MEAL = "meal"
    private const val MESSAGE = "message"
    private const val MASCOT = "mascot"
}
