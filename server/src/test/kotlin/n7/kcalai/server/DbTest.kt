package n7.kcalai.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DbTest {

    private val db = Db.inMemory()
    private val milk = Product("4600699500001", "Молоко 3,2%", "Домик в деревне", Nutriments(59, 290, 320, 470), 250)

    @Test
    fun `товара нет — null`() {
        assertNull(db.findProduct("4600699500001"))
    }

    @Test
    fun `товар находится после записи, повторная запись обновляет`() {
        db.upsertProduct(milk, now = 1)
        db.upsertProduct(milk.copy(name = "Молоко 3,2% пастеризованное"), now = 2)

        assertEquals("Молоко 3,2% пастеризованное", db.findProduct(milk.gtin)?.name)
    }

    @Test
    fun `вклады копятся все, повтор того же GTIN — ещё один голос`() {
        val vote = Contribution(milk.gtin, milk.name, milk.nutriments, 250, createdAt = 10)
        db.insertContribution(vote, receivedAt = 100)
        db.insertContribution(vote, receivedAt = 101)

        assertEquals(2, db.contributionsFor(milk.gtin).size)
    }
}
