package n7.kcalai.remote

import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import n7.kcalai.model.Log
import n7.kcalai.model.Nutriments
import n7.kcalai.model.ProductOrigin
import n7.kcalai.repositories.NutrimentValidator
import n7.kcalai.repositories.RemoteProduct
import n7.kcalai.repositories.RemoteProductSource

/**
 * Каталог ВкусВилла по штрих-коду.
 *
 * Сеть сама держит открытый сервер для ИИ-агентов (MCP поверх HTTP, без ключа),
 * и один из его инструментов ищет товар по EAN-13. Собственные товары ВкусВилла
 * в Open Food Facts попадают редко и чаще без таблицы, а здесь КБЖУ отдаёт сам продавец.
 *
 * Спрашивается последним: это запрос к чужому каталогу, и уходит он, только когда
 * чисел не нашлось ни у себя, ни в OFF. Ответ помечается [ProductOrigin.VKUSVILL]
 * и дальше кэша на телефоне не идёт.
 *
 * Вызов инструмента не требует ни рукопожатия, ни сессии: сервер отвечает на одиночный
 * `tools/call`. Проверено вживую 2026-11-12, как и то, что заголовка
 * `Accept: application/json` ему достаточно.
 */
class VkusVillProductSource(
    private val userAgent: String,
) : RemoteProductSource {

    override suspend fun fetch(gtin: String): RemoteProduct? {
        val request = vkusVillRequest(gtin) ?: return null
        val response = Http.postJson(ENDPOINT, userAgent, request) ?: return null
        if (!response.isSuccess) return null
        return parseVkusVillProduct(gtin, response.body)
    }

    private companion object {
        const val ENDPOINT = "https://mcp.vkusvill.ru/mcp"
    }
}

/**
 * Тело вызова инструмента `vkusvill_product_barcode`.
 *
 * @return `null` для кода не из тринадцати цифр: схема инструмента другого не принимает,
 *         и EAN-8 незачем гонять по сети ради гарантированного отказа
 */
internal fun vkusVillRequest(gtin: String): String? {
    if (gtin.length != EAN13_LENGTH || !gtin.all { it in '0'..'9' }) return null
    return buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 1)
        put("method", "tools/call")
        putJsonObject("params") {
            put("name", "vkusvill_product_barcode")
            putJsonObject("arguments") { put("barcode", gtin) }
        }
    }.toString()
}

/**
 * Ответ инструмента — JSON строкой внутри JSON-RPC: `result.content[0].text`.
 *
 * «Товар не найден» приходит тем же успешным ответом с `ok: false`, и это штатный
 * промах. Товар без годных чисел возвращается с одним названием, как и из OFF.
 */
internal fun parseVkusVillProduct(gtin: String, body: String): RemoteProduct? {
    val text = parseJsonObject(body)
        ?.obj("result")
        ?.array("content")
        ?.firstNotNullOfOrNull { (it as? JsonObject)?.string("text") }
    val answer = text?.let(::parseJsonObject) ?: run {
        Log.w(TAG, "ответ ВкусВилла по $gtin не разобрался")
        return null
    }
    if (answer.string("ok") != "true") return null
    val data = answer.obj("data") ?: return null

    val name = data.firstNonBlank("name")?.let(::plainText) ?: return null
    val nutrition = data.array("properties")
        ?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { property ->
            val title = property.string("name")?.lowercase().orEmpty()
            "ценность" in title && "100" in title
        }
        ?.string("value")
    val nutriments = nutrition?.let(::parseVkusVillNutriments)
    if (nutriments == null) Log.i(TAG, "$gtin без годного КБЖУ: остаётся только название")

    return RemoteProduct(
        gtin = gtin,
        name = name,
        brand = data.firstNonBlank("brand"),
        nutriments = nutriments,
        // Вес упаковки в ответе есть, но это не порция: литр кефира за раз не выпивают.
        servingG = null,
        origin = ProductOrigin.VKUSVILL,
    )
}

/**
 * Пищевая ценность приходит текстом: «белки 12.2 г, жиры 2.6 г, углеводы 12.8 г,,
 * в том числе сахара (общие) - 0.5 г; 123.4 ккал».
 *
 * Один товар ВкусВиллу делают несколько заводов, и тогда строк несколько, через `<br>`,
 * каждая со своим производителем. Чей пакет у человека в руках, по коду не узнать.
 * Пока строки расходятся в пределах допуска [NutrimentValidator.BALANCE_TOLERANCE] —
 * берётся серединная по калориям: настоящая этикетка, от которой остальные отстоят
 * меньше всего. Расходятся сильнее (салаты разных кухонь — в полтора раза) — чисел нет:
 * любая из строк соврала бы, а человек подтвердил бы её не глядя.
 */
internal fun parseVkusVillNutriments(text: String): Nutriments? {
    val labels = text.lowercase()
        .split(LINE_BREAK)
        .mapNotNull(::labelNutriments)
        .filter { NutrimentValidator.check(it).valid }
        .sortedBy { it.kcal100 }
    if (labels.isEmpty()) return null

    val spread = labels.last().kcal100 - labels.first().kcal100
    if (spread > labels.first().kcal100 * NutrimentValidator.BALANCE_TOLERANCE) return null
    return labels[(labels.size - 1) / 2]
}

/** Одна строка — одна этикетка. Без калорий строки нет; пропущенный макрос — ноль, как в OFF. */
private fun labelNutriments(line: String): Nutriments? {
    val kcal = KCAL.find(line)?.number() ?: return null
    return Nutriments(
        kcal100 = kcal.roundToInt(),
        prot100 = centigrams(PROTEIN, line),
        fat100 = centigrams(FAT, line),
        carb100 = centigrams(CARBS, line),
    )
}

private fun centigrams(macro: Regex, line: String): Int =
    macro.find(line)?.number()?.let { (it * 100).roundToInt() } ?: 0

private fun MatchResult.number(): Double? = groupValues[1].replace(',', '.').toDoubleOrNull()

/** Названия в каталоге приходят с разметкой сайта: «Творог 5%, 180&nbsp;г». */
private fun plainText(raw: String): String =
    raw.replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace(SPACES, " ")
        .trim()

private const val EAN13_LENGTH = 13

private const val NUMBER = """(\d+(?:[.,]\d+)?)"""

private val LINE_BREAK = Regex("""<br\s*/?>""")
private val SPACES = Regex("""\s+""")
private val KCAL = Regex("""$NUMBER\s*ккал""")
private val PROTEIN = macro("белки")
private val FAT = macro("жиры")
private val CARBS = macro("углеводы")

/** Число сразу за словом: «сахара» и «соль» дальше по строке под «углеводы» не подходят. */
private fun macro(word: String) = Regex("""$word\s*[-–:]?\s*$NUMBER""")

private const val TAG = "VkusVillProductSource"
