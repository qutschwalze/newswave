package com.wavenews.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Vorlesen über die Android-System-TTS (TextToSpeech) — keine Cloud, keine Extra-App.
 * Die Sprache wird automatisch gesetzt: Deutsch, sonst die Feed-/Artikelsprache (EN-Fallback).
 * Verantwortlich für Lifecycle: die UI ruft start/stop; bei App-Ende shutdown().
 */
class TtsReader(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    private var pendingLangTag: String? = null

    /** Beobachtbar für die UI (läuft / fertig). */
    var onStateChanged: ((playing: Boolean) -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    private fun ensureEngine(onReady: (Boolean) -> Unit) {
        if (tts != null) {
            onReady(ready)
            return
        }
        tts = TextToSpeech(context.applicationContext, this).also { engine ->
            // onInit feuert asynchron; wir merken uns den Wunschtext bis dahin
            pendingReadyCallback = onReady
        }
    }

    private var pendingReadyCallback: ((Boolean) -> Unit)? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            // Standard: Gerätessprache; wird pro Vorlese-Vorgang überschrieben
            tts?.language = Locale.getDefault()
        }
        pendingReadyCallback?.invoke(ready)
        pendingReadyCallback = null
    }

    /**
     * Liest den Text vor. @param langTag BCP-47 ("de", "en") — steuert die Engine-Sprache,
     * damit deutschsprachige Artikel nicht mit englischer Stimme gelesen werden.
     */
    fun speak(text: String, langTag: String? = null) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        ensureEngine { ok ->
            if (!ok) return@ensureEngine
            val engine = tts ?: return@ensureEngine
            val locale = langTag?.let { runCatching { Locale.forLanguageTag(it) }.getOrNull() } ?: Locale.getDefault()
            engine.language = locale
            // QUEUE_FLUSH: laufende Wiedergabe abbrechen, neu starten
            engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onStateChanged?.invoke(true)
                }

                override fun onDone(utteranceId: String?) {
                    onStateChanged?.invoke(false)
                    onFinished?.invoke()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onStateChanged?.invoke(false)
                }
            })
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "newswave-article")
        }
    }

    fun stop() {
        tts?.stop()
        onStateChanged?.invoke(false)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true
}
