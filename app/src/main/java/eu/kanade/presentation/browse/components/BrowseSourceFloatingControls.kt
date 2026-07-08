package eu.kanade.presentation.browse.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

// Yakuyomi：探索來源頁的「浮動控制」——由全局 floatingSearchBar 開關啟用（同書庫那顆）。
// 收合時書目全螢幕、只留頂部窄 bar；搜尋/篩選/overflow/清單特有動作收進右下球。關閉開關時走傳統工具列（本檔不參與）。
// 頂部 bar（返回＋名稱＋熱門/最新＋快照）＋球。展開列＝主輸入（左）＋常駐功能（右）＋⋮：
//   非快照＝搜尋框 / ⧩篩選 · ▶錨點 / ⋮；快照＝「快照 · N 本」標題 / ☁擷取詳情 · ⧩篩選 / ⋮。
// 兩份選單（對齊書庫）：⋮（展開點三點）＝非常駐（沒擺上 bar 的剩餘功能）；
//   長壓（收合球，快捷用）＝非常駐置頂 ＋ 常駐靠底（收合時無行內圖示可點，篩選置最底最好按）。
// 背景任務（自動載入/批次擷取）跑時：收合球顯示進度＋停止，且不自動收合。

/** 當前清單（給頂部 bar 與球決定變體）。Search 當隱藏頁、不佔清單鈕狀態。 */
enum class BrowseListingKind { POPULAR, LATEST, SEARCH, SNAPSHOT }

