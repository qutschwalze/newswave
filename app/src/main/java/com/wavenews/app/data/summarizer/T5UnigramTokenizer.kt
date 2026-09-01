package com.wavenews.app.data.summarizer

/**
 * T5-Unigram-Tokenizer (SentencePiece) in reinem Kotlin — 1:1-Port des in Python
 * validierten Algorithmus (Roundtrip gegen Xenova/t5-small verifiziert: IDs identisch).
 *
 * Vokabular kommt aus der tokenizer.json (Liste von [piece, score]-Paaren) und wird
 * beim Modell-Download mitgeladen. Metaspace-Normalisierung: Leerzeichen → ▁ (U+2581),
 * Text startet mit ▁ (add_prefix_space).
 */
class T5UnigramTokenizer(vocabEntries: List<Pair<String, Float>>) {

    private val pieces: List<String> = vocabEntries.map { it.first }
    private val scores: List<Float> = vocabEntries.map { it.second }
    private val idsByPiece: Map<String, Int> = vocabEntries.withIndex().associate { (i, p) -> p.first to i }
    private val unkId: Int = idsByPiece["<unk>"] ?: 2
    private val padId: Int = idsByPiece["<pad>"] ?: 0
    private val eosId: Int = idsByPiece["</s>"] ?: 1
    private val maxLengthPiece = pieces.maxOfOrNull { it.length } ?: 16

    val vocabSize: Int = pieces.size

    fun tokenFor(id: Int): String = if (id in pieces.indices) pieces[id] else "<unk>"

    /** Viterbi-Segmentierung: bestscore-Pfad über Vokabular-Pieces. */
    fun encode(inputText: String): IntArray {
        var text = inputText.replace("\r\n", "\n").replace("\r", "\n")
        text = text.replace(" ", "\u2581")
        if (!text.startsWith("\u2581")) text = "\u2581" + text

        val n = text.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val back = IntArray(n + 1) { -1 }
        best[0] = 0f

        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            val maxJ = minOf(n, i + maxLengthPiece)
            for (j in (i + 1)..maxJ) {
                val id = idsByPiece[text.substring(i, j)] ?: continue
                val score = best[i] + scores[id]
                if (score > best[j]) {
                    best[j] = score
                    back[j] = i
                }
            }
        }

        if (best[n] == Float.NEGATIVE_INFINITY) return intArrayOf(unkId)

        // Backtrack
        val ids = ArrayList<Int>(n / 4 + 1)
        var j = n
        while (j > 0) {
            val i = back[j]
            ids.add(idsByPiece[text.substring(i, j)] ?: unkId)
            j = i
        }
        ids.reverse()
        return ids.toIntArray()
    }

    /** Pieces → Text (Metaspace zurück). */
    fun decode(ids: IntArray): String =
        ids.filter { it != padId && it != eosId }
            .joinToString("") { tokenFor(it) }
            .replace("\u2581", " ")
            .trim()
}
