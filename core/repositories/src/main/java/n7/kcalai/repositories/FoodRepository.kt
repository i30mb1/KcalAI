package n7.kcalai.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import n7.kcalai.database.CachedProductDao
import n7.kcalai.database.CachedProductEntity
import n7.kcalai.database.ContributionDao
import n7.kcalai.database.ContributionEntity
import n7.kcalai.database.UserFoodDao
import n7.kcalai.database.UserFoodEntity
import n7.kcalai.fooddb.FoodDb
import n7.kcalai.fooddb.ProductRow
import n7.kcalai.fooddb.matchesQuery
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.Nutriments
import n7.kcalai.model.ProductOrigin

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

/** Товар, пришедший по сети. [origin] решает, можно ли его отдавать дальше. */
data class RemoteProduct(
    val gtin: String,
    val name: String,
    val brand: String?,
    val nutriments: Nutriments,
    val servingG: Int?,
    val origin: ProductOrigin,
)

/**
 * Сеть глазами репозитория.
 *
 * `null` значит «не нашли» и «не смогли спросить» одновременно, и различать их здесь
 * незачем: оба случая ведут в одну и ту же форму ручного ввода. Реализация обязана
 * не бросать на сетевых сбоях — промах это штатный ход, а не исключительная ситуация.
 */
