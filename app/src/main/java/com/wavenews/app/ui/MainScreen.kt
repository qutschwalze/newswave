package com.wavenews.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.wavenews.app.R
import com.wavenews.app.WaveNewsApp
import com.wavenews.app.data.Account
import com.wavenews.app.data.AppSettings
import com.wavenews.app.data.BackBehavior
import com.wavenews.app.data.CardSize
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
    val selectedCategory: String? = null,
    val openArticle: ArticleEntity? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(private val app: WaveNewsApp) : ViewModel() {

    private val ui = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = ui

    val appSettings: StateFlow<AppSettings> = app.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val feeds = app.repository.observeFeeds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories = app.repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val articles: StateFlow<List<ArticleEntity>> = ui
        .flatMapLatest { s ->
            app.repository.observeArticles(
                feedId = s.selectedFeed,
                category = s.selectedCategory,
                onlyUnread = s.filter == ReadFilter.UNREAD,
                onlyStarred = s.filter == ReadFilter.STARRED,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            app.settings.account.collect { ui.value = ui.value.copy(account = it) }
        }
        // Deep-Link newswave://article/<id> → Detailansicht öffnen
        viewModelScope.launch {
            app.pendingArticleId.collect { id ->
                if (id != null) {
                    app.repository.article(id)?.let { article ->
                        ui.value = ui.value.copy(openArticle = article)
                    }
                    app.pendingArticleId.value = null
                }
            }
        }
        viewModelScope.launch {
            if (app.settings.accountOnce() != null) sync()
        }
    }

    fun openArticle(article: ArticleEntity) {
        ui.value = ui.value.copy(openArticle = article)
    }

    fun closeArticle() {
        ui.value = ui.value.copy(openArticle = null)
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
        ui.value = ui.value.copy(selectedFeed = feedId, selectedCategory = null)
    }

    fun selectCategory(category: String?) {
        ui.value = ui.value.copy(selectedCategory = category, selectedFeed = null)
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

    fun setBackBehavior(value: BackBehavior) = viewModelScope.launch { app.settings.setBackBehavior(value) }
    fun setCardSize(value: CardSize) = viewModelScope.launch { app.settings.setCardSize(value) }
    fun setTopicImages(value: Boolean) = viewModelScope.launch { app.settings.setTopicImages(value) }
    fun setSwipeActions(value: Boolean) = viewModelScope.launch { app.settings.setSwipeActions(value) }

    class Factory(private val app: WaveNewsApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
    }
}

// --- Helpers ---

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Relative Zeit wie "5 Min.", "2 Std.", "3 Tage" — sonst Datum. */
private fun relativeTime(publishedSeconds: Long): String {
    val diff = System.currentTimeMillis() / 1000 - publishedSeconds
    return when {
        diff < 90 -> "gerade eben"
        diff < 3600 -> "${diff / 60} Min."
        diff < 86_400 -> "${diff / 3600} Std."
        diff < 7 * 86_400 -> "${diff / 86_400} Tage"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(publishedSeconds * 1000))
    }
}

// --- Root ---

