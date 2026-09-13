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

/**
 * Сериализация ссылки для хранения в дневнике и в журнале подтверждений.
 *
 * Формат стабилен: его читает «повторить», по нему же сопоставляются записи истории
 * в моделях персонализации. Строка служит ключом словарей — менять её нельзя.
 */
fun FoodRef.serialize(): String = when (this) {
    is FoodRef.Barcode -> "barcode:$gtin"
    is FoodRef.Generic -> "generic:$id"
    is FoodRef.User -> "user:$id"
}

/**
 * Обратная операция к [serialize].
 *
 * `null` означает мусор в строке. Такую запись истории модели просто пропускают:
 * дневник от этого не страдает — там лежит снимок КБЖУ, а не ссылка.
 */
fun parseFoodRef(value: String?): FoodRef? {
    val raw = value ?: return null
    val separator = raw.indexOf(':')
    if (separator <= 0) return null

    val payload = raw.substring(separator + 1)
    if (payload.isEmpty()) return null

    return when (raw.substring(0, separator)) {
        "barcode" -> FoodRef.Barcode(payload)
        "generic" -> payload.toLongOrNull()?.let(FoodRef::Generic)
        "user" -> payload.toLongOrNull()?.let(FoodRef::User)
        else -> null
    }
}
