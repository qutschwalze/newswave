package com.wavenews.app.data.summarizer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Abstraktive Zusammenfassung mit T5-small (ONNX, quantisiert) — komplett on-device.
 *
 * Architektur (in Python end-to-end validiert, 1:1-Port):
 *  - Encoder: input_ids + attention_mask → last_hidden_state [1, seq, 512]
 *  - Decoder-Schritt 1: decoder_model (ohne Past) mit input_ids=[[padId]];
 *    liefert Logits + 24 KV-Tensoren (12 decoder + 12 encoder)
 *  - Decoder-Schritte 2..n: decoder_with_past_model; nur die 12 Decoder-KV-Tensoren
 *    werden ersetzt, die 12 Encoder-KV-Tensoren bleiben vom ersten Schritt konstant
 *  - Greedy-Decoding bis </s> (id=1) oder max_new_tokens
 *
 * KV-Caches werden als Kopien gehalten (eigene OnnxTensors), damit die Result-Objekte
 * der Sessions direkt nach jedem Schritt geschlossen werden können (begrenzter RAM).
 *
 * Modell-Dateien (~118 MB, Xenova/t5-small quantisiert) werden bei Aktivierung in den
 * Einstellungen einmalig nach filesDir/onnx/ geladen.
 */
class OnnxSummarizer private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoderStep1: OrtSession,
    private val decoderWithPast: OrtSession,
    private val tokenizer: T5UnigramTokenizer,
) {

    /** Liefert die Zusammenfassung oder null bei Misserfolg. Läuft im aufrufenden IO-Context. */
    suspend fun summarize(text: String, maxNewTokens: Int = 64): String? = withContext(Dispatchers.Default) {
        try {
            summarizeInternal(text, maxNewTokens)
        } catch (_: Exception) {
            null
        }
    }

    private fun summarizeInternal(text: String, maxNewTokens: Int): String? {
        val sourceIds = tokenizer.encode("summarize: " + text.take(2000)).take(MAX_SOURCE_TOKENS)
        if (sourceIds.isEmpty()) return null
        val seqLen = sourceIds.size.toLong()
        val sourceArray = LongArray(sourceIds.size) { sourceIds[it].toLong() }

        val held = ArrayList<AutoCloseable>(32)
        try {
            // --- Encoder ---
            val hidden: Array<Array<FloatArray>> = encoder.run(
                mapOf(
                    "input_ids" to longTensor(sourceArray, longArrayOf(1, seqLen)).also { held.add(it) },
                    "attention_mask" to longTensor(LongArray(seqLen.toInt()) { 1L }, longArrayOf(1, seqLen)).also { held.add(it) },
                ),
            ).use { results ->
                @Suppress("UNCHECKED_CAST")
                results[0].value as Array<Array<FloatArray>>
            }
            val hiddenTensor = floatTensor3D(hidden).also { held.add(it) }

            // --- Decoder-Schritt 1 (ohne Past) ---
            val step1 = decoderStep1.run(
                mapOf(
                    "input_ids" to longTensor(longArrayOf(PAD_ID), longArrayOf(1, 1)).also { held.add(it) },
                    "encoder_hidden_states" to hiddenTensor,
                    "encoder_attention_mask" to held[1] as OnnxTensor,
                ),
            )
            val logits1 = step1.toFloatRows()
            val kvCache = LinkedHashMap<String, OnnxTensor>(24)
            decoderStep1.outputNames.toList().forEachIndexed { idx, name ->
                if (idx > 0) kvCache[name.replace("present.", "past_key_values.")] = (step1[idx] as OnnxTensor).copy()
            }
            step1.close()

            var last = argmax(logits1)
            val generated = ArrayList<Int>(maxNewTokens)
            if (last != EOS_ID) generated.add(last)

            // --- Decode-Loop (mit Past) ---
            var guard = 0
            while (last != EOS_ID && generated.size < maxNewTokens && guard++ < maxNewTokens + 2) {
                val feeds = LinkedHashMap<String, OnnxTensor>(kvCache.size + 3)
                feeds.putAll(kvCache)
                feeds["input_ids"] = longTensor(longArrayOf(last.toLong()), longArrayOf(1, 1)).also { held.add(it) }
                feeds["encoder_hidden_states"] = hiddenTensor
                feeds["encoder_attention_mask"] = held[1] as OnnxTensor

                val step = decoderWithPast.run(feeds)
                val logits = step.toFloatRows()
                // Nur decoder-*-Present ersetzen (encoder-* bleiben konstant)
                decoderWithPast.outputNames.toList().forEachIndexed { idx, name ->
                    if (idx > 0) {
                        val pastName = name.replace("present.", "past_key_values.")
                        if (pastName.contains(".decoder.")) {
                            kvCache[pastName]?.close()
                            kvCache[pastName] = (step[idx] as OnnxTensor).copy()
                        }
                    }
                }
                step.close()

                last = argmax(logits)
                if (last != EOS_ID) generated.add(last)
            }

            if (generated.isEmpty()) return null
            return tokenizer.decode(generated.toIntArray()).takeIf { it.isNotBlank() }
        } finally {
            held.forEach { runCatching { it.close() } }
        }
    }

    // Helper ---------------------------------------------------------------

    private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    /** [1, seq, 512]-Floats aus nested Array, als eigener Tensor. */
    private fun floatTensor3D(data: Array<Array<FloatArray>>): OnnxTensor {
        val seq = data[0].size
        val dim = data[0][0].size
        val flat = FloatArray(seq * dim)
        var p = 0
        for (t in data[0]) for (v in t) flat[p++] = v
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, seq.toLong(), dim.toLong()))
    }

    /** Result → letzte-Position-Logits-Zeile (FloatArray, Vokabulargröße). */
    private fun OrtSession.Result.toFloatRows(): FloatArray {
        @Suppress("UNCHECKED_CAST")
        val all = (this[0].value as Array<Array<FloatArray>>)
        return all[0][all[0].size - 1]
    }

    private fun OnnxTensor.copy(): OnnxTensor {
        // KV-Cache: [1, heads, seq, dim] (rank 4)
        @Suppress("UNCHECKED_CAST")
        val v = value as Array<Array<Array<FloatArray>>>
        val heads = v[0].size
        val seq = v[0][0].size
        val dim = v[0][0][0].size
        val flat = FloatArray(heads * seq * dim)
        var p = 0
        for (h in v[0]) for (s in h) for (x in s) flat[p++] = x
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, heads.toLong(), seq.toLong(), dim.toLong()))
    }

    private fun argmax(row: FloatArray): Int {
        var best = 0
        var bestVal = row[0]
        for (i in 1 until row.size) if (row[i] > bestVal) { bestVal = row[i]; best = i }
        return best
    }

    fun close() {
        runCatching { encoder.close() }
        runCatching { decoderStep1.close() }
        runCatching { decoderWithPast.close() }
    }

    companion object {
        private const val EOS_ID = 1
        private const val PAD_ID = 0L
        private const val MAX_SOURCE_TOKENS = 480

        const val MODEL_REPO = "Xenova/t5-small"
        const val ENCODER_FILE = "encoder_model_quantized.onnx"
        const val DECODER_FILE = "decoder_model_quantized.onnx"
        const val DECODER_PAST_FILE = "decoder_with_past_model_quantized.onnx"
        const val TOKENIZER_FILE = "tokenizer.json"

        /** (Remote-Pfad im Repo, lokale Zieldatei) — Gesamtgröße ~118 MB. */
        val REQUIRED_FILES = listOf(
            "onnx/$ENCODER_FILE" to ENCODER_FILE,
            "onnx/$DECODER_FILE" to DECODER_FILE,
            "onnx/$DECODER_PAST_FILE" to DECODER_PAST_FILE,
            TOKENIZER_FILE to TOKENIZER_FILE,
        )

        fun dir(context: Context): File = File(context.filesDir, "onnx").apply { mkdirs() }

        fun isDownloaded(context: Context): Boolean =
            REQUIRED_FILES.all { (_, dest) -> File(dir(context), dest).length() > 1_000_000 }

        /** Lädt die Modell-Dateien (mit Redirect-Loop, Temp-Datei + Rename). true = vollständig. */
        fun download(context: Context, onProgress: (String) -> Unit): Boolean {
            val dir = dir(context)
            for ((remote, dest) in REQUIRED_FILES) {
                val out = File(dir, dest)
                if (out.length() > 1_000_000) continue
                onProgress(dest)
                val tmp = File(dir, "$dest.part")
                try {
                    tmp.delete()
                    var currentUrl = "https://huggingface.co/$MODEL_REPO/resolve/main/$remote"
                    // Redirect-Loop (HuggingFace leitet auf CDN um)
                    for (attempt in 1..5) {
                        val conn = java.net.URL(currentUrl).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 20_000
                        conn.readTimeout = 120_000
                        conn.instanceFollowRedirects = false // Manuell verfolgen
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) NewsWave/1.0")
                        val code = conn.responseCode
                        if (code in 301..308) {
                            currentUrl = conn.getHeaderField("Location") ?: break
                            conn.disconnect()
                            continue
                        }
                        if (code != 200) {
                            conn.disconnect()
                            throw IllegalStateException("HTTP $code für $dest")
                        }
                        conn.inputStream.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                        conn.disconnect()
                        break
                    }
                    if (!tmp.exists() || tmp.length() < 1_000_000) {
                        throw IllegalStateException("Download zu klein oder fehlend: $dest (${tmp.length()} bytes)")
                    }
                    if (!tmp.renameTo(out)) {
                        out.delete()
                        if (!tmp.renameTo(out)) throw IllegalStateException("rename fehlgeschlagen: $dest")
                    }
                } catch (e: Exception) {
                    tmp.delete()
                    return false
                }
            }
            return true
        }

        @Volatile private var instance: OnnxSummarizer? = null

        /** Singleton-Zugriff; null wenn Modell nicht (vollständig) geladen. */
        fun get(context: Context): OnnxSummarizer? {
            instance?.let { return it }
            if (!isDownloaded(context)) return null
            return try {
                val env = OrtEnvironment.getEnvironment()
                val dir = dir(context)
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
                val created = OnnxSummarizer(
                    env,
                    env.createSession(File(dir, ENCODER_FILE).absolutePath, opts),
                    env.createSession(File(dir, DECODER_FILE).absolutePath, opts),
                    env.createSession(File(dir, DECODER_PAST_FILE).absolutePath, opts),
                    T5UnigramTokenizer(parseTokenizerJson(File(dir, TOKENIZER_FILE).readText())),
                )
                instance = created
                created
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Extraktiert model.vocab ([[piece, score], ...]) aus tokenizer.json.
         * Bewusst ohne JSON-Library: nur dieser eine Teil des Dokuments interessiert,
         * und Pieces können alle Escape-Formen (\u2581, \", \\ …) enthalten.
         */
        private fun parseTokenizerJson(json: String): List<Pair<String, Float>> {
            val result = ArrayList<Pair<String, Float>>(32_000)
            val vocabStart = json.indexOf("\"vocab\"")
            if (vocabStart < 0) throw IllegalStateException("tokenizer.json: vocab fehlt")
            var pos = json.indexOf('[', vocabStart) + 1
            while (pos < json.length) {
                // Whitespace/Kommas überspringen
                while (pos < json.length && (json[pos] == ',' || json[pos].isWhitespace())) pos++
                if (pos >= json.length || json[pos] == ']') break
                if (json[pos] != '[') { pos++; continue }
                // Piece-String lesen (escape-aware)
                val openQuote = json.indexOf('"', pos)
                if (openQuote < 0) break
                val sb = StringBuilder()
                var p = openQuote + 1
                while (p < json.length) {
                    val c = json[p]
                    if (c == '\\' && p + 1 < json.length) { sb.append(c).append(json[p + 1]); p += 2; continue }
                    if (c == '"') break
                    sb.append(c); p++
                }
                val piece = unescapeJson(sb.toString())
                // score bis ']'
                val comma = json.indexOf(',', p)
                val closeBracket = json.indexOf(']', p)
                if (comma < 0 || closeBracket < 0 || comma > closeBracket) { pos = p + 1; continue }
                val score = json.substring(comma + 1, closeBracket).trim().toFloatOrNull() ?: 0f
                result.add(piece to score)
                pos = closeBracket + 1
            }
            if (result.size < 10_000) throw IllegalStateException("tokenizer.json: nur ${result.size} Pieces geparst")
            return result
        }

        private fun unescapeJson(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'u' -> { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue }
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        else -> sb.append(n)
                    }
                    i += 2
                } else { sb.append(c); i++ }
            }
            return sb.toString()
        }
    }
}
