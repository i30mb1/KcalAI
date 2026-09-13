package n7.kcalai.resolver

import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate
import n7.kcalai.personal.PersonalContext
import n7.kcalai.personal.Personalization
import n7.kcalai.repositories.FoodRepository

/**
 * Свободный текст -> позиции с граммами.
 *
 * Цифры КБЖУ берутся из локальной базы и никогда не выдумываются — резолвер
 * отвечает только на «что это» и «сколько грамм».
 *
 * Два режима, и они отвечают на разные вопросы:
 *
 * - [suggest] — одно блюдо, несколько вариантов «что это». Так человек вводит
 *   руками: пишет «гречка 200» и выбирает из предложенного.
 * - [resolve] — несколько блюд, по одному варианту на каждое. Так придут
 *   ингредиенты, перечисленные через запятую в сфотографированном меню.
 */
class TextFoodResolver(
    private val foods: FoodRepository,
    /**
     * Персонализация. `null` — обезличенный режим: та же выдача и те же табличные
     * порции для всех. Это рабочее состояние, а не заглушка: пока человек ничего
     * не подтвердил, персонализировать нечем.
     */
    private val personalization: Personalization? = null,
) : FoodResolver {

    /**
     * Одно блюдо -> до [limit] вариантов того, чем оно может быть.
     *
     * Количество разбирается один раз, а вот граммы считаются для каждого варианта
     * свои: «2 шт» для яйца это 120 г, а для банана 240 г. Одну цифру на всех
     * подставить нельзя.
     *
     * Вся строка считается одним блюдом — запятая здесь разделителем не служит.
     */
    suspend fun suggest(
        text: String,
        context: PersonalContext? = null,
        limit: Int = SUGGESTION_LIMIT,
    ): List<ResolvedItem> {
        if (text.isBlank()) return emptyList()
        val segment = parseSegment(text) ?: return emptyList()

        val found = foods.search(segment.name, limit)
        // Ранжирование меняет порядок, но не состав: выбросив кандидата, модель
        // лишила бы человека возможности себя поправить.
        val ranked = if (context == null || personalization == null) {
            found
        } else {
            personalization.rerank(found, context)
        }

        return ranked.map { candidate -> segment.toResolved(candidate) }
    }

    /**
     * Фраза из нескольких блюд -> по лучшему варианту на каждое.
     *
     * Сегментация по запятой и «и». Нужна для перечислений: состав блюда в меню,
     * результат распознавания фото. В ручном вводе не используется.
     */
    override suspend fun resolve(input: ResolveInput): List<ResolvedItem> {
        val text = (input as? ResolveInput.FreeText)?.text.orEmpty()
        if (text.isBlank()) return emptyList()

        return parsePhrase(text).map { segment ->
            val best = foods.search(segment.name, 1).firstOrNull()
                ?: return@map ResolvedItem(
                    sourceText = segment.raw,
                    candidate = null,
                    grams = 0,
                    confidence = 0f,
                    source = EntrySource.TEXT,
                )
            segment.toResolved(best)
        }
    }

    override fun handles(input: ResolveInput): Boolean = input is ResolveInput.FreeText

    /**
     * Личные граммовки ложатся поверх табличных.
     *
     * Общая таблица знает, сколько весит горсть орехов, — в среднем. Но «тарелка супа»
     * у разных людей отличается вдвое, и подтверждённая человеком цифра всегда
     * достовернее средней по миру.
     */
    private suspend fun ParsedSegment.toResolved(candidate: FoodCandidate): ResolvedItem {
        val personal = personalization?.portionUnits(candidate.ref).orEmpty()
        val grams = quantity.toGrams(
            units = foods.portionUnits(candidate.ref) + personal,
            defaultPortionG = personal[Units.PORTION] ?: candidate.servingG,
        )
        return ResolvedItem(
            sourceText = raw,
            candidate = candidate,
            grams = grams.value,
            confidence = confidenceOf(candidate, quantity),
            source = EntrySource.TEXT,
            gramsGuessed = grams.guessed,
            portionUnit = grams.unit,
            portionCount = grams.count,
        )
    }

    private companion object {
        const val SUGGESTION_LIMIT = 6
    }
}

/** Резолвер штрих-кода: ответ всегда один и всегда точный, вес берётся из порции. */
class BarcodeFoodResolver(
    private val foods: FoodRepository,
) : FoodResolver {

    override fun handles(input: ResolveInput): Boolean = input is ResolveInput.Barcode

    override suspend fun resolve(input: ResolveInput): List<ResolvedItem> {
        val gtin = (input as? ResolveInput.Barcode)?.gtin ?: return emptyList()
        val candidate = foods.byBarcode(gtin) ?: return emptyList()
        return listOf(
            ResolvedItem(
                sourceText = gtin,
                candidate = candidate,
                grams = candidate.servingG ?: 100,
                confidence = 1f,
                source = EntrySource.BARCODE,
                gramsGuessed = candidate.servingG == null,
            )
        )
    }
}

/**
 * Насколько можно доверять разбору.
 *
 * Два независимых источника сомнения: опознан ли продукт (совпал алиас целиком
 * или только отдельные слова) и назван ли вес.
 */
private fun confidenceOf(candidate: FoodCandidate, quantity: Quantity): Float {
    val nameScore = if (candidate.exactMatch) 1f else 0.7f
    val quantityScore = when (quantity) {
        is Quantity.Weight, is Quantity.Units -> 1f
        is Quantity.Bare -> 0.85f
        is Quantity.Vague -> 0.75f
        Quantity.None -> 0.7f
    }
    return nameScore * quantityScore
}
