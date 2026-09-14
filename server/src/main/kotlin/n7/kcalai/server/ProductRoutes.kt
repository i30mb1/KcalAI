package n7.kcalai.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Только то, что разработчик положил в `product` сам. 404 — штатный ответ, клиент идёт в OFF. */
fun Route.productRoutes(db: Db) {
    get("/products/{gtin}") {
        val gtin = call.parameters["gtin"].orEmpty()
        if (!Gtin.isValid(gtin)) {
            call.respondText("неверная контрольная цифра", status = HttpStatusCode.BadRequest)
            return@get
        }
        val product = db.findProduct(gtin)
        if (product == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respondText(product.toJson().toString(), ContentType.Application.Json)
        }
    }
}

fun Product.toJson(): JsonObject = buildJsonObject {
    put("gtin", gtin)
    put("name", name)
    brand?.let { put("brand", it) }
    put("kcal100", nutriments.kcal100)
    put("prot100", nutriments.prot100)
    put("fat100", nutriments.fat100)
    put("carb100", nutriments.carb100)
    servingG?.let { put("servingG", it) }
}
