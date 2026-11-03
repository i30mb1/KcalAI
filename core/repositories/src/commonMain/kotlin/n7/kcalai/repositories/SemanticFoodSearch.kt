package n7.kcalai.repositories

import kotlin.math.sqrt
import n7.kcalai.database.EmbeddingMarkup
import n7.kcalai.fooddb.normalizeForSearch
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef

/**
 * Текст -> отпечаток той же модели, которой посчитаны отпечатки справочника.
 *
 * `null` — модели на телефоне нет: Play ещё не докачал пак или телефон его не тянет.
 * Это штатное состояние, а не ошибка.
 */
interface QueryEmbedder {

    /** Отпечаток того, что человек набрал: разметка запроса. */
    suspend fun embed(text: String): FloatArray?

    /** Отпечаток того, что ищут: разметка документа, как у продуктов справочника. */
    suspend fun embedDocument(text: String): FloatArray?
}

data class ScoredId(val id: Long, val score: Float)

/**
 * Отпечатки продуктов и поиск ближайших по косинусу.
 *
 * Продуктов сотни, поэтому перебор: дерево поиска окупилось бы на сотнях тысяч.
 * Отпечатки базы уже приведены к длине 1, и косинус — это просто скалярное произведение.
 */
class SemanticIndex(private val vectors: Map<Long, FloatArray>) {

    private val dim = vectors.values.firstOrNull()?.size ?: 0

    val isEmpty: Boolean get() = vectors.isEmpty()

