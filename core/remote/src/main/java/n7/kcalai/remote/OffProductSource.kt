package n7.kcalai.remote

import android.util.Log
import kotlin.math.roundToInt
import n7.kcalai.model.Nutriments
import n7.kcalai.model.ProductOrigin
import n7.kcalai.repositories.NutrimentValidator
import n7.kcalai.repositories.RemoteProduct
import n7.kcalai.repositories.RemoteProductSource
import org.json.JSONObject

/**
 * Open Food Facts — единственная бесплатная база «штрих-код → КБЖУ» без ключа.
 *
 * Спрашивается последней и заведомо промахивается чаще, чем попадает: российских
 * товаров там порядка тридцати шести тысяч. Это не повод от неё отказываться —
 * попадание экономит человеку заполнение формы, — но повод не считать промах сбоем.
 *
 * Ответ отсюда помечается [ProductOrigin.OFF] и дальше кэша не уходит: база лежит
 * под ODbL, и её попадание в общий пул обязало бы опубликовать пул целиком.
 *
 * @param userAgent OFF режет запросы без описательного User-Agent. Указывать здесь
 *        реальный способ связи — их требование к клиентам, и стоит его выполнить.
 */
class OffProductSource(
    private val userAgent: String,
) : RemoteProductSource {

    override suspend fun fetch(gtin: String): RemoteProduct? {
        val response = Http.get(url(gtin), userAgent) ?: return null
        if (!response.isSuccess) return null

        return try {
            parse(gtin, JSONObject(response.body))
        } catch (error: org.json.JSONException) {
            // Разбор чужого JSON — заведомо ненадёжное место. Ломаться здесь нельзя:
            // человек стоит у полки, и ему нужна форма ввода, а не стектрейс.
            Log.w(TAG, "ответ OFF по $gtin не разобрался", error)
            null
        }
    }

    /**
     * `status: 0` — это «такого товара нет», а не ошибка.
     *
     * Различие важное: на ошибку следовало бы ругаться и пробовать снова, а на
     * отсутствие — молча вести человека в форму. Промах здесь основной сценарий.
     */
    private fun parse(gtin: String, root: JSONObject): RemoteProduct? {
        if (root.optInt("status", 0) != 1) return null
        val product = root.optJSONObject("product") ?: return null

        val name = product.firstNonBlank("product_name_ru", "product_name") ?: return null
        val nutriments = product.optJSONObject("nutriments")?.toNutriments() ?: return null

        // В OFF хватает мусорных записей — нули во всех полях, калории на порцию
        // вместо ста грамм. Показать такое хуже, чем не показать ничего:
        // человек подтвердит цифру не глядя, потому что она пришла «из базы».
        if (!NutrimentValidator.check(nutriments).valid) {
            Log.i(TAG, "$gtin отброшен: КБЖУ не проходит проверку")
            return null
        }

        return RemoteProduct(
            gtin = gtin,
            name = name,
            brand = product.firstNonBlank("brands")?.substringBefore(','),
            nutriments = nutriments,
            servingG = product.optDouble("serving_quantity", Double.NaN)
                .takeIf { !it.isNaN() && it > 0 }
                ?.roundToInt(),
            origin = ProductOrigin.OFF,
        )
    }

    private fun url(gtin: String): String =
        "https://world.openfoodfacts.org/api/v2/product/$gtin.json?fields=$FIELDS"

    private companion object {
        const val TAG = "OffProductSource"

        /**
         * Поля перечисляются явно: полная карточка товара — это десятки килобайт
         * ингредиентов, меток и ссылок на фотографии, и тянуть их по мобильной сети
         * ради четырёх чисел незачем.
         */
        const val FIELDS = "product_name,product_name_ru,brands,nutriments,serving_quantity"
    }
}

/** Первое непустое из перечисленных полей. У OFF половина названий приходит пустыми строками. */
internal fun JSONObject.firstNonBlank(vararg keys: String): String? =
    keys.asSequence()
        .map { optString(it).trim() }
        .firstOrNull { it.isNotEmpty() }

/**
 * Блок `nutriments` в значения на 100 г.
 *
 * Калории берутся готовыми, а если их нет — считаются из килоджоулей: европейские
 * этикетки часто несут только их, и отбрасывать такой товар было бы расточительно.
 */
internal fun JSONObject.toNutriments(): Nutriments? {
    val kcal = optDouble("energy-kcal_100g", Double.NaN)
        .takeIf { !it.isNaN() }
        ?: optDouble("energy-kj_100g", Double.NaN)
            .takeIf { !it.isNaN() }
            ?.let { it / KJ_PER_KCAL }
        ?: return null

    return Nutriments(
        kcal100 = kcal.roundToInt(),
        prot100 = centigrams("proteins_100g"),
        fat100 = centigrams("fat_100g"),
        carb100 = centigrams("carbohydrates_100g"),
    )
}

/**
 * Граммы этикетки в сотые грамма модели.
 *
 * Отсутствующий макрос — это ноль, а не отказ от продукта: у растительного масла
 * в OFF действительно не заполнены белки, и терять из-за этого масло не хочется.
 */
private fun JSONObject.centigrams(key: String): Int {
    val grams = optDouble(key, Double.NaN)
    return if (grams.isNaN() || grams < 0) 0 else (grams * 100).roundToInt()
}

private const val KJ_PER_KCAL = 4.184
