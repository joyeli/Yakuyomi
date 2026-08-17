package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseGlobalFilterDialog
import eu.kanade.presentation.browse.BrowseMangaActionsDialog
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.components.BrowseBallMenuItem
import eu.kanade.presentation.browse.components.BrowseListingKind
import eu.kanade.presentation.browse.components.BrowseSourceFloatingBall
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.BrowseSourceTopControlBar
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.Listing
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
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

        val viewModel = viewModel<BrowseSourceViewModel>(
            factory = BrowseSourceViewModel.Factory,
            extras = CreationExtras {
                set(BrowseSourceViewModel.SOURCE_ID_KEY, sourceId)
                set(BrowseSourceViewModel.LISTING_QUERY_KEY, listingQuery)
            },
        )
        val state by viewModel.state.collectAsState()

        // Yakuyomi：探索全域篩選（收藏/開卷/擷取）狀態 + sheet 顯示旗標。
        val favoriteFilter by viewModel.browseFilterFavorite.prefCollectAsState()
        val readFilter by viewModel.browseFilterRead.prefCollectAsState()
        val fetchedFilter by viewModel.browseFilterFetched.prefCollectAsState()
        var showGlobalFilter by remember { mutableStateOf(false) }

        // Yakuyomi：錨點 url（已設就在任何清單顯示旗標徽章，含快照）。
        val anchorUrlPref by viewModel.browseAnchor.prefCollectAsState()
        val anchorUrl = anchorUrlPref.takeIf { it.isNotEmpty() }
        // Yakuyomi：自動載入續傳頁碼（>0＝可續 → 按鈕顯示「繼續載入」引導）。
        val anchorResumePage by viewModel.browseAnchorResumePage.prefCollectAsState()
        // Yakuyomi：錨點被當前全域篩選濾掉、僅被強制留下 → 該本加區別視覺。
        val anchorFilteredOut by viewModel.anchorFilteredOut.collectAsState()

        // Yakuyomi：清單分頁項 + 捲動狀態（自動載入到錨點要驅動載入＋視覺捲動，故 hoist 到此）。
        val context = LocalContext.current
        val mangaList = viewModel.mangaPagerFlowFlow.collectAsLazyPagingItems()
        val gridState = rememberLazyGridState()
        val listState = rememberLazyListState()

        // Yakuyomi：探索批次擷取的全域狀態（背景單一槽）+ 完成結果（含失敗清單）。
        val fetchState by viewModel.browseFetchState.collectAsState()
        val fetchResult by viewModel.browseFetchResult.collectAsState()

        // Yakuyomi：快照（離線清單）狀態 + 自動載入到錨點（改為背景任務）+ 相關對話框旗標。
        val snapshotRaw by viewModel.browseSnapshot.prefCollectAsState()
        val snapshot = remember(snapshotRaw) { viewModel.readSnapshot() }
        val isSnapshotListing = state.listing is Listing.Snapshot
        // 自動載入到錨點＝背景 BrowseAnchorLoadManager（週期冷卻防 ban、可停、完成自動存快照）；autoLoading 由其狀態決定。
        val anchorLoadState by viewModel.anchorLoadState.collectAsState()
        val anchorLoadResult by viewModel.anchorLoadResult.collectAsState()
        val autoLoading = anchorLoadState.running
        var showSnapshotOverwriteConfirm by remember { mutableStateOf(false) }
        var showSnapshotClearConfirm by remember { mutableStateOf(false) }
        var showLeaveSnapshotClearConfirm by remember { mutableStateOf(false) }

        // Yakuyomi：浮動搜尋（探索版）——全局開關開時，頂部窄 bar + 右下球取代傳統工具列/chip/FAB。里程碑①骨架。
        val floatingBar by viewModel.floatingSearchBar.prefCollectAsState()
        var ballExpanded by remember { mutableStateOf(true) } // 初始展開（借鏡書庫），閒置後收成球
        var ballMenuOpen by remember { mutableStateOf(false) }
        var ballSearchFocused by remember { mutableStateOf(false) }
        // 只在使用者主動點球展開時聚焦搜尋框（借鏡書庫）——初始/自動展開不搶焦點、不彈鍵盤。
        var pendingSearchFocus by remember { mutableStateOf(false) }
        val ballFocusRequester = remember { FocusRequester() }
        val listingKind = when (state.listing) {
            is Listing.Latest -> BrowseListingKind.LATEST
            is Listing.Snapshot -> BrowseListingKind.SNAPSHOT
            is Listing.Search -> BrowseListingKind.SEARCH
            else -> BrowseListingKind.POPULAR
        }
        val snapshotCount = snapshot?.urls?.size ?: -1
        // 搜尋/快照＝頂部清單鈕顯示（並返回）「最後所在的列表清單」。此狀態存在 viewModel（state.lastListListing）
        // → 進漫畫再返回也保留，不像 Composable remember 會被重置成預設（曾是「返回後鈕變熱門」的 bug）。
        val preSearchListing = if (state.listing is Listing.Latest || state.lastListListing is Listing.Latest) {
            BrowseListingKind.LATEST
        } else {
            BrowseListingKind.POPULAR
        }
        // 搜尋/快照＝顯示（並返回）最後所在的列表清單（熱門/最新）；在列表清單則顯示自己。
        val topBarShownListing = when (listingKind) {
            BrowseListingKind.POPULAR, BrowseListingKind.LATEST -> listingKind
            else -> preSearchListing
        }
        val listingHighlighted = !isSnapshotListing && listingKind != BrowseListingKind.SEARCH
        // 背景任務（自動載入到錨點 / 批次擷取詳情）跑時：不自動收合 + 收合球顯示進度＋停止。
        val bgJobRunning = fetchState.running || autoLoading
        val bgJobProgress = when {
            fetchState.running -> "${fetchState.done}/${fetchState.total}"
            autoLoading -> context.contextStringResource(
                MR.strings.browse_anchor_load_progress,
                anchorLoadState.page,
                anchorLoadState.loaded,
            )
            else -> ""
        }
        val onStopBgJob: () -> Unit = {
            if (fetchState.running) viewModel.cancelBatchFetch() else viewModel.cancelAnchorLoad()
        }
        val bgJobRunningState = rememberUpdatedState(bgJobRunning)
        // 使用者主動點球展開時才聚焦搜尋框（非快照）；捲動書目時收回球（讓書目全螢幕）。
        LaunchedEffect(ballExpanded) {
            if (ballExpanded && pendingSearchFocus && !isSnapshotListing) {
                runCatching { ballFocusRequester.requestFocus() }
                pendingSearchFocus = false
            }
        }
        // 閒置 3 秒收合成球（借鏡書庫）——搜尋框有字/有焦點/球選單或任何對話框開著/背景任務跑時不收（維持展開）。
        LaunchedEffect(
            ballExpanded,
            state.toolbarQuery,
            ballSearchFocused,
            ballMenuOpen,
            showGlobalFilter,
            showSnapshotClearConfirm,
            showLeaveSnapshotClearConfirm,
            state.dialog,
            bgJobRunning,
        ) {
            if (floatingBar && ballExpanded && state.toolbarQuery.isNullOrEmpty() &&
                !ballSearchFocused && !ballMenuOpen && !showGlobalFilter &&
                !showSnapshotClearConfirm && !showLeaveSnapshotClearConfirm &&
                state.dialog == null && !bgJobRunning
            ) {
                delay(3_000)
                ballExpanded = false
            }
        }
        val ballCollapseOnScroll = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    // 背景任務跑時不因捲動收合（維持展開顯示進度）。
                    if (available.y != 0f && !bgJobRunningState.value) ballExpanded = false
                    return Offset.Zero
                }
            }
        }

        val navigator = LocalNavigator.currentOrThrow
        val navigateUp: () -> Unit = {
            when {
                !state.isUserQuery && state.toolbarQuery != null -> viewModel.setToolbarQuery(null)
                else -> navigator.pop()
            }
        }

        if (viewModel.source is StubSource) {
            MissingSourceScreen(
                source = viewModel.source,
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
            val source = viewModel.source as? HttpSource ?: return@f
            navigator.push(
                WebViewScreen(
                    url = source.getHomeUrl(),
                    initialTitle = source.name,
                    sourceId = source.id,
                ),
            )
        }

        LaunchedEffect(viewModel.source) {
            assistUrl = (viewModel.source as? HttpSource)?.getHomeUrl()
        }

        // Yakuyomi：把目前已載入清單存成快照（覆蓋同 source 舊的），並提示存了幾本。
        val saveSnapshotNow: () -> Unit = {
            val urls = mangaList.itemSnapshotList.items.map { it.value.url }
            viewModel.saveSnapshot(urls)
            scope.launchIO {
                snackbarHostState.showSnackbar(
                    context.contextStringResource(MR.strings.snapshot_saved, urls.size),
                )
            }
        }

        // Yakuyomi：背景自動載入到錨點完成 → toast 回饋（載了幾本 / 有無找到錨點；快照已由 manager 存好），並 consume。
        LaunchedEffect(anchorLoadResult) {
            val r = anchorLoadResult ?: return@LaunchedEffect
            if (r.sourceId == sourceId) {
                context.toast(
                    when {
                        r.found -> context.contextStringResource(MR.strings.browse_anchor_load_complete_found, r.loaded)
                        r.done -> context.contextStringResource(MR.strings.browse_anchor_load_complete_end, r.loaded)
                        else -> context.contextStringResource(MR.strings.browse_anchor_load_paused, r.loaded)
                    },
                )
            }
            viewModel.consumeAnchorLoadResult()
        }

        // Yakuyomi：離開快照模式時，詢問是否清除快照。
        var prevSnapshotListing by remember { mutableStateOf(isSnapshotListing) }
        LaunchedEffect(isSnapshotListing) {
            if (prevSnapshotListing && !isSnapshotListing && viewModel.hasSnapshot()) {
                showLeaveSnapshotClearConfirm = true
            }
            prevSnapshotListing = isSnapshotListing
        }

        // Yakuyomi：背景擷取完成且有失敗 → 只在「對應來源」的畫面推「失敗清單」（逐一檢查），並消費結果。
        // 背景單一全域槽 → 結果帶 sourceId，非本來源不彈（背景跑時你可能在別的來源畫面）。
        LaunchedEffect(fetchResult) {
            val result = fetchResult
            if (result != null && result.sourceId == viewModel.source.id) {
                navigator.push(SourceFetchResultsScreen(result.failedIds))
                viewModel.consumeFetchResult()
            }
        }

        Scaffold(
            modifier = if (floatingBar) Modifier.nestedScroll(ballCollapseOnScroll) else Modifier,
            topBar = {
                if (floatingBar) {
                    BrowseSourceTopControlBar(
                        sourceName = viewModel.source.name,
                        navigateUp = navigateUp,
                        supportsLatest = viewModel.source.supportsLatest,
                        shownListing = topBarShownListing,
                        listingHighlighted = listingHighlighted,
                        onSelectPopular = {
                            viewModel.resetFilters()
                            viewModel.setListing(Listing.Popular)
                        },
                        onSelectLatest = {
                            viewModel.resetFilters()
                            viewModel.setListing(Listing.Latest)
                        },
                        snapshotCount = snapshotCount,
                        snapshotSelected = isSnapshotListing,
                        onSnapshotClick = {
                            if (snapshot == null) saveSnapshotNow()
                            viewModel.setListing(Listing.Snapshot)
                        },
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .pointerInput(Unit) {},
                    ) {
                        BrowseSourceToolbar(
                            searchQuery = state.toolbarQuery,
                            onSearchQueryChange = viewModel::setToolbarQuery,
                            source = viewModel.source,
                            displayMode = viewModel.displayMode,
                            onDisplayModeChange = { viewModel.displayMode = it },
                            navigateUp = navigateUp,
                            onWebViewClick = onWebViewClick,
                            onHelpClick = onHelpClick,
                            onSettingsClick = { navigator.push(SourcePreferencesScreen(sourceId)) },
                            onSearch = viewModel::search,
                            // 錨點的 start/stop/mark 移到右下 FAB（比照擷取詳情：進度可見、就地可停；
                            // 最新＋有錨點→載入到錨點，其餘→直接存當前頁快照）。設錨點改由長按書本「設為錨點」。
                            onStopAutoLoad = null,
                            onAutoLoadToAnchor = null,
                            onMarkFirstAsAnchor = null,
                            onClearSnapshot = if (snapshot != null) {
                                { showSnapshotClearConfirm = true }
                            } else {
                                null
                            },
                            // 滑到錨點鈕移除：修剪後錨點一定是快照最後一筆，往下滑到底＝錨點，不需專用鈕。
                            onScrollToAnchor = null,
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
                                    viewModel.resetFilters()
                                    viewModel.setListing(Listing.Popular)
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
                            if (viewModel.source.supportsLatest) {
                                FilterChip(
                                    selected = state.listing == Listing.Latest,
                                    onClick = {
                                        viewModel.resetFilters()
                                        viewModel.setListing(Listing.Latest)
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
                                    viewModel.setListing(Listing.Snapshot)
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
                }
            },
            bottomBar = {
                if (floatingBar) {
                    // 錨點狀態機（行內鈕與 ⋮ 選單共用，避免分歧；對齊傳統模式 FAB）：
                    // 載入中→停止（任何清單，A2）；最新且有錨點→開始/繼續載入到錨點；
                    // 其餘（無錨點/熱門/搜尋）→直接存當前頁快照（設錨點改由長按書本「設為錨點」）。
                    val anchorIcon: ImageVector?
                    val anchorLabel: String
                    val onAnchor: (() -> Unit)?
                    when {
                        autoLoading -> {
                            anchorIcon = Icons.Filled.Stop
                            anchorLabel = context.contextStringResource(MR.strings.action_stop_auto_load)
                            onAnchor = { viewModel.cancelAnchorLoad() }
                        }
                        state.listing is Listing.Latest && anchorUrlPref.isNotEmpty() -> {
                            anchorIcon = Icons.Outlined.PlayArrow
                            anchorLabel = context.contextStringResource(
                                if (anchorResumePage > 0) {
                                    MR.strings.action_continue_auto_load
                                } else {
                                    MR.strings.action_auto_load_to_anchor
                                },
                            )
                            onAnchor = {
                                val started = viewModel.startAnchorLoad()
                                context.toast(
                                    if (started) {
                                        MR.strings.browse_anchor_load_started
                                    } else {
                                        MR.strings.browse_fetch_busy
                                    },
                                )
                            }
                        }
                        else -> {
                            anchorIcon = Icons.Outlined.History
                            anchorLabel = context.contextStringResource(MR.strings.action_save_snapshot)
                            onAnchor = { saveSnapshotNow() }
                        }
                    }
                    // 擷取詳情動作（點＝開始/停止）：快照展開列的行內鈕與長壓選單共用。
                    val fetchDetailsAction: () -> Unit = {
                        val list = mangaList.itemSnapshotList.items.map { it.value }
                        when {
                            fetchState.running -> viewModel.cancelBatchFetch()
                            list.isEmpty() -> context.toast(MR.strings.fetch_details_empty)
                            !viewModel.startBatchFetch(list) -> context.toast(MR.strings.browse_fetch_busy)
                        }
                    }
                    // 快照左側標題：閒置「快照 · N 本」、擷取中「擷取中 X/N」。
                    val snapshotLeftText = if (fetchState.running) {
                        context.contextStringResource(MR.strings.browse_fetching, fetchState.done, fetchState.total)
                    } else {
                        context.contextStringResource(
                            MR.strings.browse_snapshot_bar_title,
                            snapshotCount.coerceAtLeast(0),
                        )
                    }
                    // 動作項（⋮ tap 與長壓共用的中段）：顯示模式 / 網頁 / 設定 / 說明 / 清除快照。
                    val actionItems = buildList {
                        add(
                            BrowseBallMenuItem(
                                label = context.contextStringResource(MR.strings.action_display_mode),
                                onClick = {
                                    viewModel.displayMode = when (viewModel.displayMode) {
                                        LibraryDisplayMode.ComfortableGrid -> LibraryDisplayMode.CompactGrid
                                        LibraryDisplayMode.CompactGrid -> LibraryDisplayMode.List
                                        else -> LibraryDisplayMode.ComfortableGrid
                                    }
                                },
                            ),
                        )
                        if (viewModel.source is HttpSource) {
                            add(
                                BrowseBallMenuItem(
                                    label = context.contextStringResource(MR.strings.action_open_in_web_view),
                                    onClick = onWebViewClick,
                                ),
                            )
                        }
                        if (viewModel.source is ConfigurableSource) {
                            add(
                                BrowseBallMenuItem(
                                    label = context.contextStringResource(MR.strings.action_settings),
                                    onClick = { navigator.push(SourcePreferencesScreen(sourceId)) },
                                ),
                            )
                        }
                        if (viewModel.source is LocalSource) {
                            add(
                                BrowseBallMenuItem(
                                    label = context.contextStringResource(MR.strings.label_help),
                                    onClick = onHelpClick,
                                ),
                            )
                        }
                        if (snapshot != null) {
                            add(
                                BrowseBallMenuItem(
                                    label = context.contextStringResource(MR.strings.action_clear_snapshot),
                                    onClick = { showSnapshotClearConfirm = true },
                                ),
                            )
                        }
                    }
                    // ⋮（展開點三點）＝只有動作項（篩選/錨點/滑到錨點都是行內圖示，不重複）。
                    val tapMenuItems = actionItems
                    // 長壓（收合球，快捷用）＝非常駐（動作項）置頂 ＋ 常駐靠底（收合時無行內圖示可點，篩選置最底最好按）。
                    val longPressMenuItems = buildList {
                        addAll(actionItems)
                        val persistents = buildList {
                            if (isSnapshotListing) {
                                add(
                                    BrowseBallMenuItem(
                                        label = context.contextStringResource(
                                            if (fetchState.running) {
                                                MR.strings.action_cancel
                                            } else {
                                                MR.strings.action_fetch_details
                                            },
                                        ),
                                        icon = if (fetchState.running) {
                                            Icons.Filled.Stop
                                        } else {
                                            Icons.Outlined.CloudDownload
                                        },
                                        onClick = fetchDetailsAction,
                                    ),
                                )
                            } else {
                                onAnchor?.let {
                                    add(BrowseBallMenuItem(label = anchorLabel, icon = anchorIcon, onClick = it))
                                }
                            }
                            // 篩選一律置最底（最靠球/拇指）。
                            add(
                                BrowseBallMenuItem(
                                    label = context.contextStringResource(MR.strings.action_filter),
                                    icon = Icons.Outlined.FilterList,
                                    onClick = { showGlobalFilter = true },
                                ),
                            )
                        }
                        persistents.forEachIndexed { i, item ->
                            add(if (i == 0) item.copy(dividerBefore = true) else item)
                        }
                    }
                    BrowseSourceFloatingBall(
                        expanded = ballExpanded,
                        onBallClick = {
                            ballExpanded = true
                            pendingSearchFocus = true
                        },
                        focusRequester = ballFocusRequester,
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = viewModel::setToolbarQuery,
                        onSubmitSearch = viewModel::search,
                        onClearSearch = {
                            // 清除＝清搜尋框並離開搜尋結果，回到搜尋前所在的列表清單（Search 當隱藏頁）。
                            viewModel.setToolbarQuery(null)
                            if (state.listing is Listing.Search) {
                                viewModel.setListing(
                                    when (preSearchListing) {
                                        BrowseListingKind.LATEST -> Listing.Latest
                                        else -> Listing.Popular
                                    },
                                )
                            }
                        },
                        hasActiveFilters = favoriteFilter != TriState.DISABLED ||
                            readFilter != TriState.DISABLED ||
                            fetchedFilter != TriState.DISABLED ||
                            state.listing is Listing.Search,
                        onClickFilter = { showGlobalFilter = true },
                        isSnapshot = isSnapshotListing,
                        onFetchDetails = fetchDetailsAction,
                        fetchRunning = fetchState.running,
                        snapshotLeftText = snapshotLeftText,
                        anchorInline = onAnchor,
                        anchorInlineIcon = anchorIcon,
                        anchorInlineDesc = anchorLabel,
                        bgJobRunning = bgJobRunning,
                        bgJobProgress = bgJobProgress,
                        onStopBgJob = onStopBgJob,
                        tapMenuItems = tapMenuItems,
                        longPressMenuItems = longPressMenuItems,
                        menuExpanded = ballMenuOpen,
                        onMenuExpandedChange = { ballMenuOpen = it },
                        onSearchFocusChanged = { ballSearchFocused = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            floatingActionButton = {
                // Yakuyomi：傳統模式（浮動搜尋關）的單一 FAB（比照擷取詳情：進度可見、就地可停）。優先序：
                // ① 自動載入到錨點跑中＝已載入本數＋停止（任何清單都露出）。
                // ② 快照清單＝「擷取詳情」FAB（凍結有界清單、擷取有明確終點；進行中 X/N＋停止）。
                // ③ 最新＋已設錨點＝「載入到錨點」起始（跑起來後轉①）。
                // ④ 其餘（無錨點/熱門/搜尋）＝「存快照」＝直接把當前頁載入的清單存成快照。
                if (!floatingBar) {
                    val autoLoadingThisSource =
                        anchorLoadState.running && anchorLoadState.sourceId == sourceId
                    when {
                        autoLoadingThisSource -> {
                            SmallExtendedFloatingActionButton(
                                text = {
                                    Text(
                                        text = stringResource(
                                            MR.strings.browse_anchor_load_progress,
                                            anchorLoadState.page,
                                            anchorLoadState.loaded,
                                        ),
                                    )
                                },
                                icon = { Icon(Icons.Filled.Stop, contentDescription = null) },
                                onClick = { viewModel.cancelAnchorLoad() },
                            )
                        }
                        isSnapshotListing -> {
                            // 背景單一全域槽：Running 時按鈕變身成「進度＋中止」（顯示的是背景那份、非當前 filter），
                            // 所以按不到第二次送出＝天然防多重送；按下＝中止背景那份（不論哪個來源在跑）。
                            val running = fetchState.running
                            SmallExtendedFloatingActionButton(
                                text = {
                                    Text(
                                        text = if (running) {
                                            "${fetchState.done}/${fetchState.total}"
                                        } else {
                                            stringResource(MR.strings.action_fetch_details)
                                        },
                                    )
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (running) {
                                            Icons.Filled.Stop
                                        } else {
                                            Icons.Outlined.CloudDownload
                                        },
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    if (running) {
                                        viewModel.cancelBatchFetch()
                                    } else {
                                        val list = mangaList.itemSnapshotList.items.map { it.value }
                                        if (list.isEmpty()) {
                                            scope.launchIO {
                                                snackbarHostState.showSnackbar(
                                                    context.contextStringResource(MR.strings.fetch_details_empty),
                                                )
                                            }
                                        } else if (!viewModel.startBatchFetch(list)) {
                                            // 後端忙線硬拒（理論上 UI 已擋，作後援）。
                                            scope.launchIO {
                                                snackbarHostState.showSnackbar(
                                                    context.contextStringResource(MR.strings.browse_fetch_busy),
                                                )
                                            }
                                        }
                                    }
                                },
                            )
                        }
                        state.listing is Listing.Latest && anchorUrlPref.isNotEmpty() -> {
                            SmallExtendedFloatingActionButton(
                                text = {
                                    Text(
                                        text = if (anchorResumePage > 0) {
                                            stringResource(MR.strings.action_continue_auto_load)
                                        } else {
                                            stringResource(MR.strings.action_auto_load_to_anchor)
                                        },
                                    )
                                },
                                icon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                                onClick = {
                                    val started = viewModel.startAnchorLoad()
                                    context.toast(
                                        if (started) {
                                            MR.strings.browse_anchor_load_started
                                        } else {
                                            MR.strings.browse_fetch_busy
                                        },
                                    )
                                },
                            )
                        }
                        else -> {
                            // 無錨點 / 熱門 / 搜尋：直接把當前頁載入的清單存成快照。
                            SmallExtendedFloatingActionButton(
                                text = { Text(stringResource(MR.strings.action_save_snapshot)) },
                                icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                                onClick = saveSnapshotNow,
                            )
                        }
                    }
                }
            },
        ) { paddingValues ->
            BrowseSourceContent(
                source = viewModel.source,
                mangaList = mangaList,
                columns = viewModel.getColumnsPreference(),
                displayMode = viewModel.displayMode,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = onWebViewClick,
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = onHelpClick,
                anchorUrl = anchorUrl,
                anchorFilteredOut = anchorFilteredOut,
                gridState = gridState,
                listState = listState,
                hideLoadingFooter = isSnapshotListing,
                onMangaClick = { navigator.push((MangaScreen(it.id, true))) },
                onMangaLongClick = { manga ->
                    // Yakuyomi：長按 → 動作選單（加入/移除書庫 + 設/清錨點），取代原本「長按直接收藏」。
                    viewModel.setDialog(BrowseSourceViewModel.Dialog.MangaActions(manga))
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
        }

        // 長按選單的「加入/移除書庫」實際動作（沿用原本 favorite/duplicate/remove 邏輯）。先關選單；favorite/duplicate 會再開各自對話框。
        val onToggleLibrary: (tachiyomi.domain.manga.model.Manga) -> Unit = { manga ->
            viewModel.setDialog(null)
            scope.launchIO {
                val duplicates = viewModel.getDuplicateLibraryManga(manga)
                when {
                    manga.favorite -> viewModel.setDialog(BrowseSourceViewModel.Dialog.RemoveManga(manga))
                    duplicates.isNotEmpty() -> viewModel.setDialog(
                        BrowseSourceViewModel.Dialog.AddDuplicateManga(manga, duplicates),
                    )
                    else -> viewModel.addFavorite(manga)
                }
            }
        }

        if (showGlobalFilter) {
            BrowseGlobalFilterDialog(
                favorite = favoriteFilter,
                read = readFilter,
                fetched = fetchedFilter,
                onToggleFavorite = viewModel::toggleGlobalFavoriteFilter,
                onToggleRead = viewModel::toggleGlobalReadFilter,
                onToggleFetched = viewModel::toggleGlobalFetchedFilter,
                onClear = viewModel::clearGlobalFilters,
                onDismissRequest = { showGlobalFilter = false },
                hasSourceFilters = state.filters.isNotEmpty(),
                onOpenSourceFilters = {
                    showGlobalFilter = false
                    viewModel.openFilterSheet()
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
                            if (isSnapshotListing) viewModel.setListing(Listing.Latest)
                            viewModel.clearSnapshot()
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
                            viewModel.clearSnapshot()
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

        val onDismissRequest = { viewModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseSourceViewModel.Dialog.MangaActions -> {
                BrowseMangaActionsDialog(
                    favorite = dialog.manga.favorite,
                    isAnchor = dialog.manga.url == anchorUrlPref,
                    onToggleLibrary = {
                        onToggleLibrary(dialog.manga)
                    },
                    onToggleAnchor = {
                        viewModel.toggleAnchor(dialog.manga)
                        onDismissRequest()
                    },
                    onOpenManga = {
                        navigator.push(MangaScreen(dialog.manga.id, true))
                        onDismissRequest()
                    },
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceViewModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = viewModel::resetFilters,
                    onFilter = { viewModel.search(filters = state.filters) },
                    onUpdate = viewModel::setFilters,
                )
            }
            is BrowseSourceViewModel.Dialog.AddDuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { viewModel.setDialog(BrowseSourceViewModel.Dialog.Migrate(dialog.manga, it)) },
                )
            }

            is BrowseSourceViewModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceViewModel.Dialog.RemoveManga -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        viewModel.changeMangaFavorite(dialog.manga)
                    },
                    mangaToRemove = dialog.manga,
                )
            }
            is BrowseSourceViewModel.Dialog.ChangeMangaCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        viewModel.changeMangaFavorite(dialog.manga)
                        viewModel.moveMangaToCategories(dialog.manga, include)
                    },
                )
            }
            else -> {}
        }

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> viewModel.searchGenre(it.txt)
                        is SearchType.Text -> viewModel.search(it.txt)
                    }
                }
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}
