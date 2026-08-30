package com.wavenews.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "feeds")
data class FeedEntity(
    @PrimaryKey val id: String, // streamId, z. B. "feed/12"
    val title: String,
    val category: String,
    val htmlUrl: String?,
    val iconUrl: String?,
)

@Entity(
    tableName = "articles",
    indices = [Index("feedId"), Index("published")],
)
data class ArticleEntity(
    @PrimaryKey val id: String, // Kurz-Form-Item-ID (normalisiert, s. NewsRepository)
    val feedId: String,
    val feedTitle: String,
    val title: String,
    val url: String,
    val author: String?,
    val published: Long, // Epoch-Sekunden
    val summaryHtml: String,
    val imageUrl: String?, //Enclosure oder erstes Bild aus dem Inhalt
    val unread: Boolean,
    val starred: Boolean,
)

@Dao
interface FeedDao {

    @Query("SELECT * FROM feeds ORDER BY category, title")
    fun observeAll(): Flow<List<FeedEntity>>

    @Query("SELECT DISTINCT category FROM feeds WHERE category != '' ORDER BY category")
    fun observeCategories(): Flow<List<String>>

    @Query("DELETE FROM feeds")
    suspend fun clear()

    @Upsert
    suspend fun upsertAll(feeds: List<FeedEntity>)
}

@Dao
interface ArticleDao {

    @Query(
        """SELECT * FROM articles
           WHERE (:feedId IS NULL OR feedId = :feedId)
             AND (:category IS NULL OR feedId IN (SELECT id FROM feeds WHERE category = :category))
             AND (:onlyUnread = 0 OR unread = 1)
             AND (:onlyStarred = 0 OR starred = 1)
           ORDER BY published DESC
           LIMIT 500"""
    )
    fun observeArticles(feedId: String?, category: String?, onlyUnread: Boolean, onlyStarred: Boolean): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE unread = 1 ORDER BY published DESC LIMIT :n")
    suspend fun latestUnread(n: Int): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun byId(id: String): ArticleEntity?

    @Query("SELECT COUNT(*) FROM articles WHERE unread = 1")
    suspend fun countUnread(): Int

    @Query("SELECT COUNT(*) FROM articles WHERE unread = 1")
    fun observeUnreadCount(): Flow<Int>

    @Upsert
    suspend fun upsertAll(articles: List<ArticleEntity>)

    @Query("UPDATE articles SET unread = :unread WHERE id = :id")
    suspend fun setUnread(id: String, unread: Boolean)

    @Query("UPDATE articles SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)

    /** Cache-Aufräumen: behalte Gemerktes und alles, was aktuell ungelesen auf dem Server ist. */
    @Query("DELETE FROM articles WHERE starred = 0 AND id NOT IN (:keepIds)")
    suspend fun deleteNotIn(keepIds: List<String>)

    @Query("DELETE FROM articles")
    suspend fun clear()
}

@Entity(tableName = "summaries")
data class SummaryEntity(
    @PrimaryKey val articleId: String, // Kurz-Form-Item-ID, 1:1 zu articles.id
    val summary: String,
    val createdAt: Long = System.currentTimeMillis() / 1000,
)

@Dao
interface SummaryDao {

    @Query("SELECT * FROM summaries WHERE articleId = :articleId")
    suspend fun byArticle(articleId: String): SummaryEntity?

    @Upsert
    suspend fun upsert(summary: SummaryEntity)

    @Query("DELETE FROM summaries WHERE articleId NOT IN (SELECT id FROM articles)")
    suspend fun cleanup()
}

@Database(entities = [FeedEntity::class, ArticleEntity::class, SummaryEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao
    abstract fun summaryDao(): SummaryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context, AppDatabase::class.java, "wave-news.db")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
