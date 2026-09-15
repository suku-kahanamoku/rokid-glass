package cz.suku.rokidglass.products

import android.content.Context
import org.json.JSONObject

class ProductCache(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): FannProduct? = preferences.getString(PRODUCT_JSON_KEY, null)
        ?.let { json -> runCatching { FannProduct.fromJson(JSONObject(json)) }.getOrNull() }

    fun save(product: FannProduct) {
        preferences.edit().putString(PRODUCT_JSON_KEY, product.toJson().toString()).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "fann_products"
        const val PRODUCT_JSON_KEY = "last_product_json"
    }
}
