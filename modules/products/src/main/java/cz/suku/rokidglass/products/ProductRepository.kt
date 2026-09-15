package cz.suku.rokidglass.products

interface ProductRepository {
    fun getProduct(id: Int): FannProduct
    fun getRandomProduct(excludingId: Int? = null): FannProduct
}
