package cz.suku.rokidglass.products

import kotlin.random.Random

enum class ProductDirection {
    NEXT,
    PREVIOUS,
}

/** Keeps product ordering and selection outside the phone and glasses applications. */
class ProductNavigator(
    private val repository: ProductRepository,
    private val random: Random = Random.Default,
) {
    private var productIds: List<Int> = emptyList()

    fun getRandomProduct(excludingId: Int? = null): FannProduct {
        val ids = catalogIds()
        val candidates = ids.filterNot { it == excludingId }.ifEmpty { ids }
        return repository.getProduct(candidates.random(random))
    }

    fun getAdjacentProduct(currentId: Int?, direction: ProductDirection): FannProduct {
        var ids = catalogIds()
        var currentIndex = ids.indexOf(currentId)
        if (currentId != null && currentIndex < 0) {
            productIds = emptyList()
            ids = catalogIds()
            currentIndex = ids.indexOf(currentId)
        }

        val targetIndex = when {
            currentIndex < 0 && direction == ProductDirection.NEXT -> 0
            currentIndex < 0 -> ids.lastIndex
            direction == ProductDirection.NEXT -> (currentIndex + 1) % ids.size
            else -> (currentIndex - 1 + ids.size) % ids.size
        }
        return repository.getProduct(ids[targetIndex])
    }

    private fun catalogIds(): List<Int> {
        if (productIds.isEmpty()) {
            productIds = repository.getProducts().map(FannProduct::id).distinct()
        }
        if (productIds.isEmpty()) throw ProductApiException("FAnn API neobsahuje produkty")
        return productIds
    }
}
