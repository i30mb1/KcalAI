package n7.kcalai.repositories

import n7.kcalai.database.PastPickRow
import n7.kcalai.database.SemanticDao
import n7.kcalai.model.FoodRef
import n7.kcalai.model.parseFoodRef

/**
 * Что нужно поиску по смыслу, чтобы знать этого человека.
 *
 * @param model [EmbeddingCache]: какой моделью считаются отпечатки
 * @param dim длина отпечатков — та же, что у справочника
 * @param queryOf строка из журнала -> то, что из неё ушло бы в поиск. В журнале лежит
 *        ввод целиком («каша утром 200 г»), а разбор строки живёт в `:core:resolver`,
 *        который сам зависит от этого модуля. `null` — искать в строке нечего.
 */
class PersonalSemantic(
    val dao: SemanticDao,
    val model: String,
    val dim: Int,
    val queryOf: (String) -> String?,
)

/** Человек [times] раз назвал еду [query] и выбрал [ref]; в последний раз — в [lastAt]. */
data class PastPick(val query: String, val ref: FoodRef, val times: Int, val lastAt: Long)

/**
 * Журнал подтверждений -> прошлые выборы, свежие первыми.
 *
 * Вес из запроса убирается не для красоты: «каша утром 200 г» и «моя утренняя каша»
 * похожи на 0.90, а без веса — на 0.95, и порог прошлых запросов лежит между ними.
 * Заодно «кофе 200» и «кофе 250» складываются в один выбор и один отпечаток.
 */
fun pastPicks(rows: List<PastPickRow>, queryOf: (String) -> String?): List<PastPick> =
    rows.mapNotNull { row ->
        val query = queryOf(row.query) ?: return@mapNotNull null
        val ref = parseFoodRef(row.pickedRef) ?: return@mapNotNull null
        PastPick(query, ref, row.times, row.lastAt)
    }
        .groupBy { it.query to it.ref }
        .map { (_, same) -> same.first().copy(times = same.sumOf { it.times }, lastAt = same.maxOf { it.lastAt }) }
        .sortedByDescending { it.lastAt }
