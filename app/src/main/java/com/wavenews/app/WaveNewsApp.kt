package com.wavenews.app

import android.app.Application
import com.wavenews.app.data.NewsRepository
import com.wavenews.app.data.SettingsStore
import com.wavenews.app.data.db.AppDatabase

class WaveNewsApp : Application() {

    lateinit var settings: SettingsStore
        private set
    lateinit var repository: NewsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        repository = NewsRepository(settings, AppDatabase.get(this))
    }
}
