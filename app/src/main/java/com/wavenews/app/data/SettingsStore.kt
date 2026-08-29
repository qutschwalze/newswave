package com.wavenews.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "wave_news")

/** Angemeldetes Konto. Das Passwort wird bewusst NICHT gespeichert — nur der Auth-Token. */
data class Account(val serverUrl: String, val username: String, val authKey: String)

class SettingsStore(private val context: Context) {

    private val keyServer = stringPreferencesKey("server_url")
    private val keyUser = stringPreferencesKey("username")
    private val keyAuth = stringPreferencesKey("auth_key")

    val account: Flow<Account?> = context.dataStore.data.map { p ->
        val server = p[keyServer]
        val user = p[keyUser]
        val auth = p[keyAuth]
        if (server != null && user != null && auth != null) Account(server, user, auth) else null
    }

    suspend fun accountOnce(): Account? = account.first()

    suspend fun saveAccount(account: Account) {
        context.dataStore.edit { p ->
            p[keyServer] = account.serverUrl
            p[keyUser] = account.username
            p[keyAuth] = account.authKey
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
