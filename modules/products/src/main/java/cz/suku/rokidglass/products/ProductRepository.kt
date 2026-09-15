package cz.suku.rokidglass.products

interface ProductRepository {
    fun getProducts(): List<FannProduct>
    fun getProduct(id: Int): FannProduct
}
