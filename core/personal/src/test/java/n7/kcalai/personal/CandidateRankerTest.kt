package n7.kcalai.personal

import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.MealType
import n7.kcalai.model.Nutriments
import org.junit.Assert.assertEquals
import org.junit.Test

/** Идея 2: ранжирование выдачи под конкретного человека. */
class CandidateRankerTest {

    /** Человек две недели пьёт кефир и ни разу не брал кетчуп. */
    private fun history(): FoodHistory {
        val entries = mutableListOf<DiaryEntryEntity>()
        var id = 1L
        for (day in TODAY - 13..TODAY) {
            entries += entry(id++, day, 21, MealType.SNACK, "Кефир 1%", KEFIR, grams = 250)
        }
        return historyOf(entries)
    }

    /** Порядок FTS: «кетчуп» точным совпадением выше, «кефир» — вторым. */
    private val fromSearch = listOf(
        candidate(KETCHUP, "Кетчуп", exactMatch = true),
        candidate(KEFIR, "Кефир 1%", exactMatch = false),
    )

    @Test
    fun `до обучения порядок в точности такой же, как у поиска`() {
        val ranked = CandidateRanker.rerank(
            weights = CandidateRanker.INITIAL.copyOf(),
            history = history(),
            candidates = fromSearch,
            context = contextAt(hour = 21, meal = MealType.SNACK),
        )

        assertEquals(listOf("Кетчуп", "Кефир 1%"), ranked.map { it.displayName })
    }

    @Test
    fun `после нескольких выборов второй вариант поднимается на первое место`() {
        val history = history()
        val context = contextAt(hour = 21, meal = MealType.SNACK)
        val weights = CandidateRanker.INITIAL.copyOf()

        val features = fromSearch.mapIndexed { index, candidate ->
            CandidateRanker.features(history, candidate.ref, candidate.exactMatch, index, context)
        }

        // Человек раз за разом выбирает не то, что поиск поставил первым, —
        // ровно тот сигнал, ради которого журнал подтверждений и заводился.
        repeat(TRAINING_ROUNDS) {
            CandidateRanker.train(weights, features, pickedIndex = 1)
        }

        val ranked = CandidateRanker.rerank(weights, history, fromSearch, context)
        assertEquals(listOf("Кефир 1%", "Кетчуп"), ranked.map { it.displayName })
    }

    @Test
    fun `состав выдачи обучение не меняет`() {
        val history = history()
        val context = contextAt(hour = 21, meal = MealType.SNACK)
        val weights = CandidateRanker.INITIAL.copyOf()
        val features = fromSearch.mapIndexed { index, candidate ->
            CandidateRanker.features(history, candidate.ref, candidate.exactMatch, index, context)
        }
        repeat(TRAINING_ROUNDS) { CandidateRanker.train(weights, features, pickedIndex = 1) }

        val ranked = CandidateRanker.rerank(weights, history, fromSearch, context)

        // Выбросив кандидата, модель лишила бы человека возможности себя поправить.
        assertEquals(fromSearch.toSet(), ranked.toSet())
    }

    private fun candidate(id: Long, name: String, exactMatch: Boolean) = FoodCandidate(
        ref = FoodRef.Generic(id),
        displayName = name,
        nutriments = Nutriments(100, 1000, 500, 1000),
        servingG = 200,
        exactMatch = exactMatch,
    )

    private companion object {
        const val KETCHUP = 10L
        const val KEFIR = 11L
        const val TRAINING_ROUNDS = 20
    }
}
