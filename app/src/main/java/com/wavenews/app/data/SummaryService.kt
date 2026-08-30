package com.wavenews.app.data

import com.wavenews.app.data.db.AppDatabase
import com.wavenews.app.data.db.SummaryEntity
import com.wavenews.app.data.summarizer.ArticleTextExtractor
import com.wavenews.app.data.summarizer.Summarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 5: Artikel-Zusammenfassung ("Clickbait-Entzauberung").
 * 1. Volltext aus der Artikel-URL extrahieren (Screen 2 zeigt meist nur Teaser)
 * 2. On-device zusammenfassen (extraktiv; ONNX-Modell ist als Drop-in geplant)
 * 3. In Room cachen (nur 1x pro Artikel)
 */
class SummaryService(
    private val db: AppDatabase,
) {

    /** Ergebnis-Klassen für die UI: Cache-Hit, neu berechnet, oder fehlgeschlagen. */
    sealed class SummaryResult {
        data class Success(val summary: String, val fromCache: Boolean) : SummaryResult()
        data object Unavailable : SummaryResult()
    }

    suspend fun getSummary(articleId: String, url: String): SummaryResult = withContext(Dispatchers.IO) {
        db.summaryDao().byArticle(articleId)?.let {
            return@withContext SummaryResult.Success(it.summary, fromCache = true)
        }

        val fullText = url.takeIf { it.startsWith("http") }?.let { ArticleTextExtractor.extract(it) }
            ?: return@withContext SummaryResult.Unavailable

        val summary = Summarizer.summarize(fullText)
            ?: return@withContext SummaryResult.Unavailable

        db.summaryDao().upsert(SummaryEntity(articleId = articleId, summary = summary))
        SummaryResult.Success(summary, fromCache = false)
    }
}
