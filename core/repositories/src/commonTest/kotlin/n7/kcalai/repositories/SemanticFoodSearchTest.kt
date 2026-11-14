package n7.kcalai.repositories

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.Nutriments

/**
 * Векторы — единичные на плоскости ([at]): косинус двух — косинус разности углов.
 * Пороги в углах: 0.73 ≈ 43°, 0.80 ≈ 37°, 0.94 ≈ 20°.
 */
class SemanticFoodSearchTest {

    private val borscht = generic(602, "Борщ")
    private val bread = generic(17, "Хлеб ржаной (чёрный)")
    private val oatmeal = generic(7, "Овсяная каша на молоке")
    private val semolina = generic(8, "Манная каша на молоке")
    private val cottage = own(3, "Творог Простоквашино 5%")
    private val cola = FoodCandidate(
        ref = FoodRef.Barcode("4600000000001"),
        displayName = "Кола Зеро",
        nutriments = Nutriments(kcal100 = 0, prot100 = 0, fat100 = 0, carb100 = 0),
        servingG = 330,
    )

    private val catalog = listOf(borscht, bread, oatmeal, semolina, cottage, cola).associateBy { it.ref }

    private val index = SemanticIndex(
        mapOf(
            602L to floatArrayOf(1f, 0f),
            17L to floatArrayOf(0f, 1f),
        )
    )

    private var embedded = 0

    private fun search(
        vector: FloatArray?,
        index: SemanticIndex? = this.index,
    ) = SemanticFoodSearch(
        embedder = object : QueryEmbedder {
            override suspend fun embed(text: String): FloatArray? = vector.also { embedded++ }
            override suspend fun embedDocument(text: String): FloatArray? = null
        },
        index = { index },
        resolve = { refs -> refs.mapNotNull(catalog::get) },
    )

    /** Поиск с личными источниками; [query] — текущий запрос, у него угол 0°. */
    private fun personal(
        embedder: FakeEmbedder,
        index: SemanticIndex? = null,
        ownFoods: List<FoodCandidate> = emptyList(),
        pastPicks: List<PastPick> = emptyList(),
        catalog: Map<FoodRef, FoodCandidate> = this.catalog,
    ) = SemanticFoodSearch(
        embedder = embedder,
        index = { index },
        resolve = { refs -> refs.mapNotNull(catalog::get) },
        cache = EmbeddingCache(FakeSemanticDao(), embedder, model = "m", dim = 2),
        ownFoods = { ownFoods },
        pastPicks = { pastPicks },
    )

    @Test
    fun `опечатка находит продукт по смыслу`() = runTest {
        val found = search(floatArrayOf(0.95f, 0.31f)).search("барщ", limit = 3)

        assertEquals(borscht, found.first())
    }

    @Test
    fun `найденное по смыслу не выдаётся за точное совпадение`() = runTest {
        val found = search(floatArrayOf(1f, 0f)).search("борщец", limit = 3)

        assertFalse(found.first().exactMatch)
    }

    @Test
    fun `бессмыслица не притягивает ближайший продукт`() = runTest {
        // Косинус 0.7 у обоих — так «хрючево» похоже на всё сразу и ни на что.
        val found = search(floatArrayOf(0.7f, 0.7f)).search("хрючево", limit = 3)

        assertTrue(found.isEmpty(), "$found")
    }

    @Test
    fun `модель ещё не скачана — поиск по смыслу молчит`() = runTest {
        assertTrue(search(vector = null).search("барщ", limit = 3).isEmpty())
    }

    @Test
    fun `база без отпечатков и без личного не будит модель`() = runTest {
        // Докачанный старый seed.db таблицы не знает: грузить модель ради пустого
        // индекса — это сотня мегабайт памяти впустую.
        val found = search(floatArrayOf(1f, 0f), index = null).search("барщ", limit = 3)

        assertTrue(found.isEmpty())
        assertEquals(0, embedded)
    }

    // --- Свои продукты -------------------------------------------------------------------

