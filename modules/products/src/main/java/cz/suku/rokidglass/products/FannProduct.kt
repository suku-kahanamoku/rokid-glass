package cz.suku.rokidglass.products

import org.json.JSONArray
import org.json.JSONObject

data class FannProduct(
    val id: Int,
    val sku: String,
    val name: String,
    val description: String,
    val priceWithVat: String,
    val currency: String,
    val character: String,
    val salesArgument: String,
    val upsellUpgrade: String,
    val basketIfUpgradeDeclined: String,
    val categories: List<String>,
    val alternatives: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("sku", sku)
        .put("name", name)
        .put("description", description)
        .put("priceWithVat", priceWithVat)
        .put("currency", currency)
        .put("character", character)
        .put("salesArgument", salesArgument)
        .put("upsellUpgrade", upsellUpgrade)
        .put("basketIfUpgradeDeclined", basketIfUpgradeDeclined)
        .put("categories", JSONArray(categories))
        .put("alternatives", JSONArray(alternatives))

    companion object {
        fun fromJson(json: JSONObject): FannProduct = FannProduct(
            id = json.optInt("id"),
            sku = json.optString("sku"),
            name = json.optString("name"),
            description = json.optString("description"),
            priceWithVat = json.optString("priceWithVat"),
            currency = json.optString("currency", "CZK"),
            character = json.optString("character"),
            salesArgument = json.optString("salesArgument"),
            upsellUpgrade = json.optString("upsellUpgrade"),
            basketIfUpgradeDeclined = json.optString("basketIfUpgradeDeclined"),
            categories = json.optStringList("categories"),
            alternatives = json.optStringList("alternatives"),
        )

        private fun JSONObject.optStringList(name: String): List<String> {
            val values = optJSONArray(name) ?: return emptyList()
            return buildList {
                for (index in 0 until values.length()) {
                    values.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }
}
