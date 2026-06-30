package eu.kanade.presentation.library.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.library.LibraryItem
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private const val HEADER_KEY_PREFIX = "library_category_header_"

/** 拖出原分類後彈「選擇分類」用：被拖的漫畫 + 來源分類 id。 */
private data class CategoryMoveRequest(val manga: Manga, val sourceCat: Long)

/** 拖曳期間追蹤來源/目標分類與被拖的漫畫（plain 欄位，不觸發 recompose）。 */
private class LibraryDragState {
    var sourceCat: Long? = null
    var targetCat: Long? = null
    var manga: Manga? = null
    var sourceOriginalOrder: List<Long>? = null

    // 已在「跨出分類當下」跳過選單→本次拖曳不再處理（避免重複）。
    var menuTriggered: Boolean = false
    fun reset() {
        sourceCat = null
        targetCat = null
        manga = null
        sourceOriginalOrder = null
        menuTriggered = false
    }
}

/**
 * Yakuyomi：單一可摺疊清單模式——所有分類塞進一個 [LazyLibraryGrid]，每個分類前掛一個整行
 * （[GridItemSpan] maxLineSpan）的「黏性」標題，點標題摺疊/展開該分類。取代頁籤 + 分頁。
 * 重用既有的 [MangaCompactGridItem]/[MangaComfortableGridItem]/[MangaListItem] 格子。
 *
 * 拖放（grid 版型、非搜尋/篩選時）：
 * - **分類內**：手動排序的分類可長按拖曳調順序。
 * - **跨分類**：把書拖到別的（展開的）分類放開＝改分類（move）。靠 handle 的 onDragStarted/onDragStopped 偵測。
 * List 版型不提供拖曳。
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
    // Yakuyomi：手動排序拖放（分類內）＋ 跨分類改分類。
    getIsManualSort: (Category) -> Boolean,
    onMoveManga: (Category, List<Long>) -> Unit,
    onMoveMangaToCategory: (Manga, Long, Long) -> Unit,
    // Yakuyomi：每分類排序（標頭）＋ 長壓分類名改名。
    onSetCategorySort: (Category, LibrarySort.Type, LibrarySort.Direction) -> Unit,
    onRenameCategory: (Category, String) -> Unit,
    // Yakuyomi：標頭 ≡ →「排序分類」對話框重排分類順序。newIndex＝非系統分類清單中的目標位置。
    onMoveCategory: (Category, Int) -> Unit,
) {
    // 長壓分類名改名的對話框目標（null＝不顯示）。
    var renameTarget by remember { mutableStateOf<Category?>(null) }
    // 拖出原分類後彈「選擇分類」的請求（null＝不顯示）。
    var moveRequest by remember { mutableStateOf<CategoryMoveRequest?>(null) }
    // 標頭 ≡ →「排序分類」對話框（true＝顯示）。
    var showReorderCategories by remember { mutableStateOf(false) }
    // 至少兩個非系統分類才有重排意義；否則標頭不顯示 ≡。
    val canReorderCategories = categories.count { !it.isSystemCategory } >= 2
    // 每次重組重算每分類項目（state 變才重組）。不可 remember(categories)：移動漫畫時分類清單內容相等、
    // remember 會回舊快取 → 分組變動不更新（getItemsForCategory 綁當前 state，每次重組是新 lambda）。
    val categoryItems = categories.map { it to getItemsForCategory(it) }
    val filtering = !searchQuery.isNullOrEmpty() || hasActiveFilters
    // 只有「預設」一個系統分類時不畫標頭＝等同無分類的扁平網格。
    val showHeaders = !(categories.size == 1 && categories.first().isSystemCategory)
    val gridState = rememberLazyGridState()
    val isList = displayMode is LibraryDisplayMode.List

    // 拖放用的每分類本地有序清單；signature 含每分類 itemId 序 → 資料變才重建、拖曳期間穩定（不引回分組 staleness）。
    val signature = categoryItems.map { (c, items) -> c.id to items.map { it.id } }
    val localByCat = remember(signature) {
        categoryItems.associate { (c, items) -> c.id to items.toMutableStateList() }
    }
    // 某分類可「拖出」= 非搜尋/篩選、非 List 版型、且該分類為手動排序。
    val reorderableCats = remember(signature, filtering, isList) {
        if (filtering || isList) {
            emptySet()
        } else {
            categoryItems.mapNotNull { (c, _) -> c.id.takeIf { getIsManualSort(c) } }.toSet()
        }
    }
    // 有任一可拖分類時，全部 grid 項目都包成 ReorderableItem（才能當跨分類的放置目標）；只有可拖分類掛 handle。
    val anyReorder = reorderableCats.isNotEmpty()
    val drag = remember { LibraryDragState() }

    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyGridState
        val toKey = to.key as? String ?: return@rememberReorderableLazyGridState
        if (fromKey.startsWith(HEADER_KEY_PREFIX) || toKey.startsWith(HEADER_KEY_PREFIX)) {
            return@rememberReorderableLazyGridState
        }
        val fromCat = fromKey.substringBefore('_').toLongOrNull() ?: return@rememberReorderableLazyGridState
        val toCat = toKey.substringBefore('_').toLongOrNull() ?: return@rememberReorderableLazyGridState
        drag.targetCat = toCat
        // 一跨出來源分類就「立刻」跳「移到分類」選單（不讓套件對全寬標題的位移繼續發展），並還原來源順序。
        if (fromCat != toCat) {
            if (!drag.menuTriggered) {
                drag.menuTriggered = true
                val src = drag.sourceCat
                val m = drag.manga
                if (src != null) {
                    drag.sourceOriginalOrder?.let { orig ->
                        categories.find { it.id == src }?.let { cat -> onMoveManga(cat, orig) }
                    }
                    if (m != null) moveRequest = CategoryMoveRequest(m, src)
                }
            }
            return@rememberReorderableLazyGridState
        }
        // 同分類就地調順序；已跳選單則不再重排。
        if (drag.menuTriggered || fromCat !in reorderableCats) return@rememberReorderableLazyGridState
        val list = localByCat[fromCat] ?: return@rememberReorderableLazyGridState
        val fi = list.indexOfFirst { "${fromCat}_${it.id}" == fromKey }
        val ti = list.indexOfFirst { "${fromCat}_${it.id}" == toKey }
        if (fi in list.indices && ti in list.indices) {
            list.add(ti, list.removeAt(fi))
            categories.find { it.id == fromCat }?.let { cat -> onMoveManga(cat, list.map { m -> m.id }) }
        }
    }
    // 拖曳期間把分類標題改成非黏性：黏性標題的回報位置是「黏在頂部」，會讓 reorderable 算錯被拖物的偏移
    // → 跨分類時漫畫往手指反方向位移。拖曳結束恢復黏性。
    val dragging = reorderState.isAnyItemDragging

    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        coverMinWidth = coverMinWidth,
        contentPadding = contentPadding,
        state = gridState,
    ) {
        globalSearchItem(searchQuery, onGlobalSearchClicked)

        categoryItems.forEach { (category, _) ->
            val catList = localByCat[category.id].orEmpty()
            // 搜尋/篩選時隱藏空分類；否則保留空標頭以維持結構。
            if (filtering && catList.isEmpty()) return@forEach
            val collapsed = category.id in collapsedCategoryIds
            val catReorder = category.id in reorderableCats

            if (showHeaders) {
                val headerContent: @Composable () -> Unit = {
                    LibraryCategoryHeader(
                        name = if (category.isSystemCategory) {
                            stringResource(MR.strings.label_default)
                        } else {
                            category.name
                        },
                        count = catList.size,
                        collapsed = collapsed,
                        sort = category.sort,
                        onClick = { onToggleCategoryCollapsed(category.id) },
                        // 系統「預設」分類不可改名。
                        onLongClickName = if (category.isSystemCategory) {
                            null
                        } else {
                            { renameTarget = category }
                        },
                        onSetSort = { type, direction -> onSetCategorySort(category, type, direction) },
                        // ≡：至少兩個非系統分類才可重排，否則隱藏。
                        onClickReorder = if (canReorderCategories) {
                            { showReorderCategories = true }
                        } else {
                            null
                        },
                    )
                }
                // 拖曳中改用非黏性 item（黏性標題會讓 reorderable 算錯偏移→跨分類反向位移）。
                if (dragging) {
                    item(
                        key = "$HEADER_KEY_PREFIX${category.id}",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "library_category_header",
                    ) { headerContent() }
                } else {
                    stickyHeader(
                        key = "$HEADER_KEY_PREFIX${category.id}",
                        contentType = "library_category_header",
                    ) { headerContent() }
                }
            }
            if (collapsed) return@forEach

            if (isList) {
                items(
                    items = catList,
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
            } else {
                items(
                    items = catList,
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
                    // 可拖項目 onLongClick=null：讓 combinedClickable 不攔長按、交給 longPressDraggableHandle。
                    val onLong: (() -> Unit)? = if (catReorder) {
                        null
                    } else {
                        { onLongClickManga(category, libraryItem.libraryManga) }
                    }
                    val gridCell: @Composable (Modifier) -> Unit = { cellModifier ->
                        if (displayMode is LibraryDisplayMode.ComfortableGrid) {
                            MangaComfortableGridItem(
                                modifier = cellModifier,
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
                                onLongClick = onLong,
                                onClickContinueReading = continueReading,
                            )
                        } else {
                            MangaCompactGridItem(
                                modifier = cellModifier,
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
                                onLongClick = onLong,
                                onClickContinueReading = continueReading,
                            )
                        }
                    }
                    if (anyReorder) {
                        ReorderableItem(reorderState, key = "${category.id}_${libraryItem.id}") {
                            val handleMod = if (catReorder) {
                                Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        drag.sourceCat = category.id
                                        drag.targetCat = category.id
                                        drag.manga = manga
                                        drag.sourceOriginalOrder = localByCat[category.id]?.map { it.id }
                                        drag.menuTriggered = false
                                    },
                                    onDragStopped = {
                                        // 跨出分類已在 onMove 當下處理（跳選單）；這裡只作後援（極快拖放沒被 onMove 抓到）。
                                        if (!drag.menuTriggered) {
                                            val s = drag.sourceCat
                                            val t = drag.targetCat
                                            val m = drag.manga
                                            if (s != null && t != null && m != null && s != t) {
                                                drag.sourceOriginalOrder?.let { orig ->
                                                    categories.find { it.id == s }?.let { cat ->
                                                        onMoveManga(cat, orig)
                                                    }
                                                }
                                                moveRequest = CategoryMoveRequest(m, s)
                                            }
                                        }
                                        drag.reset()
                                    },
                                )
                            } else {
                                Modifier
                            }
                            gridCell(handleMod)
                        }
                    } else {
                        gridCell(Modifier)
                    }
                }
            }
        }
    }

    renameTarget?.let { cat ->
        RenameCategoryDialog(
            initialName = cat.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                onRenameCategory(cat, newName)
                renameTarget = null
            },
        )
    }

    moveRequest?.let { req ->
        CategoryMoveDialog(
            // 排除來源分類；移到該分類＝move（離開來源、加入目標）。
            targets = categories.filter { it.id != req.sourceCat },
            onPick = { target ->
                onMoveMangaToCategory(req.manga, req.sourceCat, target.id)
                moveRequest = null
            },
            onDismiss = { moveRequest = null },
        )
    }

    if (showReorderCategories) {
        ReorderCategoriesDialog(
            // 系統「預設」分類不參與排序（ReorderCategory 也只動非系統）。
            categories = categories.filterNot { it.isSystemCategory },
            onMove = onMoveCategory,
            onDismiss = { showReorderCategories = false },
        )
    }
}

// 排序欄位 → 顯示字串（對齊 LibrarySettingsDialog 的排序選項；略過需追蹤器的 TrackerMean）。
private val sortTypeLabels = listOf(
    LibrarySort.Type.Alphabetical to MR.strings.action_sort_alpha,
    LibrarySort.Type.TotalChapters to MR.strings.action_sort_total,
    LibrarySort.Type.LastRead to MR.strings.action_sort_last_read,
    LibrarySort.Type.LastUpdate to MR.strings.action_sort_last_manga_update,
    LibrarySort.Type.UnreadCount to MR.strings.action_sort_unread_count,
    LibrarySort.Type.LatestChapter to MR.strings.action_sort_latest_chapter,
    LibrarySort.Type.ChapterFetchDate to MR.strings.action_sort_chapter_fetch_date,
    LibrarySort.Type.DateAdded to MR.strings.action_sort_date_added,
    LibrarySort.Type.Random to MR.strings.action_sort_random,
    LibrarySort.Type.Manual to MR.strings.action_sort_manual,
)

@Composable
private fun LibraryCategoryHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    sort: LibrarySort,
    onClick: () -> Unit,
    onLongClickName: (() -> Unit)?,
    onSetSort: (LibrarySort.Type, LibrarySort.Direction) -> Unit,
    onClickReorder: (() -> Unit)?,
) {
    val rotation by animateFloatAsState(if (collapsed) -90f else 0f, label = "category_chevron")
    var sortMenu by remember { mutableStateOf(false) }
    val currentLabel = sortTypeLabels.firstOrNull { it.first == sort.type }?.second
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左區：chevron + 名稱 + 數量 —— 點＝摺疊/展開、長壓名稱＝改名。
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClickName)
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
                    modifier = Modifier.padding(start = 8.dp),
                )
                Text(
                    text = " ($count)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 左右區之間留間距。
            Spacer(Modifier.width(16.dp))

            // 右區：排序欄位 + 方向（點＝每分類排序選單）｜ ≡（點＝排序分類對話框）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Row(
                        modifier = Modifier
                            .clickable { sortMenu = true }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (currentLabel != null) {
                            Text(
                                text = stringResource(currentLabel),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = if (sort.isAscending) {
                                Icons.Filled.ArrowUpward
                            } else {
                                Icons.Filled.ArrowDownward
                            },
                            contentDescription = null,
                            modifier = Modifier
                                .padding(start = 2.dp)
                                .size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        sortTypeLabels.forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                trailingIcon = {
                                    if (type == sort.type) {
                                        Icon(
                                            imageVector = if (sort.isAscending) {
                                                Icons.Filled.ArrowUpward
                                            } else {
                                                Icons.Filled.ArrowDownward
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                },
                                onClick = {
                                    // 點當前欄位＝切方向；點別的欄位＝設該欄位、保留方向。
                                    val direction = when {
                                        type != sort.type -> sort.direction
                                        sort.isAscending -> LibrarySort.Direction.Descending
                                        else -> LibrarySort.Direction.Ascending
                                    }
                                    onSetSort(type, direction)
                                    sortMenu = false
                                },
                            )
                        }
                    }
                }
                // ≡：點＝跳「排序分類」對話框（重排整個分類順序）。
                onClickReorder?.let { reorder ->
                    Icon(
                        imageVector = Icons.Outlined.DragHandle,
                        contentDescription = stringResource(MR.strings.action_reorder_categories),
                        modifier = Modifier
                            .clickable(onClick = reorder)
                            .padding(start = 6.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameCategoryDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.action_rename_category)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun CategoryMoveDialog(
    targets: List<Category>,
    onPick: (Category) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.move_to_category)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                targets.forEach { cat ->
                    Text(
                        text = if (cat.isSystemCategory) stringResource(MR.strings.label_default) else cat.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(cat) }
                            .padding(vertical = 14.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

/**
 * 「排序分類」對話框：均一全寬可拖曳清單（避開主網格全寬標題的 reorderable 位移坑），
 * 拖曳放下即時持久化（[onMove] → ReorderCategory）。[categories]＝非系統分類，順序＝目前順序。
 */
@Composable
private fun ReorderCategoriesDialog(
    categories: List<Category>,
    onMove: (Category, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val ordered = remember { categories.toMutableStateList() }
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val moved = ordered.removeAt(from.index)
        ordered.add(to.index, moved)
        onMove(moved, to.index)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.action_reorder_categories)) },
        text = {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(ordered, key = { "reorder_cat_${it.id}" }) { category ->
                    ReorderableItem(reorderState, key = "reorder_cat_${category.id}") {
                        Surface(
                            tonalElevation = 2.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DragHandle,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .draggableHandle()
                                        .padding(end = 8.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
    )
}
