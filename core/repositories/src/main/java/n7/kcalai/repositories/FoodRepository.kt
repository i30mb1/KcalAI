package n7.kcalai.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import n7.kcalai.database.UserFoodDao
import n7.kcalai.database.UserFoodEntity
import n7.kcalai.fooddb.FoodDb
import n7.kcalai.fooddb.ProductRow
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.Nutriments

/**
 * Отложенное открытие справочника.
 *
 * Раскладывание seed.db из assets — файловый ввод-вывод, и делать его в конструкторе
 * репозитория означает делать его в главном потоке. Источник открывает базу при первом
 * реальном запросе и на том же диспетчере, на котором она потом читается.
 */
fun interface FoodDbSource {
    suspend fun get(): FoodDb
}

/**
 * Единственная точка, где продукт превращается в КБЖУ.
 *
 * Порядок источников: свои продукты -> генерик-таблица -> брендовые товары.
 * Свои идут первыми, потому что пользователь завёл их вручную и ищет именно их.
 *
 * @param dispatcher обязан быть однопоточным: [FoodDb] держит одно соединение SQLite,
 *        а оно не потокобезопасно. Пул (обычный `Dispatchers.IO`) сюда не годится.
 */
class FoodRepository(
    private val foodDb: FoodDbSource,
    private val userFoodDao: UserFoodDao,
    private val dispatcher: CoroutineDispatcher,
) {

    private suspend fun <T> onDb(block: (FoodDb) -> T): T =
        withContext(dispatcher) { block(foodDb.get()) }

    suspend fun byBarcode(gtin: String): FoodCandidate? {
        userFoodDao.findByBarcode(gtin)?.let { return it.toCandidate() }
        return onDb { it.findByBarcode(gtin) }?.toCandidate()
    }

    suspend fun search(query: String, limit: Int): List<FoodCandidate> {
        if (query.isBlank() || limit <= 0) return emptyList()

        val own = userFoodDao.search(query, limit).map { it.toCandidate() }
        if (own.size >= limit) return own.take(limit)

        return onDb { db ->
            val generic = db.searchGeneric(query, limit - own.size).map {
                FoodCandidate(
                    ref = FoodRef.Generic(it.id),
                    displayName = it.nameRu,
                    nutriments = it.nutriments,
                    servingG = it.defaultPortionG,
                    exactMatch = it.exactMatch,
                )
            }
            val taken = own.size + generic.size
            if (taken >= limit) {
                (own + generic).take(limit)
            } else {
                val products = db.searchProducts(query, limit - taken).map { it.toCandidate() }
                (own + generic + products).take(limit)
            }
        }
    }

    /** Порционная единица конкретного продукта: «шт» у яйца — 60 г, у банана — 120 г. */
    suspend fun portionUnits(ref: FoodRef): Map<String, Int> = when (ref) {
        is FoodRef.Generic -> onDb { it.portionUnits(ref.id) }
        else -> emptyMap()
    }

    /** Актуальные значения по ссылке. `null`, если ссылка протухла после обновления справочника. */
    suspend fun nutrimentsFor(ref: FoodRef): Nutriments? = when (ref) {
        is FoodRef.Barcode -> onDb { it.findByBarcode(ref.gtin) }?.nutriments
        is FoodRef.Generic -> onDb { it.genericById(ref.id) }?.nutriments
        is FoodRef.User -> userFoodDao.findById(ref.id)?.toNutriments()
    }
}

private fun ProductRow.toCandidate(): FoodCandidate =
    FoodCandidate(
        ref = FoodRef.Barcode(barcode),
        displayName = name,
        nutriments = nutriments,
        servingG = servingG,
    )

private fun UserFoodEntity.toCandidate(): FoodCandidate =
    FoodCandidate(
        ref = FoodRef.User(id),
        displayName = name,
        nutriments = toNutriments(),
        servingG = null,
    )

private fun UserFoodEntity.toNutriments(): Nutriments =
    Nutriments(kcal100 = kcal100, prot100 = prot100, fat100 = fat100, carb100 = carb100)