    fun nearest(query: FloatArray, limit: Int, minScore: Float): List<ScoredId> {
        if (dim == 0 || query.size < dim) return emptyList()
        val q = normalized(query, dim)
        return vectors.entries
            .map { (id, v) -> ScoredId(id, dot(q, v)) }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /** Похожесть на один продукт; `null` — такого в индексе нет. */
    fun score(query: FloatArray, id: Long): Float? = vectors[id]?.let { similarity(query, it) }
}

/**
 * Поиск по смыслу: «барщ», «кофеёк с молочком», «оливьешка».
 *
 * Только запасной путь — когда обычный поиск по словам не нашёл ничего. Слова
 * точнее: «гречка» обязана найти гречку отварную, а у отпечатков гречка отварная,
 * с грибами и сухая крупа почти равны. Найденное здесь всегда помечено как
 * неточное совпадение, и человек выбирает из вариантов тапом.
 *
 * Три источника, и порядок выдачи такой:
 *
 * 1. Прошлые выборы: похожий запрос человек уже подтверждал, см. [confirmedPicks].
 * 2. Свои продукты, похожие уверенно — не ниже [OWN_FIRST_SCORE].
 * 3. Справочник вместе с остальными своими — просто по похожести.
 *
 * Свои продукты не идут первыми безусловно: «кофе с молоком» похож на своё «Молоко
 * Домик в деревне» на 0.76, и выше настоящего кофе из справочника это молоко стоять
 * не должно. Порядок здесь черновой — дальше выдачу переставляет личное ранжирование.
 *
 * @param resolve ссылки -> продукты в том же порядке; пропавшие пропускаются
 * @param cache отпечатки личных строк; `null` — личных источников нет
 * @param ownFoods свои продукты, от новых к старым
 * @param pastPicks прошлые выборы, от свежих к старым
 */
class SemanticFoodSearch(
    private val embedder: QueryEmbedder,
    private val index: suspend () -> SemanticIndex?,
    private val resolve: suspend (refs: List<FoodRef>) -> List<FoodCandidate>,
    private val cache: EmbeddingCache? = null,
    private val ownFoods: suspend () -> List<FoodCandidate> = { emptyList() },
    private val pastPicks: suspend () -> List<PastPick> = { emptyList() },
) {

    suspend fun search(query: String, limit: Int): List<FoodCandidate> {
        val generic = index()?.takeUnless { it.isEmpty }
        val own = if (cache == null) emptyList() else ownFoods()
        val past = if (cache == null) emptyList() else pastPicks()
        if (generic == null && own.isEmpty() && past.isEmpty()) return emptyList()
        val vector = embedder.embed(query) ?: return emptyList()

        val ownKeys = own.map { documentKey(it.displayName) }
        val pastKeys = past.map { EmbeddingKey(EmbeddingMarkup.QUERY, it.query) }
        // Свои продукты вперёд: их десятки, и прошлые запросы не должны съесть их бюджет.
        val personal = cache?.vectors(ownKeys + pastKeys, NEW_VECTORS_PER_SEARCH).orEmpty()

        val genericHits = generic?.nearest(vector, limit, ANY_SCORE).orEmpty()
        val ownHits = indexOf(ownKeys, personal).nearest(vector, limit, ANY_SCORE)
        val close = indexOf(pastKeys, personal).nearest(vector, past.size, PAST_QUERY_SCORE)
        val picks = confirmedPicks(
            typed = normalizeForSearch(query),
            vector = vector,
            past = past,
            close = close.map { past[it.id.toInt()] },
            generic = generic,
            bestFound = listOfNotNull(genericHits.firstOrNull()?.score, ownHits.firstOrNull()?.score),
        )

        val (ownFirst, ownRest) = ownHits.filter { it.score >= MIN_SCORE }.partition { it.score >= OWN_FIRST_SCORE }
        val rest = ownRest.map { own[it.id.toInt()].ref to it.score } +
            genericHits.filter { it.score >= MIN_SCORE }.map { FoodRef.Generic(it.id) to it.score }

        val refs = picks +
            ownFirst.map { own[it.id.toInt()].ref } +
            rest.sortedByDescending { it.second }.map { it.first }
        return resolve(refs.distinct().take(limit)).map { it.copy(exactMatch = false) }
    }

    /**
     * Прошлые выборы, которым можно верить для этого запроса; частые первыми, из равных — свежие.
     *
     * Близости запросов мало: «пельмени со сметаной» и «вареники со сметаной» похожи на 0.96,
     * больше, чем «каша утром» и «моя утренняя каша» (0.95). Форму фразы модель видит лучше,
     * чем еду в ней. Поэтому сам выбранный продукт тоже сверяется с запросом: он обязан быть
     * не сильно хуже лучшего из найденного, см. [PICK_SLACK].
     *
     * Без сверки проходит только тот же самый запрос: человек уже сказал, что это значит,
     * а своё словечко на название продукта и не обязано быть похоже.
     */
    private suspend fun confirmedPicks(
        typed: String,
        vector: FloatArray,
        past: List<PastPick>,
        close: List<PastPick>,
        generic: SemanticIndex?,
        bestFound: List<Float>,
    ): List<FoodRef> {
        val repeated = past.filter { it.query == typed }
        val ordered = (repeated + close).distinct()
            .groupBy { it.ref }
            .entries
            .sortedWith(
                compareBy(
                    { (_, same) -> -same.sumOf { it.times } },
                    { (_, same) -> -same.maxOf { it.lastAt } },
                )
            )
            .map { it.key }
        if (ordered.isEmpty()) return emptyList()

        val found = resolve(ordered)
        // Отпечаток товара со штрих-кодом раньше считать было не с чего: название известно только здесь.
        val documents = cache?.vectors(
            found.filterNot { it.ref is FoodRef.Generic }.map { documentKey(it.displayName) },
            NEW_VECTORS_PER_SEARCH,
        ).orEmpty()
        val scores = found.associate { candidate ->
            val ref = candidate.ref
            ref to if (ref is FoodRef.Generic) {
                generic?.score(vector, ref.id)
            } else {
                documents[documentKey(candidate.displayName)]?.let { similarity(vector, it) }
            }
        }
        val best = (scores.values.filterNotNull() + bestFound).maxOrNull()

        return found.map { it.ref }.filter { ref ->
            val score = scores[ref]
            repeated.any { it.ref == ref } || (score != null && best != null && score >= best - PICK_SLACK)
        }
    }

    private companion object {
        /**
         * Порог похожести. Подобран на EmbeddingGemma 2 270M по нашей базе:
         * бессмыслица («хрючево», «привет как дела») даёт 0.66–0.72,
         * а слабые, но верные совпадения («ложка мёда» -> Мёд) — от 0.73.
         */
        const val MIN_SCORE = 0.73f

        /**
         * С этой похожести свой продукт стоит выше справочника. Мерено на 22 парах запрос ->
         * свой продукт: чужие доходят до 0.78 («гречка» -> Греческий йогурт, «сырок
         * глазированный» -> Творог), верные — 0.78–0.89, восемь из десяти выше 0.80.
         * Оставшиеся не теряются, а стоят среди справочника по похожести.
         */
        const val OWN_FIRST_SCORE = 0.80f

        /**
         * Запросы похожи настолько, что прошлый выбор годится и сейчас.
         *
         * Намного строже [MIN_SCORE]: оба текста в одной разметке, и косинус у них высокий
         * сам по себе. Мерено на 60 парах: разная еда в одинаковой фразе — обычно до 0.93
         * («блины со сметаной» и «сырники со сметаной», «йогурт клубничный» и «черничный»),
         * а при 0.73 совпало бы почти всё. Пересказ той же еды — 0.84–0.99, и выше 0.94
         * остаётся 13 пар из 20; остальное находит справочник, просто без подсказки прошлым
         * выбором. Порог разделяет не всё: «пельмени» и «вареники со сметаной» дают 0.96,
         * и такие случаи ловит сверка с продуктом.
         */
        const val PAST_QUERY_SCORE = 0.94f

        /**
         * На сколько выбранный продукт может быть хуже лучшего из найденного. Верные выборы
         * отстают на 0.00–0.03 («творожок утром» -> Творог 5%: 0.751 при лучшем 0.781), а
         * пельмени для «вареников со сметаной» — на 0.055. Отпечатки каш и творогов почти
         * равны, поэтому отставание в сотые доли — ещё не повод спорить с человеком.
         */
        const val PICK_SLACK = 0.04f

        /**
         * Сколько новых отпечатков считается за один поиск: 12 по 70 мс — меньше секунды
         * сверх ожидания. Обычно это одна-две новые строки; накопленное до обновления
         * приложения досчитается за десятки поисков, начиная с новых продуктов и запросов.
         */
        const val NEW_VECTORS_PER_SEARCH = 12

        /** Отбор порогами идёт позже: лучшая похожесть нужна и тогда, когда порога никто не взял. */
        const val ANY_SCORE = -1f
    }
}

private fun documentKey(name: String) = EmbeddingKey(EmbeddingMarkup.DOCUMENT, name)

/** Индекс по позициям в [keys]: то, чего в [vectors] ещё нет, в поиске не участвует. */
private fun indexOf(keys: List<EmbeddingKey>, vectors: Map<EmbeddingKey, FloatArray>) = SemanticIndex(
    keys.withIndex()
        .mapNotNull { (position, key) -> vectors[key]?.let { position.toLong() to it } }
        .toMap()
)

/** Косинус запроса с отпечатком длины 1; запрос короче отпечатка сравнивать не с чем. */
private fun similarity(query: FloatArray, vector: FloatArray): Float? =
    if (query.size < vector.size) null else dot(normalized(query, vector.size), vector)

internal fun normalized(v: FloatArray, dim: Int): FloatArray {
    var sum = 0.0
    for (i in 0 until dim) sum += v[i] * v[i]
    val norm = sqrt(sum).toFloat()
    return FloatArray(dim) { if (norm == 0f) 0f else v[it] / norm }
}

private fun dot(a: FloatArray, b: FloatArray): Float {
    var sum = 0f
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}
