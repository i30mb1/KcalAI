package n7.kcalai.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Вклады пишутся все, без дедупа: повтор того же GTIN — ещё один голос, а не
 * дубликат. Отбраковка — только по инвариантам; порог согласия не здесь,
 * публикация в `product` — ручная.
 */
fun Route.contributionRoutes(db: Db, now: () -> Long = System::currentTimeMillis) {
    post("/contributions") {
        // Сервер открыт в интернет: читается не больше, чем бывает в пачке с телефона.
        val declared = call.request.contentLength()
        val raw = if (declared != null && declared > MAX_BODY_BYTES) null else call.receiveChannel().readRemaining(MAX_BODY_BYTES + 1).readByteArray()
        if (raw == null || raw.size > MAX_BODY_BYTES) {
            call.respondText("не больше $MAX_BODY_BYTES байт за раз", status = HttpStatusCode.PayloadTooLarge)
            return@post
        }
        val items = try {
            Json.parseToJsonElement(raw.decodeToString()) as? JsonArray
        } catch (error: SerializationException) {
            null
        }
        if (items == null) {
            call.respondText("ожидается JSON-массив", status = HttpStatusCode.BadRequest)
            return@post
        }
        if (items.size > MAX_ITEMS) {
            call.respondText("не больше $MAX_ITEMS вкладов за раз", status = HttpStatusCode.PayloadTooLarge)
            return@post
        }

        val receivedAt = now()
        val accepted = items.mapNotNull { element ->
            val contribution = (element as? JsonObject)?.toContribution() ?: return@mapNotNull null
            if (!Gtin.isValid(contribution.gtin) || !contribution.nutriments.isPlausible()) return@mapNotNull null
            db.insertContribution(contribution, receivedAt)
            contribution.gtin
        }

        val body = buildJsonObject {
            put("accepted", buildJsonArray { accepted.forEach { add(JsonPrimitive(it)) } })
        }
        call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.Accepted)
    }
}

/** `null` — не хватает обязательного поля; такой элемент просто не принимается. */
private fun JsonObject.toContribution(): Contribution? {
    fun int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    val name = (this["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return Contribution(
        gtin = (this["gtin"] as? JsonPrimitive)?.contentOrNull ?: return null,
        name = name,
        nutriments = Nutriments(
            kcal100 = int("kcal100") ?: return null,
            prot100 = int("prot100") ?: return null,
            fat100 = int("fat100") ?: return null,
            carb100 = int("carb100") ?: return null,
        ),
        servingG = int("servingG")?.takeIf { it > 0 },
        createdAt = (this["createdAt"] as? JsonPrimitive)?.longOrNull ?: return null,
    )
}

/** Телефон шлёт пачками по 50 — с запасом вдвое. */
private const val MAX_ITEMS = 100
private const val MAX_BODY_BYTES = 128L * 1024
