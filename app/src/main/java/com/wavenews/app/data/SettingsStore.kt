package com.wavenews.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "wave_news")

/** Angemeldetes Konto. Das Passwort wird bewusst NICHT gespeichert — nur der Auth-Token. */
data class Account(val serverUrl: String, val username: String, val authKey: String)

/** Back-Verhalten: Kette (Übersicht → News-Kategorie → Beenden) oder direkt beenden. */
enum class BackBehavior { CHAIN, DIRECT }

/** Kartengröße in der Hauptübersicht. */
enum class CardSize { STANDARD, MEDIUM, SMALL }

data class AppSettings(
    val backBehavior: BackBehavior = BackBehavior.CHAIN,
    val cardSize: CardSize = CardSize.STANDARD,
    val topicImages: Boolean = true,
    val swipeActions: Boolean = true,
)

class SettingsStore(private val context: Context) {

    private val keyServer = stringPreferencesKey("server_url")
    private val keyUser = stringPreferencesKey("username")
    private val keyAuth = stringPreferencesKey("auth_key")
    private val keyBack = stringPreferencesKey("back_behavior")
    private val keyCard = stringPreferencesKey("card_size")
    private val keyTopic = booleanPreferencesKey("topic_images")
    private val keySwipe = booleanPreferencesKey("swipe_actions")

    val account: Flow<Account?> = context.dataStore.data.map { p ->
        val server = p[keyServer]
        val user = p[keyUser]
        val auth = p[keyAuth]
        if (server != null && user != null && auth != null) Account(server, user, auth) else null
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            backBehavior = p[keyBack]?.let { runCatching { BackBehavior.valueOf(it) }.getOrNull() } ?: BackBehavior.CHAIN,
            cardSize = p[keyCard]?.let { runCatching { CardSize.valueOf(it) }.getOrNull() } ?: CardSize.STANDARD,
            topicImages = p[keyTopic] ?: true,
            swipeActions = p[keySwipe] ?: true,
        )
    }

    suspend fun accountOnce(): Account? = account.first()

    suspend fun settingsOnce(): AppSettings = settings.first()

    suspend fun saveAccount(account: Account) {
        context.dataStore.edit { p ->
            p[keyServer] = account.serverUrl
            p[keyUser] = account.username
            p[keyAuth] = account.authKey
        }
    }

    suspend fun setBackBehavior(value: BackBehavior) = context.dataStore.edit { it[keyBack] = value.name }
    suspend fun setCardSize(value: CardSize) = context.dataStore.edit { it[keyCard] = value.name }
    suspend fun setTopicImages(value: Boolean) = context.dataStore.edit { it[keyTopic] = value }
    suspend fun setSwipeActions(value: Boolean) = context.dataStore.edit { it[keySwipe] = value }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
