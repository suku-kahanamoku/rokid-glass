package cz.suku.rokidglass.products

import java.net.HttpURLConnection
import java.net.URL

class FannProductRepository(
    private val endpoint: String = DEFAULT_ENDPOINT,
) : ProductRepository {
    override fun getProducts(): List<FannProduct> =
        FannProductParser.parseList(get("$endpoint?limit=100"))

    override fun getProduct(id: Int): FannProduct {
        val product = FannProductParser.parseDetail(get("$endpoint/$id"))
        if (product.id != id) throw ProductApiException("FAnn API vrátilo jiný produkt")
        return product
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw ProductApiException("FAnn API vrátilo HTTP $responseCode")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://fann-crm.netlify.app/api/admin/product"
        private const val NETWORK_TIMEOUT_MS = 10_000
    }
}
