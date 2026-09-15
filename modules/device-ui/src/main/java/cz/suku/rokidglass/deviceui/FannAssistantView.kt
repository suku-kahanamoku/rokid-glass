package cz.suku.rokidglass.deviceui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cz.suku.rokidglass.deviceui.R
import cz.suku.rokidglass.platform.DisplayProduct

class FannAssistantView(context: Context) : ScrollView(context) {
    private val transcriptText: TextView
    private val productName: TextView
    private val productMeta: TextView
    private val productDescription: TextView
    private val productPrice: TextView
    private val productDetails: TextView
    private val productAlternatives: TextView
    private val productViews: List<TextView>
    private val scrollTranscriptToBottom = Runnable { fullScroll(View.FOCUS_DOWN) }
    private val scrollContentToTop = Runnable { scrollTo(0, 0) }

    init {
        val density = resources.displayMetrics.density
        val horizontalPadding = (28 * density).toInt()
        val verticalPadding = (14 * density).toInt()

        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
        }

        transcriptText = text(19f, Color.WHITE).apply {
            text = ""
            visibility = View.INVISIBLE
        }
        productName = text(21f, Color.WHITE, bold = true)
        productMeta = text(11f, Color.rgb(185, 200, 190))
        productDescription = text(14f, Color.rgb(232, 245, 236))
        productPrice = text(14f, Color.rgb(255, 209, 102), bold = true)
        productDetails = text(12f, Color.rgb(210, 225, 214))
        productAlternatives = text(11f, Color.rgb(185, 200, 190))
        productViews = listOf(
            productName,
            productMeta,
            productDescription,
            productPrice,
            productDetails,
            productAlternatives,
        ).onEach { it.visibility = View.GONE }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            setBackgroundColor(Color.BLACK)
            addView(transcriptText)
            productViews.forEach(::addView)
        }

        isFillViewport = true
        setBackgroundColor(Color.BLACK)
        addView(content)
    }

    fun showTranscript(text: String, isFinal: Boolean) {
        val transcript = text.trim()
        if (transcript.isBlank()) {
            transcriptText.text = ""
            transcriptText.visibility = View.INVISIBLE
            return
        }

        productViews.forEach { it.visibility = View.GONE }
        transcriptText.text = transcript
        transcriptText.alpha = if (isFinal) 1f else 0.82f
        transcriptText.visibility = View.VISIBLE
        removeCallbacks(scrollTranscriptToBottom)
        post(scrollTranscriptToBottom)
    }

    fun showProduct(product: DisplayProduct) {
        removeCallbacks(scrollTranscriptToBottom)
        transcriptText.text = ""
        transcriptText.visibility = View.GONE

        productName.text = product.name
        productMeta.text = listOf(
            product.sku,
            product.categories.joinToString(", "),
        ).filter(String::isNotBlank).joinToString(" • ")
        productDescription.text = product.description
        productPrice.text = product.priceWithVat
            .takeIf(String::isNotBlank)
            ?.let { context.getString(R.string.product_price, it, product.currency) }
            .orEmpty()
        productDetails.text = buildList {
            product.character.takeIf(String::isNotBlank)
                ?.let { add(context.getString(R.string.product_character, it)) }
            product.salesArgument.takeIf(String::isNotBlank)
                ?.let { add(context.getString(R.string.product_sales_argument, it)) }
            product.upsellUpgrade.takeIf(String::isNotBlank)
                ?.let { add(context.getString(R.string.product_upsell, it)) }
            product.basketIfUpgradeDeclined.takeIf(String::isNotBlank)
                ?.let { add(context.getString(R.string.product_basket, it)) }
        }.joinToString("\n")
        productAlternatives.text = product.alternatives
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString(", ")
            ?.let { context.getString(R.string.product_alternatives, it) }
            .orEmpty()

        productViews.forEach { view ->
            view.visibility = if (view.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        removeCallbacks(scrollContentToTop)
        post(scrollContentToTop)
    }

    fun clear() {
        removeCallbacks(scrollTranscriptToBottom)
        transcriptText.text = ""
        transcriptText.visibility = View.INVISIBLE
        productViews.forEach { it.visibility = View.GONE }
        removeCallbacks(scrollContentToTop)
        post(scrollContentToTop)
    }
}
