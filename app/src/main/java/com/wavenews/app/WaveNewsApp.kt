package com.wavenews.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.wavenews.app.data.NewsRepository
import com.wavenews.app.data.SettingsStore
import com.wavenews.app.data.SummaryService
import com.wavenews.app.data.db.AppDatabase
import com.wavenews.app.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow

class WaveNewsApp : Application(), ImageLoaderFactory {

    lateinit var settings: SettingsStore
        private set
    lateinit var repository: NewsRepository
        private set
    lateinit var summaryService: SummaryService
        private set

    /** Deep-Link-Ziel (newswave://article/<id>), wird von der UI konsumiert und geleert. */
    val pendingArticleId = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        repository = NewsRepository(settings, AppDatabase.get(this))
        summaryService = SummaryService(AppDatabase.get(this), this)
        SyncWorker.schedule(this)
    }

    /** Coil-ImageLoader mit SVG-Support für die Themen-Logos. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .crossfade(true)
            .build()
}
