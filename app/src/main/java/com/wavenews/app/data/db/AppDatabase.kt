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
    @PrimaryKey val id: String, // streamId, z. B. "feed/https://..."
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
    @PrimaryKey val id: String, // Long-Form-Item-ID (tag:google.com,2005:reader/item/<hex>)
    val feedId: String,
    val feedTitle: String,
    val title: String,
    val url: String,
    val author: String?,
    val published: Long, // Epoch-Sekunden
    val summaryHtml: String,
    val unread: Boolean,
    val starred: Boolean,
)

@Dao
interface FeedDao {

    @Query("SELECT * FROM feeds ORDER BY category, title")
    fun observeAll(): Flow<List<FeedEntity>>

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
             AND (:onlyUnread = 0 OR unread = 1)
             AND (:onlyStarred = 0 OR starred = 1)
           ORDER BY published DESC
           LIMIT 500"""
    )
    fun observeArticles(feedId: String?, onlyUnread: Boolean, onlyStarred: Boolean): Flow<List<ArticleEntity>>

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

@Database(entities = [FeedEntity::class, ArticleEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao

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
