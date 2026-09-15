package cz.suku.rokidglass.products

import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

class FannProductRepository(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val random: Random = Random.Default,
) : ProductRepository {
    override fun getProduct(id: Int): FannProduct {
        val product = FannProductParser.parseDetail(get("$endpoint/$id"))
        if (product.id != id) throw ProductApiException("FAnn API vrátilo jiný produkt")
        return product
    }

    override fun getRandomProduct(excludingId: Int?): FannProduct {
        val products = FannProductParser.parseList(get("$endpoint?limit=100"))
        val candidates = products.filterNot { it.id == excludingId }.ifEmpty { products }
        if (candidates.isEmpty()) throw ProductApiException("FAnn API neobsahuje produkty")
        return getProduct(candidates.random(random).id)
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