fun interface RemoteProductSource {
    suspend fun fetch(gtin: String): RemoteProduct?
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
    private val cachedProductDao: CachedProductDao,
    private val contributionDao: ContributionDao,
    private val remote: RemoteProductSource,
    private val dispatcher: CoroutineDispatcher,
) {

    private suspend fun <T> onDb(block: (FoodDb) -> T): T =
        withContext(dispatcher) { block(foodDb.get()) }

    /**
     * Цепочка поиска по штрих-коду: локальное, потом сеть.
     *
     * Каждый шаг дороже предыдущего, поэтому порядок не косметический. Свои продукты
     * и справочник отвечают мгновенно и офлайн; кэш — это прошлые сетевые ответы,
     * из-за него повторный скан того же товара работает в самолёте; и только потом
     * поднимается радио.
     *
     * Сетевой ответ оседает в `cached_product` с пометкой источника. В `user_food`
     * он не попадает никогда — там живёт только введённое человеком, и именно
     * поэтому очередь отправки не может заразиться данными под ODbL.
     */
    suspend fun byBarcode(gtin: String): FoodCandidate? {
        userFoodDao.findByBarcode(gtin)?.let { return it.toCandidate() }
        onDb { it.findByBarcode(gtin) }?.let { return it.toCandidate() }
        cachedProductDao.findByGtin(gtin)?.let { return it.toCandidate() }

        val fetched = remote.fetch(gtin) ?: return null
        cachedProductDao.insert(fetched.toEntity(fetchedAt = System.currentTimeMillis()))
        return fetched.toCandidate()
    }

    /**
     * Продукт, заведённый человеком после промаха скана.
     *
     * Две записи в одном вызове, и разводить их по разным местам нельзя: `user_food`
     * закрывает вопрос для этого телефона навсегда и офлайн, `contribution` — обещание
     * отдать находку остальным. Забыть второе означало бы, что каждый пользователь
     * бьётся с одним и тем же промахом в одиночку.
     */
    suspend fun saveOwnProduct(
        gtin: String?,
        name: String,
        nutriments: Nutriments,
        servingG: Int?,
        now: Long,
    ): FoodCandidate {
        // Тот же код мог заводиться раньше: человек пересканировал пачку, чтобы
        // поправить цифру. Тогда это правка существующей строки, а не новый
        // продукт, — иначе `id` меняется, и личная история продукт не узнаёт.
        val existing = gtin?.let { userFoodDao.findByBarcode(it) }
        val entity = UserFoodEntity(
            id = existing?.id ?: 0,
            barcode = gtin,
            name = name,
            kcal100 = nutriments.kcal100,
            prot100 = nutriments.prot100,
            fat100 = nutriments.fat100,
            carb100 = nutriments.carb100,
            servingG = servingG,
            // Дата заведения — дата первой встречи с продуктом, а не последней правки.
            createdAt = existing?.createdAt ?: now,
        )

        val id = if (existing != null) {
            userFoodDao.update(entity)
            existing.id
        } else {
            userFoodDao.insert(entity)
        }

        // Без кода вклад бесполезен: у остальных нечем его найти.
        if (gtin != null) {
            contributionDao.insert(
                ContributionEntity(
                    gtin = gtin,
                    name = name,
                    kcal100 = nutriments.kcal100,
                    prot100 = nutriments.prot100,
                    fat100 = nutriments.fat100,
                    carb100 = nutriments.carb100,
                    servingG = servingG,
                    createdAt = now,
                )
            )
        }

        return FoodCandidate(
            ref = FoodRef.User(id),
            displayName = name,
            nutriments = nutriments,
            servingG = servingG,
        )
    }

    suspend fun search(query: String, limit: Int): List<FoodCandidate> {
        if (query.isBlank() || limit <= 0) return emptyList()

        val own = ownMatches(query, limit)
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

    /**
     * Свои продукты, отвечающие запросу.
     *
     * Первыми — короткие названия: чем короче название, тем большую его долю
     * покрыл запрос, а значит, тем точнее попадание. При равной длине выигрывает
     * заведённое позже — свежая находка человеку нужнее прошлогодней. Тот же
     * порядок, что был у SQL-запроса до того, как отбор переехал сюда.
     */
    private suspend fun ownMatches(query: String, limit: Int): List<FoodCandidate> =
        userFoodDao.all()
            .filter { matchesQuery(it.name, query) }
            .sortedWith(compareBy({ it.name.length }, { -it.createdAt }))
            .take(limit)
            .map { it.toCandidate() }

    /** Порционная единица конкретного продукта: «шт» у яйца — 60 г, у банана — 120 г. */
    suspend fun portionUnits(ref: FoodRef): Map<String, Int> = when (ref) {
        is FoodRef.Generic -> onDb { it.portionUnits(ref.id) }
        else -> emptyMap()
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
        servingG = servingG,
    )

private fun UserFoodEntity.toNutriments(): Nutriments =
    Nutriments(kcal100 = kcal100, prot100 = prot100, fat100 = fat100, carb100 = carb100)

/**
 * Бренд перед названием: «Активиа» и «Danone Активиа» человек ищет одинаково.
 *
 * Задвоение отсекается, потому что в Open Food Facts оно массовое: у Nutella
 * и бренд, и название — «Nutella», и без проверки чипс подписан «Nutella Nutella».
 */
private fun brandedName(brand: String?, name: String): String {
    val prefix = brand?.trim()?.takeIf { it.isNotEmpty() } ?: return name
    return if (name.startsWith(prefix, ignoreCase = true)) name else "$prefix $name"
}

private fun CachedProductEntity.toCandidate(): FoodCandidate =
    FoodCandidate(
        ref = FoodRef.Barcode(gtin),
        displayName = brandedName(brand, name),
        nutriments = toNutriments(),
        servingG = servingG,
    )

private fun CachedProductEntity.toNutriments(): Nutriments =
    Nutriments(kcal100 = kcal100, prot100 = prot100, fat100 = fat100, carb100 = carb100)

private fun RemoteProduct.toCandidate(): FoodCandidate =
    FoodCandidate(
        ref = FoodRef.Barcode(gtin),
        displayName = brandedName(brand, name),
        nutriments = nutriments,
        servingG = servingG,
    )

private fun RemoteProduct.toEntity(fetchedAt: Long): CachedProductEntity =
    CachedProductEntity(
        gtin = gtin,
        name = name,
        brand = brand,
        kcal100 = nutriments.kcal100,
        prot100 = nutriments.prot100,
        fat100 = nutriments.fat100,
        carb100 = nutriments.carb100,
        servingG = servingG,
        origin = origin,
        fetchedAt = fetchedAt,
    )
