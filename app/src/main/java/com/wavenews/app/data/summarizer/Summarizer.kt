package com.wavenews.app.data.summarizer

/**
 * On-device, extraktiver Summarizer (kein Cloud-Aufruf, kein Modell-Download):
 * Bewertet Sätze nach Worthäufigkeit (TF ohne IDF) mit DE/EN-Stopwörtern und
 * Satzlängen-Bonus, wählt die Top-Sätze in Original-Reihenfolge.
 *
 * bewusst so gebaut, dass [summarize] später intern ein ONNX-Modell (abstraktiv,
 * z. B. DistilBART/T5 quantisiert) nutzen kann, ohne dass UI/Repository sich ändern.
 */
object Summarizer {

    private val germanStopwords = setOf(
        "aber", "alle", "allem", "allen", "aller", "alles", "als", "also", "am", "an", "ander", "andere",
        "anderem", "anderen", "auch", "auf", "aus", "bei", "bin", "bis", "bist", "da", "damit", "dann",
        "der", "den", "des", "dem", "die", "das", "dass", "dein", "deine", "denn", "derer", "dessen",
        "dich", "dir", "du", "dies", "diese", "diesem", "diesen", "dieser", "dieses", "doch", "dort",
        "durch", "ein", "eine", "einem", "einen", "einer", "eines", "einig", "er", "ihn", "ihm", "es",
        "etwas", "euer", "eure", "für", "gegen", "gewesen", "hab", "habe", "haben", "hat", "hatte",
        "hatten", "hier", "hin", "hinter", "ich", "mich", "mir", "ihr", "ihre", "ihren", "ihrem", "ihrer",
        "ihres", "euch", "im", "in", "indem", "ins", "ist", "jede", "jedem", "jeden", "jeder", "jedes",
        "jene", "jetzt", "kann", "kein", "keine", "können", "könnte", "machen", "man", "manche", "mein",
        "meine", "mit", "muss", "musste", "nach", "nicht", "nichts", "noch", "nun", "nur", "ob", "oder",
        "ohne", "über", "um", "und", "uns", "unse", "unser", "unter", "vom", "von", "vor", "während",
        "war", "waren", "warst", "was", "weg", "weil", "weiter", "welche", "wenn", "werde", "werden",
        "wie", "wieder", "will", "wir", "wird", "wirst", "wo", "wollen", "wollte", "während", "würde",
        "würden", "zu", "zum", "zur", "zwar", "zwischen", "sehr", "sich", "sie", "sind", "so", "soll",
        "sollte", "sondern", "sowie", "um", "viel", "viele", "mehr", "meist", "seit", "bereits", "immer",
        "morgen", "heute", "gestern", "neue", "neuer", "neues", "neuen", "wurde", "wurden", "gegen",
        "beim", "beim", "affect", "künftig", "dabei", "davon", "dazu", "dieser", "seine", "ihren",
    )

    private val englishStopwords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "could", "did", "do",
        "does", "for", "from", "had", "has", "have", "he", "her", "here", "hers", "him", "his", "how",
        "i", "if", "in", "into", "is", "it", "its", "just", "me", "more", "most", "my", "no", "nor",
        "not", "of", "off", "on", "or", "our", "out", "over", "own", "she", "should", "so", "some",
        "than", "that", "the", "their", "them", "then", "there", "these", "they", "this", "those",
        "through", "to", "too", "under", "until", "up", "very", "was", "we", "were", "what", "when",
        "where", "which", "while", "who", "whom", "why", "will", "with", "would", "you", "your",
    )

    private val stopwords = germanStopwords + englishStopwords

    /** Erzeugt 2–4 Sätze Kerninhalt; null, wenn der Text zu kurz/leer ist. */
    fun summarize(text: String, maxSentences: Int = 3): String? {
        val sentences = splitSentences(text).filter { it.split(" ").size in 4..60 }
        if (sentences.size < 2) return null

        val freq = HashMap<String, Int>()
        sentences.forEach { s ->
            tokenize(s).forEach { w -> freq[w] = (freq[w] ?: 0) + 1 }
        }
        val maxFreq = freq.values.maxOrNull() ?: return null
        if (maxFreq < 2) return null

        // Erst-Mention-Bonus: Sätze mit organisations/personen-artigen Mustern sind kernhaltig
        val scored = sentences.mapIndexed { index, s ->
            val words = tokenize(s)
            var score = words.sumOf { w -> (freq[w] ?: 0).toDouble() / maxFreq }
            score /= (words.size.coerceAtLeast(1) * 0.75 + 4) // Längen-Normierung
            if (index == 0) score *= 1.25 // Lead-Satz gewichtet
            if (Regex("(nach|laut|zufolge|according|said)").containsMatchIn(s.lowercase())) score *= 1.1
            index to score
        }

        return scored.sortedByDescending { it.second }
            .take(maxSentences)
            .sortedBy { it.first } // Original-Reihenfolge = Lesbarkeit
            .joinToString(" ") { sentences[it.first].trim() }
    }

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+(?=[A-ZÄÖÜ„\"»])"))
            .map { it.trim() }
            .filter { it.length in 30..600 }

    private fun tokenize(sentence: String): List<String> =
        sentence.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}\\s-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopwords }
}
