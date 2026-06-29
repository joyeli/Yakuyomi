package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.browse.components.BrowseSourceComfortableGrid
import eu.kanade.presentation.browse.components.BrowseSourceCompactGrid
import eu.kanade.presentation.browse.components.BrowseSourceList
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceContent(
    source: Source?,
    mangaList: LazyPagingItems<StateFlow<Manga>>,
    columns: GridCells,
    displayMode: LibraryDisplayMode,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onLocalSourceHelpClick: () -> Unit,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    // Yakuyomi：探索錨點 url（只 Latest 清單帶值；該本顯示旗標徽章）。
    anchorUrl: String? = null,
    // Yakuyomi：錨點不符當前全域篩選、僅被強制留下 → 該本加區別視覺（暗化 + 灰旗）。
    anchorFilteredOut: Boolean = false,
    // Yakuyomi：外部提供的捲動狀態（自動載入到錨點時用以驅動載入＋捲動）。
    gridState: LazyGridState = rememberLazyGridState(),
    listState: LazyListState = rememberLazyListState(),
    // Yakuyomi：隱藏載入動畫（快照是靜態清單，不該有底部轉圈）。
    hideLoadingFooter: Boolean = false,
) {
    val context = LocalContext.current

    val errorState = mangaList.loadState.refresh.takeIf { it is LoadState.Error }
        ?: mangaList.loadState.append.takeIf { it is LoadState.Error }

    val getErrorMessage: (LoadState.Error) -> String = { state ->
        with(context) { state.error.formattedMessage }
    }

    LaunchedEffect(errorState) {
        if (mangaList.itemCount > 0 && errorState != null && errorState is LoadState.Error) {
            val result = snackbarHostState.showSnackbar(
                message = getErrorMessage(errorState),
                actionLabel = context.stringResource(MR.strings.action_retry),
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
                SnackbarResult.ActionPerformed -> mangaList.retry()
            }
        }
    }

    if (mangaList.itemCount == 0 && mangaList.loadState.refresh is LoadState.Loading) {
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }

    if (mangaList.itemCount == 0) {
        EmptyScreen(
            modifier = Modifier.padding(contentPadding),
            message = when (errorState) {
                is LoadState.Error -> getErrorMessage(errorState)
                else -> stringResource(MR.strings.no_results_found)
            },
            actions = if (source is LocalSource) {
                listOf(
                    EmptyScreenAction(
                        stringRes = MR.strings.local_source_help_guide,
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = onLocalSourceHelpClick,
                    ),
                )
            } else {
                listOf(
                    EmptyScreenAction(
                        stringRes = MR.strings.action_retry,
                        icon = Icons.Outlined.Refresh,
                        onClick = mangaList::refresh,
                    ),
                    EmptyScreenAction(
                        stringRes = MR.strings.action_open_in_web_view,
                        icon = Icons.Outlined.Public,
                        onClick = onWebViewClick,
                    ),
                    EmptyScreenAction(
                        stringRes = MR.strings.label_help,
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = onHelpClick,
                    ),
                )
            },
        )

        return
    }

    when (displayMode) {
        LibraryDisplayMode.ComfortableGrid -> {
            BrowseSourceComfortableGrid(
                mangaList = mangaList,
                columns = columns,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                anchorUrl = anchorUrl,
                anchorFilteredOut = anchorFilteredOut,
                state = gridState,
                hideLoadingFooter = hideLoadingFooter,
            )
        }
        LibraryDisplayMode.List -> {
            BrowseSourceList(
                mangaList = mangaList,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                anchorUrl = anchorUrl,
                anchorFilteredOut = anchorFilteredOut,
                state = listState,
                hideLoadingFooter = hideLoadingFooter,
            )
        }
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
            BrowseSourceCompactGrid(
                mangaList = mangaList,
                columns = columns,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                anchorUrl = anchorUrl,
                anchorFilteredOut = anchorFilteredOut,
                state = gridState,
                hideLoadingFooter = hideLoadingFooter,
            )
        }
    }
}

@Composable
internal fun MissingSourceScreen(
    source: StubSource,
    navigateUp: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = source.name,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        EmptyScreen(
            message = stringResource(MR.strings.source_not_installed, source.toString()),
            modifier = Modifier.padding(paddingValues),
        )
    }
}
