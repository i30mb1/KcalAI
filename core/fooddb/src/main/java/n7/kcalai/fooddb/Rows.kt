package n7.kcalai.fooddb

import n7.kcalai.model.Nutriments

/** Товар со штрих-кодом из `product` или `product_seed`. */
data class ProductRow(
    val barcode: String,
    val name: String,
    val brand: String?,
    val nutriments: Nutriments,
    val servingG: Int?,
)

/** Позиция генерик-таблицы. */
data class GenericRow(
    val id: Long,
    val nameKey: String,
    /** Имя для человека: «Гречка отварная». `nameKey` показывать нельзя. */
    val nameRu: String,
    val nutriments: Nutriments,
    /** Типичная разовая порция в граммах — подставляется, когда вес в тексте не указан. */
    val defaultPortionG: Int,
    /** true, если совпал алиас целиком, а не отдельные слова. Поднимает confidence. */
    val exactMatch: Boolean = false,
)
