package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.util.plus

/**
 * Yakuyomi：紀錄書庫網格「實際可用寬度」(dp，已扣 content padding／nav rail／系統 insets)，
 * 供書庫顯示設定的「每列數目」chips 現算欄數級距用——避免用 screenWidthDp 估算在平板 UI 下因側邊
 * 導覽列而高估寬度、造成點選後欄數 n-1。
 */
object LibraryGridSize {
    var availWidthDp: Int = 0
}

@Composable
internal fun LazyLibraryGrid(
    modifier: Modifier = Modifier,
    coverMinWidth: Int,
    contentPadding: PaddingValues,
    state: LazyGridState = rememberLazyGridState(),
    content: LazyGridScope.() -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // Adaptive 實際分割的寬度＝元件寬度 − 水平 content padding（scaffold insets + 兩側各 8dp）。
    val horizontalPadding = contentPadding.calculateStartPadding(layoutDirection) +
        contentPadding.calculateEndPadding(layoutDirection) + 16.dp
    FastScrollLazyVerticalGrid(
        // Yakuyomi：依封面最小寬度自適應欄數（跨手機/平板/折疊機自動）。
        columns = GridCells.Adaptive(coverMinWidth.dp),
        state = state,
        modifier = modifier.onSizeChanged { size ->
            val availPx = size.width - with(density) { horizontalPadding.toPx() }
            LibraryGridSize.availWidthDp = with(density) { availPx.toDp().value.toInt() }
        },
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
        content = content,
    )
}

internal fun LazyGridScope.globalSearchItem(
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
) {
    if (!searchQuery.isNullOrEmpty()) {
        item(
            span = { GridItemSpan(maxLineSpan) },
            contentType = { "library_global_search_item" },
        ) {
            GlobalSearchItem(
                searchQuery = searchQuery,
                onClick = onGlobalSearchClicked,
            )
        }
    }
}