    @Test
    fun `свой продукт находится по смыслу размеченный документом`() = runTest {
        val embedder = FakeEmbedder(
            queries = mapOf("творожок простоквашинский" to at(0.0)),
            documents = mapOf(cottage.displayName to at(20.0)),
        )

        val found = personal(embedder, ownFoods = listOf(cottage)).search("творожок простоквашинский", limit = 3)

        assertEquals(listOf(cottage.ref), found.map { it.ref })
        assertFalse(found.single().exactMatch)
        assertTrue("d:${cottage.displayName}" in embedder.calls, "${embedder.calls}")
    }

    @Test
    fun `уверенно похожий свой продукт — раньше справочника`() = runTest {
        val embedder = FakeEmbedder(
            queries = mapOf("борщец" to at(0.0)),
            documents = mapOf(cottage.displayName to at(30.0)),
        )

        val found = personal(embedder, index = index, ownFoods = listOf(cottage)).search("борщец", limit = 3)

        assertEquals(listOf(cottage.ref, borscht.ref), found.map { it.ref })
    }

    @Test
    fun `слабо похожий свой продукт встаёт среди справочника по похожести`() = runTest {
        // 40° — косинус 0.77: выше порога поиска, но ниже уверенного.
        val embedder = FakeEmbedder(
            queries = mapOf("борщец" to at(0.0), "хлебушек" to at(85.0)),
            documents = mapOf(cottage.displayName to at(40.0)),
        )
        val search = personal(embedder, index = index, ownFoods = listOf(cottage))

        assertEquals(listOf(borscht.ref, cottage.ref), search.search("борщец", limit = 3).map { it.ref })
        // Для «хлебушка» творог на 45° от запроса — не похож; хлеб на 5°.
        assertEquals(listOf(bread.ref), search.search("хлебушек", limit = 3).map { it.ref })
    }

    @Test
    fun `справочника нет а свои продукты есть — ищем по своим`() = runTest {
        val embedder = FakeEmbedder(
            queries = mapOf("творожок" to at(0.0)),
            documents = mapOf(cottage.displayName to at(10.0)),
        )

        val found = personal(embedder, index = null, ownFoods = listOf(cottage)).search("творожок", limit = 3)

        assertEquals(listOf(cottage.ref), found.map { it.ref })
    }

    // --- Прошлые выборы ------------------------------------------------------------------

    /** Справочник каш: манка на 25° от текущего запроса, овсянка — на [oatmealAngle]. */
    private fun porridges(oatmealAngle: Double) = SemanticIndex(mapOf(8L to at(25.0), 7L to at(oatmealAngle)))

    @Test
    fun `похожий прошлый запрос приносит то что человек тогда выбрал`() = runTest {
        val embedder = FakeEmbedder(queries = mapOf("моя утренняя каша" to at(0.0), "каша утром" to at(15.0)))
        val past = listOf(PastPick("каша утром", oatmeal.ref, times = 1, lastAt = 100))

        // Сама по себе овсянка (28°) запросу чуть дальше манки (25°), но выбирал человек её.
        val found = personal(embedder, index = porridges(28.0), pastPicks = past).search("моя утренняя каша", limit = 3)

        assertEquals(listOf(oatmeal.ref, semolina.ref), found.map { it.ref })
        assertFalse(found.first().exactMatch)
    }

    @Test
    fun `прошлый выбор не годится если сам продукт запросу заметно чужой`() = runTest {
        // «пельмени со сметаной» и «вареники со сметаной» как запросы почти одно и то же,
        // а пельмени для «вареников» далеко позади лучшего из справочника.
        val embedder = FakeEmbedder(queries = mapOf("моя утренняя каша" to at(0.0), "каша утром" to at(15.0)))
        val past = listOf(PastPick("каша утром", oatmeal.ref, times = 5, lastAt = 100))

        val found = personal(embedder, index = porridges(40.0), pastPicks = past).search("моя утренняя каша", limit = 3)

        assertEquals(semolina.ref, found.first().ref)
    }

    @Test
    fun `просто похожий прошлый запрос — не повод`() = runTest {
        // 25° — косинус 0.91: так похожи «творог на завтрак» и «яичница на завтрак».
        val embedder = FakeEmbedder(queries = mapOf("моя утренняя каша" to at(0.0), "каша утром" to at(25.0)))
        val past = listOf(PastPick("каша утром", bread.ref, times = 5, lastAt = 100))

        val found = personal(embedder, index = index, pastPicks = past).search("моя утренняя каша", limit = 3)

        assertFalse(found.any { it.ref == bread.ref }, "$found")
    }

