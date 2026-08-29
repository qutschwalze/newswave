package com.wavenews.app

import android.app.Application
import com.wavenews.app.data.NewsRepository
import com.wavenews.app.data.SettingsStore
import com.wavenews.app.data.db.AppDatabase
import com.wavenews.app.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow

class WaveNewsApp : Application() {

    lateinit var settings: SettingsStore
        private set
    lateinit var repository: NewsRepository
        private set

    /** Deep-Link-Ziel (newswave://article/<id>), wird von der UI konsumiert und geleert. */
    val pendingArticleId = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        repository = NewsRepository(settings, AppDatabase.get(this))
        SyncWorker.schedule(this)
    }
}
