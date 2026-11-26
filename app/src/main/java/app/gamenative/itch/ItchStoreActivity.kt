package app.gamenative.itch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.app.Presentation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.gamenative.PrefManager
import app.gamenative.ui.component.focusRing
import app.gamenative.utils.rememberExternalDisplay
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import com.skydoves.landscapist.ImageOptions
import app.gamenative.ui.component.Button
import app.gamenative.ui.component.FilterChip
import app.gamenative.ui.component.ConsoleIconButton
import app.gamenative.ui.gcds.GcdsCard
import app.gamenative.ui.screen.library.components.ConsolePanelHeader
import app.gamenative.ui.theme.currentGameplayAppearance
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.ItchGame
import app.gamenative.ui.theme.PluviaTheme
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ItchStoreActivity : ComponentActivity() {
    companion object {
        fun open(context: Context) {
            val intent = Intent(context, ItchStoreActivity::class.java)
            val activity = app.gamenative.utils.BrightnessManager.findActivity(context)
            if (activity != null) activity.startActivity(intent)
            else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private var signedIn by mutableStateOf(false)

    private var switchCategory: ((Int) -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> switchCategory?.let { change ->
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    change(if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else -1)
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_A -> return super.dispatchKeyEvent(KeyEvent(
                event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER,
                event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
            ))
        }
        return super.dispatchKeyEvent(event)
    }

    private var accountId by mutableStateOf<Long?>(null)
    private var foreground by mutableStateOf(false)

    override fun onPause() {
        foreground = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        signedIn = ItchAuthUi.hasAccount(this)
        accountId = if (signedIn) ItchAccess.repository(this).account()?.userId else null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent { currentGameplayAppearance().Provide { Surface { Store() } } }
    }

    @Composable
    private fun Store() {
        val categories = listOf(stringResource(R.string.tab_all) to "", stringResource(R.string.itch_owned) to "owned", "PICO-8" to "tag-pico-8", "Game Boy" to "tag-gameboy", "HTML5" to "html5", "Windows" to "platform-windows")
        var category by rememberSaveable { mutableStateOf(categories.first().second) }
        var input by rememberSaveable { mutableStateOf("") }
        var searching by rememberSaveable { mutableStateOf(false) }
        fun closeSearch() {
            searching = false
            input = ""
        }
        androidx.activity.compose.BackHandler(searching) { closeSearch() }
        val ownedTab = category == "owned"
        val prefs = remember { getPreferences(MODE_PRIVATE) }
        var order by remember { mutableStateOf(CatalogOrder.values().find { it.name == prefs.getString("order", null) } ?: CatalogOrder.POPULAR) }
        var orderMenu by remember { mutableStateOf(false) }
        val query = input.trim()
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        var refresh by remember { mutableIntStateOf(0) }
        var games by remember(category, query, order, refresh) { mutableStateOf<List<CatalogGame>?>(null) }
        var error by remember(category, query, order, refresh) { mutableStateOf<String?>(null) }
        var pending by remember { mutableStateOf<String?>(null) }
        var builds by remember { mutableStateOf<Pair<ItchGame, List<ItchUploadCandidate>>?>(null) }
        val repo = remember { ItchAccess.repository(this) }
        val downloads = remember { ItchAccess.downloads(this) }
        val library by repo.observeGames().collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()
        val messages = remember { SnackbarHostState() }
        val progress = remember { mutableStateMapOf<Long, Int>() }

        var page by remember(category, query, order, refresh) { mutableIntStateOf(1) }
        var hasMore by remember(category, query, order, refresh) { mutableStateOf(false) }
        var loadingPage by remember(category, query, order, refresh) { mutableStateOf(false) }
        var retryPage by remember { mutableIntStateOf(0) }
        var pageError by remember(category, query, order, refresh) { mutableStateOf<String?>(null) }
        LaunchedEffect(category, query, order, refresh, signedIn, page, retryPage) {
            if (ownedTab || (query.isNotEmpty() && !signedIn)) return@LaunchedEffect
            error = null
            pageError = null
            loadingPage = true
            try {
                if (query.isEmpty()) {
                    val batch = ItchFreeCatalog.games(category, page, order)
                    val previous = if (page == 1) emptyList() else games.orEmpty()
                    val merged = (previous + batch).distinctBy { it.url.trimEnd('/') }
                    hasMore = merged.size > previous.size
                    games = merged
                } else {
                    delay(350)
                    games = repo.searchFreeGames(query)
                }

            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (page == 1) error = e.localizedMessage ?: "itch.io"
                else pageError = e.localizedMessage ?: "itch.io"
            } finally { loadingPage = false }
        }
        LaunchedEffect(downloads) {
            while (true) {
                val running = downloads.getActiveDownloads()
                progress.keys.retainAll(running.keys)
                running.forEach { (id, info) -> progress[id] = (info.getProgress() * 100).toInt() }
                delay(500)
            }
        }

        suspend fun install(game: ItchGame, uploadId: Long) {
            repo.addCatalogGame(game)
            when (val result = downloads.start(game.gameId, uploadId)) {
                is ItchDownloadStart.Failed -> throw IllegalStateException(result.message)
                is ItchDownloadStart.OpenInBrowser -> throw IllegalStateException(getString(R.string.itch_no_store_build))
                is ItchDownloadStart.NeedsUploadSelection -> builds = game to ItchUploadSelector.candidates(result.selection.uploads)
                else -> Unit
            }
        }

        fun action(url: String, operation: suspend () -> Unit) {
            if (pending != null) return
            pending = url
            scope.launch {
                try { operation() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { scope.launch { messages.showSnackbar(e.localizedMessage ?: "itch.io") } }
                finally { pending = null }
            }
        }

        var selectedUrl by rememberSaveable(category, query, order) { mutableStateOf<String?>(null) }
        val owned = remember(library, accountId) {
            library.filter { accountId != null && it.accountId == accountId && it.isOwned && it.isGame }
                .associateBy { it.url.trimEnd('/') }
        }
        val ownedEntries = remember(owned, query) {
            owned.values.filter { it.title.contains(query, ignoreCase = true) }
                .map { CatalogGame(it.title, it.url, it.coverUrl) }
        }
        val entries = when {
            ownedTab -> ownedEntries
            query.isNotEmpty() -> (games.orEmpty() + ownedEntries).distinctBy { it.url.trimEnd('/') }
            else -> games.orEmpty()
        }
        val entry = entries.find { it.url == selectedUrl } ?: entries.firstOrNull()
        val local = entry?.let { item -> library.find { it.url.trimEnd('/') == item.url.trimEnd('/') } }
        val percent = local?.let { progress[it.gameId] }
        val installEnabled = entry != null && pending == null && percent == null && local?.isInstalled != true
        val installFocus = remember { FocusRequester() }
        val firstGame = remember { FocusRequester() }
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        LaunchedEffect(category, query, order) { gridState.scrollToItem(0) }
        val installLabel = when {
            percent != null -> "$percent%"
            local?.isInstalled == true -> stringResource(R.string.installed)
            pending == entry?.url && pending != null -> stringResource(R.string.main_loading)
            else -> stringResource(R.string.install)
        }
        val stageTitle = entry?.title ?: stringResource(R.string.itch_free_store)
        val subtitle = entry?.let { android.net.Uri.parse(it.url).host }.orEmpty()
        val stageNote = when {
            error != null -> error.orEmpty()
            (ownedTab || query.isNotEmpty()) && !signedIn -> stringResource(if (ownedTab) R.string.itch_settings_login_title else R.string.itch_store_search_login)
            !ownedTab && games == null -> stringResource(R.string.main_loading)
            entry == null -> stringResource(if (ownedTab && query.isEmpty()) R.string.itch_owned_empty else R.string.itch_store_search_empty)
            else -> subtitle
        }
        StoreCompanion(stageTitle, stageNote, entry?.image.orEmpty())
        DisposableEffect(searching, category) {
            switchCategory = if (searching) null else { delta ->
                val index = categories.indexOfFirst { it.second == category }
                category = categories[(index + delta + categories.size) % categories.size].second
            }
            onDispose { switchCategory = null }
        }
        Scaffold(snackbarHost = { SnackbarHost(messages) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (searching) {
                        val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                        LaunchedEffect(Unit) { fieldFocus.requestFocus() }
                        OutlinedTextField(
                            value = input, onValueChange = { input = it }, singleLine = true,
                            modifier = Modifier.weight(1f).focusRequester(fieldFocus),
                            label = { Text(stringResource(if (ownedTab) R.string.itch_owned_search else R.string.itch_store_search)) },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                                focus.clearFocus()
                                if (entries.isNotEmpty()) scope.launch {
                                    gridState.scrollToItem(0)
                                    withFrameNanos { }
                                    if (gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.key == entries.first().url) firstGame.requestFocus()
                                }
                            }),
                        )
                        ConsoleIconButton(::closeSearch, Icons.Default.Close, stringResource(R.string.back))
                    } else {
                        ConsolePanelHeader(stringResource(R.string.itch_free_store), { finish() }, Modifier.weight(1f))
                        if (!ownedTab) Box {
                            ConsoleIconButton({ orderMenu = true }, Icons.Default.Sort,
                                stringResource(R.string.options_sort_by) + " · " + stringResource(order.label))
                            DropdownMenu(expanded = orderMenu, onDismissRequest = { orderMenu = false }) {
                                CatalogOrder.values().forEach { option ->
                                    DropdownMenuItem(text = { Text(stringResource(option.label)) },
                                        trailingIcon = { if (order == option) Icon(Icons.Default.Check, null) },
                                        onClick = {
                                            order = option
                                            prefs.edit().putString("order", option.name).apply()
                                            orderMenu = false
                                        })
                                }
                            }
                        }
                        ConsoleIconButton({ searching = true }, Icons.Default.Search, stringResource(R.string.itch_store_search))
                    }
                }
                if (query.isEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { (label, slug) ->
                        FilterChip(selected = category == slug, onClick = { category = slug }, label = { Text(label) })
                    }
                }
                if ((ownedTab || query.isNotEmpty()) && !signedIn) {
                    TextButton(onClick = { ItchAuthUi.signIn(this@ItchStoreActivity) }) { Text(stringResource(if (ownedTab) R.string.itch_settings_login_title else R.string.itch_store_search_login)) }
                } else if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(error.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.itch_store_retry)) }
                } else if (!ownedTab && games == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stageNote, style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(148.dp),
                        state = gridState,
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(entries, key = { it.url }) { item ->
                            val shape = RoundedCornerShape(PluviaTheme.tokens.cornerMd)
                            val selected = item == entry
                            var focused by remember { mutableStateOf(false) }
                            val modifier = if (item == entries.first()) Modifier.focusRequester(firstGame) else Modifier
                            GcdsCard(modifier.fillMaxWidth().clip(shape)
                                .focusRing(focused, shape)
                                .onFocusChanged {
                                    focused = it.isFocused
                                    if (it.isFocused) selectedUrl = item.url
                                }
                                .semantics { this.selected = selected }
                                .clickable(role = Role.Button) {
                                    selectedUrl = item.url
                                    val installed = library.find { it.url.trimEnd('/') == item.url.trimEnd('/') }
                                    if (installed?.isInstalled != true && pending == null && installed?.gameId !in progress.keys) {
                                        scope.launch { withFrameNanos { }; installFocus.requestFocus() }
                                    }
                                },
                                shape = shape,
                                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else PluviaTheme.colors.surfaceElevated,
                                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                            ) {
                                Column {
                                    CoilImage(imageModel = { item.image }, imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                                        modifier = Modifier.fillMaxWidth().aspectRatio(1.85f))
                                    if (!ownedTab && item.url.trimEnd('/') in owned) {
                                        Text(stringResource(R.string.itch_owned_badge), style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(start = 10.dp, top = 4.dp))
                                    }
                                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                                }
                            }
                        }
                        if (!ownedTab && query.isEmpty() && hasMore) {
                            item(key = "more", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    pageError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    Button(enabled = !loadingPage, onClick = {
                                        if (!loadingPage) {
                                            loadingPage = true
                                            if (pageError != null) retryPage++ else page++
                                        }
                                    }) { Text(stringResource(when {
                                        loadingPage -> R.string.main_loading
                                        pageError != null -> R.string.itch_store_retry
                                        else -> R.string.community_config_load_more
                                    })) }
                                }
                            }
                        }
                    }
                    if (entry != null) {
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(local?.lastError?.takeIf { percent == null } ?: subtitle,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Button(modifier = Modifier.focusRequester(installFocus), enabled = installEnabled, onClick = {
                                if (!ItchAuthUi.hasAccount(this@ItchStoreActivity)) {
                                    ItchAuthUi.signIn(this@ItchStoreActivity) { scope.launch { messages.showSnackbar(it) } }
                                } else action(entry.url) {
                                    val game = owned[entry.url.trimEnd('/')] ?: ItchFreeCatalog.details(entry)
                                    val selection = repo.chooseUpload(game.gameId).getOrThrow()
                                    when (val choice = selection.choice) {
                                        is ItchUploadChoice.Single -> install(game, choice.upload.id)
                                        is ItchUploadChoice.Multiple -> builds = game to choice.uploads
                                        is ItchUploadChoice.None -> throw IllegalStateException(getString(R.string.itch_no_store_build))
                                    }
                                }
                            }) { Text(installLabel) }
                        }
                    }
                }
            }
        }
        builds?.let { (game, candidates) ->
            AlertDialog(onDismissRequest = { builds = null }, title = { Text(stringResource(R.string.itch_store_build)) },
                text = {
                    LazyColumn {
                        items(candidates) { upload ->
                            TextButton(onClick = {
                                builds = null
                                action(game.url) { install(game, upload.id) }
                            }) { Text("${upload.title} · ${upload.sizeLabel}") }
                        }
                    }
                }, confirmButton = { TextButton(onClick = { builds = null }) { Text(stringResource(R.string.cancel)) } })
        }
    }

    @Composable
    private fun StoreCompanion(title: String, note: String, image: String) {
        val display = rememberExternalDisplay()
        val enabled = PrefManager.dualScreenLauncherState.value && foreground
        val parent = rememberCompositionContext()
        val preview by rememberUpdatedState<@Composable () -> Unit> {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (image.isNotBlank()) {
                        CoilImage(imageModel = { image }, imageOptions = ImageOptions(contentScale = ContentScale.Fit),
                            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(PluviaTheme.tokens.cornerLg))
                                .background(PluviaTheme.colors.surfaceElevated))
                    } else Spacer(Modifier.weight(1f))
                    Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DisposableEffect(display?.displayId, enabled, parent) {
            if (!enabled || display == null) return@DisposableEffect onDispose {}
            val panel = Presentation(this@ItchStoreActivity, display)
            panel.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            val view = ComposeView(panel.context).apply {
                setParentCompositionContext(parent)
                setViewTreeLifecycleOwner(this@ItchStoreActivity)
                setViewTreeSavedStateRegistryOwner(this@ItchStoreActivity)
                setContent { preview() }
            }
            panel.setContentView(view)
            try { panel.show() } catch (e: WindowManager.InvalidDisplayException) {
                timber.log.Timber.d(e, "Store companion display disconnected")
            }
            onDispose { panel.dismiss(); view.disposeComposition() }
        }
    }
}
