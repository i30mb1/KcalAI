package n7.kcalai.remote

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import n7.kcalai.database.ContributionEntity
import n7.kcalai.model.Nutriments
import n7.kcalai.model.ProductOrigin
import n7.kcalai.repositories.NutrimentValidator
import n7.kcalai.repositories.RemoteProduct
import n7.kcalai.repositories.RemoteProductSource
import org.json.JSONArray
import org.json.JSONObject

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
) : RemoteProductSource, ContributionUploader, SeedSource, ScanUploader {

    private val root: String? get() = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    override suspend fun fetch(gtin: String): RemoteProduct? {
        val base = root ?: return null

        val response = Http.get("$base/v1/products/$gtin", userAgent) ?: return null
        // 404 — штатный ответ «такого у нас нет», и отличать его от сбоя незачем:
        // дальше в обоих случаях идёт OFF.
        if (response.code == HttpURLConnection.HTTP_NOT_FOUND || !response.isSuccess) return null

        return try {
            parse(gtin, JSONObject(response.body))
        } catch (error: org.json.JSONException) {
            Log.w(TAG, "ответ сервера по $gtin не разобрался", error)
            null
        }
    }

    /**
     * Сервер отдаёт значения уже в наших единицах — калории целыми, макросы в сотых
     * грамма. Проверка всё равно выполняется: свой сервер это не гарантия
     * от собственной ошибки, а цена проверки — четыре сравнения.
     */
    private fun parse(gtin: String, json: JSONObject): RemoteProduct? {
        val name = json.firstNonBlank("name") ?: return null
        val nutriments = Nutriments(
            kcal100 = json.optInt("kcal100", -1),
            prot100 = json.optInt("prot100", 0),
            fat100 = json.optInt("fat100", 0),
            carb100 = json.optInt("carb100", 0),
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
            servingG = json.optInt("servingG", 0).takeIf { it > 0 },
            origin = ProductOrigin.SERVER,
        )
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

        val payload = JSONArray().apply {
            items.forEach { put(it.toJson()) }
        }.toString()

        val response = Http.postJson("$base/v1/contributions", userAgent, payload) ?: return emptySet()
        if (!response.isSuccess) {
            Log.i(TAG, "вклады не приняты: HTTP ${response.code}")
            return emptySet()
        }

        return try {
            val accepted = JSONObject(response.body).optJSONArray("accepted") ?: return emptySet()
            buildSet {
                for (index in 0 until accepted.length()) add(accepted.getString(index))
            }
        } catch (error: org.json.JSONException) {
            // Отправка прошла, а подтверждение не разобралось. Считаем, что не ушло:
            // повторить дешевле, чем потерять вклад.
            Log.w(TAG, "подтверждение отправки не разобралось", error)
            emptySet()
        }
    }

    override suspend fun seedManifest(): SeedManifest? {
        val base = root ?: return null
        val response = Http.get("$base/v1/seed/manifest.json", userAgent) ?: return null
        if (!response.isSuccess) return null

        return try {
            val json = JSONObject(response.body)
            val url = json.getString("url").let { if (it.startsWith("http")) it else base + it }
            SeedManifest(
                version = json.optInt("version", 0),
                sha256 = json.getString("sha256"),
                size = json.optLong("size", 0),
                url = url,
            )
        } catch (error: org.json.JSONException) {
            Log.w(TAG, "манифест справочника не разобрался", error)
            null
        }
    }

    override suspend fun downloadSeed(manifest: SeedManifest, to: File): Boolean =
        Http.download(manifest.url, userAgent, to)

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

    private fun ContributionEntity.toJson(): JSONObject = JSONObject().apply {
        put("gtin", gtin)
        put("name", name)
        put("kcal100", kcal100)
        put("prot100", prot100)
        put("fat100", fat100)
        put("carb100", carb100)
        servingG?.let { put("servingG", it) }
        put("createdAt", createdAt)
    }

    private companion object {
        const val TAG = "KcalServerSource"
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
    suspend fun downloadSeed(manifest: SeedManifest, to: File): Boolean
}

class ScanFrame(val name: String, val bytes: ByteArray)

/** Сессия съёмки этикетки уезжает на сервер целиком. `true` — принято (в том числе повторно). */
fun interface ScanUploader {
    suspend fun uploadScan(id: String, readings: String, frames: List<ScanFrame>): Boolean
}
