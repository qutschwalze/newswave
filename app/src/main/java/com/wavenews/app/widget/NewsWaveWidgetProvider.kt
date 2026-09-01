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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home-Screen-Widget: zeigt bis zu 8 ungelesene Artikel mit Thumbnails in einer
 * scrollbaren Liste. Header: Refresh + „Alles gelesen". Tippen → Deep-Link in die App.
 */
class NewsWaveWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> updateInstance(context, manager, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> com.wavenews.app.sync.SyncWorker.enqueue(context)
            ACTION_MARK_ALL_READ -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        (context.applicationContext as? WaveNewsApp)?.repository?.markAllRead()
                    } finally {
                        pending.finish()
                    }
                }
                refreshAll(context)
            }
        }
    }

    private fun updateInstance(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.newswave_widget)

        // Header: App öffnen
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("newswave://open")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_header, openApp)

        // Refresh
        val refresh = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, NewsWaveWidgetProvider::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_refresh, refresh)

        // Alles gelesen
        val markAll = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, NewsWaveWidgetProvider::class.java).setAction(ACTION_MARK_ALL_READ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_mark_all, markAll)

        // ListView mit RemoteViewsService verbinden
        val serviceIntent = Intent(context, NewsWidgetService::class.java).apply {
            data = Uri.parse("widget://news/$appWidgetId")
        }
        views.setRemoteAdapter(R.id.widget_list, serviceIntent)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)
        views.setPendingIntentTemplate(R.id.widget_list, openApp)
        manager.updateAppWidget(appWidgetId, views)
    }

    companion object {
        const val ACTION_REFRESH = "com.wavenews.app.widget.REFRESH"
        const val ACTION_MARK_ALL_READ = "com.wavenews.app.widget.MARK_ALL_READ"

        /** Nach Sync vom WorkManager aufgerufen: Widget neu rendern. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NewsWaveWidgetProvider::class.java))
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }
    }
}