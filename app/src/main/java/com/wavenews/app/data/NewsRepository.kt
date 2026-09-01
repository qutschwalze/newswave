package com.wavenews.app.data

import com.wavenews.app.data.api.ApiFactory
import com.wavenews.app.data.api.GoogleReaderApi
import com.wavenews.app.data.db.AppDatabase
import com.wavenews.app.data.db.ArticleEntity
import com.wavenews.app.data.db.FeedEntity
import com.wavenews.app.data.db.WidgetArticleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Datenquelle: FreshRSS über die Google-Reader-kompatible API (Standard, unverändert).
 * Offline-Cache: Room. Der Server ist die Wahrheit für Gelesen/Stern-Zustände.
 */
class NewsRepository(
    private val settings: SettingsStore,
    private val db: AppDatabase,
) {

    private suspend fun account(): Account =
        requireNotNull(settings.accountOnce()) { "Nicht angemeldet" }

    private suspend fun client(): Pair<GoogleReaderApi, String> {
        val acc = account()
        val api = ApiFactory.create(acc.serverUrl)
        return api to "GoogleLogin auth=${acc.authKey}"
    }

    suspend fun login(serverUrl: String, username: String, password: String): Account {
        val api = ApiFactory.create(serverUrl)
        val authKey = ApiFactory.authKey(api, username, password)
        val account = Account(serverUrl.trimEnd('/'), username, authKey)
        settings.saveAccount(account)
        return account
    }

    suspend fun logout() {
        settings.clear()
    }

    fun observeFeeds() = db.feedDao().observeAll()

    fun observeCategories() = db.feedDao().observeCategories()

    fun observeArticles(feedId: String?, category: String?, onlyUnread: Boolean, onlyStarred: Boolean) =
        db.articleDao().observeArticles(feedId, category, onlyUnread, onlyStarred)

    suspend fun unreadCount(): Int = db.articleDao().countUnread()

    suspend fun latestUnread(n: Int): List<ArticleEntity> = db.articleDao().latestUnread(n)

    /** Neueste ungelesene Artikel mit Kategorie (fürs Widget). */
    suspend fun latestUnreadWithCategory(n: Int): List<WidgetArticleRow> = db.articleDao().latestUnreadWithCategory(n)

    /** Alle ungelesenen Artikel als gelesen markieren (lokal + FreshRSS). */
    suspend fun markAllRead() {
        val unread = db.articleDao().latestUnread(500)
        if (unread.isEmpty()) return
        try {
            val (api, auth) = client()
            val token = api.requestToken(auth).string().trim()
            api.editTag(auth, unread.map { it.id }, addTag = TAG_READ, token = token)
        } catch (_: Exception) {
            // Offline: lokalen Stand trotzdem aktualisieren; nächster Sync gleicht ab
        }
        db.articleDao().markAllRead()
    }

    suspend fun article(id: String): ArticleEntity? = db.articleDao().byId(id)

    /**
     * Feeds + ALLE Artikel (inkl. Gelesen-/Stern-Zustand) vom Server holen und in den
     * Room-Cache schreiben. Dadurch unterscheiden sich die Filter "Alle"/"Ungelesen"/"Gemerkt".
     */
    suspend fun sync(onProgress: (String) -> Unit = {}) {
        val (api, auth) = client()

        onProgress("Feeds laden …")
        val subs = api.subscriptions(auth).subscriptions.orEmpty()
        val feeds = subs.map { s ->
            FeedEntity(
                id = s.id,
                title = (s.title ?: s.url ?: s.id).ifBlank { s.id },
                category = s.categories.orEmpty().firstOrNull()?.label?.takeIf { it.isNotBlank() } ?: "Alle",
                feedUrl = s.url,
                htmlUrl = s.htmlUrl,
                iconUrl = s.iconUrl,
            )
        }
        db.feedDao().clear()
        db.feedDao().upsertAll(feeds)

        onProgress("Artikel-Liste laden …")
        val token = api.requestToken(auth).string().trim()
        val allIds = api.itemIds(auth, stream = STREAM_READING_LIST).itemRefs.orEmpty().map { it.id }
        if (allIds.isEmpty()) {
            db.articleDao().deleteNotIn(emptyList())
            onProgress("Keine Artikel vorhanden")
            return
        }
        val readIds = api.itemIds(auth, stream = STREAM_READ).itemRefs.orEmpty().map { it.id }.toSet()
        val starredIds = api.itemIds(auth, stream = STREAM_STARRED).itemRefs.orEmpty().map { it.id }.toSet()

        onProgress("Artikel laden (0/${allIds.size}) …")
        val entities = mutableListOf<ArticleEntity>()
        allIds.chunked(50).forEachIndexed { index, batch ->
            val contents = api.itemContents(auth, batch, token)
            contents.items.orEmpty().forEach { item ->
                val id = item.id ?: return@forEach // defektes Item überspringen
                // items/ids liefert Kurz-IDs (Dezimal), contents Lang-IDs (tag:.../item/<hex>).
                val shortId = normalizeItemId(id)
                val summaryHtml = item.summary?.content ?: ""
                entities += ArticleEntity(
                    id = shortId,
                    feedId = item.origin?.streamId ?: "",
                    feedTitle = item.origin?.title ?: "",
                    title = (item.title ?: "").ifBlank { "(ohne Titel)" },
                    url = item.canonical.orEmpty().firstOrNull()?.href ?: item.origin?.htmlUrl ?: "",
                    author = item.author,
                    published = item.published,
                    summaryHtml = summaryHtml,
                    imageUrl = extractImageUrl(item, summaryHtml),
                    unread = shortId !in readIds,
                    starred = shortId in starredIds,
                )
            }
            onProgress("Artikel laden (${(index + 1) * 50}/${allIds.size}) …")
        }
        db.articleDao().upsertAll(entities)
        db.articleDao().deleteNotIn(allIds)
        onProgress("Synchronisiert ✓")
    }

    /** Artikel gelesen/unlesen setzen (Server + lokal). */
    suspend fun markRead(articleId: String, read: Boolean) {
        val (api, auth) = client()
        val token = api.requestToken(auth).string().trim()
        if (read) {
            api.editTag(auth, listOf(articleId), addTag = TAG_READ, token = token)
        } else {
            api.editTag(auth, listOf(articleId), removeTag = TAG_READ, token = token)
        }
        db.articleDao().setUnread(articleId, !read)
    }

    suspend fun markStarred(articleId: String, starred: Boolean) {
        val (api, auth) = client()
        val token = api.requestToken(auth).string().trim()
        if (starred) {
            api.editTag(auth, listOf(articleId), addTag = TAG_STARRED, token = token)
        } else {
            api.editTag(auth, listOf(articleId), removeTag = TAG_STARRED, token = token)
        }
        db.articleDao().setStarred(articleId, starred)
    }

    // --- Feed-Verwaltung (FreshRSS-Google-Reader-Admin-Endpunkte) ---

    /**
     * Feed per URL hinzufügen, optional in eine Kategorie (Label) einsortieren.
     * FreshRSS legt Kategorien beim Labeln automatisch an. Liefert true bei Erfolg.
     */
    suspend fun isFeedSubscribed(url: String): Boolean =
        db.feedDao().byUrl(url.trim()) != null

    suspend fun addFeed(url: String, category: String?): Boolean {
        val (api, auth) = client()
        val body = api.quickAddFeed(auth, url.trim()).string()
        val numResults = Regex("\"numResults\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (numResults < 1) return false
        val streamId = Regex("\"streamId\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        if (streamId != null && !category.isNullOrBlank()) {
            runCatching { api.subscriptionEdit(auth, action = "edit", stream = streamId, addLabel = category.trim()) }
        }
        sync()
        return true
    }

    /** Feed abbestellen (Server) + Cache aktualisieren. */
    suspend fun removeFeed(feedId: String) {
        val (api, auth) = client()
        api.subscriptionEdit(auth, action = "unsubscribe", stream = feedId)
        sync()
    }

    /** Feed in eine (ggf. neue) Kategorie verschieben. */
    suspend fun moveFeedToCategory(feedId: String, category: String) {
        val (api, auth) = client()
        api.subscriptionEdit(auth, action = "edit", stream = feedId, addLabel = category.trim())
        sync()
    }

    /**
     * OPML-Import (Datei-Inhalt oder von einer URL geladen): fügt alle noch nicht
     * abonnierten Feeds hinzu, sortiert sie in die OPML-Kategorien (Ordner) ein und
     * synchronisiert am Ende. Liefert (added, skipped, failed).
     */
    suspend fun importOpml(opml: String): Triple<Int, Int, Int> = withContext(Dispatchers.IO) {
        val feeds = OpmlParser.parse(opml)
        var added = 0
        var skipped = 0
        var failed = 0
        feeds.forEach { feed ->
            if (isFeedSubscribed(feed.url)) {
                skipped++
                return@forEach
            }
            val ok = runCatching { addFeed(feed.url, feed.category.takeIf { it.isNotBlank() }) }.getOrDefault(false)
            if (ok) added++ else failed++
        }
        Triple(added, skipped, failed)
    }

    private companion object {
        const val TAG_READ = "user/-/state/com.google/read"
        const val TAG_STARRED = "user/-/state/com.google/starred"
        const val STREAM_READING_LIST = "user/-/state/com.google/reading-list"
        const val STREAM_READ = "user/-/state/com.google/read"
        const val STREAM_STARRED = "user/-/state/com.google/starred"

        /** Bild-URL: erst Enclosure mit Bild-Typ, sonst erstes img-src aus dem Inhalt. */
        private fun extractImageUrl(item: com.wavenews.app.data.api.StreamItem, summaryHtml: String): String? {
            item.enclosure.orEmpty().firstOrNull { it.type?.startsWith("image/") == true && !it.href.isNullOrBlank() }
                ?.let { return it.href }
            val match = IMG_SRC_REGEX.find(summaryHtml) ?: return null
            val src = match.groupValues[1]
            return src.takeIf { it.startsWith("http") }
        }

        private val IMG_SRC_REGEX = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        /**
         * "tag:google.com,2005:reader/item/00065a348e0b36dd" → "1788031628162781"
         * (16-stelliger Hex der 64-Bit-Darstellung → vorzeichenbehafteter Dezimalstring).
         * Bereits kurze IDs werden unverändert durchgereicht.
         */
        fun normalizeItemId(rawId: String): String {
            val hex = rawId.substringAfterLast("/item/", "")
            if (hex.isEmpty() || !hex.matches(Regex("^[0-9a-fA-F]{1,16}$"))) return rawId
            return try {
                val value = java.math.BigInteger(hex, 16)
                val two64 = java.math.BigInteger.ONE.shiftLeft(64)
                if (value >= two64.shiftRight(1)) value.subtract(two64).toString()
                else value.toString()
            } catch (_: NumberFormatException) {
                rawId
            }
        }
    }
}
