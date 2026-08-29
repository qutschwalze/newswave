package com.wavenews.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.wavenews.app.MainActivity
import com.wavenews.app.R
import com.wavenews.app.WaveNewsApp
import com.wavenews.app.data.db.ArticleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home-Screen-Widget: zeigt die 3 neuesten ungelesenen Artikel.
 * Tippen auf einen Artikel öffnet die In-App-Detailansicht (Deep-Link newswave://article/<id>).
 */
class NewsWaveWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                render(context, manager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            com.wavenews.app.sync.SyncWorker.enqueue(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.wavenews.app.widget.REFRESH"

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NewsWaveWidgetProvider::class.java))
            render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
            val app = context.applicationContext as? WaveNewsApp ?: return
            val articles = try {
                kotlinx.coroutines.runBlocking { app.repository.latestUnread(3) }
            } catch (_: Exception) {
                emptyList()
            }
            val unread = try {
                kotlinx.coroutines.runBlocking { app.repository.unreadCount() }
            } catch (_: Exception) {
                0
            }

            appWidgetIds.forEach { id ->
                val views = buildViews(context, articles, unread)
                manager.updateAppWidget(id, views)
            }
        }

        private fun buildViews(context: Context, articles: List<ArticleEntity>, unread: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.newswave_widget)
            views.setTextViewText(R.id.widget_count, if (unread > 0) "$unread ungelesen" else "")

            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("newswave://open")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_title, openApp)

            val rows = listOf(R.id.widget_row1, R.id.widget_row2, R.id.widget_row3)
            rows.forEachIndexed { i, viewId ->
                val article = articles.getOrNull(i)
                if (article != null) {
                    views.setTextViewText(viewId, article.title)
                    val openArticle = PendingIntent.getActivity(
                        context, i + 1,
                        Intent(context, MainActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .setData(Uri.parse("newswave://article/${article.id}")),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    views.setOnClickPendingIntent(viewId, openArticle)
                } else {
                    views.setTextViewText(viewId, "")
                    views.setOnClickPendingIntent(viewId, openApp)
                }
            }
            views.setViewVisibility(
                R.id.widget_empty,
                if (articles.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE,
            )
            return views
        }
    }
}
