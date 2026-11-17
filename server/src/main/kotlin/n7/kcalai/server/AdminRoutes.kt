package n7.kcalai.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.io.File
import java.net.InetAddress
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Панель разработчика: что прислали телефоны, что опубликовано, сессии съёмки.
 *
 * Сервер смотрит в интернет, а пароля у панели нет — поэтому она отвечает только
 * тому, кто сидит за этим компьютером (`http://localhost:8080/admin`).
 */
fun Route.adminRoutes(
    db: Db,
    seed: SeedLookup,
    scansDir: File,
    isLocal: (ApplicationCall) -> Boolean,
    now: () -> Long = System::currentTimeMillis,
) {
    suspend fun RoutingContext.local(block: suspend RoutingContext.() -> Unit) {
        if (isLocal(call)) block() else call.respond(HttpStatusCode.Forbidden)
    }

    get("/admin") {
        local { call.respondText(ADMIN_PAGE, ContentType.Text.Html) }
    }

    route("/admin/api") {
        get("/queue") {
            local {
                val body = buildJsonArray {
                    db.queue().forEach { item ->
                        add(buildJsonObject {
                            put("gtin", item.gtin)
                            put("name", item.name)
                            put("count", item.votes)
                            put("lastReceivedAt", item.lastReceivedAt)
                        })
                    }
                }
                call.respondJson(body)
            }
        }

        get("/gtin/{gtin}") {
            local {
                val gtin = call.parameters["gtin"].orEmpty()
                val votes = db.votesFor(gtin)
                val product = db.findProduct(gtin)
                val seedProduct = seed.find(gtin)
                val body = buildJsonObject {
                    put("gtin", gtin)
                    put("contributions", buildJsonArray {
                        votes.forEach { vote ->
                            val c = vote.contribution
                            add(buildJsonObject {
                                put("name", c.name)
                                put("kcal100", c.nutriments.kcal100)
                                put("prot100", c.nutriments.prot100)
                                put("fat100", c.nutriments.fat100)
                                put("carb100", c.nutriments.carb100)
                                put("fiber100", c.fiber100)
                                put("servingG", c.servingG)
                                put("createdAt", c.createdAt)
                                put("receivedAt", vote.receivedAt)
                                put("pending", vote.pending)
                            })
                        }
                    })
                    put("product", product?.toJson() ?: JsonNull)
                    put("seed", seedProduct?.toJson() ?: JsonNull)
                    put("decision", db.decisionFor(gtin)?.let {
                        buildJsonObject {
                            put("verdict", it.verdict.name.lowercase())
                            put("decidedAt", it.decidedAt)
                        }
                    } ?: JsonNull)
                    put("suggestion", suggest(votes, brand = product?.brand ?: seedProduct?.brand)?.toJson() ?: JsonNull)
                }
                call.respondJson(body)
            }
        }

        post("/gtin/{gtin}/publish") {
            local {
                val gtin = call.parameters["gtin"].orEmpty()
                val product = call.receiveProduct(gtin)
                if (product == null || !Gtin.isValid(gtin) || !product.nutriments.isPlausible()) {
                    call.respondText("нужны название, КБЖУ в пределах и верный код", status = HttpStatusCode.BadRequest)
                    return@local
                }
                db.upsertProduct(product, now())
                db.decide(gtin, Verdict.PUBLISHED, now())
                call.respondJson(product.toJson())
            }
        }

        post("/gtin/{gtin}/reject") {
            local {
                db.decide(call.parameters["gtin"].orEmpty(), Verdict.REJECTED, now())
                call.respond(HttpStatusCode.OK)
            }
        }

        get("/products") {
            local { call.respondJson(JsonArray(db.products().map { it.toJson() })) }
        }

        post("/products/{gtin}/delete") {
            local {
                db.deleteProduct(call.parameters["gtin"].orEmpty())
                call.respond(HttpStatusCode.OK)
            }
        }

        get("/scans") {
            local {
                val sessions = scansDir.listFiles { file -> file.isDirectory && SCAN_ID.matches(file.name) }.orEmpty()
                    .sortedByDescending { it.lastModified() }
                val body = buildJsonArray {
                    sessions.forEach { dir ->
                        add(buildJsonObject {
                            put("id", dir.name)
                            put("receivedAt", dir.lastModified())
                            put("readings", File(dir, "readings.txt").takeIf { it.isFile }?.readText().orEmpty())
                            put("frames", buildJsonArray {
                                dir.list().orEmpty().filter { FRAME_NAME.matches(it) }.sorted().forEach { add(JsonPrimitive(it)) }
                            })
                        })
                    }
                }
                call.respondJson(body)
            }
        }
    }

    get("/admin/scans/{id}/{frame}") {
        local {
            val id = call.parameters["id"].orEmpty()
            val frame = call.parameters["frame"].orEmpty()
            val file = File(File(scansDir, id), frame)
            if (!SCAN_ID.matches(id) || !FRAME_NAME.matches(frame) || !file.isFile) {
                call.respond(HttpStatusCode.NotFound)
                return@local
            }
            call.respond(LocalFileContent(file, ContentType.Image.JPEG))
        }
    }
}

