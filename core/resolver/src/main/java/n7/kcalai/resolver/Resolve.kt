package n7.kcalai.resolver

import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate

sealed interface ResolveInput {
    data class Barcode(val gtin: String) : ResolveInput
    data class FreeText(val text: String) : ResolveInput
    // v2: data class Photo(val uri: Uri) : ResolveInput
}

/**
 * Одна разобранная позиция.
 *
 * [candidate] == null означает «сегмент прочитан, но продукт не опознан» — это не
 * ошибка и не повод молчать: чипс покажется серым, и человек поправит формулировку.
 */
data class ResolvedItem(
    /** Исходный кусок фразы — показывается, когда продукт не опознан. */
    val sourceText: String,
    val candidate: FoodCandidate?,
    val grams: Int,
    val confidence: Float,
    val source: EntrySource,
    /** Вес не был назван, подставлена типичная порция. Повод показать это в UI. */
    val gramsGuessed: Boolean = false,
    /**
     * Единица, в которой человек назвал количество, и сколько её было.
     *
     * `null` означает «вес мы подставили сами». Запоминать такой вес как личную
     * порцию нельзя: модель выучила бы собственную догадку вместо предпочтения человека.
     */
    val portionUnit: String? = null,
    val portionCount: Double = 1.0,
) {
    val isResolved: Boolean get() = candidate != null && grams > 0

    /** Вес одной единицы — то, что уходит в память личных порций. */
    val gramsPerUnit: Int
        get() = if (portionCount <= 0.0) grams else Math.round(grams / portionCount).toInt()
}

interface FoodResolver {
    fun handles(input: ResolveInput): Boolean
    suspend fun resolve(input: ResolveInput): List<ResolvedItem>
}
