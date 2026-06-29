package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.library.LibraryItem
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.MangaCover

@Composable
internal fun LibraryCompactGrid(
    items: List<LibraryItem>,
    showTitle: Boolean,
    coverMinWidth: Int,
    contentPadding: PaddingValues,
    selection: Set<Long>,
    onClick: (LibraryManga) -> Unit,
    onLongClick: (LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    // Yakuyomi：手動排序時長按拖曳調順序（onMoveOrder 回傳新的 manga id 序）。
    reorderEnabled: Boolean = false,
    onMoveOrder: (List<Long>) -> Unit = {},
) {
    // Yakuyomi：拖曳期間的本地順序（依 id 序穩定 key，避免每次 recompose 重置）；移動時就地調動 + 回報持久化。
    val itemIds = items.map { it.id }
    val localItems = remember(itemIds) { items.toMutableStateList() }
    val gridState = rememberLazyGridState()
    val reorderableState = rememberReorderableLazyGridState(gridState) { from, to ->
        val fromIdx = localItems.indexOfFirst { it.id == from.key }
        val toIdx = localItems.indexOfFirst { it.id == to.key }
        if (fromIdx in localItems.indices && toIdx in localItems.indices) {
            localItems.add(toIdx, localItems.removeAt(fromIdx))
            onMoveOrder(localItems.map { it.id })
        }
    }

    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        coverMinWidth = coverMinWidth,
        contentPadding = contentPadding,
        state = gridState,
    ) {
        globalSearchItem(searchQuery, onGlobalSearchClicked)

        items(
            items = localItems,
            key = { it.id },
            contentType = { "library_compact_grid_item" },
        ) { libraryItem ->
            val manga = libraryItem.libraryManga.manga
            val gridItem: @Composable (Modifier) -> Unit = { itemModifier ->
                MangaCompactGridItem(
                    modifier = itemModifier,
                    isSelected = manga.id in selection,
                    title = manga.title.takeIf { showTitle },
                    coverData = MangaCover(
                        mangaId = manga.id,
                        sourceId = manga.source,
                        isMangaFavorite = manga.favorite,
                        url = manga.thumbnailUrl,
                        lastModified = manga.coverLastModified,
                    ),
                    coverBadgeStart = {
                        DownloadsBadge(count = libraryItem.badges.downloadCount)
                        TranslatedBadge(count = libraryItem.badges.translatedCount)
                        UnreadBadge(count = libraryItem.badges.unreadCount)
                    },
                    coverBadgeEnd = {
                        LanguageBadge(
                            isLocal = libraryItem.badges.isLocal,
                            sourceLanguage = libraryItem.badges.sourceLanguage,
                        )
                    },
                    // 手動排序模式下長按＝拖曳：傳 null 讓 combinedClickable 不攔長按、交給 longPressDraggableHandle。
                    onLongClick = if (reorderEnabled) {
                        null
                    } else {
                        { onLongClick(libraryItem.libraryManga) }
                    },
                    onClick = { onClick(libraryItem.libraryManga) },
                    onClickContinueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
                        { onClickContinueReading(libraryItem.libraryManga) }
                    } else {
                        null
                    },
                )
            }
            if (reorderEnabled) {
                ReorderableItem(reorderableState, key = libraryItem.id) {
                    gridItem(Modifier.longPressDraggableHandle())
                }
            } else {
                gridItem(Modifier)
            }
        }
    }
}
