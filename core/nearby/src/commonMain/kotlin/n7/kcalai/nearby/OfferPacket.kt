package n7.kcalai.nearby

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import n7.kcalai.model.Nutriments
import n7.kcalai.repositories.NutrimentValidator

/**
 * Что один телефон предлагает записать другому.
 *
 * Снимок, а не ссылка на продукт. Ссылка вида `user:7` — это номер строки
 * в базе отправителя; на чужом телефоне под тем же номером лежит другая еда,
 * и разрешилась бы такая ссылка молча, с правдоподобным видом. `generic:7`
 * не лучше: справочник у двоих бывает разных версий. Поэтому едут сами числа —
 * ровно как их хранит дневник, который тоже кладёт снимок, а не ссылку.
 *
 * Приём пищи не передаётся: его определяет получатель по своим часам. Завтрак
 * отправителя и завтрак получателя — разное время, а поправить приём человек
 * может и после.
 *
 * @param from как зовут отправителя. Подпись, а не удостоверение: имя приходит
 *        с чужого телефона и ничем не подтверждено.
 * @param gtin код товара — единственная ссылка, переносимая между телефонами.
 *        `null` — еда без кода, и это обычное дело.
 */
data class OfferPacket(
    val from: String,
    val food: String,
    val grams: Int,
    val nutriments: Nutriments,
    val gtin: String? = null,
)

/** Версия протокола. Чужую не разбираем: понять наполовину хуже, чем промолчать. */
const val OFFER_VERSION = 1

/** Длиннее имя и название не нужны: они встают в реплику персонажа, а не в документ. */
const val OFFER_NAME_MAX = 80

/** Больше десяти килограммов за раз человек не ест, а ноль записывать нечего. */
private const val GRAMS_MAX = 10_000

fun encodeOffer(packet: OfferPacket): String = buildJsonObject {
    put("v", OFFER_VERSION)
    put("from", packet.from)
    put("food", packet.food)
    put("grams", packet.grams)
    put("kcal100", packet.nutriments.kcal100)
    put("prot100", packet.nutriments.prot100)
    put("fat100", packet.nutriments.fat100)
    put("carb100", packet.nutriments.carb100)
    // Явным null, а не пропуском поля: «клетчатки нет на пачке» и «клетчатки ноль» —
    // разные вещи, и приняв первое за второе, получатель занизит себе недобор.
    put("fiber100", packet.nutriments.fiber100)
    packet.gtin?.let { put("gtin", it) }
}.toString()

/**
 * Пакет из сети -> предложение. `null` — разбирать нечего: битый JSON, чужая
 * версия, пустое имя или числа, которых не бывает.
 *
 * Не бросает: пакет приходит с чужого телефона, и «пришла ерунда» — штатный
 * исход, из-за которого нельзя ронять приём.
 */
fun parseOffer(body: String): OfferPacket? {
    val json = parseObject(body) ?: return null
    if (json.int("v") != OFFER_VERSION) return null

    val from = json.text("from")?.clip() ?: return null
    val food = json.text("food")?.clip() ?: return null
    val grams = json.int("grams") ?: return null
    if (grams !in 1..GRAMS_MAX) return null

    val nutriments = Nutriments(
        kcal100 = json.int("kcal100") ?: return null,
        prot100 = json.int("prot100") ?: return null,
        fat100 = json.int("fat100") ?: return null,
        carb100 = json.int("carb100") ?: return null,
        // Отсутствующее поле и явный null — одно и то же: клетчатка неизвестна.
        fiber100 = json.int("fiber100"),
    )
    // Тот же валидатор, что у набранного руками: свой сервер проверяют, и чужой
    // телефон тем более. Предупреждение о балансе здесь не помеха — человек
    // предложение ещё увидит и подтвердит сам.
    if (NutrimentValidator.check(nutriments).error != null) return null

    return OfferPacket(from = from, food = food, grams = grams, nutriments = nutriments, gtin = json.text("gtin"))
}

private fun parseObject(body: String): JsonObject? = try {
    Json.parseToJsonElement(body) as? JsonObject
} catch (error: SerializationException) {
    null
} catch (error: IllegalArgumentException) {
    null
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }?.toInt()

/** Пустое отбрасывается, длинное укорачивается: из-за подписи терять еду незачем. */
private fun String.clip(): String? = trim().takeIf { it.isNotEmpty() }?.take(OFFER_NAME_MAX)
