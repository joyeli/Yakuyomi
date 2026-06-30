package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.material.PullRefresh
import kotlin.time.Duration.Companion.seconds

@Composable
fun LibraryContent(
    categories: List<Category>,
    searchQuery: String?,
    selection: Set<Long>,
    contentPadding: PaddingValues,
    currentPage: Int,
    hasActiveFilters: Boolean,
    showPageTabs: Boolean,
    onChangeCurrentPage: (Int) -> Unit,
    onClickManga: (Long) -> Unit,
    onContinueReadingClicked: ((LibraryManga) -> Unit)?,
    onToggleSelection: (Category, LibraryManga) -> Unit,
    onToggleRangeSelection: (Category, LibraryManga) -> Unit,
    onRefresh: () -> Boolean,
    // Yakuyomi：下拉更新開關（預設關）。關時整個 PullRefresh 停用。
    pullToRefreshEnabled: Boolean,
    onGlobalSearchClicked: () -> Unit,
    getItemCountForCategory: (Category) -> Int?,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    coverMinWidth: Int,
    getItemsForCategory: (Category) -> List<LibraryItem>,
    // Yakuyomi：手動排序拖放。
    getIsManualSort: (Category) -> Boolean,
    onMoveManga: (Category, List<Long>) -> Unit,
    // Yakuyomi：單一可摺疊清單模式。
    singleListMode: Boolean,
    collapsedCategoryIds: Set<Long>,
    onToggleCategoryCollapsed: (Long) -> Unit,
    onMoveMangaToCategory: (Manga, Long, Long) -> Unit,
    onSetCategorySort: (Category, LibrarySort.Type, LibrarySort.Direction) -> Unit,
    onRenameCategory: (Category, String) -> Unit,
    onMoveCategory: (Category, Int) -> Unit,
) {
    Column(
        modifier = Modifier.padding(
            top = contentPadding.calculateTopPadding(),
            start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
            end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
        ),
    ) {
        val pagerState = rememberPagerState(currentPage) { categories.size }

        val scope = rememberCoroutineScope()
        var isRefreshing by remember(pagerState.currentPage) { mutableStateOf(false) }

        if (!singleListMode &&
            showPageTabs &&
            categories.isNotEmpty() &&
            (categories.size > 1 || !categories.first().isSystemCategory)
        ) {
            LaunchedEffect(categories) {
                if (categories.size <= pagerState.currentPage) {
                    pagerState.scrollToPage(categories.size - 1)
                }
            }
            LibraryTabs(
                categories = categories,
                pagerState = pagerState,
                getItemCountForCategory = getItemCountForCategory,
                onTabItemClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(it)
                    }
                },
            )
        }

        PullRefresh(
            refreshing = isRefreshing,
            enabled = selection.isEmpty() && pullToRefreshEnabled,
            onRefresh = {
                val started = onRefresh()
                if (!started) return@PullRefresh
                scope.launch {
                    // Fake refresh status but hide it after a second as it's a long running task
                    isRefreshing = true
                    delay(1.seconds)
                    isRefreshing = false
                }
            },
        ) {
            if (singleListMode) {
                val displayMode by getDisplayMode(0) // getDisplayMode 忽略 index → 全域顯示模式
                LibraryAllCategories(
                    categories = categories,
                    getItemsForCategory = getItemsForCategory,
                    displayMode = displayMode,
                    coverMinWidth = coverMinWidth,
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                    selection = selection,
                    searchQuery = searchQuery,
                    hasActiveFilters = hasActiveFilters,
                    collapsedCategoryIds = collapsedCategoryIds,
                    onToggleCategoryCollapsed = onToggleCategoryCollapsed,
                    onClickManga = { category, manga ->
                        if (selection.isNotEmpty()) {
                            onToggleSelection(category, manga)
                        } else {
                            onClickManga(manga.manga.id)
                        }
                    },
                    onLongClickManga = onToggleRangeSelection,
                    onClickContinueReading = onContinueReadingClicked,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                    getIsManualSort = getIsManualSort,
                    onMoveManga = onMoveManga,
                    onMoveMangaToCategory = onMoveMangaToCategory,
                    onSetCategorySort = onSetCategorySort,
                    onRenameCategory = onRenameCategory,
                    onMoveCategory = onMoveCategory,
                )
            } else {
                LibraryPager(
                    state = pagerState,
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                    hasActiveFilters = hasActiveFilters,
                    selection = selection,
                    searchQuery = searchQuery,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                    getCategoryForPage = { page -> categories[page] },
                    getDisplayMode = getDisplayMode,
                    coverMinWidth = coverMinWidth,
                    getItemsForCategory = getItemsForCategory,
                    onClickManga = { category, manga ->
                        if (selection.isNotEmpty()) {
                            onToggleSelection(category, manga)
                        } else {
                            onClickManga(manga.manga.id)
                        }
                    },
                    onLongClickManga = onToggleRangeSelection,
                    onClickContinueReading = onContinueReadingClicked,
                    getIsManualSort = getIsManualSort,
                    onMoveManga = onMoveManga,
                )
            }
        }

        if (!singleListMode) {
            LaunchedEffect(pagerState.currentPage) {
                onChangeCurrentPage(pagerState.currentPage)
            }
        }
    }
}
