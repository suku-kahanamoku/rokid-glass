package cz.suku.rokidglass.products

import org.json.JSONObject

object FannProductParser {
    fun parseList(response: String): List<FannProduct> {
        val root = successfulRoot(response)
        val data = root.optJSONArray("data")
            ?: throw ProductApiException("FAnn API neobsahuje seznam produktů")

        return buildList {
            for (index in 0 until data.length()) {
                val product = data.optJSONObject(index) ?: continue
                if (product.optInt("published", 1) == 1) add(parseProduct(product))
            }
        }
    }

    fun parseDetail(response: String): FannProduct {
        val product = successfulRoot(response).optJSONObject("data")
            ?: throw ProductApiException("FAnn API neobsahuje produkt")
        if (product.optInt("published", 1) != 1) {
            throw ProductApiException("FAnn produkt není publikovaný")
        }
        return parseProduct(product)
    }

    private fun successfulRoot(response: String): JSONObject = JSONObject(response).also { root ->
        if (!root.optBoolean("success")) {
            throw ProductApiException("FAnn API nevrátilo úspěšnou odpověď")
        }
    }

    private fun parseProduct(product: JSONObject): FannProduct {
        val customData = product.optJSONObject("data")
        val price = customData?.optJSONObject("approx_price")

        return FannProduct(
            id = product.optInt("id"),
            sku = clean(product.optString("sku"), 40),
            name = clean(product.optString("name").ifBlank { "FAnn produkt" }, 100),
            description = clean(product.optString("description"), 220),
            priceWithVat = clean(product.optString("price_with_vat"), 30),
            currency = clean(price?.optString("currency", "CZK").orEmpty(), 10)
                .ifBlank { "CZK" },
            character = clean(customData?.optString("character").orEmpty(), 160),
            salesArgument = clean(
                customData?.optString("main_sales_argument").orEmpty(),
                220,
            ),
            upsellUpgrade = clean(customData?.optString("upsell_upgrade").orEmpty(), 180),
            basketIfUpgradeDeclined = clean(
                customData?.optString("basket_if_upgrade_declined").orEmpty(),
                180,
            ),
            categories = product.stringObjects("categories", "name", 3),
            alternatives = product.stringObjects("alternatives", "name", 3),
        )
    }

    private fun JSONObject.stringObjects(
        arrayName: String,
        propertyName: String,
        limit: Int,
    ): List<String> {
        val values = optJSONArray(arrayName) ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                values.optJSONObject(index)
                    ?.optString(propertyName)
                    ?.takeIf(String::isNotBlank)
                    ?.let { add(clean(it, 100)) }
                if (size == limit) break
            }
        }
    }

    private fun clean(text: String, maxLength: Int): String =
        text.replace(Regex("\\s+"), " ").trim().take(maxLength)
}

class ProductApiException(message: String) : Exception(message)
