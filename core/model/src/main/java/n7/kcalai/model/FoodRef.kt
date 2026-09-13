package n7.kcalai.model

/** Ссылка на продукт в одном из трёх источников. */
sealed interface FoodRef {

    /** Товар со штрих-кодом из `product` или `product_seed`. */
    data class Barcode(val gtin: String) : FoodRef

    /** Позиция генерик-таблицы («гречка отварная»). */
    data class Generic(val id: Long) : FoodRef

    /** Продукт, заведённый самим пользователем. */
    data class User(val id: Long) : FoodRef
}
