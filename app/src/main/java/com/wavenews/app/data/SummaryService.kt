package com.wavenews.app.data

import android.content.Context
import com.wavenews.app.data.db.AppDatabase
import com.wavenews.app.data.db.SummaryEntity
import com.wavenews.app.data.summarizer.ArticleTextExtractor
import com.wavenews.app.data.summarizer.OnnxSummarizer
import com.wavenews.app.data.summarizer.Summarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 5: Artikel-Zusammenfassung ("Clickbait-Entzauberung").
 * 1. Volltext aus der Artikel-URL extrahieren (Screen 2 zeigt meist nur Teaser)
 * 2. ONNX (T5-small, abstraktiv) wenn aktiviert UND Modell geladen; sonst extraktiv
 * 3. In Room cachen (nur 1x pro Artikel)
 */
class SummaryService(
    private val db: AppDatabase,
    private val appContext: Context? = null,
) {

    /** Ergebnis-Klassen für die UI: Cache-Hit, neu berechnet, oder fehlgeschlagen. */
    sealed class SummaryResult {
        data class Success(val summary: String, val fromCache: Boolean) : SummaryResult()
        data object Unavailable : SummaryResult()
    }

    suspend fun getSummary(
        articleId: String,
        url: String,
        teaserHtml: String? = null,
        onnxEnabled: Boolean = false,
    ): SummaryResult = withContext(Dispatchers.IO) {
        db.summaryDao().byArticle(articleId)?.let {
            return@withContext SummaryResult.Success(it.summary, fromCache = true)
        }

        // 1) Volltext aus der Artikel-URL (scheitert bei Consent-/Botwall-Seiten wie Golem)
        val fullText = url.takeIf { it.startsWith("http") }?.let { ArticleTextExtractor.extract(it) }

        // 2) Fallback: Feed-Teaser (summaryHtml) — deutlich besser als der Cookie-Banner
        val sourceText = fullText
            ?: teaserHtml?.let { org.jsoup.Jsoup.parse(it).text().trim().takeIf { t -> t.length >= 150 } }

        val usableText = sourceText ?: return@withContext SummaryResult.Unavailable

        // Bevorzugter Weg: ONNX (abstraktiv, von Null lernt) — wenn aktiviert und Modell geladen
        val onnxResult: String? = if (onnxEnabled && appContext != null) {
            OnnxSummarizer.get(appContext)?.summarize(usableText)
        } else null

        // Fallback: extraktiver Summarizer (kein Modell nötig)
        val summary: String = onnxResult
            ?: Summarizer.summarize(usableText)
            ?: return@withContext SummaryResult.Unavailable

        db.summaryDao().upsert(SummaryEntity(articleId = articleId, summary = summary))
        SummaryResult.Success(summary, fromCache = false)
    }

    /** ONNX-Modell geladen? */
    fun isOnnxReady(): Boolean = appContext != null && OnnxSummarizer.isDownloaded(appContext!!)

    /** ONNX-Modell löschen. */
    fun deleteOnnxModel() {
        appContext?.let { OnnxSummarizer.dir(it).deleteRecursively() }
    }
}
