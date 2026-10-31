package n7.kcalai.remote

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Разбор без компиляторного плагина: через JsonElement, как на сервере.
 * Помощники ниже повторяют снисходительность `org.json` — число строкой
 * читается как число, а отсутствующее поле даёт значение по умолчанию.
 */
internal fun parseJsonObject(body: String): JsonObject? = parseJson(body) as? JsonObject

internal fun parseJson(body: String): JsonElement? = try {
    Json.parseToJsonElement(body)
} catch (error: SerializationException) {
    null
} catch (error: IllegalArgumentException) {
    null
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** Строка поля; у чисел — их запись. `null` у отсутствующих и у JSON null. */
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)
        ?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        // Строка "NaN" читается в NaN, а округление NaN бросает.
        ?.takeIf { !it.isNaN() }

internal fun JsonObject.int(key: String, default: Int): Int = long(key)?.toInt() ?: default

/** Целое сначала читается как целое: через Double большие значения теряют точность. */
internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: double(key)?.toLong()

/** Первое непустое из перечисленных полей. У OFF половина названий приходит пустыми строками. */
internal fun JsonObject.firstNonBlank(vararg keys: String): String? =
    keys.asSequence()
        .mapNotNull { string(it)?.trim() }
        .firstOrNull { it.isNotEmpty() }
