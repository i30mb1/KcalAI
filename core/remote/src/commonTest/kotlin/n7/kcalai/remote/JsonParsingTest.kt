package n7.kcalai.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import n7.kcalai.database.ContributionEntity
import n7.kcalai.model.Nutriments
import n7.kcalai.model.ProductOrigin

/**
 * Разбор ответов — единственное, что в источниках можно проверить без сети,
 * и ровно то место, где чужой JSON ломает всё тихо.
 */
class JsonParsingTest {

    @Test
    fun `карточка OFF в наших единицах`() {
        val body = """
            {"status":1,"product":{
              "product_name":"Milk","product_name_ru":"Молоко 3,2%","brands":"Домик в деревне, ВБД",
              "serving_quantity":"250",
              "nutriments":{"energy-kcal_100g":59,"proteins_100g":2.9,"fat_100g":3.2,"carbohydrates_100g":4.7}
            }}
        """.trimIndent()

        val product = parseOffProduct("4600699500001", body)!!

        assertEquals("Молоко 3,2%", product.name)
        assertEquals("Домик в деревне", product.brand)
        assertEquals(Nutriments(kcal100 = 59, prot100 = 290, fat100 = 320, carb100 = 470), product.nutriments)
        assertEquals(250, product.servingG)
        assertEquals(ProductOrigin.OFF, product.origin)
    }

    @Test
    fun `пустое русское название уступает общему`() {
        val body = """{"status":1,"product":{"product_name":"Milk","product_name_ru":"  ",
            "nutriments":{"energy-kcal_100g":59}}}"""

        assertEquals("Milk", parseOffProduct("1", body)!!.name)
    }

    @Test
    fun `калории из килоджоулей, если ккал нет`() {
        val body = """{"status":1,"product":{"product_name":"X","nutriments":{"energy-kj_100g":418.4}}}"""

        assertEquals(100, parseOffProduct("1", body)!!.nutriments.kcal100)
    }

    @Test
    fun `status 0 — товара нет`() {
        assertNull(parseOffProduct("1", """{"status":0}"""))
    }

    @Test
    fun `битый JSON — не исключение, а промах`() {
        assertNull(parseOffProduct("1", "<html>502</html>"))
        assertNull(parseServerProduct("1", "{"))
        assertNull(parseManifest("http://s", "[]"))
        assertEquals(emptySet(), parseAccepted("oops"))
    }

    @Test
    fun `КБЖУ вне допустимого отбрасывается`() {
        val body = """{"status":1,"product":{"product_name":"X","nutriments":{"energy-kcal_100g":5000}}}"""

        assertNull(parseOffProduct("1", body))
    }

    @Test
    fun `ответ своего сервера уже в наших единицах`() {
        val body = """{"gtin":"4600699500001","name":"Молоко","brand":"ДвД",
            "kcal100":59,"prot100":290,"fat100":320,"carb100":470,"servingG":250}"""

        val product = parseServerProduct("4600699500001", body)!!

        assertEquals(Nutriments(59, 290, 320, 470), product.nutriments)
        assertEquals("ДвД", product.brand)
        assertEquals(250, product.servingG)
        assertEquals(ProductOrigin.SERVER, product.origin)
    }

    @Test
    fun `без порции и бренда — null, а не ноль и пустая строка`() {
        val product = parseServerProduct("1", """{"name":"X","kcal100":59}""")!!

        assertNull(product.brand)
        assertNull(product.servingG)
    }

    @Test
    fun `подтверждённые вклады`() {
        assertEquals(setOf("1", "2"), parseAccepted("""{"accepted":["1","2"]}"""))
    }

    @Test
    fun `относительная ссылка манифеста достраивается от сервера`() {
        val manifest = parseManifest("http://s", """{"version":3,"sha256":"ab","size":10,"url":"/v1/seed/3.db"}""")!!

        assertEquals(SeedManifest(3, "ab", 10, "http://s/v1/seed/3.db"), manifest)
    }

    @Test
    fun `вклад уходит в единицах контракта, без пустой порции`() {
        val json = contributionsJson(
            listOf(
                ContributionEntity(
                    gtin = "1", name = "Сыр \"Российский\"", kcal100 = 350, prot100 = 2400, fat100 = 2900,
                    carb100 = 0, servingG = null, createdAt = 1757721600000,
                ),
            ),
        )

        assertEquals(
            """[{"gtin":"1","name":"Сыр \"Российский\"","kcal100":350,"prot100":2400,"fat100":2900,""" +
                """"carb100":0,"createdAt":1757721600000}]""",
            json,
        )
    }
}