@Composable
fun MainScreen() {
    val app = LocalContext.current.applicationContext as WaveNewsApp
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(app))
    val state by vm.state.collectAsState()
    val articles by vm.articles.collectAsState()
    val settings by vm.appSettings.collectAsState()

    if (state.account == null) {
        LoginScreen(vm, state)
    } else {
        NewsScreen(vm, state, articles, settings)
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

// --- Hauptübersicht mit Back-Logik ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewsScreen(
    vm: MainViewModel,
    state: UiState,
    articles: List<ArticleEntity>,
    settings: AppSettings,
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val feeds by vm.feeds.collectAsState()
    val categories by vm.categories.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }
    var backStage by remember { mutableStateOf(0) }

    // Manuelles Navigieren setzt die Back-Kette zurück
    LaunchedEffect(state.selectedFeed, state.selectedCategory, state.openArticle) {
        backStage = 0
    }

    BackHandler(enabled = state.account != null) {
        when {
            state.openArticle != null -> vm.closeArticle()
            settings.backBehavior == BackBehavior.DIRECT -> context.findActivity()?.finishAffinity()
            state.selectedFeed != null || state.selectedCategory != null -> {
                // 1. Stufe: zurück zur Hauptübersicht
                vm.selectFeed(null)
                backStage = 1
                Toast.makeText(context, context.getString(R.string.toast_main), Toast.LENGTH_SHORT).show()
            }
            backStage <= 1 -> {
                // 2. Stufe: in die News-Kategorie springen
                val newsCategory = categories.firstOrNull { it.contains("nachricht", ignoreCase = true) }
                    ?: categories.firstOrNull()
                if (newsCategory != null) {
                    vm.selectCategory(newsCategory)
                    backStage = 2
                    Toast.makeText(context, context.getString(R.string.toast_category, newsCategory), Toast.LENGTH_SHORT).show()
                } else {
                    context.findActivity()?.finishAffinity()
                }
            }
            else -> context.findActivity()?.finishAffinity() // 3. Stufe: beenden
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(stringResource(R.string.drawer_categories), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.drawer_all)) },
                    selected = state.selectedFeed == null && state.selectedCategory == null,
                    onClick = { vm.selectFeed(null); scope.launch { drawerState.close() } },
                )
                categories.forEach { category ->
                    NavigationDrawerItem(
                        label = { Text(category) },
                        selected = state.selectedCategory == category,
                        onClick = { vm.selectCategory(category); scope.launch { drawerState.close() } },
                    )
                }
                Text(stringResource(R.string.drawer_feeds), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
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
                    title = {
                        Text(
                            when {
                                state.selectedFeed != null -> feeds.firstOrNull { it.id == state.selectedFeed }?.title ?: stringResource(R.string.app_name)
                                state.selectedCategory != null -> state.selectedCategory
                                else -> stringResource(R.string.app_name)
                            }
                        )
                    },
                    actions = {
                        // Karten-Größe durchtippen: Standard → Mittel → Klein
                        IconButton(onClick = {
                            val next = when (settings.cardSize) {
                                CardSize.STANDARD -> CardSize.MEDIUM
                                CardSize.MEDIUM -> CardSize.SMALL
                                CardSize.SMALL -> CardSize.STANDARD
                            }
                            vm.setCardSize(next)
                        }) {
                            Text(
                                when (settings.cardSize) {
                                    CardSize.STANDARD -> "▤"
                                    CardSize.MEDIUM -> "▥"
                                    CardSize.SMALL -> "☰"
                                },
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        IconButton(onClick = { vm.sync() }, enabled = !state.loading) {
                            Text("⟳", style = MaterialTheme.typography.titleLarge)
                        }
                        IconButton(onClick = { showSettings = true }) { Text("⋯") }
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
                    ArticleList(articles, vm, settings)
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            vm = vm,
            onDismiss = { showSettings = false },
            onLogoutRequest = { showSettings = false; showLogout = true },
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    settings: AppSettings,
    vm: MainViewModel,
    onDismiss: () -> Unit,
    onLogoutRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.settings_cards), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            listOf(
                CardSize.STANDARD to R.string.card_standard,
                CardSize.MEDIUM to R.string.card_medium,
                CardSize.SMALL to R.string.card_small,
            ).forEach { (size, label) ->
                Row(Modifier.fillMaxWidth().clickable { vm.setCardSize(size) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.cardSize == size, onClick = { vm.setCardSize(size) })
                    Text(stringResource(label))
                }
            }
            Spacer(Modifier.height(8.dp))

            Text(stringResource(R.string.settings_back), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth().clickable { vm.setBackBehavior(BackBehavior.CHAIN) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.backBehavior == BackBehavior.CHAIN, onClick = { vm.setBackBehavior(BackBehavior.CHAIN) })
                Text(stringResource(R.string.back_chain))
            }
            Row(Modifier.fillMaxWidth().clickable { vm.setBackBehavior(BackBehavior.DIRECT) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.backBehavior == BackBehavior.DIRECT, onClick = { vm.setBackBehavior(BackBehavior.DIRECT) })
                Text(stringResource(R.string.back_direct))
            }
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_topic), modifier = Modifier.weight(1f))
                Switch(checked = settings.topicImages, onCheckedChange = { vm.setTopicImages(it) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_swipe), modifier = Modifier.weight(1f))
                Switch(checked = settings.swipeActions, onCheckedChange = { vm.setSwipeActions(it) })
            }
            Spacer(Modifier.height(12.dp))

            TextButton(onClick = onLogoutRequest) { Text(stringResource(R.string.logout)) }
        }
    }
}

