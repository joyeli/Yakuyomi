package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus

@Composable
fun LibraryPager(
    state: PagerState,
    contentPadding: PaddingValues,
    hasActiveFilters: Boolean,
    selection: Set<Long>,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    getCategoryForPage: (Int) -> Category,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    coverMinWidth: Int,
    getItemsForCategory: (Category) -> List<LibraryItem>,
    onClickManga: (Category, LibraryManga) -> Unit,
    onLongClickManga: (Category, LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
    // Yakuyomi：手動排序拖放。
    getIsManualSort: (Category) -> Boolean,
    onMoveManga: (Category, List<Long>) -> Unit,
) {
    HorizontalPager(
        modifier = Modifier.fillMaxSize(),
        state = state,
        verticalAlignment = Alignment.Top,
    ) { page ->
        if (page !in ((state.currentPage - 1)..(state.currentPage + 1))) {
            // To make sure only one offscreen page is being composed
            return@HorizontalPager
        }
        val category = getCategoryForPage(page)
        val items = getItemsForCategory(category)

        if (items.isEmpty()) {
            LibraryPagerEmptyScreen(
                searchQuery = searchQuery,
                hasActiveFilters = hasActiveFilters,
                contentPadding = contentPadding,
                onGlobalSearchClicked = onGlobalSearchClicked,
            )
            return@HorizontalPager
        }

        val displayMode by getDisplayMode(page)

        val onClickManga: (LibraryManga) -> Unit = { onClickManga(category, it) }
        val onLongClickManga: (LibraryManga) -> Unit = { onLongClickManga(category, it) }
        // Yakuyomi：手動排序且未搜尋/未篩選時，啟用該分類網格的長按拖曳調順序。
        val reorderEnabled = searchQuery.isNullOrEmpty() && !hasActiveFilters && getIsManualSort(category)
        val onMoveOrder: (List<Long>) -> Unit = { onMoveManga(category, it) }

        when (displayMode) {
            LibraryDisplayMode.List -> {
                LibraryList(
                    items = items,
                    contentPadding = contentPadding,
                    selection = selection,
                    onClick = onClickManga,
                    onLongClick = onLongClickManga,
                    onClickContinueReading = onClickContinueReading,
                    searchQuery = searchQuery,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                )
            }
            LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
                LibraryCompactGrid(
                    items = items,
                    showTitle = displayMode is LibraryDisplayMode.CompactGrid,
                    coverMinWidth = coverMinWidth,
                    contentPadding = contentPadding,
                    selection = selection,
                    onClick = onClickManga,
                    onLongClick = onLongClickManga,
                    onClickContinueReading = onClickContinueReading,
                    searchQuery = searchQuery,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                    reorderEnabled = reorderEnabled,
                    onMoveOrder = onMoveOrder,
                )
            }
            LibraryDisplayMode.ComfortableGrid -> {
                LibraryComfortableGrid(
                    items = items,
                    coverMinWidth = coverMinWidth,
                    contentPadding = contentPadding,
                    selection = selection,
                    onClick = onClickManga,
                    onLongClick = onLongClickManga,
                    onClickContinueReading = onClickContinueReading,
                    searchQuery = searchQuery,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                    reorderEnabled = reorderEnabled,
                    onMoveOrder = onMoveOrder,
                )
            }
        }
    }
}

@Composable
private fun LibraryPagerEmptyScreen(
    searchQuery: String?,
    hasActiveFilters: Boolean,
    contentPadding: PaddingValues,
    onGlobalSearchClicked: () -> Unit,
) {
    val msg = when {
        !searchQuery.isNullOrEmpty() -> MR.strings.no_results_found
        hasActiveFilters -> MR.strings.error_no_match
        else -> MR.strings.information_no_manga_category
    }

    Column(
        modifier = Modifier
            .padding(contentPadding + PaddingValues(8.dp))
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (!searchQuery.isNullOrEmpty()) {
            GlobalSearchItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
                searchQuery = searchQuery,
                onClick = onGlobalSearchClicked,
            )
        }

        EmptyScreen(
            stringRes = msg,
            modifier = Modifier.weight(1f),
        )
    }
}
