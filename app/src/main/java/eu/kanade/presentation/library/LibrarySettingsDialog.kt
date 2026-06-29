package eu.kanade.presentation.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.library.components.LibraryGridSize
import eu.kanade.tachiyomi.ui.library.LibrarySettingsScreenModel
import eu.kanade.tachiyomi.util.system.isReleaseBuildType
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BaseSortItem
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun LibrarySettingsDialog(
    onDismissRequest: () -> Unit,
    screenModel: LibrarySettingsScreenModel,
    category: Category?,
) {
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = listOf(
            stringResource(MR.strings.action_filter),
            stringResource(MR.strings.action_sort),
            stringResource(MR.strings.action_display),
        ),
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> FilterPage(
                    screenModel = screenModel,
                )
                1 -> SortPage(
                    category = category,
                    screenModel = screenModel,
                )
                2 -> DisplayPage(
                    screenModel = screenModel,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.FilterPage(
    screenModel: LibrarySettingsScreenModel,
) {
    val filterDownloaded by screenModel.libraryPreferences.filterDownloaded.collectAsState()
    val downloadedOnly by screenModel.preferences.downloadedOnly.collectAsState()
    val autoUpdateMangaRestrictions by screenModel.libraryPreferences.autoUpdateMangaRestrictions.collectAsState()

    TriStateItem(
        label = stringResource(MR.strings.label_downloaded),
        state = if (downloadedOnly) {
            TriState.ENABLED_IS
        } else {
            filterDownloaded
        },
        enabled = !downloadedOnly,
        onClick = { screenModel.toggleFilter(LibraryPreferences::filterDownloaded) },
    )
    val filterUnread by screenModel.libraryPreferences.filterUnread.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_unread),
        state = filterUnread,
        onClick = { screenModel.toggleFilter(LibraryPreferences::filterUnread) },
    )
    val filterStarted by screenModel.libraryPreferences.filterStarted.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.label_started),
        state = filterStarted,
        onClick = { screenModel.toggleFilter(LibraryPreferences::filterStarted) },
    )
    val filterBookmarked by screenModel.libraryPreferences.filterBookmarked.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_bookmarked),
        state = filterBookmarked,
        onClick = { screenModel.toggleFilter(LibraryPreferences::filterBookmarked) },
    )
    val filterCompleted by screenModel.libraryPreferences.filterCompleted.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.completed),
        state = filterCompleted,
        onClick = { screenModel.toggleFilter(LibraryPreferences::filterCompleted) },
    )
    val filterTranslated by screenModel.libraryPreferences.filterTranslated.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_translated),
        state = filterTranslated,
        onClick = { screenModel.toggleFilter(LibraryPreferences::filterTranslated) },
    )
    // TODO: re-enable when custom intervals are ready for stable
    if ((!isReleaseBuildType) && LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in autoUpdateMangaRestrictions) {
        val filterIntervalCustom by screenModel.libraryPreferences.filterIntervalCustom.collectAsState()
        TriStateItem(
            label = stringResource(MR.strings.action_filter_interval_custom),
            state = filterIntervalCustom,
            onClick = { screenModel.toggleFilter(LibraryPreferences::filterIntervalCustom) },
        )
    }

    val trackers by screenModel.trackersFlow.collectAsState()
    when (trackers.size) {
        0 -> {
            // No trackers
        }
        1 -> {
            val service = trackers[0]
            val filterTracker by screenModel.libraryPreferences.filterTracking(service.id.toInt()).collectAsState()
            TriStateItem(
                label = stringResource(MR.strings.action_filter_tracked),
                state = filterTracker,
                onClick = { screenModel.toggleTracker(service.id.toInt()) },
            )
        }
        else -> {
            HeadingItem(MR.strings.action_filter_tracked)
            trackers.map { service ->
                val filterTracker by screenModel.libraryPreferences.filterTracking(service.id.toInt()).collectAsState()
                TriStateItem(
                    label = service.name,
                    state = filterTracker,
                    onClick = { screenModel.toggleTracker(service.id.toInt()) },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.SortPage(
    category: Category?,
    screenModel: LibrarySettingsScreenModel,
) {
    val trackers by screenModel.trackersFlow.collectAsState()
    val sortingMode = category.sort.type
    val sortDescending = !category.sort.isAscending

    val options = remember(trackers.isEmpty()) {
        val trackerMeanPair = if (trackers.isNotEmpty()) {
            MR.strings.action_sort_tracker_score to LibrarySort.Type.TrackerMean
        } else {
            null
        }
        listOfNotNull(
            MR.strings.action_sort_alpha to LibrarySort.Type.Alphabetical,
            MR.strings.action_sort_total to LibrarySort.Type.TotalChapters,
            MR.strings.action_sort_last_read to LibrarySort.Type.LastRead,
            MR.strings.action_sort_last_manga_update to LibrarySort.Type.LastUpdate,
            MR.strings.action_sort_unread_count to LibrarySort.Type.UnreadCount,
            MR.strings.action_sort_latest_chapter to LibrarySort.Type.LatestChapter,
            MR.strings.action_sort_chapter_fetch_date to LibrarySort.Type.ChapterFetchDate,
            MR.strings.action_sort_date_added to LibrarySort.Type.DateAdded,
            trackerMeanPair,
            MR.strings.action_sort_random to LibrarySort.Type.Random,
            // Yakuyomi：手動排序（拖放）。像 Random 一樣不切方向；選了之後可在書庫長按拖曳調順序。
            MR.strings.action_sort_manual to LibrarySort.Type.Manual,
        )
    }

    options.map { (titleRes, mode) ->
        if (mode == LibrarySort.Type.Random || mode == LibrarySort.Type.Manual) {
            BaseSortItem(
                label = stringResource(titleRes),
                icon = if (mode == LibrarySort.Type.Manual) {
                    Icons.Outlined.DragHandle.takeIf { sortingMode == LibrarySort.Type.Manual }
                } else {
                    Icons.Default.Refresh.takeIf { sortingMode == LibrarySort.Type.Random }
                },
                onClick = {
                    screenModel.setSort(category, mode, LibrarySort.Direction.Ascending)
                },
            )
            return@map
        }
        SortItem(
            label = stringResource(titleRes),
            sortDescending = sortDescending.takeIf { sortingMode == mode },
            onClick = {
                val isTogglingDirection = sortingMode == mode
                val direction = when {
                    isTogglingDirection -> if (sortDescending) {
                        LibrarySort.Direction.Ascending
                    } else {
                        LibrarySort.Direction.Descending
                    }
                    else -> if (sortDescending) {
                        LibrarySort.Direction.Descending
                    } else {
                        LibrarySort.Direction.Ascending
                    }
                }
                screenModel.setSort(category, mode, direction)
            },
        )
    }
}

private val displayModes = listOf(
    MR.strings.action_display_grid to LibraryDisplayMode.CompactGrid,
    MR.strings.action_display_comfortable_grid to LibraryDisplayMode.ComfortableGrid,
    MR.strings.action_display_cover_only_grid to LibraryDisplayMode.CoverOnlyGrid,
    MR.strings.action_display_list to LibraryDisplayMode.List,
)

@Composable
private fun ColumnScope.DisplayPage(
    screenModel: LibrarySettingsScreenModel,
) {
    val displayMode by screenModel.libraryPreferences.displayMode.collectAsState()
    SettingsChipRow(MR.strings.action_display_mode) {
        displayModes.map { (titleRes, mode) ->
            FilterChip(
                selected = displayMode == mode,
                onClick = { screenModel.setDisplayMode(mode) },
                label = { Text(stringResource(titleRes)) },
            )
        }
    }

    if (displayMode != LibraryDisplayMode.List) {
        // Yakuyomi：封面大小＝每行數量。級距依「當前螢幕寬度」現算（每個選項都對應一個實際欄數，無死步、
        // 大螢幕自動多出更多選項）；選後仍存成封面最小寬度 dp，故折/展、換裝置自動適應。標籤＝該選項在
        // 目前螢幕排成的欄數（所見即所得）。欄數計算對齊 Compose GridCells.Adaptive：floor((W+s)/(minW+s))。
        val coverWidthPreference = screenModel.libraryPreferences.gridCoverMinWidth
        val coverWidth by coverWidthPreference.collectAsState()
        // 用網格量到的「實際可用寬度」（已扣 nav rail/insets/padding）；尚未量到才退回螢幕寬估算。
        val measuredAvail = LibraryGridSize.availWidthDp
        val avail = (
            if (measuredAvail > 0) measuredAvail else LocalConfiguration.current.screenWidthDp - 16
            ).coerceAtLeast(120)
        val spacing = 4 // CommonMangaItemDefaults.GridHorizontalSpacer
        // 目前設定在此螢幕實際排出的欄數（對齊 Adaptive：floor((W+s)/(minW+s))）。
        val currentCols = ((avail + spacing) / (coverWidth + spacing)).coerceAtLeast(1)
        // 正常範圍上限＝以最小封面約 90dp 估的合理欄數。
        val normalMax = (avail + spacing) / (90 + spacing)
        // 級距一定涵蓋「目前實際欄數」，否則跨姿態（如折疊選的 dp 在展開排成 7 欄）會沒有對應選項。
        val maxCols = maxOf(normalMax, currentCols).coerceAtMost(15)
        val minCols = minOf(2, currentCols)
        SettingsChipRow(MR.strings.pref_library_columns) {
            (minCols..maxCols).forEach { cols ->
                // 殘留值：超出此螢幕正常範圍、只因跨姿態（另一姿態選的尺寸）才出現的當前值 → 特別標示。
                val isResidual = cols < 2 || cols > normalMax
                FilterChip(
                    selected = currentCols == cols,
                    // 取該欄數區間中點的 dp，避免落在邊界誤判成相鄰欄數。
                    onClick = {
                        val dp = ((avail + spacing).toFloat() / (cols + 0.5f) - spacing).toInt().coerceAtLeast(60)
                        coverWidthPreference.set(dp)
                    },
                    label = { Text(cols.toString()) },
                    leadingIcon = if (isResidual) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                    colors = if (isResidual) {
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onTertiary,
                        )
                    } else {
                        FilterChipDefaults.filterChipColors()
                    },
                )
            }
        }
    }

    HeadingItem(MR.strings.overlay_header)
    CheckboxItem(
        label = stringResource(MR.strings.action_display_download_badge),
        pref = screenModel.libraryPreferences.downloadBadge,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_translation_badge),
        pref = screenModel.libraryPreferences.translationBadge,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_unread_badge),
        pref = screenModel.libraryPreferences.unreadBadge,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_local_badge),
        pref = screenModel.libraryPreferences.localBadge,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_language_badge),
        pref = screenModel.libraryPreferences.languageBadge,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_show_continue_reading_button),
        pref = screenModel.libraryPreferences.showContinueReadingButton,
    )
    // Yakuyomi：底部浮動搜尋列（單手）。
    CheckboxItem(
        label = stringResource(MR.strings.action_display_floating_search_bar),
        pref = screenModel.libraryPreferences.floatingSearchBar,
    )

    HeadingItem(MR.strings.tabs_header)
    CheckboxItem(
        label = stringResource(MR.strings.action_display_show_tabs),
        pref = screenModel.libraryPreferences.categoryTabs,
    )
    CheckboxItem(
        label = stringResource(MR.strings.action_display_show_number_of_items),
        pref = screenModel.libraryPreferences.categoryNumberOfItems,
    )
    // Yakuyomi：把所有分類顯示為單一可摺疊清單（取代頁籤/分頁）。
    CheckboxItem(
        label = stringResource(MR.strings.action_display_single_list_collapsible),
        pref = screenModel.libraryPreferences.singleListCollapsibleCategories,
    )
}
