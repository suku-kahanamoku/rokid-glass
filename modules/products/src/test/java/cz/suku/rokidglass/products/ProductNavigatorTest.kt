package cz.suku.rokidglass.products

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductNavigatorTest {
    private val products = listOf(product(10), product(20), product(30))
    private val repository = object : ProductRepository {
        override fun getProducts(): List<FannProduct> = products
        override fun getProduct(id: Int): FannProduct = products.first { it.id == id }
    }

    @Test
    fun nextProductFollowsApiOrderAndWraps() {
        val navigator = ProductNavigator(repository)

        assertEquals(20, navigator.getAdjacentProduct(10, ProductDirection.NEXT).id)
        assertEquals(10, navigator.getAdjacentProduct(30, ProductDirection.NEXT).id)
    }

    @Test
    fun previousProductFollowsApiOrderAndWraps() {
        val navigator = ProductNavigator(repository)

        assertEquals(20, navigator.getAdjacentProduct(30, ProductDirection.PREVIOUS).id)
        assertEquals(30, navigator.getAdjacentProduct(10, ProductDirection.PREVIOUS).id)
    }

    @Test
    fun randomProductExcludesCurrentWhenPossible() {
        val navigator = ProductNavigator(repository, Random(42))

        repeat(10) {
            check(navigator.getRandomProduct(excludingId = 20).id != 20)
        }
    }

    private fun product(id: Int) = FannProduct(
        id = id,
        sku = "SKU-$id",
        name = "Product $id",
        description = "",
        priceWithVat = "",
        currency = "CZK",
        character = "",
        salesArgument = "",
        upsellUpgrade = "",
        basketIfUpgradeDeclined = "",
        categories = emptyList(),
        alternatives = emptyList(),
    )
}
