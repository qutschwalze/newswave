package com.wavenews.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.wavenews.app.data.ThemeMode
import com.wavenews.app.ui.MainScreen
import com.wavenews.app.ui.NewsWaveTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        enableEdgeToEdge()
        setContent {
            val app = applicationContext as WaveNewsApp
            val settings by app.settings.settings.collectAsState(initial = null)
            NewsWaveTheme(
                darkTheme = when (settings?.themeMode) {
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                    else -> null // SYSTEM
                },
            ) {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /** newswave://open → App öffnen; newswave://article/<id> → Artikel-Detailansicht. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "newswave") return
        when (data.host) {
            "open" -> { /* App öffnen genügt */ }
            "article" -> {
                val id = data.lastPathSegment ?: data.toString().substringAfterLast('/')
                if (!id.isNullOrBlank()) {
                    (applicationContext as WaveNewsApp).pendingArticleId.value = id
                }
            }
        }
    }
}
