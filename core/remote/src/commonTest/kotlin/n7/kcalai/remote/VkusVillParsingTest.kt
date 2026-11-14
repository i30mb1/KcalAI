package n7.kcalai.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import n7.kcalai.model.Nutriments
import n7.kcalai.model.ProductOrigin

/**
 * Ответы сервера ВкусВилла. Строки пищевой ценности взяты из живых ответов 2026-11-12:
 * числа там приходят текстом, и ломается разбор именно на его вариантах.
 */
class VkusVillParsingTest {

    @Test
    fun `карточка по коду в наших единицах`() {
        val body = answer(
            name = "Котлеты \"неМясо\" со вкусом курочки, зам.",
            nutrition = "белки 12.2 г, жиры 2.6 г, углеводы 12.8 г,, в том числе сахара (общие) - 0.5 г, " +
                "соль - 0.8 г; 123.4 ккал",
        )

        val product = parseVkusVillProduct(GTIN, body)!!

        assertEquals("Котлеты \"неМясо\" со вкусом курочки, зам.", product.name)
        assertEquals(Nutriments(kcal100 = 123, prot100 = 1220, fat100 = 260, carb100 = 1280), product.nutriments)
        assertEquals(GTIN, product.gtin)
        assertNull(product.brand)
        assertEquals(ProductOrigin.VKUSVILL, product.origin)
    }

    /** Сахар и соль стоят после углеводов теми же «число г» — в углеводы они попасть не должны. */
    @Test
    fun `сахар и соль не подменяют углеводы`() {
        val n = parseVkusVillNutriments("белки 4.5 г, жиры 6.2 г, углеводы 76.1 г,, в том числе сахара (общие) - 24.3 г; 394.8 ккал")

        assertEquals(Nutriments(kcal100 = 395, prot100 = 450, fat100 = 620, carb100 = 7610), n)
    }

    @Test
    fun `запятая в дробях и точка с запятой между числами`() {
        val n = parseVkusVillNutriments("белки 1,5 г; жиры 0,2 г; углеводы 2,3 г; 17 ккал")

        assertEquals(Nutriments(kcal100 = 17, prot100 = 150, fat100 = 20, carb100 = 230), n)
    }

    /** У твёрдых сыров строку углеводов не пишут вовсе — это ноль, а не отказ от товара. */
    @Test
    fun `углеводов в строке нет — ноль`() {
        val n = parseVkusVillNutriments("белки 26.5 г, жиры 25.7 г, ; 337.3 ккал")

        assertEquals(Nutriments(kcal100 = 337, prot100 = 2650, fat100 = 2570, carb100 = 0), n)
    }

    @Test
    fun `хвост с поставщиками числам не мешает`() {
        val n = parseVkusVillNutriments(
            "белки 12.9 г, жиры 13.3 г, углеводы 46.6 г,, в том числе сахара (общие) - 5.1 г, соль - 0.7 г; " +
                "357.7 ккал Поставщики:ЗАО БКК \"КОЛОМЕНСКИЙ\";ООО \"ХЛЕБОКОМБИНАТ ПЕКО\"",
        )

        assertEquals(Nutriments(kcal100 = 358, prot100 = 1290, fat100 = 1330, carb100 = 4660), n)
    }

    /**
     * Один товар ВкусВиллу делают несколько заводов, и у каждого своя строка. Чей пакет
     * в руках, по коду не узнать, поэтому берётся серединный по калориям: настоящая
     * этикетка, от которой остальные отстоят меньше всего.
     */
    @Test
    fun `несколько производителей с близкими числами — серединный`() {
        val n = parseVkusVillNutriments(
            "ООО \"ЛЕБЕДЯНЬМОЛОКО\": белки 16 г, жиры 5 г, углеводы 3 г; 121 ккал<br>" +
                "ООО \"КУРСКОЕ МОЛОКО\": белки 16 г, жиры 5 г, углеводы 1.8 г; 116.2 ккал<br>" +
                "ООО \"МИЛКЭКСПРЕСС\": белки 14 г, жиры 5 г, углеводы 3 г; 113 ккал",
        )

        assertEquals(Nutriments(kcal100 = 116, prot100 = 1600, fat100 = 500, carb100 = 180), n)
    }

