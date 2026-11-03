package n7.kcalai.assistant

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.totals
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.plus
import n7.kcalai.model.serialize
import n7.kcalai.model.title
import n7.kcalai.resolver.ResolvedItem

/**
 * То, что модель может спросить у приложения, — её инструменты.
 *
 * Только чтение. Записывает в дневник человек, тапом по чипсу: модель предлагает,
 * а не делает. Поэтому и «предложить продукт» здесь не действие, а вызов, который
 * экран перехватывает и превращает в чипс, см. [proposal].
 *
 * Ответы — текст, а не структуры: маленькой модели строка «520 ккал, осталось 1480»
 * понятнее вложенного JSON, и всё, что требует арифметики, посчитано здесь же.
 * Складывать числа модели не доверяем — она это делает хуже калькулятора.
 */
class DiaryTools(
    private val search: suspend (query: String, limit: Int) -> List<FoodCandidate>,
    private val day: suspend (epochDay: Long) -> List<DiaryEntryEntity>,
    private val goal: suspend (epochDay: Long) -> DailyGoalEntity?,
    private val today: () -> Long,
) {

    /**
     * Всё, что поиск показывал модели, по ключу.
     *
     * Предложить можно только отсюда: ключ, которого поиск не отдавал, —
     * выдуманный, и за ним нет ни продукта, ни цифр.
     */
    private val seen = mutableMapOf<String, FoodCandidate>()
    private val lock = Mutex()

    /** Инструмент `search_food`: продукты из локальной базы с ключами и КБЖУ на 100 г. */
    suspend fun searchFood(query: String): String {
        val found = search(query.trim(), SEARCH_LIMIT)
        if (found.isEmpty()) return "По запросу «$query» ничего нет. Попробуй другое название, проще или короче."
        lock.withLock { found.forEach { seen[it.ref.serialize()] = it } }
        return found.joinToString("\n") { it.describe() }
    }

    /**
     * Вызов инструмента -> чипс, если это `propose_food` и он честный.
     *
     * Имя приходит то коротким, то полным — с пакетом и классом службы, смотря
     * кто его собрал. Вес модель шлёт как попало: числом с точкой, целым, строкой.
     *
     * @return `null` — не предложение, продукт не из выдачи или вес невозможный
     */
    suspend fun proposal(call: String, args: Map<String, Any?>): ResolvedItem? {
        if (call.substringAfterLast('#').substringAfterLast('.') != PROPOSE) return null
        val candidate = lock.withLock { seen[args["foodId"]?.toString()] } ?: return null
        val grams = args["grams"].toGrams()?.takeIf { it in 1..MAX_GRAMS } ?: return null
        val phrase = args["phrase"]?.toString()?.trim().orEmpty()
        return ResolvedItem(
            sourceText = phrase.ifEmpty { candidate.displayName },
            candidate = candidate,
            grams = grams,
            confidence = PROPOSAL_CONFIDENCE,
            source = EntrySource.LLM,
            // Вес назвала модель. Это догадка, и учить на ней память порций нельзя.
            gramsGuessed = true,
        )
    }

    /** Инструмент `diary_days`: итоги последних дней, с остатком до цели на сегодня. */
    suspend fun diaryDays(days: Int): String {
        val now = today()
        val lines = mutableListOf<String>()
        for (back in 0 until days.coerceIn(1, MAX_DAYS)) {
            val date = now - back
            val entries = day(date)
            val label = dayLabel(back)
            if (entries.isEmpty()) {
                lines += "$label: записей нет"
                continue
            }

            val eaten = entries.sumTotals()
            val target = goal(date)
            lines += buildString {
                append("$label: ${eaten.describe()}")
                if (target == null) return@buildString
                append("; цель ${target.kcal} ккал, белок ${target.prot} г")
                if (back == 0) {
                    append(" — осталось ${target.kcal - eaten.kcal} ккал, ")
                    append("белка ${target.prot - eaten.protCg / CENTIGRAMS} г")
                }
            }
        }
        return lines.joinToString("\n")
    }

    /** Инструмент `day_entries`: что записано в день, [daysAgo] дней назад. */
    suspend fun dayEntries(daysAgo: Int): String {
        val back = daysAgo.coerceIn(0, MAX_DAYS_BACK)
        val entries = day(today() - back)
        if (entries.isEmpty()) return "${dayLabel(back)}: записей нет"
        return entries.joinToString("\n") { entry ->
            "${entry.meal.title}: ${entry.displayName} ${entry.grams} г, ${entry.totals().kcal} ккал"
        }
    }

    private fun FoodCandidate.describe(): String = buildString {
        append(ref.serialize()).append(" | ").append(displayName).append(" | ")
        append("${nutriments.kcal100} ккал, Б ${grams(nutriments.prot100)}, ")
        append("Ж ${grams(nutriments.fat100)}, У ${grams(nutriments.carb100)} на 100 г")
        servingG?.let { append(" | порция $it г") }
    }

    private fun NutrimentTotals.describe(): String =
        "$kcal ккал, белки ${protCg / CENTIGRAMS} г, жиры ${fatCg / CENTIGRAMS} г, углеводы ${carbCg / CENTIGRAMS} г"

    companion object {
        /** Имя инструмента, вызов которого становится чипсом. */
        const val PROPOSE = "propose_food"

        private const val SEARCH_LIMIT = 5
        private const val MAX_DAYS = 14
        private const val MAX_DAYS_BACK = 30

        /** Порция больше полутора килограмм — не еда, а ошибка в разборе. */
        private const val MAX_GRAMS = 1_500

        /** Ниже любого разбора правилами: продукт из базы, но выбирала его модель. */
        private const val PROPOSAL_CONFIDENCE = 0.6f

        private const val CENTIGRAMS = 100
    }
}

private fun dayLabel(back: Int): String = when (back) {
    0 -> "сегодня"
    1 -> "вчера"
    else -> "$back дн. назад"
}

/** Сотые грамма -> «4.2 г». */
private fun grams(centigrams: Int): String {
    val tenths = (centigrams + 5) / 10
    return if (tenths % 10 == 0) "${tenths / 10} г" else "${tenths / 10}.${tenths % 10} г"
}

private fun Any?.toGrams(): Int? = when (this) {
    is Number -> toDouble().takeIf { it.isFinite() }?.let { kotlin.math.round(it).toInt() }
    is String -> trim().replace(',', '.').toDoubleOrNull()?.let { kotlin.math.round(it).toInt() }
    else -> null
}

private fun List<DiaryEntryEntity>.sumTotals(): NutrimentTotals =
    fold(NutrimentTotals.ZERO) { sum, entry -> sum + entry.totals() }
