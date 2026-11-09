package n7.kcalai.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import n7.kcalai.model.Nutriments
import n7.kcalai.model.ProductOrigin
import n7.kcalai.repositories.RemoteProduct
import n7.kcalai.repositories.RemoteProductSource

/** Цепочка источников: товар с КБЖУ дороже товара, известного только по названию. */
class ChainedProductSourceTest {

    private val named = RemoteProduct(GTIN, "Творог 5%", brand = null, nutriments = null, servingG = null, origin = ProductOrigin.OFF)
    private val full = named.copy(nutriments = Nutriments(121, 1600, 500, 300), origin = ProductOrigin.SERVER)

    @Test
    fun `первый знает только название — спрашивается следующий`() = runTest {
        val chain = ChainedProductSource(listOf(RemoteProductSource { named }, RemoteProductSource { full }))

        assertEquals(full, chain.fetch(GTIN))
    }

    @Test
    fun `чисел нет ни у кого — остаётся название`() = runTest {
        val chain = ChainedProductSource(listOf(RemoteProductSource { named }, RemoteProductSource { null }))

        assertEquals(named, chain.fetch(GTIN))
    }

    private companion object {
        const val GTIN = "4600000000017"
    }
}
