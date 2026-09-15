package cz.suku.rokidglass.products

import org.junit.Assert.assertEquals
import org.junit.Test

class FannProductParserTest {
    @Test
    fun parsesPublishedProductDetail() {
        val product = FannProductParser.parseDetail(
            """{
                "success": true,
                "data": {
                    "id": 90,
                    "sku": "FUN-P016",
                    "name": "YSL Black Opium",
                    "description": "Výrazná večerní vůně.",
                    "published": 1,
                    "price_with_vat": "2420.00",
                    "data": {
                        "character": "Sladká, kávová",
                        "approx_price": {"currency": "CZK"},
                        "main_sales_argument": "Výrazná vůně."
                    },
                    "categories": [{"name": "Parfémy"}],
                    "alternatives": [{"name": "Alternativa"}]
                }
            }""".trimIndent(),
        )

        assertEquals(90, product.id)
        assertEquals("YSL Black Opium", product.name)
        assertEquals("2420.00", product.priceWithVat)
        assertEquals(listOf("Parfémy"), product.categories)
        assertEquals(listOf("Alternativa"), product.alternatives)
    }

    @Test
    fun listSkipsUnpublishedProducts() {
        val products = FannProductParser.parseList(
            """{
                "success": true,
                "data": [
                    {"id": 1, "name": "A", "published": 1},
                    {"id": 2, "name": "B", "published": 0}
                ]
            }""".trimIndent(),
        )

        assertEquals(listOf(1), products.map(FannProduct::id))
    }
}
