package com.wavenews.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.wavenews.app.R
import com.wavenews.app.WaveNewsApp
import com.wavenews.app.data.Account
import com.wavenews.app.data.db.ArticleEntity
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// --- ViewModel ---

enum class ReadFilter { ALL, UNREAD, STARRED }

data class UiState(
    val account: Account? = null,
    val loading: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val filter: ReadFilter = ReadFilter.UNREAD,
    val selectedFeed: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(private val app: WaveNewsApp) : ViewModel() {

    private val ui = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = ui

    val feeds = app.repository.observeFeeds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val articles: StateFlow<List<ArticleEntity>> = ui
        .flatMapLatest { s ->
            app.repository.observeArticles(
                feedId = s.selectedFeed,
                onlyUnread = s.filter == ReadFilter.UNREAD,
                onlyStarred = s.filter == ReadFilter.STARRED,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            app.settings.account.collect { ui.value = ui.value.copy(account = it) }
        }
        viewModelScope.launch {
            if (app.settings.accountOnce() != null) sync()
        }
    }

    fun login(server: String, user: String, password: String) {
        viewModelScope.launch {
            ui.value = ui.value.copy(loading = true, error = null)
            try {
                app.repository.login(server, user, password)
                sync()
            } catch (e: Exception) {
                ui.value = ui.value.copy(error = e.message ?: "Login fehlgeschlagen")
            } finally {
                ui.value = ui.value.copy(loading = false)
            }
        }
    }

    fun sync() {
        viewModelScope.launch {
            ui.value = ui.value.copy(loading = true, error = null)
            try {
                app.repository.sync { p -> ui.value = ui.value.copy(progress = p) }
            } catch (e: Exception) {
                ui.value = ui.value.copy(error = e.message ?: "Sync fehlgeschlagen")
            } finally {
                ui.value = ui.value.copy(loading = false, progress = "")
            }
        }
    }

    fun setFilter(filter: ReadFilter) {
        ui.value = ui.value.copy(filter = filter)
    }

    fun selectFeed(feedId: String?) {
        ui.value = ui.value.copy(selectedFeed = feedId)
    }

    fun markRead(article: ArticleEntity, read: Boolean) {
        viewModelScope.launch {
            try {
                app.repository.markRead(article.id, read)
            } catch (_: Exception) {
                // Offline: beim nächsten Sync abgleichen
            }
        }
    }

    fun toggleStar(article: ArticleEntity) {
        viewModelScope.launch {
            try {
                app.repository.markStarred(article.id, !article.starred)
            } catch (_: Exception) {
            }
        }
    }

    fun logout() {
        viewModelScope.launch { app.repository.logout() }
    }

    class Factory(private val app: WaveNewsApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
    }
}

// --- UI ---

@Composable
fun MainScreen() {
    val app = LocalContext.current.applicationContext as WaveNewsApp
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(app))
    val state by vm.state.collectAsState()
    val articles by vm.articles.collectAsState()

    if (state.account == null) {
        LoginScreen(vm, state)
    } else {
        NewsScreen(vm, state, articles)
    }
}

@Composable
private fun LoginScreen(vm: MainViewModel, state: UiState) {
    var server by remember { mutableStateOf("https://freshrss.heddrich.com") }
    var user by remember { mutableStateOf("jens") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text(stringResource(R.string.login_server)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text(stringResource(R.string.login_user)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.login_password)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.login(server, user, password) },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.loading) "…" else stringResource(R.string.login_button))
        }
        if (state.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.login_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewsScreen(vm: MainViewModel, state: UiState, articles: List<ArticleEntity>) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val feeds by vm.feeds.collectAsState()
    var showLogout by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("Quellen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                NavigationDrawerItem(
                    label = { Text("Alle Quellen") },
                    selected = state.selectedFeed == null,
                    onClick = { vm.selectFeed(null); scope.launch { drawerState.close() } },
                )
                feeds.forEach { feed ->
                    NavigationDrawerItem(
                        label = { Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        selected = state.selectedFeed == feed.id,
                        onClick = { vm.selectFeed(feed.id); scope.launch { drawerState.close() } },
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Wave News") },
                    actions = {
                        IconButton(onClick = { vm.sync() }, enabled = !state.loading) {
                            Text("⟳", style = MaterialTheme.typography.titleLarge)
                        }
                        IconButton(onClick = { showLogout = true }) { Text("⋯") }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.filter == ReadFilter.UNREAD, onClick = { vm.setFilter(ReadFilter.UNREAD) }, label = { Text(stringResource(R.string.filter_unread)) })
                    FilterChip(selected = state.filter == ReadFilter.ALL, onClick = { vm.setFilter(ReadFilter.ALL) }, label = { Text(stringResource(R.string.filter_all)) })
                    FilterChip(selected = state.filter == ReadFilter.STARRED, onClick = { vm.setFilter(ReadFilter.STARRED) }, label = { Text(stringResource(R.string.filter_starred)) })
                }
                if (state.loading && state.progress.isNotBlank()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        state.progress,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                }
                if (articles.isEmpty() && !state.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.empty_state), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    ArticleList(articles, vm)
                }
            }
        }
    }

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text(stringResource(R.string.logout)) },
            text = { Text("Vom Konto abmelden? Lokale Daten bleiben erhalten.") },
            confirmButton = {
                TextButton(onClick = { showLogout = false; vm.logout() }) { Text(stringResource(R.string.logout)) }
            },
            dismissButton = {
                TextButton(onClick = { showLogout = false }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun ArticleList(articles: List<ArticleEntity>, vm: MainViewModel) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(articles, key = { it.id }) { article ->
            ArticleRow(
                article,
                onClick = {
                    if (article.unread) vm.markRead(article, true)
                    article.url.takeIf { it.isNotBlank() }?.let {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                    }
                },
                onStar = { vm.toggleStar(article) },
            )
        }
    }
}

@Composable
private fun ArticleRow(article: ArticleEntity, onClick: () -> Unit, onStar: () -> Unit) {
    val date = remember(article.published) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(article.published * 1000))
    }
    ListItem(
        headlineContent = {
            Text(
                article.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (article.unread) FontWeight.Bold else FontWeight.Normal,
            )
        },
        supportingContent = {
            Text(
                "$date · ${article.feedTitle}",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Text(
                if (article.starred) "★" else "☆",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clickable(onClick = onStar),
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