/** Соединение пришло с этого же компьютера. Заголовкам вроде X-Forwarded-For не верим — их пишет клиент. */
fun fromThisComputer(call: ApplicationCall): Boolean =
    runCatching { InetAddress.getByName(call.request.local.remoteAddress).isLoopbackAddress }.getOrDefault(false)

/**
 * Что предложить к публикации: медиана голосов после последнего решения (а если их нет — всех),
 * название — последнее присланное. Медиана, а не среднее: один лишний разряд не сдвигает её.
 */
private fun suggest(votes: List<Vote>, brand: String?): Product? {
    val basis = votes.filter { it.pending }.ifEmpty { votes }.map { it.contribution }
    if (basis.isEmpty()) return null
    fun median(pick: (Nutriments) -> Int) = basis.map { pick(it.nutriments) }.sorted().let { it[(it.size - 1) / 2] }
    // Клетчатку присылают не все: медиана тех, кто прислал, а без неё — неизвестна.
    val fibers = basis.mapNotNull { it.fiber100 }.sorted()
    return Product(
        gtin = basis.last().gtin,
        name = basis.last().name,
        brand = brand,
        nutriments = Nutriments(median { it.kcal100 }, median { it.prot100 }, median { it.fat100 }, median { it.carb100 }),
        servingG = basis.lastOrNull { it.servingG != null }?.servingG,
        fiber100 = fibers.getOrNull((fibers.size - 1) / 2),
    )
}

private suspend fun ApplicationCall.receiveProduct(gtin: String): Product? {
    val body = try {
        Json.parseToJsonElement(receiveText()) as? JsonObject
    } catch (error: SerializationException) {
        null
    } ?: return null
    fun int(key: String) = (body[key] as? JsonPrimitive)?.intOrNull
    fun text(key: String) = (body[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    return Product(
        gtin = gtin,
        name = text("name") ?: return null,
        brand = text("brand"),
        nutriments = Nutriments(int("kcal100") ?: return null, int("prot100") ?: return null, int("fat100") ?: return null, int("carb100") ?: return null),
        servingG = int("servingG")?.takeIf { it > 0 },
        fiber100 = int("fiber100")?.takeIf { it in 0..MAX_FIBER_CG },
    )
}

/** Клетчатка не больше 100 г на 100 г. */
private const val MAX_FIBER_CG = 10_000

private suspend fun ApplicationCall.respondJson(body: kotlinx.serialization.json.JsonElement) =
    respondText(body.toString(), ContentType.Application.Json)

private val ADMIN_PAGE: String by lazy {
    checkNotNull(object {}.javaClass.getResource("/admin.html")) { "admin.html нет в ресурсах" }.readText()
}
