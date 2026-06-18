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
    state: LazyListState = rememberLazyListState(),
    hideLoadingFooter: Boolean = false,
) {
    LazyColumn(
        state = state,
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
    ) {
        item {
            if (!hideLoadingFooter && mangaList.loadState.prepend is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }

        items(count = mangaList.itemCount) { index ->
            val manga by mangaList[index]?.collectAsState() ?: return@items
            BrowseSourceListItem(
                manga = manga,
                isAnchor = anchorUrl != null && manga.url == anchorUrl,
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
            )
        }

        item {
            if (!hideLoadingFooter &&
                (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading)
            ) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun BrowseSourceListItem(
    manga: Manga,
    isAnchor: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    MangaListItem(
        title = manga.title,
        coverData = MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            url = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        ),
        coverAlpha = if (manga.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
        badge = {
            InLibraryBadge(enabled = manga.favorite)
            AnchorBadge(enabled = isAnchor)
        },
        isAnchor = isAnchor,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
