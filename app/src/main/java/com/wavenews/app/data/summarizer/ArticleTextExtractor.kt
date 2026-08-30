package com.wavenews.app.data.summarizer

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Lädt die Artikel-URL und extrahiert den Volltext (ohne Navigation, Werbung, Scripts).
 * Läuft im IO-Dispatcher — aufrufender Code ist suspend.
 */
object ArticleTextExtractor {

    private val CONTENT_SELECTORS = listOf(
        "article",
        "[itemprop=articleBody]",
        ".article__content",
        ".article-body",
        ".article-content",
        ".post-content",
        ".entry-content",
        ".story",
        "main",
    )

    private val NOISE_SELECTORS = listOf(
        "script", "style", "nav", "header", "footer", "aside", "form",
        ".ad", ".ads", ".advertisement", ".related", ".sharing", ".social",
        ".comments", ".teaser-list", ".navigation", ".breadcrumb", "figure .caption",
    )

    /** Liefert den Volltext oder null, wenn nichts Sinnvolles extrahierbar war. */
    fun extract(url: String): String? {
        return try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 13) NewsWave/0.7")
                .referrer("https://www.google.com")
                .timeout(15_000)
                .followRedirects(true)
                .get()
            clean(doc)
        } catch (_: Exception) {
            null
        }
    }

    private fun clean(doc: Document): String? {
        doc.select(NOISE_SELECTORS.joinToString(", ")).remove()

        // Erst gezielte Selektoren, sonst das textreichste Element, sonst body
        val container: Element? = CONTENT_SELECTORS.firstNotNullOfOrNull { sel ->
            doc.select(sel).firstOrNull { it.text().length > 300 }
        } ?: doc.select("div, section").maxByOrNull { it.text().length }

        val text = (container ?: doc.body())?.wholeText()?.trim().orEmpty()
        val condensed = text.replace(Regex("\\n{2,}"), "\n\n").replace(Regex("[ \\t]+"), " ")
        return condensed.takeIf { it.length >= 400 }?.take(12_000)
    }
}