// --- Artikelliste (Karten-Größen) ---

@Composable
private fun ArticleList(articles: List<ArticleEntity>, vm: MainViewModel, settings: AppSettings) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(articles, key = { it.id }) { article ->
            when (settings.cardSize) {
                CardSize.STANDARD -> ArticleCard(article, imageHeight = null, onClick = { vm.openArticle(article) }, onStar = { vm.toggleStar(article) })
                CardSize.MEDIUM -> ArticleCard(article, imageHeight = 110.dp, onClick = { vm.openArticle(article) }, onStar = { vm.toggleStar(article) })
                CardSize.SMALL -> ArticleRowSmall(article, onClick = { vm.openArticle(article) }, onStar = { vm.toggleStar(article) })
            }
        }
    }
}

/**
 * Karten-Bild: Artikelbild (Standard) → Themen-Logo → Monogramm.
 * imageHeight = null → 16:9 (Standard-Größe); sonst feste Höhe (Mittel).
 */
@Composable
private fun CardImage(article: ArticleEntity, imageHeight: androidx.compose.ui.unit.Dp?) {
    val textForTopic = "${article.title} ${article.feedTitle}"
    val topicLogo = remember(textForTopic) { TopicMatcher.match(textForTopic) }
    val monogramColor = remember(article.feedTitle) { TopicMatcher.monogramColor(article.feedTitle) }
    val letter = article.feedTitle.trim().uppercase().firstOrNull()?.toString() ?: "N"

    when {
        article.imageUrl != null -> {
            AsyncImage(
                model = article.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (imageHeight != null) Modifier.height(imageHeight) else Modifier.aspectRatio(16f / 9f)),
            )
        }
        topicLogo != null -> {
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (imageHeight != null) Modifier.height(imageHeight) else Modifier.aspectRatio(16f / 9f))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = topicLogo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(72.dp),
                )
            }
        }
        else -> {
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (imageHeight != null) Modifier.height(imageHeight) else Modifier.aspectRatio(16f / 9f))
                    .background(monogramColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter,
                    style = MaterialTheme.typography.displayMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}

@Composable
private fun ArticleCard(article: ArticleEntity, imageHeight: androidx.compose.ui.unit.Dp?, onClick: () -> Unit, onStar: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column {
            CardImage(article, imageHeight)
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    article.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (article.unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${article.feedTitle} · ${relativeTime(article.published)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (article.starred) "★" else "☆",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable(onClick = onStar),
                    )
                }
            }
        }
    }
}

