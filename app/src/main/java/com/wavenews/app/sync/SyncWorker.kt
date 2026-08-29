package com.wavenews.app.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wavenews.app.R
import com.wavenews.app.WaveNewsApp
import com.wavenews.app.widget.NewsWaveWidgetProvider
import java.util.concurrent.TimeUnit

/**
 * Hintergrund-Sync: holt neue Artikel, benachrichtigt bei Neuigkeiten,
 * aktualisiert das Widget und plant sich selbst alle 30 Minuten neu.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? WaveNewsApp ?: return Result.failure()
        val before = try {
            app.repository.unreadCount()
        } catch (_: Exception) {
            0
        }
        return try {
            app.repository.sync()
            val after = app.repository.unreadCount()
            val fresh = (after - before).coerceAtLeast(0)
            if (fresh > 0) notifyNewArticles(fresh)
            NewsWaveWidgetProvider.refreshAll(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun notifyNewArticles(count: Int) {
        val ctx = applicationContext
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val channel = NotificationChannel(CHANNEL_ID, ctx.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(channel)

        val open = android.app.PendingIntent.getActivity(
            ctx, 42,
            android.content.Intent(ctx, com.wavenews.app.MainActivity::class.java)
                .setAction(android.content.Intent.ACTION_VIEW)
                .setData(android.net.Uri.parse("newswave://open")),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(ctx.getString(R.string.notif_new_articles, count))
            .setContentText(ctx.getString(R.string.notif_new_articles_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "new_articles"
        private const val NOTIF_ID = 1001
        private const val WORK_NAME = "news_wave_sync"

        /** Periodischer Sync alle 30 Minuten (beim App-Start registrieren). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Sofortiger Sync (z. B. vom Widget-Refresh). */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
