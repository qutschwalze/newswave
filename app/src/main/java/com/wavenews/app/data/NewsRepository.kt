package com.wavenews.app.data

import com.wavenews.app.data.api.ApiFactory
import com.wavenews.app.data.db.AppDatabase
import com.wavenews.app.data.db.ArticleEntity
import com.wavenews.app.data.db.FeedEntity

/**
 * Datenquelle: FreshRSS über die Google-Reader-kompatible API (Standard, unverändert).
 * Offline-Cache: Room.
 */
class NewsRepository(
    private val settings: SettingsStore,
    private val db: AppDatabase,
) {

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

    fun observeArticles(feedId: String?, onlyUnread: Boolean, onlyStarred: Boolean) =
        db.articleDao().observeArticles(feedId, onlyUnread, onlyStarred)

    private suspend fun account(): Account =
        requireNotNull(settings.accountOnce()) { "Nicht angemeldet" }

    /** Feeds + ungelesene Artikel vom Server holen und in den Room-Cache schreiben. */
    suspend fun sync(onProgress: (String) -> Unit = {}) {
        val acc = account()
        val api = ApiFactory.create(acc.serverUrl)
        val auth = "GoogleLogin auth=${acc.authKey}"

        onProgress("Feeds laden …")
        val subs = api.subscriptions(auth).subscriptions
        val feeds = subs.map { s ->
            FeedEntity(
                id = s.id,
                title = s.title.ifBlank { s.url ?: s.id },
                category = s.categories.firstOrNull()?.label?.takeIf { it.isNotBlank() } ?: "Alle",
                htmlUrl = s.htmlUrl,
                iconUrl = s.iconUrl,
            )
        }
        db.feedDao().clear()
        db.feedDao().upsertAll(feeds)

        onProgress("Artikel-Liste laden …")
        val token = api.requestToken(auth).string().trim()
        val ids = api.unreadItemIds(auth).itemRefs.map { it.id }
        if (ids.isEmpty()) {
            db.articleDao().deleteNotIn(emptyList())
            onProgress("Alles gelesen ✓")
            return
        }

        onProgress("Artikel laden (0/${ids.size}) …")
        val entities = mutableListOf<ArticleEntity>()
        ids.chunked(50).forEachIndexed { index, batch ->
            val contents = api.itemContents(auth, batch, token)
            contents.items.forEach { item ->
                entities += ArticleEntity(
                    id = item.id,
                    feedId = item.origin?.streamId ?: "",
                    feedTitle = item.origin?.title ?: "",
                    title = item.title.ifBlank { "(ohne Titel)" },
                    url = item.canonical.firstOrNull()?.href ?: item.origin?.htmlUrl ?: "",
                    author = item.author,
                    published = item.published,
                    summaryHtml = item.summary?.content ?: "",
                    unread = true,
                    starred = false,
                )
            }
            onProgress("Artikel laden (${(index + 1) * 50}/${ids.size}) …")
        }
        db.articleDao().upsertAll(entities)
        db.articleDao().deleteNotIn(ids)
        onProgress("Synchronisiert ✓")
    }

    /** Artikel gelesen/unlesen setzen (Server + lokal). */
    suspend fun markRead(articleId: String, read: Boolean) {
        val acc = account()
        val api = ApiFactory.create(acc.serverUrl)
        val auth = "GoogleLogin auth=${acc.authKey}"
        val token = api.requestToken(auth).string().trim()
        if (read) {
            api.editTag(auth, listOf(articleId), addTag = TAG_READ, token = token)
        } else {
            api.editTag(auth, listOf(articleId), removeTag = TAG_READ, token = token)
        }
        db.articleDao().setUnread(articleId, !read)
    }

    suspend fun markStarred(articleId: String, starred: Boolean) {
        val acc = account()
        val api = ApiFactory.create(acc.serverUrl)
        val auth = "GoogleLogin auth=${acc.authKey}"
        val token = api.requestToken(auth).string().trim()
        if (starred) {
            api.editTag(auth, listOf(articleId), addTag = TAG_STARRED, token = token)
        } else {
            api.editTag(auth, listOf(articleId), removeTag = TAG_STARRED, token = token)
        }
        db.articleDao().setStarred(articleId, starred)
    }

    private companion object {
        const val TAG_READ = "user/-/state/com.google/read"
        const val TAG_STARRED = "user/-/state/com.google/starred"
    }
}
