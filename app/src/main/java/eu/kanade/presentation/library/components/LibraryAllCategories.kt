package eu.kanade.presentation.library.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Yakuyomi：單一可摺疊清單模式——所有分類塞進一個 [LazyLibraryGrid]，每個分類前掛一個整行
 * （[GridItemSpan] maxLineSpan）的「黏性」標題，點標題摺疊/展開該分類。取代頁籤 + 分頁。
 * 重用既有的 [MangaCompactGridItem]/[MangaComfortableGridItem]/[MangaListItem] 格子。
 * 此模式下不做拖放排序（手動排序的「順序」仍生效，只是不能在此模式拖）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryAllCategories(
    categories: List<Category>,
    getItemsForCategory: (Category) -> List<LibraryItem>,
    displayMode: LibraryDisplayMode,
    coverMinWidth: Int,
    contentPadding: PaddingValues,
    selection: Set<Long>,
    searchQuery: String?,
    hasActiveFilters: Boolean,
    collapsedCategoryIds: Set<Long>,
    onToggleCategoryCollapsed: (Long) -> Unit,
    onClickManga: (Category, LibraryManga) -> Unit,
    onLongClickManga: (Category, LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
    onGlobalSearchClicked: () -> Unit,
) {
    // 每次重組重算每分類項目（state 變才重組）。不可 remember(categories)：移動漫畫時分類清單內容相等、
    // remember 會回舊快取 → 分組變動不更新（getItemsForCategory 綁當前 state，每次重組是新 lambda）。
    val categoryItems = categories.map { it to getItemsForCategory(it) }
    val filtering = !searchQuery.isNullOrEmpty() || hasActiveFilters
    // 只有「預設」一個系統分類時不畫標頭＝等同無分類的扁平網格。
    val showHeaders = !(categories.size == 1 && categories.first().isSystemCategory)
    val gridState = rememberLazyGridState()

    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        coverMinWidth = coverMinWidth,
        contentPadding = contentPadding,
        state = gridState,
    ) {
        globalSearchItem(searchQuery, onGlobalSearchClicked)

        categoryItems.forEach { (category, items) ->
            // 搜尋/篩選時隱藏空分類；否則保留空標頭以維持結構。
            if (filtering && items.isEmpty()) return@forEach
            val collapsed = category.id in collapsedCategoryIds

            if (showHeaders) {
                stickyHeader(
                    key = "library_category_header_${category.id}",
                    contentType = "library_category_header",
                ) {
                    LibraryCategoryHeader(
                        name = if (category.isSystemCategory) {
                            stringResource(MR.strings.label_default)
                        } else {
                            category.name
                        },
                        count = items.size,
                        collapsed = collapsed,
                        onClick = { onToggleCategoryCollapsed(category.id) },
                    )
                }
            }
            if (collapsed) return@forEach

            when (displayMode) {
                LibraryDisplayMode.List -> items(
                    items = items,
                    key = { "${category.id}_${it.id}" },
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = { "library_all_list_item" },
                ) { libraryItem ->
                    val manga = libraryItem.libraryManga.manga
                    MangaListItem(
                        isSelected = manga.id in selection,
                        title = manga.title,
                        coverData = MangaCover(
                            mangaId = manga.id,
                            sourceId = manga.source,
                            isMangaFavorite = manga.favorite,
                            url = manga.thumbnailUrl,
                            lastModified = manga.coverLastModified,
                        ),
                        badge = {
                            DownloadsBadge(count = libraryItem.badges.downloadCount)
                            TranslatedBadge(count = libraryItem.badges.translatedCount)
                            UnreadBadge(count = libraryItem.badges.unreadCount)
                            LanguageBadge(
                                isLocal = libraryItem.badges.isLocal,
                                sourceLanguage = libraryItem.badges.sourceLanguage,
                            )
                        },
                        onClick = { onClickManga(category, libraryItem.libraryManga) },
                        onLongClick = { onLongClickManga(category, libraryItem.libraryManga) },
                        onClickContinueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
                            { onClickContinueReading(libraryItem.libraryManga) }
                        } else {
                            null
                        },
                    )
                }
                else -> items(
                    items = items,
                    key = { "${category.id}_${it.id}" },
                    contentType = { "library_all_grid_item" },
                ) { libraryItem ->
                    val manga = libraryItem.libraryManga.manga
                    val coverData = MangaCover(
                        mangaId = manga.id,
                        sourceId = manga.source,
                        isMangaFavorite = manga.favorite,
                        url = manga.thumbnailUrl,
                        lastModified = manga.coverLastModified,
                    )
                    val continueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
                        { onClickContinueReading(libraryItem.libraryManga) }
                    } else {
                        null
                    }
                    if (displayMode is LibraryDisplayMode.ComfortableGrid) {
                        MangaComfortableGridItem(
                            isSelected = manga.id in selection,
                            title = manga.title,
                            coverData = coverData,
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
                            onClick = { onClickManga(category, libraryItem.libraryManga) },
                            onLongClick = { onLongClickManga(category, libraryItem.libraryManga) },
                            onClickContinueReading = continueReading,
                        )
                    } else {
                        MangaCompactGridItem(
                            isSelected = manga.id in selection,
                            title = manga.title.takeIf { displayMode is LibraryDisplayMode.CompactGrid },
                            coverData = coverData,
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
                            onClick = { onClickManga(category, libraryItem.libraryManga) },
                            onLongClick = { onLongClickManga(category, libraryItem.libraryManga) },
                            onClickContinueReading = continueReading,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCategoryHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(if (collapsed) -90f else 0f, label = "category_chevron")
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.rotate(rotation),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