    @Test
    fun `тот же самый запрос — его выбор и без сверки с продуктом`() = runTest {
        // Своё словечко: «хрючево» на продукт не похоже ничем, но человек так зовёт борщ.
        val embedder = FakeEmbedder(queries = mapOf("Хрючево" to at(90.0), "хрючево" to at(90.0)))
        val past = listOf(PastPick("хрючево", borscht.ref, times = 1, lastAt = 100))

        val found = personal(embedder, index = index, pastPicks = past).search("Хрючево", limit = 3)

        assertEquals(borscht.ref, found.first().ref)
    }

    @Test
    fun `из похожих прошлых выигрывает выбранное чаще при равенстве — позже`() = runTest {
        val embedder = FakeEmbedder(
            queries = mapOf(
                "кашка" to at(0.0),
                "каша утром" to at(10.0),
                "каша с утра" to at(-10.0),
                "утренняя каша" to at(5.0),
            ),
        )
        val past = listOf(
            PastPick("каша утром", semolina.ref, times = 1, lastAt = 300),
            PastPick("каша с утра", oatmeal.ref, times = 2, lastAt = 100),
            PastPick("утренняя каша", oatmeal.ref, times = 1, lastAt = 50),
            PastPick("каша с утра", borscht.ref, times = 1, lastAt = 200),
        )
        val index = SemanticIndex(mapOf(7L to at(10.0), 8L to at(10.0), 602L to at(10.0)))

        val found = personal(embedder, index = index, pastPicks = past).search("кашка", limit = 3)

        assertEquals(listOf(oatmeal.ref, semolina.ref, borscht.ref), found.map { it.ref })
    }

    @Test
    fun `товар со штрих-кодом сверяется с запросом по своему названию`() = runTest {
        val embedder = FakeEmbedder(
            queries = mapOf("колка без сахара" to at(0.0), "кола без сахара" to at(10.0)),
            documents = mapOf(cola.displayName to at(5.0)),
        )
        val past = listOf(PastPick("кола без сахара", cola.ref, times = 1, lastAt = 100))

        val found = personal(embedder, index = index, pastPicks = past).search("колка без сахара", limit = 3)

        assertEquals(listOf(cola.ref, borscht.ref), found.map { it.ref })
    }

    @Test
    fun `одно и то же не повторяется и лишнего не выдаётся`() = runTest {
        val embedder = FakeEmbedder(
            queries = mapOf("борщец" to at(0.0), "борщик" to at(5.0)),
            documents = mapOf(cottage.displayName to at(30.0)),
        )
        val past = listOf(PastPick("борщик", borscht.ref, times = 1, lastAt = 100))
        val index = SemanticIndex(mapOf(602L to at(0.0), 7L to at(10.0), 8L to at(20.0)))

        val found = personal(embedder, index = index, ownFoods = listOf(cottage), pastPicks = past)
            .search("борщец", limit = 3)

        assertEquals(listOf(borscht.ref, cottage.ref, oatmeal.ref), found.map { it.ref })
    }

    @Test
    fun `выбор пропавший из справочника пропускается`() = runTest {
        val embedder = FakeEmbedder(queries = mapOf("борщец" to at(0.0), "борщик" to at(5.0)))
        val gone = FoodRef.Generic(999)
        val past = listOf(PastPick("борщик", gone, times = 9, lastAt = 100))

        val found = personal(embedder, index = index, pastPicks = past).search("борщец", limit = 3)

        assertEquals(listOf(borscht.ref), found.map { it.ref })
    }

    private fun generic(id: Long, name: String) = FoodCandidate(
        ref = FoodRef.Generic(id),
        displayName = name,
        nutriments = Nutriments(kcal100 = 50, prot100 = 100, fat100 = 100, carb100 = 100),
        servingG = 300,
    )

    private fun own(id: Long, name: String) = FoodCandidate(
        ref = FoodRef.User(id),
        displayName = name,
        nutriments = Nutriments(kcal100 = 120, prot100 = 1_600, fat100 = 500, carb100 = 300),
        servingG = null,
    )
}
