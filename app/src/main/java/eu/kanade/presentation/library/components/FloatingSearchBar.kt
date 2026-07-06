package eu.kanade.presentation.library.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

/**
 * Yakuyomi：書庫底部浮動控制列（借鏡 J2K/Yokai 單手 UX）。
 * 展開＝整列（搜尋 + 篩選 + overflow），閒置/捲動時收成右下小球（沉浸看書）；點球展開並聚焦。
 * 開啟此列時頂部工具列整條隱藏、書目全螢幕。與書庫 [searchQuery] 共用 state。
 */
@Composable
fun FloatingSearchBar(
    expanded: Boolean,
    onBallClick: () -> Unit,
    focusRequester: FocusRequester,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    hasActiveFilters: Boolean,
    onClickFilter: () -> Unit,
    onClickGlobalUpdate: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickOpenRandomManga: () -> Unit,
    onClickSavedSearches: () -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    // Yakuyomi：回報搜尋框焦點（有焦點＝正在打字/IME 選字）→ 呼叫端據此不做閒置自動收合，避免選字中被收掉丟字。
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
        label = "floatingSearchBar",
    ) { isExpanded ->
        if (isExpanded) {
            ExpandedSearchBar(
                focusRequester = focusRequester,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                hasActiveFilters = hasActiveFilters,
                onClickFilter = onClickFilter,
                onClickGlobalUpdate = onClickGlobalUpdate,
                onClickRefresh = onClickRefresh,
                onClickOpenRandomManga = onClickOpenRandomManga,
                onClickSavedSearches = onClickSavedSearches,
                menuExpanded = menuExpanded,
                onMenuExpandedChange = onMenuExpandedChange,
                onSearchFocusChanged = onSearchFocusChanged,
            )
        } else {
            // 收合成球 → 必定回報失焦（清掉殘留焦點狀態，讓下次展開的閒置收合能正常計時）。
            LaunchedEffect(Unit) { onSearchFocusChanged(false) }
            // 收合：右下小球。
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CollapsedBall(
                    hasActiveFilters = hasActiveFilters,
                    onClick = onBallClick,
                    onClickFilter = onClickFilter,
                    onClickGlobalUpdate = onClickGlobalUpdate,
                    onClickRefresh = onClickRefresh,
                    onClickOpenRandomManga = onClickOpenRandomManga,
                    onClickSavedSearches = onClickSavedSearches,
                )
            }
        }
    }
}

@Composable
private fun ExpandedSearchBar(
    focusRequester: FocusRequester,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    hasActiveFilters: Boolean,
    onClickFilter: () -> Unit,
    onClickGlobalUpdate: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickOpenRandomManga: () -> Unit,
    onClickSavedSearches: () -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
) {
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
            Icon(
                imageVector = Icons.Outlined.Search,
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
                keyboardActions = KeyboardActions(),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isNullOrEmpty()) {
                            Text(
                                text = stringResource(MR.strings.action_search),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = LocalTextStyle.current,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (!searchQuery.isNullOrEmpty()) {
                IconButton(onClick = { onSearchQueryChange(null) }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(MR.strings.action_reset),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onClickFilter) {
                Icon(
                    imageVector = Icons.Outlined.FilterList,
                    contentDescription = stringResource(MR.strings.action_filter),
                    tint = if (hasActiveFilters) MaterialTheme.colorScheme.active else LocalContentColor.current,
                )
            }
            Box {
                IconButton(onClick = { onMenuExpandedChange(true) }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(MR.strings.label_more),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { onMenuExpandedChange(false) }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.action_update_library)) },
                        onClick = {
                            onClickGlobalUpdate()
                            onMenuExpandedChange(false)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.action_update_category)) },
                        onClick = {
                            onClickRefresh()
                            onMenuExpandedChange(false)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.action_open_random_manga)) },
                        onClick = {
                            onClickOpenRandomManga()
                            onMenuExpandedChange(false)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.action_saved_searches)) },
                        onClick = {
                            onClickSavedSearches()
                            onMenuExpandedChange(false)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsedBall(
    hasActiveFilters: Boolean,
    onClick: () -> Unit,
    onClickFilter: () -> Unit,
    onClickGlobalUpdate: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickOpenRandomManga: () -> Unit,
    onClickSavedSearches: () -> Unit,
) {
    // 點＝展開搜尋；長壓＝在球上彈出快捷選單（不必先展開）。
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
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
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { menuExpanded = true },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = stringResource(MR.strings.action_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (hasActiveFilters) {
            // 有套用篩選時，球右上角點個小色點提示。
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-4).dp, y = 4.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.active),
            )
        }
        // 長壓快捷選單：overflow 的各項在上、篩選在最下（最靠近球/拇指）。
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_update_library)) },
                onClick = {
                    onClickGlobalUpdate()
                    menuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_update_category)) },
                onClick = {
                    onClickRefresh()
                    menuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_open_random_manga)) },
                onClick = {
                    onClickOpenRandomManga()
                    menuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_saved_searches)) },
                onClick = {
                    onClickSavedSearches()
                    menuExpanded = false
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_filter)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.FilterList,
                        contentDescription = null,
                        tint = if (hasActiveFilters) MaterialTheme.colorScheme.active else LocalContentColor.current,
                    )
                },
                onClick = {
                    onClickFilter()
                    menuExpanded = false
                },
            )
        }
    }
}
