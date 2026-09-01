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

    /**
     * Cookie-/Consent-/Botwall-Phrasen. Wenn der Text nach dem Cleanen hauptsächlich
     * aus solchen Fragmenten besteht (z. B. Golem ohne JS/Consent), ist das KEIN Artikel.
     */
    private val CONSENT_MARKERS = listOf(
        "zustimmen", "einwilligung", "cookies", "privacy center", "datenschutz",
        "javascript wird benötigt", "skript wurde nicht geladen", "problembehandlung",
        "werbung und tracking", "nutzung aller cookies", "referenz-link zur seite",
        "ihre daten", "tracking", "wir verwenden cookies",
    )

    /** Liefert den Volltext oder null, wenn nichts Sinnvolles extrahierbar war. */
    fun extract(url: String): String? {
        return try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
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
        val cleaned = condensed.takeIf { it.length >= 400 }?.take(12_000) ?: return null

        // Consent-/Botwall-Erkennung: Wenn das Verhältnis Banner-Phrasen hoch ist,
        // ist das keine Artikelseite (Golem & Co. liefern ohne JS nur den Cookie-Text).
        val lower = cleaned.lowercase()
        val markerHits = CONSENT_MARKERS.count { lower.contains(it) }
        val words = lower.split(Regex("\\s+")).size.coerceAtLeast(1)
        // Z. B. 5+ Marker bei < 400 Wörtern → fast sicher ein Banner
        val bannerRatio = markerHits.toFloat() / Math.sqrt(words.toDouble()).toFloat()
        if (bannerRatio > 1.2f) return null

        return cleaned
    }
}
