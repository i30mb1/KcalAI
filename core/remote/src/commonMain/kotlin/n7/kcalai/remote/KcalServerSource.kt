package n7.kcalai.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import n7.kcalai.database.ContributionEntity
import n7.kcalai.model.Log
import n7.kcalai.model.Nutriments
import n7.kcalai.model.ProductOrigin
import n7.kcalai.repositories.NutrimentValidator
import n7.kcalai.repositories.RemoteProduct
import n7.kcalai.repositories.RemoteProductSource

/**
 * Собранное из вкладов пользователей — то, чего в Open Food Facts нет.
 *
 * Спрашивается раньше OFF намеренно: здесь лежит ровно то, что люди заводили руками
 * после промаха, то есть российские товары, которых в OFF и не будет. Заодно это
 * единственный пул, который мы вправе раздавать — он не производен от ODbL-базы.
 *
 * Сервер пишется отдельно, и пока [baseUrl] пуст источник просто пропускается.
 * Клиент от этого не ломается и не ждёт: цепочка идёт дальше, в OFF.
 *
 * @see <a href="../../../../../../../docs/server-contract.md">docs/server-contract.md</a>
 */
class KcalServerSource(
    private val baseUrl: String?,
    private val userAgent: String,
) : RemoteProductSource, ContributionUploader, SeedSource, ScanUploader, ServerProbe {

    private val root: String? get() = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    override suspend fun fetch(gtin: String): RemoteProduct? {
        val base = root ?: return null

        val response = Http.get("$base/v1/products/$gtin", userAgent) ?: return null
        // 404 — штатный ответ «такого у нас нет», и отличать его от сбоя незачем:
        // дальше в обоих случаях идёт OFF.
        if (!response.isSuccess) return null
        return parseServerProduct(gtin, response.body)
    }

    /**
     * Отправка пачкой, потому что очередь обычно копится офлайн и уходит разом,
     * когда появляется сеть.
     *
     * Идентификатора пользователя в теле нет и не будет: что человек ест —
     * медицинские данные, и аккаунта в v1 нет намеренно.
     */
    override suspend fun upload(items: List<ContributionEntity>): Set<String> {
        val base = root ?: return emptySet()
        if (items.isEmpty()) return emptySet()

        val payload = contributionsJson(items)

        val response = Http.postJson("$base/v1/contributions", userAgent, payload) ?: return emptySet()
        if (!response.isSuccess) {
            Log.i(TAG, "вклады не приняты: HTTP ${response.code}")
            return emptySet()
        }

        return parseAccepted(response.body)
    }

    override suspend fun seedManifest(): SeedManifest? {
        val base = root ?: return null
        val response = Http.get("$base/v1/seed/manifest.json", userAgent) ?: return null
        if (!response.isSuccess) return null

        return parseManifest(base, response.body)
    }

    override suspend fun downloadSeed(manifest: SeedManifest, to: String): Boolean =
        Http.download(manifest.url, userAgent, to)

    /**
     * Любой HTTP-ответ — сервер на месте, даже 404: справочник на него могли
     * ещё не положить. Нет ответа — сети нет или сервер выключен.
     */
    override suspend fun reachable(): Boolean {
        val base = root ?: return false
        return Http.get("$base/v1/seed/manifest.json", userAgent) != null
    }

    override suspend fun uploadScan(id: String, readings: String, frames: List<ScanFrame>): Boolean {
        val base = root ?: return false
        val response = Http.postMultipart(
            url = "$base/v1/scans",
            userAgent = userAgent,
            headers = mapOf("X-Scan-Id" to id),
            fields = mapOf("readings" to readings),
            files = frames.map { Triple("frame", it.name, it.bytes) },
        ) ?: return false
        if (!response.isSuccess) Log.i(TAG, "сессия $id не принята: HTTP ${response.code}")
        return response.isSuccess
    }
}

/**
 * Отправка накопленных вкладов.
 *
 * Отдельный интерфейс, а не метод источника, потому что вызывающие у них разные:
 * поиск дёргает человек у полки, отправку — фоновой рабочий по расписанию.
 */