/** Kompakte Zeilen-Karte (Klein): Mini-Bild links (Artikelbild/Logo/Monogramm), Text rechts. */
@Composable
private fun ArticleRowSmall(article: ArticleEntity, onClick: () -> Unit, onStar: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallThumb(article)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    article.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (article.unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${article.feedTitle} · ${relativeTime(article.published)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (article.starred) "★" else "☆",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable(onClick = onStar).padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun SmallThumb(article: ArticleEntity) {
    val topicLogo = remember(article.title + article.feedTitle) { TopicMatcher.match("${article.title} ${article.feedTitle}") }
    val monogramColor = remember(article.feedTitle) { TopicMatcher.monogramColor(article.feedTitle) }
    val letter = article.feedTitle.trim().uppercase().firstOrNull()?.toString() ?: "N"
    val size = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))

    when {
        article.imageUrl != null -> AsyncImage(
            model = article.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = size,
        )
        topicLogo != null -> Box(size.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = topicLogo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(34.dp),
            )
        }
        else -> Box(size.background(monogramColor), contentAlignment = Alignment.Center) {
            Text(letter, color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
        }
    }
}

// --- Artikel-Detail mit Swipe-Aktionen + internem Browser ---

private val SWIPE_THRESHOLD = 160.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleDetailScreen(article: ArticleEntity, vm: MainViewModel) {
    val context = LocalContext.current
    val settings by vm.appSettings.collectAsState()
    var internalBrowserUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(article.id) {
        if (article.unread) vm.markRead(article, true)
    }

    fun openExternal(url: String) {
        if (url.isBlank()) return
        Toast.makeText(context, context.getString(R.string.toast_external), Toast.LENGTH_SHORT).show()
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(article.feedTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { internalBrowserUrl = null; vm.closeArticle() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.toggleStar(article) }) {
                        Text(
                            if (article.starred) "★" else "☆",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    IconButton(onClick = { article.url.takeIf { it.isNotBlank() }?.let { openExternal(it) } }) { Text("↗") }
                },
            )
        },
    ) { padding ->
        if (internalBrowserUrl != null) {
            InternalBrowserScreen(url = internalBrowserUrl!!, onClose = { internalBrowserUrl = null })
        } else {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .then(
                        if (settings.swipeActions) Modifier.pointerInput(article.id) {
                            var downPull = 0f // von oben nach unten
                            var upPull = 0f   // von unten nach oben
                            detectVerticalDragGestures(
                                onDragStart = { downPull = 0f; upPull = 0f },
                                onVerticalDrag = { change, amount ->
                                    if (amount > 0) downPull += amount else upPull -= amount
                                    change.consume()
                                },
                                onDragEnd = {
                                    val threshold = SWIPE_THRESHOLD.toPx()
                                    when {
                                        downPull > threshold && article.url.isNotBlank() -> internalBrowserUrl = article.url
                                        upPull > threshold && article.url.isNotBlank() -> openExternal(article.url)
                                    }
                                },
                            )
                        } else Modifier,
                    ),
            ) {
                Text(
                    article.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    (article.author?.let { "$it · " } ?: "") + DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(article.published * 1000)),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (settings.swipeActions && article.url.isNotBlank()) {
                    Text(
                        stringResource(R.string.gesture_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
                if (article.summaryHtml.isNotBlank()) {
                    ArticleWebView(
                        html = article.summaryHtml,
                        baseUrl = article.url,
                        modifier = Modifier.fillMaxSize().padding(top = 4.dp),
                    )
                } else if (article.url.isNotBlank()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Kein Inhalt im Feed vorhanden.")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { openExternal(article.url) }) { Text(stringResource(R.string.detail_fulltext)) }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InternalBrowserScreen(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("✕") }
            Text(
                url,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }) { Text("↗") }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = WebViewClient()
                }
            },
            update = { it.loadUrl(url) },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ArticleWebView(html: String, baseUrl: String, modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = WebViewClient()
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                baseUrl.takeIf { it.isNotBlank() },
                wrapArticleHtml(html, isDark),
                "text/html",
                "utf-8",
                null,
            )
        },
    )
}

private fun wrapArticleHtml(contentHtml: String, isDark: Boolean): String {
    val textColor = if (isDark) "#E5E1E6" else "#1A1A1A"
    val bgColor = if (isDark) "#15151A" else "#FEFBFF"
    val linkColor = if (isDark) "#9FA8DA" else "#3949AB"
    return """
    <!DOCTYPE html>
    <html>
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
      body { font-family: sans-serif; font-size: 16px; line-height: 1.55;
             color: $textColor; background: $bgColor; margin: 0; padding: 12px 16px; }
      img { max-width: 100%; height: auto; }
      a { color: $linkColor; }
    </style>
    </head>
    <body>$contentHtml</body>
    </html>
""".trimIndent()
}