    /** Салат у разных кухонь расходится в полтора раза: любая из строк соврала бы, как генерик. */
    @Test
    fun `производители расходятся больше чем на четверть — чисел нет`() {
        val n = parseVkusVillNutriments(
            "ООО \"ПОЛЯНА\": белки 1.2 г, жиры 5.2 г, углеводы 11.4 г, соль - 0.7 г; 97.2 ккал<br>" +
                "ООО \"ЭКОФУД\": белки 1.1 г, жиры 3.3 г, углеводы 7.6 г, соль - 1.1 г; 64.5 ккал",
        )

        assertNull(n)
    }

    @Test
    fun `расходящиеся производители — товар известен по названию`() {
        val body = answer(
            name = "Салат \"Витаминный\" с лимонной заправкой",
            nutrition = "ООО \"ПОЛЯНА\": белки 1.2 г, жиры 5.2 г, углеводы 11.4 г; 97.2 ккал<br>" +
                "ООО \"ЭКОФУД\": белки 1.1 г, жиры 3.3 г, углеводы 7.6 г; 64.5 ккал",
        )

        val product = parseVkusVillProduct(GTIN, body)!!

        assertEquals("Салат \"Витаминный\" с лимонной заправкой", product.name)
        assertNull(product.nutriments)
    }

    /** У воды и зелени свойства «пищевая ценность» нет совсем. */
    @Test
    fun `без пищевой ценности — только название и без разметки в нём`() {
        val product = parseVkusVillProduct(GTIN, answer(name = "Вода питьевая негазированная, 5&nbsp;л", nutrition = null))!!

        assertEquals("Вода питьевая негазированная, 5 л", product.name)
        assertNull(product.nutriments)
    }

    @Test
    fun `мусорные числа не показываются`() {
        assertNull(parseVkusVillNutriments("белки 3 г, жиры 3 г, углеводы 4 г; 5000 ккал"))
        assertNull(parseVkusVillNutriments("белки 3 г, жиры 3 г, углеводы 4 г"))
    }

    @Test
    fun `товара нет — промах`() {
        val miss = rpc("""{"ok":false,"error":{"code":"invalid_input","message":"Товар по штрих-коду не найден","http_status":400}}""")

        assertNull(parseVkusVillProduct(GTIN, miss))
    }

    @Test
    fun `битый ответ — не исключение а промах`() {
        assertNull(parseVkusVillProduct(GTIN, "<html>403</html>"))
        assertNull(parseVkusVillProduct(GTIN, """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"bad"}}"""))
        assertNull(parseVkusVillProduct(GTIN, rpc("не json")))
    }

    /** Сервер принимает только EAN-13, и короткий код в сеть не уходит. */
    @Test
    fun `запрос — вызов инструмента поиска по коду и только для тринадцати цифр`() {
        assertEquals(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":""" +
                """{"name":"vkusvill_product_barcode","arguments":{"barcode":"$GTIN"}}}""",
            vkusVillRequest(GTIN),
        )
        assertNull(vkusVillRequest("46000017"))
    }

    /** Ответ инструмента — JSON строкой внутри JSON-RPC, как его отдаёт сервер. */
    private fun rpc(text: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 1)
        putJsonObject("result") {
            putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", text) })
            }
            put("isError", false)
        }
    }.toString()

    private fun answer(name: String, nutrition: String?): String = rpc(
        buildJsonObject {
            put("ok", true)
            putJsonObject("data") {
                put("id", 37580)
                put("name", name)
                put("brand", JsonPrimitive(null as String?))
                putJsonObject("weight") { put("value", 0.3); put("unit", "кг") }
                putJsonArray("properties") {
                    add(buildJsonObject { put("name", "Состав"); put("value", "вода питьевая") })
                    if (nutrition != null) {
                        add(buildJsonObject { put("name", "Пищевая и энергетическая ценность в 100 г"); put("value", nutrition) })
                    }
                }
            }
        }.toString(),
    )

    private companion object {
        const val GTIN = "2100100202484"
    }
}