@Composable
fun BrowseSourceTopControlBar(
    sourceName: String,
    navigateUp: () -> Unit,
    supportsLatest: Boolean,
    // 清單鈕要顯示的清單（Search 時＝搜尋前所在的列表清單，只會是 POPULAR/LATEST）。
    shownListing: BrowseListingKind,
    // 清單鈕是否高亮＝當前真的在該清單（搜尋隱藏頁/快照時為 false）。
    listingHighlighted: Boolean,
    onSelectPopular: () -> Unit,
    onSelectLatest: () -> Unit,
    // 快照鈕。
    snapshotCount: Int, // <0＝無快照（點＝即存當前清單）；>=0＝有快照筆數（點＝切過去）
    snapshotSelected: Boolean,
    onSnapshotClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = 52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = navigateUp) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(MR.strings.action_bar_up_description),
                )
            }
            Text(
                text = sourceName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                maxLines = 1,
            )

            // 清單鈕：支援最新→熱門↔最新 toggle（顯示當前、點切另一個）；不支援→固定「熱門」。永遠可點＝離開快照/搜尋回列表。
            val listingLabel = when {
                shownListing == BrowseListingKind.LATEST -> stringResource(MR.strings.latest)
                else -> stringResource(MR.strings.popular)
            }
            PillButton(
                text = listingLabel,
                selected = listingHighlighted,
                onClick = {
                    when {
                        // 不在列表清單（快照/搜尋隱藏頁）＝回到最後所在的列表清單，不做切換。
                        !listingHighlighted ->
                            if (shownListing == BrowseListingKind.LATEST) onSelectLatest() else onSelectPopular()
                        // 在列表清單：支援最新＝熱門↔最新切換；不支援＝固定熱門。
                        !supportsLatest -> onSelectPopular()
                        shownListing == BrowseListingKind.LATEST -> onSelectPopular()
                        else -> onSelectLatest()
                    }
                },
            )
            Spacer(Modifier.width(4.dp))
            // 快照鈕（常駐）。
            PillButton(
                text = stringResource(MR.strings.listing_snapshot) +
                    if (snapshotCount >= 0) " ($snapshotCount)" else "",
                icon = Icons.Outlined.History,
                selected = snapshotSelected,
                onClick = onSnapshotClick,
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun PillButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 探索球（里程碑①）。收合＝球；展開＝一排（搜尋框＋篩選＋overflow）。快照的擷取詳情/錨點＝里程碑②。
 */
@Composable
fun BrowseSourceFloatingBall(
    expanded: Boolean,
    onBallClick: () -> Unit,
    focusRequester: FocusRequester,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    hasActiveFilters: Boolean,
    onClickFilter: () -> Unit,
    isSnapshot: Boolean,
    onFetchDetails: () -> Unit,
    fetchRunning: Boolean,
    // 快照左側標題/進度（不可點）：閒置「快照 · N 本」、擷取中「擷取中 X/N」。
    snapshotLeftText: String = "",
    // 非快照展開列的行內「錨點」鈕（狀態機：停止/開始/繼續/標記）；null=不顯示。
    anchorInline: (() -> Unit)? = null,
    anchorInlineIcon: ImageVector? = null,
    anchorInlineDesc: String = "",
    // 背景任務（自動載入/批次擷取）跑時：收合球顯示進度＋可停。
    bgJobRunning: Boolean = false,
    bgJobProgress: String = "",
    onStopBgJob: () -> Unit = {},
    // ⋮（展開點三點）＝只有動作項（篩選/錨點/滑到錨點是行內圖示，不重複放進來）。
    tapMenuItems: List<BrowseBallMenuItem>,
    // 長壓（收合球）＝動作項 ＋ 收合時被藏起來的行內控制（錨點/滑到錨點/篩選）。
    longPressMenuItems: List<BrowseBallMenuItem>,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = expanded,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(200)) togetherWith fadeOut(tween(200))).using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.CenterEnd,
        label = "browseFloatingBall",
    ) { isExpanded ->
        if (isExpanded) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isSnapshot) {
                        // 快照左側＝標題/進度（不可點），佔滿左側；沒搜尋，故 bar 不留白。
                        Text(
                            text = snapshotLeftText,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        // 常駐：擷取詳情（跑批次時→停止）。
                        IconButton(onClick = onFetchDetails) {
                            Icon(
                                imageVector = if (fetchRunning) Icons.Filled.Stop else Icons.Outlined.CloudDownload,
                                contentDescription = stringResource(MR.strings.action_fetch_details),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        // 常駐：篩選。
                        FilterInlineButton(hasActiveFilters, onClickFilter)
                    } else {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        BasicTextField(
                            value = searchQuery ?: "",
                            onValueChange = { onSearchQueryChange(it) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .onFocusChanged { onSearchFocusChanged(it.isFocused) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { searchQuery?.takeIf { it.isNotBlank() }?.let(onSubmitSearch) },
                            ),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (searchQuery.isNullOrEmpty()) {
                                        Text(
                                            text = stringResource(MR.strings.action_search),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = LocalTextStyle.current,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        if (!searchQuery.isNullOrEmpty()) {
                            IconButton(onClick = onClearSearch) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(MR.strings.action_reset),
                                )
                            }
                        }
                        // 常駐：篩選。
                        FilterInlineButton(hasActiveFilters, onClickFilter)
                        // 常駐：錨點狀態機（開始/繼續/停止/標記）。
                        if (anchorInline != null && anchorInlineIcon != null) {
                            IconButton(onClick = anchorInline) {
                                Icon(anchorInlineIcon, contentDescription = anchorInlineDesc)
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { onMenuExpandedChange(true) }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(MR.strings.label_more))
                        }
                        BallMenu(menuExpanded, { onMenuExpandedChange(false) }, tapMenuItems)
                    }
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CollapsedBall(
                    hasActiveFilters = hasActiveFilters,
                    isSnapshot = isSnapshot,
                    onClick = onBallClick,
                    menuItems = longPressMenuItems,
                    bgJobRunning = bgJobRunning,
                    bgJobProgress = bgJobProgress,
                    onStopBgJob = onStopBgJob,
                )
            }
        }
    }
}

/** 展開列的常駐「篩選」圖示鈕（兩態共用）；有作用中篩選時高亮。 */
@Composable
private fun FilterInlineButton(hasActiveFilters: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Outlined.FilterList,
            contentDescription = stringResource(MR.strings.action_filter),
            tint = if (hasActiveFilters) {
                MaterialTheme.colorScheme.active
            } else {
                LocalContentColor.current
            },
        )
    }
}

/** 球長按選單項（由 screen 端依清單狀態組好傳入）。 */
data class BrowseBallMenuItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val onClick: () -> Unit,
    val dividerBefore: Boolean = false,
)

@Composable
private fun BallMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<BrowseBallMenuItem>) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        items.forEach { item ->
            if (item.dividerBefore) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(item.label) },
                leadingIcon = item.icon?.let { { Icon(it, contentDescription = null) } },
                onClick = {
                    item.onClick()
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun CollapsedBall(
    hasActiveFilters: Boolean,
    isSnapshot: Boolean,
    onClick: () -> Unit,
    menuItems: List<BrowseBallMenuItem>,
    bgJobRunning: Boolean,
    bgJobProgress: String,
    onStopBgJob: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        if (bgJobRunning) {
            // 背景任務跑時：球變進度膠囊，收合狀態下也永遠看得到進度＋可停。
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = bgJobProgress,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(onClick = onStopBgJob) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stringResource(MR.strings.action_cancel),
                        )
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
                tonalElevation = 3.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isSnapshot) Icons.Outlined.CloudDownload else Icons.Outlined.Search,
                        contentDescription = stringResource(MR.strings.action_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hasActiveFilters) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-4).dp, y = 4.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.active),
                )
            }
        }
        BallMenu(menuExpanded, { menuExpanded = false }, menuItems)
    }
}
