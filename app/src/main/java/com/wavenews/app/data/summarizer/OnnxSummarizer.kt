package com.wavenews.app.data.summarizer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device abstraktive Zusammenfassung mit T5-small (ONNX, quantisiert).
 *
 * Pipeline (1:1-Port der validierten Python-Variante):
 *  1. Encoder → hidden [1, seq, 512]
 *  2. Decoder (no-past) → logits + 24 KV-Tensoren (12 Decoder + 12 Encoder)
 *  3. Decoder (with-past) → logits + 12 Decoder-KV; Encoder-KVs bleiben konstant
 *  4. Greedy-Decoding bis </s> oder max_new_tokens
 *
 * KV-Caches als FloatArrays — keine Tensor-Lebenszeit-Probleme.
 */
class OnnxSummarizer private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoderNoPast: OrtSession,
    private val decoderWithPast: OrtSession,
    private val tokenizer: T5UnigramTokenizer,
) {
    /** Ein FloatArray-Cache-Eintrag: name = past_key_values.*, shape + data als FloatBuffer. */
    private data class KVCopy(val name: String, val shape: LongArray, val data: FloatArray)

    fun summarize(text: String, maxNewTokens: Int = 64): String? = try {
        summarizeInternal(text, maxNewTokens)
    } catch (e: Exception) {
        Log.e(TAG, "Summarize failed", e)
        null
    }

    private fun summarizeInternal(text: String, maxNewTokens: Int): String? {
        // 1) Tokenisieren
        val src = tokenizer.encode("summarize: " + text.take(2000)).take(MAX_SOURCE_TOKENS)
        if (src.isEmpty()) return null
        val seqLen = src.size.toLong()
        val srcArr = LongArray(src.size) { src[it].toLong() }
        val maskArr = LongArray(src.size) { 1L }

        // 2) Encoder → hidden [1, seq, 512]
        val hidden: FloatArray; val hiddenShape: LongArray
        run {
            val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(srcArr), longArrayOf(1, seqLen))
            val msk = OnnxTensor.createTensor(env, LongBuffer.wrap(maskArr), longArrayOf(1, seqLen))
            try {
                val res = encoder.run(mapOf("input_ids" to ids, "attention_mask" to msk))
                @Suppress("UNCHECKED_CAST")
                val h = (res[0].value as Array<Array<FloatArray>>)[0]
                hiddenShape = longArrayOf(1, h.size.toLong(), h[0].size.toLong())
                hidden = FloatArray(h.size * h[0].size); var p = 0
                for (t in h) for (v in t) hidden[p++] = v
                res.close()
            } finally { ids.close(); msk.close() }
        }

        // 3) Decoder Schritt 1 (no-past) → 24 KV-Caches (FloatArrays)
        val kvList: MutableList<KVCopy>
        var last: Int
        run {
            val inpIds = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0L)), longArrayOf(1, 1))
            val encH = OnnxTensor.createTensor(env, FloatBuffer.wrap(hidden), hiddenShape)
            val encM = OnnxTensor.createTensor(env, LongBuffer.wrap(maskArr), longArrayOf(1, seqLen))
            try {
                val res = decoderNoPast.run(mapOf("input_ids" to inpIds, "encoder_hidden_states" to encH, "encoder_attention_mask" to encM))
                @Suppress("UNCHECKED_CAST")
                val logits = (res[0].value as Array<Array<FloatArray>>)[0][0]
                last = logits.indices.maxByOrNull { logits[it] }?.toInt() ?: 1

                val names = decoderNoPast.outputNames.toList()
                kvList = ArrayList(names.size - 1)
                for (i in 1 until names.size) {
                    @Suppress("UNCHECKED_CAST")
                    val a = (res[i].value as Array<Array<Array<FloatArray>>>)[0]
                    val h = a.size; val s = a[0].size; val d = a[0][0].size
                    val flat = FloatArray(h * s * d); var p = 0
                    for (hd in a) for (sl in hd) for (v in sl) flat[p++] = v
                    kvList.add(KVCopy(names[i].replace("present.", "past_key_values."), longArrayOf(1, h.toLong(), s.toLong(), d.toLong()), flat))
                }
                res.close()
            } finally { inpIds.close(); encH.close(); encM.close() }
        }

        // 4) Decode-Loop
        val gen = ArrayList<Int>(maxNewTokens)
        if (last != 1) gen.add(last)

        var guard = 0
        while (last != 1 && gen.size < maxNewTokens && guard++ < maxNewTokens + 2) {
            val liveTensors = ArrayList<OnnxTensor>(kvList.size + 3)
            try {
                val feeds = HashMap<String, OnnxTensor>(kvList.size + 3)
                for (kv in kvList) {
                    val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(kv.data.copyOf()), kv.shape)
                    feeds[kv.name] = t; liveTensors.add(t)
                }
                val idT = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(last.toLong())), longArrayOf(1, 1))
                feeds["input_ids"] = idT; liveTensors.add(idT)
                val encH = OnnxTensor.createTensor(env, FloatBuffer.wrap(hidden), hiddenShape)
                feeds["encoder_hidden_states"] = encH; liveTensors.add(encH)
                val encM = OnnxTensor.createTensor(env, LongBuffer.wrap(maskArr), longArrayOf(1, seqLen))
                feeds["encoder_attention_mask"] = encM; liveTensors.add(encM)

                val step = decoderWithPast.run(feeds)
                @Suppress("UNCHECKED_CAST")
                val logits = (step[0].value as Array<Array<FloatArray>>)[0][0]
                last = logits.indices.maxByOrNull { logits[it] }?.toInt() ?: 1

                // Nur Decoder-KVs updaten
                val sNames = decoderWithPast.outputNames.toList()
                for (i in 1 until sNames.size) {
                    val pname = sNames[i].replace("present.", "past_key_values.")
                    if (pname.contains(".decoder.")) {
                        @Suppress("UNCHECKED_CAST")
                        val a = (step[i].value as Array<Array<Array<FloatArray>>>)[0]
                        val h = a.size; val s = a[0].size; val d = a[0][0].size
                        val flat = FloatArray(h * s * d); var p = 0
                        for (hd in a) for (sl in hd) for (v in sl) flat[p++] = v
                        val idx = kvList.indexOfFirst { it.name == pname }
                        if (idx >= 0) kvList[idx] = KVCopy(pname, longArrayOf(1, h.toLong(), s.toLong(), d.toLong()), flat)
                    }
                }
                step.close()
            } finally { liveTensors.forEach { runCatching { it.close() } } }
            if (last != 1) gen.add(last)
        }

        if (gen.isEmpty()) return null
        return tokenizer.decode(gen.toIntArray()).takeIf { it.isNotBlank() }
    }

    fun close() { runCatching { encoder.close() }; runCatching { decoderNoPast.close() }; runCatching { decoderWithPast.close() } }

    // ───────────────────────────── Download + Init ─────────────────────────────

    companion object {
        private const val TAG = "OnnxSummarizer"
        private const val MODEL_REPO = "Xenova/t5-small"
        private const val ENCODER_FILE = "encoder_model_quantized.onnx"
        private const val DECODER_FILE = "decoder_model_quantized.onnx"
        private const val DECODER_PAST_FILE = "decoder_with_past_model_quantized.onnx"
        private const val TOKENIZER_FILE = "tokenizer.json"
        private const val MAX_SOURCE_TOKENS = 480
        private val REQUIRED_FILES = listOf("onnx/$ENCODER_FILE" to ENCODER_FILE, "onnx/$DECODER_FILE" to DECODER_FILE, "onnx/$DECODER_PAST_FILE" to DECODER_PAST_FILE, TOKENIZER_FILE to TOKENIZER_FILE)

        fun dir(ctx: Context): File = File(ctx.filesDir, "onnx").apply { mkdirs() }
        fun isDownloaded(ctx: Context): Boolean = REQUIRED_FILES.all { (_, d) -> File(dir(ctx), d).length() > 1_000_000 }

        fun download(ctx: Context, onProgress: (String) -> Unit): Boolean {
            val dir = dir(ctx)
            for ((remote, dest) in REQUIRED_FILES) {
                val out = File(dir, dest); if (out.length() > 1_000_000) continue
                onProgress(dest); val tmp = File(dir, "$dest.part")
                try {
                    tmp.delete(); var url = "https://huggingface.co/$MODEL_REPO/resolve/main/$remote"
                    for (hop in 1..5) {
                        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 30_000; c.readTimeout = 120_000; c.instanceFollowRedirects = false
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) NewsWave/1.0"); c.connect()
                        val code = c.responseCode
                        if (code in 301..308) { val loc = c.getHeaderField("Location"); c.disconnect(); url = if (loc?.startsWith("http") == true) loc else java.net.URL(java.net.URL("https://huggingface.co/$MODEL_REPO/"), loc ?: "").toString(); continue }
                        if (code != 200) { c.disconnect(); throw IllegalStateException("HTTP $code") }
                        c.inputStream.use { i -> tmp.outputStream().use { o -> val b = ByteArray(65536); var r: Int; while (i.read(b).also { r = it } != -1) o.write(b, 0, r) } }
                        c.disconnect(); break
                    }
                    if (tmp.length() < 1_000_000) throw IllegalStateException("unvollständig")
                    if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
                } catch (e: Exception) { tmp.delete(); Log.e(TAG, "Download: $dest", e); return false }
            }
            return true
        }

        @Volatile private var inst: OnnxSummarizer? = null
        fun get(ctx: Context): OnnxSummarizer? {
            inst?.let { return it }; if (!isDownloaded(ctx)) return null
            return try {
                val env = OrtEnvironment.getEnvironment(); val dir = dir(ctx)
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
                OnnxSummarizer(env,
                    env.createSession(File(dir, ENCODER_FILE).absolutePath, opts),
                    env.createSession(File(dir, DECODER_FILE).absolutePath, opts),
                    env.createSession(File(dir, DECODER_PAST_FILE).absolutePath, opts),
                    T5UnigramTokenizer(parseTokenizerJson(File(dir, TOKENIZER_FILE).readText())),
                ).also { inst = it }
            } catch (e: Exception) { Log.e(TAG, "Init failed", e); null }
        }

        private fun parseTokenizerJson(json: String): List<Pair<String, Float>> {
            val r = ArrayList<Pair<String, Float>>(32_000); val vs = json.indexOf("\"vocab\"")
            if (vs < 0) throw IllegalStateException("vocab fehlt"); var p = json.indexOf('[', vs) + 1
            while (p < json.length) {
                while (p < json.length && (json[p] == ',' || json[p].isWhitespace())) p++
                if (p >= json.length || json[p] == ']') break; if (json[p] != '[') { p++; continue }
                val oq = json.indexOf('"', p); if (oq < 0) break; val sb = StringBuilder(); var q = oq + 1
                while (q < json.length) { val c = json[q]; if (c == '\\' && q+1<json.length) { sb.append(c).append(json[q+1]); q+=2; continue }; if (c=='"') break; sb.append(c); q++ }
                val piece = unesc(sb.toString()); val cm = json.indexOf(',', q); val cb = json.indexOf(']', q)
                if (cm < 0 || cb < 0 || cm > cb) { p = q+1; continue }
                val score = json.substring(cm+1, cb).trim().toFloatOrNull() ?: 0f; r.add(piece to score); p = cb+1
            }
            if (r.size < 10_000) throw IllegalStateException("nur ${r.size} Pieces"); return r
        }
        private fun unesc(s: String): String { val sb = StringBuilder(); var i=0; while (i<s.length) { val c=s[i]; if (c=='\\'&&i+1<s.length) { when(val n=s[i+1]) { 'u'->{sb.append(s.substring(i+2,i+6).toInt(16).toChar()); i+=6;continue}; 'n'->sb.append('\n'); 'r'->sb.append('\r'); 't'->sb.append('\t'); else->sb.append(n) }; i+=2 } else { sb.append(c); i++ } }; return sb.toString() }
    }
}
