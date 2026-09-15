package cz.suku.rokidglass.platform

import org.json.JSONArray
import org.json.JSONObject

/** Presentation-only product payload transferred from the phone to the glasses. */
data class DisplayProduct(
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
    fun toJson(): String = JSONObject()
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
        .toString()

    companion object {
        fun fromJson(value: String): DisplayProduct {
            val json = JSONObject(value)
            return DisplayProduct(
                sku = json.optString("sku"),
                name = json.optString("name"),
                description = json.optString("description"),
                priceWithVat = json.optString("priceWithVat"),
                currency = json.optString("currency", "CZK"),
                character = json.optString("character"),
                salesArgument = json.optString("salesArgument"),
                upsellUpgrade = json.optString("upsellUpgrade"),
                basketIfUpgradeDeclined = json.optString("basketIfUpgradeDeclined"),
                categories = json.stringList("categories"),
                alternatives = json.stringList("alternatives"),
            )
        }

        private fun JSONObject.stringList(name: String): List<String> {
            val array = optJSONArray(name) ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }
}
