package com.wavenews.app.data

import org.xmlpull.v1.XmlPullParser

/** Ein aus OPML extrahierter Feed. */
data class OpmlFeed(val title: String, val url: String, val category: String)

/**
 * Minimaler OPML-Parser (Standard-OPML 1.0/2.0, wie FreshRSS ihn exportiert):
 * Liefert alle <outline type="rss" xmlUrl="…">-Einträge; der umschließende
 * Outline-Text gilt als Kategorie. Verschachtelte Strukturen werden unterstützt.
 */
object OpmlParser {

    fun parse(opml: String): List<OpmlFeed> {
        val feeds = mutableListOf<OpmlFeed>()
        // (depth, category) — Depth = Verschachtelungsebene des öffnenden Outline-Tags
        val categoryStack = ArrayDeque<Pair<Int, String>>()
        var depth = 0

        val parser = android.util.Xml.newPullParser()
        parser.setInput(opml.reader())

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name.equals("outline", ignoreCase = true)) {
                        val xmlUrl = parser.getAttributeValue(null, "xmlUrl")
                        val text = (parser.getAttributeValue(null, "text") ?: parser.getAttributeValue(null, "title")).orEmpty()
                        if (!xmlUrl.isNullOrBlank()) {
                            feeds += OpmlFeed(
                                title = text.ifBlank { xmlUrl },
                                url = xmlUrl.trim(),
                                category = categoryStack.lastOrNull()?.second.orEmpty(),
                            )
                        } else if (text.isNotBlank()) {
                            categoryStack.addLast(depth to text)
                        }
                    }
                    depth++
                }
                XmlPullParser.END_TAG -> {
                    depth--
                    if (parser.name.equals("outline", ignoreCase = true)) {
                        while (categoryStack.isNotEmpty() && categoryStack.last().first >= depth) {
                            categoryStack.removeLast()
                        }
                    }
                }
            }
            event = parser.next()
        }
        return feeds.distinctBy { it.url }
    }
}
