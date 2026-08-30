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
// WICHTIG: Gson instanziiert diese Klassen ohne Konstruktor (Unsafe) — fehlende
// JSON-Felder sind daher NULL, auch wenn Kotlin nicht-null deklariert. Deshalb
// sind alle Sammlungen bewusst nullable und werden an der Verwendung mit orEmpty() gehärtet.

data class Category(val id: String = "", val label: String = "")

data class Subscription(
    val id: String = "",
    val title: String? = null,
    val categories: List<Category>? = null,
    val url: String? = null,
    val htmlUrl: String? = null,
    val iconUrl: String? = null,
)

data class SubscriptionList(val subscriptions: List<Subscription>? = null)

data class ItemRef(val id: String = "")
data class ItemIdList(val itemRefs: List<ItemRef>? = null)

data class Canonical(val href: String? = null)
data class Summary(val content: String? = null)
data class Enclosure(val href: String? = null, val type: String? = null, val length: Long = 0)
data class Origin(val streamId: String? = null, val title: String? = null, val htmlUrl: String? = null)

data class StreamItem(
    val id: String? = null,
    val title: String? = null,
    val published: Long = 0,
    val author: String? = null,
    val canonical: List<Canonical>? = null,
    val summary: Summary? = null,
    val enclosure: List<Enclosure>? = null,
    val origin: Origin? = null,
)

data class ContentList(val id: String? = null, val items: List<StreamItem>? = null)

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
    suspend fun itemIds(
        @Header("Authorization") auth: String,
        @Query("s") stream: String,
        @Query("xt") excludeTag: String? = null,
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

    // --- Feed-Verwaltung (FreshRSS unterstützt die Google-Reader-Admin-Endpunkte) ---

    /** Feed per URL hinzufügen (FreshRSS legt ihn ggf. automatisch an). */
    @FormUrlEncoded
    @POST("api/greader.php/reader/api/0/subscription/quickadd")
    suspend fun quickAddFeed(
        @Header("Authorization") auth: String,
        @Field("quickadd") url: String,
    ): ResponseBody

    /**
     * Feed ändern/entfernen: ac=edit (Kategorie via a=user/-/label/<Name>),
     * ac=unsubscribe (entfernen), ac=subscribe.
     */
    @FormUrlEncoded
    @POST("api/greader.php/reader/api/0/subscription/edit")
    suspend fun subscriptionEdit(
        @Header("Authorization") auth: String,
        @Field("ac") action: String,
        @Field("s") stream: String,
        @Field("a") addLabel: String? = null,
        @Field("r") removeLabel: String? = null,
        @Field("t") title: String? = null,
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