fun interface ContributionUploader {

    /**
     * @return GTIN'ы, которые сервер подтвердил. Пустое множество означает
     *         «ничего не ушло» — и строки очереди остаются непомеченными.
     */
    suspend fun upload(items: List<ContributionEntity>): Set<String>
}

data class SeedManifest(val version: Int, val sha256: String, val size: Long, val url: String)

/** Свежий справочник с сервера. `null`/`false` — сервера нет или он молчит; это штатно. */
interface SeedSource {
    suspend fun seedManifest(): SeedManifest?
    /** @param to путь к файлу, куда лечь закачке; при неудаче файла там нет. */
    suspend fun downloadSeed(manifest: SeedManifest, to: String): Boolean
}

class ScanFrame(val name: String, val bytes: ByteArray)

/** Отвечает ли свой сервер — чтобы не предлагать отправку, которая не сработает. */
fun interface ServerProbe {
    suspend fun reachable(): Boolean
}

/** Сессия съёмки этикетки уезжает на сервер целиком. `true` — принято (в том числе повторно). */
fun interface ScanUploader {
    suspend fun uploadScan(id: String, readings: String, frames: List<ScanFrame>): Boolean
}

private const val TAG = "KcalServerSource"

/**
 * Сервер отдаёт значения уже в наших единицах — калории целыми, макросы в сотых
 * грамма. Проверка всё равно выполняется: свой сервер это не гарантия
 * от собственной ошибки, а цена проверки — четыре сравнения.
 */
internal fun parseServerProduct(gtin: String, body: String): RemoteProduct? {
    val json = parseJsonObject(body) ?: run {
        Log.w(TAG, "ответ сервера по $gtin не разобрался")
        return null
    }
    val name = json.firstNonBlank("name") ?: return null
    val nutriments = Nutriments(
        kcal100 = json.int("kcal100", -1),
        prot100 = json.int("prot100", 0),
        fat100 = json.int("fat100", 0),
        carb100 = json.int("carb100", 0),
    )
    if (!NutrimentValidator.check(nutriments).valid) {
        Log.w(TAG, "$gtin отброшен: сервер прислал КБЖУ вне допустимого")
        return null
    }

    return RemoteProduct(
        gtin = gtin,
        name = name,
        brand = json.firstNonBlank("brand"),
        nutriments = nutriments,
        servingG = json.int("servingG", 0).takeIf { it > 0 },
        origin = ProductOrigin.SERVER,
    )
}

/** GTIN'ы, которые сервер подтвердил. */
internal fun parseAccepted(body: String): Set<String> {
    val accepted = parseJsonObject(body)?.array("accepted") ?: run {
        // Отправка прошла, а подтверждение не разобралось. Считаем, что не ушло:
        // повторить дешевле, чем потерять вклад.
        Log.w(TAG, "подтверждение отправки не разобралось")
        return emptySet()
    }
    return accepted.mapNotNullTo(mutableSetOf()) { (it as? JsonPrimitive)?.content }
}

internal fun parseManifest(base: String, body: String): SeedManifest? {
    val json = parseJsonObject(body)
    val url = json?.string("url")
    val sha256 = json?.string("sha256")
    if (url == null || sha256 == null) {
        Log.w(TAG, "манифест справочника не разобрался")
        return null
    }
    return SeedManifest(
        version = json.int("version", 0),
        sha256 = sha256,
        size = json.long("size") ?: 0,
        url = if (url.startsWith("http")) url else base + url,
    )
}

/** Тело `POST /v1/contributions` в единицах контракта. */
internal fun contributionsJson(items: List<ContributionEntity>): String =
    JsonArray(
        items.map { item ->
            buildJsonObject {
                put("gtin", item.gtin)
                put("name", item.name)
                put("kcal100", item.kcal100)
                put("prot100", item.prot100)
                put("fat100", item.fat100)
                put("carb100", item.carb100)
                item.servingG?.let { put("servingG", it) }
                put("createdAt", item.createdAt)
            }
        },
    ).toString()
