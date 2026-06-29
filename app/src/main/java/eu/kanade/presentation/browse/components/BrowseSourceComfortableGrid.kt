package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceComfortableGrid(
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    columns: GridCells,
    contentPadding: PaddingValues,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    anchorUrl: String? = null,
    anchorFilteredOut: Boolean = false,
    state: LazyGridState = rememberLazyGridState(),
    hideLoadingFooter: Boolean = false,
) {
    LazyVerticalGrid(
        columns = columns,
        state = state,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        if (!hideLoadingFooter && mangaList.loadState.prepend is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            val manga by mangaList[index]?.collectAsState() ?: return@items
            BrowseSourceComfortableGridItem(
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
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceComfortableGridItem(
    manga: Manga,
    isAnchor: Boolean = false,
    anchorFilteredOut: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    // 錨點不符當前篩選、僅被強制留下 → 封面暗化以資區別。
    val filteredOut = isAnchor && anchorFilteredOut
    MangaComfortableGridItem(
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
        coverBadgeStart = {
            // Yakuyomi：錨點旗標移到左上（與收藏徽章同側、置最前＝最角落）。
            AnchorBadge(enabled = isAnchor, filteredOut = filteredOut)
            InLibraryBadge(enabled = manga.favorite)
        },
        isAnchor = isAnchor,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
