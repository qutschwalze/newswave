package com.wavenews.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.wavenews.app.R
import com.wavenews.app.WaveNewsApp
import com.wavenews.app.data.db.WidgetArticleRow
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

/**
 * RemoteViewsService des Home-Screen-Widgets: beliefert die ListView mit den
 * neuesten ungelesenen Artikeln (inkl. Thumbnail, Kategorie-Badge, Zeit).
 */
class NewsWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        WidgetFactory(applicationContext)
}

/** Erzeugt die Zeilen-Views für die Widget-ListView. */
private class WidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private val articles = ArrayList<WidgetArticleRow>()
    private val imageCache = ConcurrentHashMap<String, WeakBitmap>()

    private class WeakBitmap(val bmp: Bitmap) // simple holder; GC-able wenn Widget weg

    override fun onCreate() = Unit
    override fun onDestroy() {
        imageCache.values.forEach { it.bmp.recycle() }
        imageCache.clear()
        articles.clear()
    }

    override fun getCount(): Int = articles.size

    override fun getViewAt(position: Int): RemoteViews {
        val article = articles[position]
        val row = RemoteViews(context.packageName, R.layout.widget_article_row)

        row.setTextViewText(R.id.widget_row_title, article.title)
        row.setTextViewText(R.id.widget_row_meta, article.category)
        val dateLabel = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(article.published * 1000))
        row.setTextViewText(R.id.widget_row_time, dateLabel)

        // Thumbnail: aus dem Artikel-Bild (oder Kategorie-Monogramm)
        val bmp = loadImage(article.imageUrl, article.category)
        if (bmp != null) {
            row.setImageViewBitmap(R.id.widget_row_image, bmp)
        } else {
            row.setImageViewResource(R.id.widget_row_image, R.drawable.widget_placeholder)
        }

        // Klick → App öffnet den Artikel (Deep-Link via Template + FillIn)
        row.setOnClickFillInIntent(R.id.widget_row, Intent().apply {
            data = Uri.parse("newswave://article/${article.id}")
        })
        return row
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true

    override fun onDataSetChanged() {
        articles.clear()
        val app = context.applicationContext as? WaveNewsApp ?: return
        val rows = try {
            runBlocking { app.repository.latestUnreadWithCategory(8) }
        } catch (_: Exception) {
            emptyList()
        }
        articles.addAll(rows)
    }

    /** Bild laden (klein, gecacht); null → Platzhalter. */
    private fun loadImage(url: String?, fallback: String): Bitmap? {
        if (url.isNullOrBlank()) return null
        imageCache[url]?.let { return it.bmp }
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) NewsWave/1.0")
            val stream = conn.inputStream
            val bmp = BitmapFactory.decodeStream(stream)?.let { scaleTo(it, 96, 96) }
            stream.close()
            conn.disconnect()
            if (bmp != null) imageCache[url] = WeakBitmap(bmp)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleTo(src: Bitmap, w: Int, h: Int): Bitmap {
        val ratio = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
        if (ratio >= 1f) return src
        return Bitmap.createScaledBitmap(src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true)
    }
}