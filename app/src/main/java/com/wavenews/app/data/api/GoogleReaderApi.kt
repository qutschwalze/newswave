package com.wavenews.app.data.api

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

// --- DTOs (Google-Reader-Protokoll, wie FreshRSS es implementiert) ---

data class Category(val id: String = "", val label: String = "")

data class Subscription(
    val id: String,
    val title: String = "",
    val categories: List<Category> = emptyList(),
    val url: String? = null,
    val htmlUrl: String? = null,
    val iconUrl: String? = null,
)

data class SubscriptionList(val subscriptions: List<Subscription> = emptyList())

data class ItemRef(val id: String)
data class ItemIdList(val itemRefs: List<ItemRef> = emptyList())

data class Canonical(val href: String = "")
data class Summary(val content: String? = null)
data class Origin(val streamId: String = "", val title: String = "", val htmlUrl: String? = null)

data class StreamItem(
    val id: String,
    val title: String = "",
    val published: Long = 0,
    val author: String? = null,
    val canonical: List<Canonical> = emptyList(),
    val summary: Summary? = null,
    val origin: Origin? = null,
)

data class ContentList(val id: String = "", val items: List<StreamItem> = emptyList())

// --- API ---

interface GoogleReaderApi {

    @FormUrlEncoded
    @POST("api/greader.php/accounts/ClientLogin")
    suspend fun clientLogin(@Field("Email") email: String, @Field("Passwd") passwd: String): ResponseBody

    @GET("api/greader.php/reader/api/0/subscription/list")
    suspend fun subscriptions(
        @Header("Authorization") auth: String,
        @Query("output") output: String = "json",
    ): SubscriptionList

    @GET("api/greader.php/reader/api/0/stream/items/ids")
    suspend fun unreadItemIds(
        @Header("Authorization") auth: String,
        @Query("s") stream: String = "user/-/state/com.google/reading-list",
        @Query("xt") excludeTag: String = "user/-/state/com.google/read",
        @Query("n") n: Int = 1000,
        @Query("output") output: String = "json",
    ): ItemIdList

    @GET("api/greader.php/reader/api/0/token")
    suspend fun requestToken(@Header("Authorization") auth: String): ResponseBody

    @FormUrlEncoded
    @POST("api/greader.php/reader/api/0/stream/items/contents")
    suspend fun itemContents(
        @Header("Authorization") auth: String,
        @Field("i") ids: List<String>,
        @Field("T") token: String,
        @Query("output") output: String = "json",
    ): ContentList

    @FormUrlEncoded
    @POST("api/greader.php/reader/api/0/edit-tag")
    suspend fun editTag(
        @Header("Authorization") auth: String,
        @Field("i") ids: List<String>,
        @Field("a") addTag: String? = null,
        @Field("r") removeTag: String? = null,
        @Field("T") token: String,
    ): ResponseBody
}

object ApiFactory {

    fun create(baseUrl: String): GoogleReaderApi {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GoogleReaderApi::class.java)
    }

    /** ClientLogin → Auth-Token aus Text-Antwort parsen. */
    suspend fun authKey(api: GoogleReaderApi, user: String, password: String): String {
        val text = api.clientLogin(user, password).string()
        val auth = text.lineSequence()
            .firstOrNull { it.startsWith("Auth=") }
            ?.removePrefix("Auth=")
            ?.trim()
        require(!auth.isNullOrBlank()) { "Server-Antwort ohne Auth-Token" }
        return auth
    }
}
