package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.paging.LoadState
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseGlobalFilterDialog
import eu.kanade.presentation.browse.BrowseMangaActionsDialog
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource
import tachiyomi.core.common.i18n.stringResource as contextStringResource
import tachiyomi.presentation.core.util.collectAsState as prefCollectAsState

data class BrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val screenModel = rememberScreenModel { BrowseSourceScreenModel(sourceId, listingQuery) }
        val state by screenModel.state.collectAsState()

        // Yakuyomi：探索全域篩選（收藏/開卷/擷取）狀態 + sheet 顯示旗標。
        val favoriteFilter by screenModel.browseFilterFavorite.prefCollectAsState()
        val readFilter by screenModel.browseFilterRead.prefCollectAsState()
        val fetchedFilter by screenModel.browseFilterFetched.prefCollectAsState()
        var showGlobalFilter by remember { mutableStateOf(false) }

        // Yakuyomi：錨點 url（已設就在任何清單顯示旗標徽章，含快照）。
        val anchorUrlPref by screenModel.browseAnchor.prefCollectAsState()
        val anchorUrl = anchorUrlPref.takeIf { it.isNotEmpty() }

        // Yakuyomi：清單分頁項 + 捲動狀態（自動載入到錨點要驅動載入＋視覺捲動，故 hoist 到此）。
        val context = LocalContext.current
        val mangaList = screenModel.mangaPagerFlowFlow.collectAsLazyPagingItems()
        val gridState = rememberLazyGridState()
        val listState = rememberLazyListState()

        // Yakuyomi：快照（離線清單）狀態 + 自動載入到錨點 + 相關對話框旗標。
        val snapshotRaw by screenModel.browseSnapshot.prefCollectAsState()
        val snapshot = remember(snapshotRaw) { screenModel.readSnapshot() }
        val isSnapshotListing = state.listing is Listing.Snapshot
        var autoLoading by remember { mutableStateOf(false) }
        var showSnapshotOverwriteConfirm by remember { mutableStateOf(false) }
        var showSnapshotClearConfirm by remember { mutableStateOf(false) }
        var showSaveAfterLoadConfirm by remember { mutableStateOf(false) }
        var showLeaveSnapshotClearConfirm by remember { mutableStateOf(false) }

        val navigator = LocalNavigator.currentOrThrow
        val navigateUp: () -> Unit = {
            when {
                !state.isUserQuery && state.toolbarQuery != null -> screenModel.setToolbarQuery(null)
                else -> navigator.pop()
            }
        }

        if (screenModel.source is StubSource) {
            MissingSourceScreen(
                source = screenModel.source,
                navigateUp = navigateUp,
            )
            return
        }

        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }

        val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }
        val onWebViewClick = f@{
            val source = screenModel.source as? HttpSource ?: return@f
            navigator.push(
                WebViewScreen(
                    url = source.baseUrl,
                    initialTitle = source.name,
                    sourceId = source.id,
                ),
            )
        }

        LaunchedEffect(screenModel.source) {
            assistUrl = (screenModel.source as? HttpSource)?.baseUrl
        }

        // Yakuyomi：把目前已載入清單存成快照（覆蓋同 source 舊的），並提示存了幾本。
        val saveSnapshotNow: () -> Unit = {
            val urls = mangaList.itemSnapshotList.items.map { it.value.url }
            screenModel.saveSnapshot(urls)
            scope.launchIO {
                snackbarHostState.showSnackbar(
                    context.contextStringResource(MR.strings.snapshot_saved, urls.size),
                )
            }
        }

        // Yakuyomi：自動載入到錨點——持續觸發 append（受節流牽制）並捲動，直到錨點出現或到底/錯誤/上限。
        LaunchedEffect(autoLoading) {
            if (!autoLoading) return@LaunchedEffect
            val useGrid = screenModel.displayMode != LibraryDisplayMode.List
            try {
                var iterations = 0
                while (autoLoading && !screenModel.anchorReached.value && iterations < AUTO_LOAD_MAX_PAGES) {
                    val append = mangaList.loadState.append
                    if (append is LoadState.Error) break
                    if (append is LoadState.NotLoading && append.endOfPaginationReached) break
                    val target = (mangaList.itemCount - 1).coerceAtLeast(0)
                    if (useGrid) gridState.scrollToItem(target) else listState.scrollToItem(target)
                    iterations++
                    // 等這次 append 結束（節流 ≥1s/頁）
                    var waited = 0
                    while (autoLoading && mangaList.loadState.append is LoadState.Loading && waited < 100) {
                        kotlinx.coroutines.delay(100)
                        waited++
                    }
                    kotlinx.coroutines.delay(200)
                }
                if (screenModel.anchorReached.value) {
                    val idx = mangaList.itemSnapshotList.items.indexOfFirst { it.value.url == anchorUrlPref }
                    if (idx >= 0) {
                        if (useGrid) gridState.animateScrollToItem(idx) else listState.animateScrollToItem(idx)
                    }
                    showSaveAfterLoadConfirm = true
                }
            } finally {
                autoLoading = false
            }
        }

        // Yakuyomi：離開快照模式時，詢問是否清除快照。
        var prevSnapshotListing by remember { mutableStateOf(isSnapshotListing) }
        LaunchedEffect(isSnapshotListing) {
            if (prevSnapshotListing && !isSnapshotListing && screenModel.hasSnapshot()) {
                showLeaveSnapshotClearConfirm = true
            }
            prevSnapshotListing = isSnapshotListing
        }

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {},
                ) {
                    BrowseSourceToolbar(
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = screenModel::setToolbarQuery,
                        source = screenModel.source,
                        displayMode = screenModel.displayMode,
                        onDisplayModeChange = { screenModel.displayMode = it },
                        navigateUp = navigateUp,
                        onWebViewClick = onWebViewClick,
                        onHelpClick = onHelpClick,
                        onSettingsClick = { navigator.push(SourcePreferencesScreen(sourceId)) },
                        onSearch = screenModel::search,
                        // 錨點按鈕只在「最新」清單露出（追更新工作流）；依狀態切換：標記錨點 / 自動載入 / 停止。
                        onStopAutoLoad = if (autoLoading) {
                            { autoLoading = false }
                        } else {
                            null
                        },
                        onAutoLoadToAnchor = if (
                            !autoLoading && state.listing is Listing.Latest && anchorUrlPref.isNotEmpty()
                        ) {
                            { autoLoading = true }
                        } else {
                            null
                        },
                        onMarkFirstAsAnchor = if (
                            !autoLoading && state.listing is Listing.Latest && anchorUrlPref.isEmpty()
                        ) {
                            {
                                mangaList.itemSnapshotList.items.firstOrNull()?.let {
                                    screenModel.setAnchor(it.value)
                                }
                            }
                        } else {
                            null
                        },
                        onClearSnapshot = if (snapshot != null) {
                            { showSnapshotClearConfirm = true }
                        } else {
                            null
                        },
                    )

                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = MaterialTheme.padding.small),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        FilterChip(
                            selected = state.listing == Listing.Popular,
                            onClick = {
                                screenModel.resetFilters()
                                screenModel.setListing(Listing.Popular)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(FilterChipDefaults.IconSize),
                                )
                            },
                            label = {
                                Text(text = stringResource(MR.strings.popular))
                            },
                        )
                        if ((screenModel.source as CatalogueSource).supportsLatest) {
                            FilterChip(
                                selected = state.listing == Listing.Latest,
                                onClick = {
                                    screenModel.resetFilters()
                                    screenModel.setListing(Listing.Latest)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.NewReleases,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.latest))
                                },
                            )
                        }
                        // Yakuyomi：篩選（整合全域收藏/開卷/擷取三態 + 來源自帶 extension 篩選）。永遠顯示。
                        FilterChip(
                            selected = favoriteFilter != TriState.DISABLED ||
                                readFilter != TriState.DISABLED ||
                                fetchedFilter != TriState.DISABLED ||
                                state.listing is Listing.Search,
                            onClick = { showGlobalFilter = true },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.FilterList,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(FilterChipDefaults.IconSize),
                                )
                            },
                            label = {
                                Text(text = stringResource(MR.strings.action_filter))
                            },
                        )
                        // Yakuyomi：快照分頁項。永遠顯示（顯示本數）；點＝有快照切過去/無快照存當下、長按＝清除快照。
                        val snapCount = snapshot?.urls?.size ?: 0
                        FilterChip(
                            selected = isSnapshotListing,
                            onClick = {
                                // 沒快照→存當下清單並切過去；有快照→切到快照清單。清除走 overflow 選單。
                                if (snapshot == null) saveSnapshotNow()
                                screenModel.setListing(Listing.Snapshot)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(FilterChipDefaults.IconSize),
                                )
                            },
                            label = {
                                Text(text = stringResource(MR.strings.listing_snapshot) + " ($snapCount)")
                            },
                        )
                    }

                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { paddingValues ->
            BrowseSourceContent(
                source = screenModel.source,
                mangaList = mangaList,
                columns = screenModel.getColumnsPreference(),
                displayMode = screenModel.displayMode,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = onWebViewClick,
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = onHelpClick,
                anchorUrl = anchorUrl,
                gridState = gridState,
                listState = listState,
                hideLoadingFooter = isSnapshotListing,
                onMangaClick = { navigator.push((MangaScreen(it.id, true))) },
                onMangaLongClick = { manga ->
                    // Yakuyomi：長按 → 動作選單（加入/移除書庫 + 設/清錨點），取代原本「長按直接收藏」。
                    screenModel.setDialog(BrowseSourceScreenModel.Dialog.MangaActions(manga))
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
        }

        // 長按選單的「加入/移除書庫」實際動作（沿用原本 favorite/duplicate/remove 邏輯）。先關選單；favorite/duplicate 會再開各自對話框。
        val onToggleLibrary: (tachiyomi.domain.manga.model.Manga) -> Unit = { manga ->
            screenModel.setDialog(null)
            scope.launchIO {
                val duplicates = screenModel.getDuplicateLibraryManga(manga)
                when {
                    manga.favorite -> screenModel.setDialog(BrowseSourceScreenModel.Dialog.RemoveManga(manga))
                    duplicates.isNotEmpty() -> screenModel.setDialog(
                        BrowseSourceScreenModel.Dialog.AddDuplicateManga(manga, duplicates),
                    )
                    else -> screenModel.addFavorite(manga)
                }
            }
        }

        if (showGlobalFilter) {
            BrowseGlobalFilterDialog(
                favorite = favoriteFilter,
                read = readFilter,
                fetched = fetchedFilter,
                onToggleFavorite = screenModel::toggleGlobalFavoriteFilter,
                onToggleRead = screenModel::toggleGlobalReadFilter,
                onToggleFetched = screenModel::toggleGlobalFetchedFilter,
                onClear = screenModel::clearGlobalFilters,
                onDismissRequest = { showGlobalFilter = false },
                hasSourceFilters = state.filters.isNotEmpty(),
                onOpenSourceFilters = {
                    showGlobalFilter = false
                    screenModel.openFilterSheet()
                },
            )
        }

        // Yakuyomi：快照覆蓋確認。
        if (showSnapshotOverwriteConfirm) {
            AlertDialog(
                onDismissRequest = { showSnapshotOverwriteConfirm = false },
                title = { Text(text = stringResource(MR.strings.action_save_snapshot)) },
                text = { Text(text = stringResource(MR.strings.snapshot_overwrite_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showSnapshotOverwriteConfirm = false
                            saveSnapshotNow()
                        },
                    ) { Text(text = stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { showSnapshotOverwriteConfirm = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        // Yakuyomi：快照清除確認（從工具列「清除快照」觸發）。
        if (showSnapshotClearConfirm) {
            AlertDialog(
                onDismissRequest = { showSnapshotClearConfirm = false },
                title = { Text(text = stringResource(MR.strings.action_clear_snapshot)) },
                text = { Text(text = stringResource(MR.strings.snapshot_clear_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showSnapshotClearConfirm = false
                            // 若正看快照，先切回最新避免停在空清單。
                            if (isSnapshotListing) screenModel.setListing(Listing.Latest)
                            screenModel.clearSnapshot()
                        },
                    ) { Text(text = stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { showSnapshotClearConfirm = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        // Yakuyomi：離開快照模式後，詢問是否清除快照。
        if (showLeaveSnapshotClearConfirm) {
            AlertDialog(
                onDismissRequest = { showLeaveSnapshotClearConfirm = false },
                title = { Text(text = stringResource(MR.strings.action_clear_snapshot)) },
                text = { Text(text = stringResource(MR.strings.snapshot_clear_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showLeaveSnapshotClearConfirm = false
                            screenModel.clearSnapshot()
                        },
                    ) { Text(text = stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { showLeaveSnapshotClearConfirm = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        // Yakuyomi：自動載入到錨點完成後，詢問是否把目前清單存成快照。
        if (showSaveAfterLoadConfirm) {
            AlertDialog(
                onDismissRequest = { showSaveAfterLoadConfirm = false },
                title = { Text(text = stringResource(MR.strings.action_save_snapshot)) },
                text = {
                    Text(
                        text = stringResource(
                            MR.strings.save_snapshot_after_load_confirm,
                            mangaList.itemCount,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showSaveAfterLoadConfirm = false
                            if (screenModel.hasSnapshot()) {
                                showSnapshotOverwriteConfirm = true
                            } else {
                                saveSnapshotNow()
                            }
                        },
                    ) { Text(text = stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveAfterLoadConfirm = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseSourceScreenModel.Dialog.MangaActions -> {
                BrowseMangaActionsDialog(
                    favorite = dialog.manga.favorite,
                    isAnchor = dialog.manga.url == anchorUrlPref,
                    onToggleLibrary = {
                        onToggleLibrary(dialog.manga)
                    },
                    onToggleAnchor = {
                        screenModel.toggleAnchor(dialog.manga)
                        onDismissRequest()
                    },
                    onOpenManga = {
                        navigator.push(MangaScreen(dialog.manga.id, true))
                        onDismissRequest()
                    },
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceScreenModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = screenModel::resetFilters,
                    onFilter = { screenModel.search(filters = state.filters) },
                    onUpdate = screenModel::setFilters,
                )
            }
            is BrowseSourceScreenModel.Dialog.AddDuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { screenModel.setDialog(BrowseSourceScreenModel.Dialog.Migrate(dialog.manga, it)) },
                )
            }

            is BrowseSourceScreenModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceScreenModel.Dialog.RemoveManga -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.changeMangaFavorite(dialog.manga)
                    },
                    mangaToRemove = dialog.manga,
                )
            }
            is BrowseSourceScreenModel.Dialog.ChangeMangaCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.changeMangaFavorite(dialog.manga)
                        screenModel.moveMangaToCategories(dialog.manga, include)
                    },
                )
            }
            else -> {}
        }

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> screenModel.searchGenre(it.txt)
                        is SearchType.Text -> screenModel.search(it.txt)
                    }
                }
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()

        // Yakuyomi：自動載入到錨點的安全上限（避免來源無此錨點時無限翻頁）。
        private const val AUTO_LOAD_MAX_PAGES = 60
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}
