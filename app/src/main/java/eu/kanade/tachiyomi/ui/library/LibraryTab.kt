package eu.kanade.tachiyomi.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.library.DeleteLibraryMangaDialog
import eu.kanade.presentation.library.LibrarySavedSearchesDialog
import eu.kanade.presentation.library.LibrarySettingsDialog
import eu.kanade.presentation.library.components.FloatingSearchBar
import eu.kanade.presentation.library.components.LibraryContent
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.manga.components.LibraryBottomActionMenu
import eu.kanade.presentation.more.onboarding.GETTING_STARTED_URL
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.feature.migration.config.MigrationConfigScreen
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.isLocal

data object LibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            return TabOptions(
                index = 0u,
                title = stringResource(MR.strings.label_library),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        requestOpenSettingsSheet()
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current

        val screenModel = rememberScreenModel { LibraryScreenModel() }
        val settingsScreenModel = rememberScreenModel { LibrarySettingsScreenModel() }
        val state by screenModel.state.collectAsState()

        // Yakuyomi：網格封面最小寬度（dp），欄數依此自適應。
        val coverMinWidth by screenModel.getCoverMinWidth()

        val snackbarHostState = remember { SnackbarHostState() }

        val onClickRefresh: (Category?) -> Boolean = { category ->
            val started = LibraryUpdateJob.startNow(context, category)
            scope.launch {
                val msgRes = when {
                    !started -> MR.strings.update_already_running
                    category != null -> MR.strings.updating_category
                    else -> MR.strings.updating_library
                }
                snackbarHostState.showSnackbar(context.stringResource(msgRes))
            }
            started
        }

        // Yakuyomi：頂部與底部浮動列共用的「隨機開啟一本」動作。
        val onClickOpenRandomManga: () -> Unit = {
            scope.launch {
                val randomItem = screenModel.getRandomLibraryItemForCurrentCategory()
                if (randomItem != null) {
                    navigator.push(MangaScreen(randomItem.libraryManga.manga.id))
                } else {
                    snackbarHostState.showSnackbar(
                        context.stringResource(MR.strings.information_no_entries_found),
                    )
                }
            }
            Unit
        }

        // Yakuyomi：浮動搜尋列展開/收合——初始展開、閒置 3 秒或捲動時收成右下小球、點球展開並聚焦。
        var searchBarExpanded by remember { mutableStateOf(true) }
        var pendingSearchFocus by remember { mutableStateOf(false) }
        val searchFocusRequester = remember { FocusRequester() }
        // Yakuyomi：三點選單是否開著（從 FloatingSearchBar 提升）。
        var overflowMenuOpen by remember { mutableStateOf(false) }
        // 閒置 3 秒收合——但搜尋字非空、篩選/已存搜尋對話框開著、或三點選單開著時皆不收（維持展開）。
        LaunchedEffect(searchBarExpanded, state.searchQuery, state.floatingSearchBar, state.dialog, overflowMenuOpen) {
            if (state.floatingSearchBar && searchBarExpanded && state.searchQuery.isNullOrEmpty() &&
                state.dialog == null && !overflowMenuOpen
            ) {
                delay(3_000)
                searchBarExpanded = false
            }
        }
        LaunchedEffect(searchBarExpanded) {
            if (searchBarExpanded && pendingSearchFocus) {
                searchFocusRequester.requestFocus()
                pendingSearchFocus = false
            }
        }
        // 捲動書庫時收成球（讓位看書）；搜尋字保留，球可再展開。
        val collapseOnScroll = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y != 0f) searchBarExpanded = false
                    return Offset.Zero
                }
            }
        }

        Scaffold(
            modifier = Modifier.nestedScroll(collapseOnScroll),
            topBar = { scrollBehavior ->
                // Yakuyomi：浮動搜尋列開啟（非選取模式）→ 整條頂部工具列隱藏、書目全螢幕；
                // 只留狀態列高度避免內容被狀態列遮。搜尋/篩選/overflow 全移到底部浮動列。
                if (state.floatingSearchBar && !state.selectionMode) {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                } else {
                    val title = state.getToolbarTitle(
                        defaultTitle = stringResource(MR.strings.label_library),
                        defaultCategoryTitle = stringResource(MR.strings.label_default),
                        page = state.coercedActiveCategoryIndex,
                    )
                    LibraryToolbar(
                        hasActiveFilters = state.hasActiveFilters,
                        selectedCount = state.selection.size,
                        title = title,
                        onClickUnselectAll = screenModel::clearSelection,
                        onClickSelectAll = screenModel::selectAll,
                        onClickInvertSelection = screenModel::invertSelection,
                        onClickFilter = screenModel::showSettingsDialog,
                        onClickRefresh = { onClickRefresh(state.activeCategory) },
                        onClickGlobalUpdate = { onClickRefresh(null) },
                        onClickOpenRandomManga = onClickOpenRandomManga,
                        onClickSavedSearches = screenModel::openSavedSearchesDialog,
                        searchQuery = state.searchQuery,
                        onSearchQueryChange = screenModel::search,
                        // For scroll overlay when no tab
                        scrollBehavior = scrollBehavior.takeIf { !state.showCategoryTabs },
                    )
                }
            },
            bottomBar = {
                // Yakuyomi：浮動搜尋列放 Scaffold bottomBar——content 自動扣其高度（不擋最後一排書），
                // bottomBar 不自動避鍵盤故自己加 ime∪navbar（取較大者：鍵盤開=ime、關=navbar，不雙倍位移）。
                if (state.floatingSearchBar && !state.selectionMode) {
                    FloatingSearchBar(
                        expanded = searchBarExpanded,
                        onBallClick = {
                            searchBarExpanded = true
                            pendingSearchFocus = true
                        },
                        focusRequester = searchFocusRequester,
                        searchQuery = state.searchQuery,
                        onSearchQueryChange = screenModel::search,
                        hasActiveFilters = state.hasActiveFilters,
                        onClickFilter = screenModel::showSettingsDialog,
                        onClickGlobalUpdate = { onClickRefresh(null) },
                        onClickRefresh = { onClickRefresh(state.activeCategory) },
                        onClickOpenRandomManga = onClickOpenRandomManga,
                        onClickSavedSearches = screenModel::openSavedSearchesDialog,
                        menuExpanded = overflowMenuOpen,
                        onMenuExpandedChange = { overflowMenuOpen = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                } else {
                    LibraryBottomActionMenu(
                        visible = state.selectionMode,
                        onChangeCategoryClicked = screenModel::openChangeCategoryDialog,
                        onMarkAsReadClicked = { screenModel.markReadSelection(true) },
                        onMarkAsUnreadClicked = { screenModel.markReadSelection(false) },
                        onDownloadClicked = screenModel::performDownloadAction
                            .takeIf { state.selectedManga.fastAll { !it.isLocal() } },
                        onDeleteClicked = screenModel::openDeleteMangaDialog,
                        onMigrateClicked = {
                            val selection = state.selection
                            screenModel.clearSelection()
                            navigator.push(MigrationConfigScreen(selection))
                        },
                    )
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            when {
                state.isLoading -> {
                    LoadingScreen(Modifier.padding(contentPadding))
                }
                state.searchQuery.isNullOrEmpty() && !state.hasActiveFilters && state.isLibraryEmpty -> {
                    val handler = LocalUriHandler.current
                    EmptyScreen(
                        stringRes = MR.strings.information_empty_library,
                        modifier = Modifier.padding(contentPadding),
                        actions = listOf(
                            EmptyScreenAction(
                                stringRes = MR.strings.getting_started_guide,
                                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                                onClick = { handler.openUri(GETTING_STARTED_URL) },
                            ),
                        ),
                    )
                }
                else -> {
                    LibraryContent(
                        categories = state.displayedCategories,
                        searchQuery = state.searchQuery,
                        selection = state.selection,
                        contentPadding = contentPadding,
                        currentPage = state.coercedActiveCategoryIndex,
                        hasActiveFilters = state.hasActiveFilters,
                        showPageTabs = state.showCategoryTabs || !state.searchQuery.isNullOrEmpty(),
                        onChangeCurrentPage = screenModel::updateActiveCategoryIndex,
                        onClickManga = { navigator.push(MangaScreen(it)) },
                        onContinueReadingClicked = { it: LibraryManga ->
                            scope.launchIO {
                                val chapter = screenModel.getNextUnreadChapter(it.manga)
                                if (chapter != null) {
                                    context.startActivity(
                                        ReaderActivity.newIntent(context, chapter.mangaId, chapter.id),
                                    )
                                } else {
                                    snackbarHostState.showSnackbar(
                                        context.stringResource(MR.strings.no_next_chapter),
                                    )
                                }
                            }
                            Unit
                        }.takeIf { state.showMangaContinueButton },
                        onToggleSelection = screenModel::toggleSelection,
                        onToggleRangeSelection = { category, manga ->
                            screenModel.toggleRangeSelection(category, manga)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onRefresh = { onClickRefresh(state.activeCategory) },
                        onGlobalSearchClicked = {
                            navigator.push(GlobalSearchScreen(screenModel.state.value.searchQuery ?: ""))
                        },
                        getItemCountForCategory = { state.getItemCountForCategory(it) },
                        getDisplayMode = { screenModel.getDisplayMode() },
                        coverMinWidth = coverMinWidth,
                        getItemsForCategory = { state.getItemsForCategory(it) },
                    )
                }
            }
        }

        val onDismissRequest = screenModel::closeDialog
        when (val dialog = state.dialog) {
            is LibraryScreenModel.Dialog.SettingsSheet -> run {
                LibrarySettingsDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    category = state.activeCategory,
                )
            }
            is LibraryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = {
                        screenModel.clearSelection()
                        navigator.push(CategoryScreen())
                    },
                    onConfirm = { include, exclude ->
                        screenModel.clearSelection()
                        screenModel.setMangaCategories(dialog.manga, include, exclude)
                    },
                )
            }
            is LibraryScreenModel.Dialog.DeleteManga -> {
                DeleteLibraryMangaDialog(
                    containsLocalManga = dialog.manga.any(Manga::isLocal),
                    onDismissRequest = onDismissRequest,
                    onConfirm = { deleteManga, deleteChapter ->
                        screenModel.removeMangas(dialog.manga, deleteManga, deleteChapter)
                        screenModel.clearSelection()
                    },
                )
            }
            is LibraryScreenModel.Dialog.SavedSearches -> {
                val savedSearches by screenModel.savedSearches.collectAsState()
                LibrarySavedSearchesDialog(
                    onDismissRequest = onDismissRequest,
                    savedSearches = savedSearches,
                    currentQuery = state.searchQuery,
                    onLoad = screenModel::search,
                    onSave = screenModel::saveCurrentSearch,
                    onDelete = screenModel::deleteSavedSearch,
                )
            }
            null -> {}
        }

        BackHandler(enabled = state.selectionMode || state.searchQuery != null) {
            when {
                state.selectionMode -> screenModel.clearSelection()
                state.searchQuery != null -> screenModel.search(null)
            }
        }

        LaunchedEffect(state.selectionMode, state.dialog) {
            HomeScreen.showBottomNav(!state.selectionMode)
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            launch { queryEvent.receiveAsFlow().collect(screenModel::search) }
            launch { requestSettingsSheetEvent.receiveAsFlow().collectLatest { screenModel.showSettingsDialog() } }
        }
    }

    // For invoking search from other screen
    private val queryEvent = Channel<String>()
    suspend fun search(query: String) = queryEvent.send(query)

    // For opening settings sheet in LibraryController
    private val requestSettingsSheetEvent = Channel<Unit>()
    private suspend fun requestOpenSettingsSheet() = requestSettingsSheetEvent.send(Unit)
}
