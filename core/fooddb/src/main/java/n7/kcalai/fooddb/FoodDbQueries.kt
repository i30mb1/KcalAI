package n7.kcalai.fooddb

/**
 * Имена таблиц зависят от того, докачан ли полный срез. Выбор делается один раз
 * при открытии соединения — это единственная ветка «есть полная база / нет».
 *
 * Про синтаксис MATCH. Слева от него стоит не таблица, а её скрытая колонка,
 * которую FTS5 называет так же, как саму таблицу. Отсюда два правила:
 * псевдоним сам по себе (`f MATCH ?`) — ошибка «no such column: f», а имя со
 * схемой (`main.product_seed_fts MATCH ?`) разбирается как колонка таблицы `main`.
 * Однозначна только форма `псевдоним.имя_таблицы`.
 */
internal class FoodDbQueries(hasFullDb: Boolean) {

    private val productTable = if (hasFullDb) "food.product" else "main.product_seed"
    private val productFtsName = if (hasFullDb) "product_fts" else "product_seed_fts"
    private val productFts = if (hasFullDb) "food.$productFtsName" else "main.$productFtsName"

    val findByBarcode: String =
        "SELECT barcode, name, brand, kcal100, prot100, fat100, carb100, serving_g " +
            "FROM $productTable WHERE barcode = ?"

    val searchProducts: String =
        "SELECT p.barcode, p.name, p.brand, p.kcal100, p.prot100, p.fat100, p.carb100, p.serving_g " +
            "FROM $productFts f JOIN $productTable p ON p.barcode = f.barcode " +
            "WHERE f.$productFtsName MATCH ? " +
            "GROUP BY p.barcode ORDER BY p.popularity DESC, p.name LIMIT ?"

    companion object {
        private const val GENERIC_COLUMNS =
            "g.id, g.name_key, g.name_ru, g.kcal100, g.prot100, g.fat100, g.carb100, g.default_portion_g"

        /**
         * Совпадение алиаса целиком. «гречка» -> ровно гречка отварная, без конкуренции
         * с «гречневой крупой», которую притянул бы префиксный поиск.
         */
        const val EXACT_ALIAS: String =
            "SELECT $GENERIC_COLUMNS FROM generic_alias a " +
                "JOIN generic g ON g.id = a.generic_id " +
                "WHERE a.alias = ? GROUP BY g.id ORDER BY g.id LIMIT ?"

        /**
         * Префиксный поиск по основам слов.
         *
         * Порядок — по длине самого короткого совпавшего алиаса: чем короче алиас,
         * тем большую его долю покрыл запрос. Это не bm25, но детерминированно
         * и не зависит от версии FTS5.
         */
        const val SEARCH_FTS: String =
            "SELECT $GENERIC_COLUMNS FROM generic_fts f " +
                "JOIN generic g ON g.id = f.generic_id " +
                "WHERE f.generic_fts MATCH ? " +
                "GROUP BY g.id ORDER BY MIN(length(f.alias)), g.id LIMIT ?"

        const val GENERIC_BY_ID: String =
            "SELECT g.id, g.name_key, g.name_ru, g.kcal100, g.prot100, g.fat100, g.carb100, " +
                "g.default_portion_g FROM generic g WHERE g.id = ?"

        const val PORTION_UNITS: String =
            "SELECT unit, grams FROM portion_unit WHERE generic_id = ?"
    }
}
