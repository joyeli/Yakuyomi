package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaListItem
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceList(
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    contentPadding: PaddingValues,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    anchorUrl: String? = null,
    anchorFilteredOut: Boolean = false,
    state: LazyListState = rememberLazyListState(),
    hideLoadingFooter: Boolean = false,
) {
    LazyColumn(
        state = state,
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
    ) {
        // 條件放在 item{} 外（對齊兩個 grid 版）：無條件的空 item 會讓 list index = 資料 index + 1，
        // 依資料 index 捲動（滑到錨點）就會差一格。
        if (!hideLoadingFooter && mangaList.loadState.prepend is LoadState.Loading) {
            item {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            val manga by mangaList[index]?.collectAsState() ?: return@items
            BrowseSourceListItem(
                manga = manga,
                isAnchor = anchorUrl != null && manga.url == anchorUrl,
                anchorFilteredOut = anchorFilteredOut,
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
            )
        }

        if (!hideLoadingFooter &&
            (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading)
        ) {
            item {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceListItem(
    manga: Manga,
    isAnchor: Boolean = false,
    anchorFilteredOut: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    // 錨點不符當前篩選、僅被強制留下 → 封面暗化以資區別。
    val filteredOut = isAnchor && anchorFilteredOut
    MangaListItem(
        title = manga.title,
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            url = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        coverAlpha = when {
            filteredOut -> CommonMangaItemDefaults.BrowseFilteredAnchorCoverAlpha
            manga.favorite -> CommonMangaItemDefaults.BrowseFavoriteCoverAlpha
            else -> 1f
        },
        badge = {
            InLibraryBadge(enabled = manga.favorite)
            AnchorBadge(enabled = isAnchor, filteredOut = filteredOut)
        },
        isAnchor = isAnchor,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
