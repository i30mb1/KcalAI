package n7.kcalai.repositories

import kotlin.test.Test
import kotlin.test.assertEquals
import n7.kcalai.database.PastPickRow
import n7.kcalai.model.FoodRef

class PastPicksTest {

    /** Как разбор строки ввода: количество — не часть того, что человек ищет. */
    private val queryOf: (String) -> String? = { raw ->
        raw.replace(Regex("[0-9]+| г$"), " ").trim().replace(Regex(" +"), " ").ifEmpty { null }
    }

    @Test
    fun `тот же запрос с другим весом — тот же выбор`() {
        val picks = pastPicks(
            listOf(
                PastPickRow("каша утром", "generic:7", times = 1, lastAt = 300),
                PastPickRow("каша утром", "user:3", times = 1, lastAt = 200),
                PastPickRow("каша утром 200 г", "generic:7", times = 2, lastAt = 100),
            ),
            queryOf,
        )

        assertEquals(
            listOf(
                PastPick("каша утром", FoodRef.Generic(7), times = 3, lastAt = 300),
                PastPick("каша утром", FoodRef.User(3), times = 1, lastAt = 200),
            ),
            picks,
        )
    }

    @Test
    fun `битая ссылка и запрос из одних чисел пропускаются`() {
        val picks = pastPicks(
            listOf(
                PastPickRow("200", "generic:7", times = 1, lastAt = 300),
                PastPickRow("кефир", "мусор", times = 1, lastAt = 200),
                PastPickRow("кефир", "generic:9", times = 1, lastAt = 100),
            ),
            queryOf,
        )

        assertEquals(listOf(PastPick("кефир", FoodRef.Generic(9), times = 1, lastAt = 100)), picks)
    }
}
