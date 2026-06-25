package eu.kanade.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.core.common.preference.TriState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Yakuyomi：探索的「篩選」sheet。整合全域篩選（跨所有來源：已收藏 / 已開卷三態）與來源自帶 extension 篩選
 * （功能類似，顯示上合在一處）。三態循環由呼叫端 toggle 處理；來源篩選有才顯示入口。
 */
@Composable
fun BrowseGlobalFilterDialog(
    favorite: TriState,
    read: TriState,
    fetched: TriState,
    onToggleFavorite: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleFetched: () -> Unit,
    onClear: () -> Unit,
    onDismissRequest: () -> Unit,
    hasSourceFilters: Boolean = false,
    onOpenSourceFilters: (() -> Unit)? = null,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            TriStateItem(
                label = stringResource(MR.strings.action_browse_filter_favorite),
                state = favorite,
                onClick = { onToggleFavorite() },
            )
            TriStateItem(
                label = stringResource(MR.strings.action_browse_filter_read),
                state = read,
                onClick = { onToggleRead() },
            )
            TriStateItem(
                label = stringResource(MR.strings.action_browse_filter_fetched),
                state = fetched,
                onClick = { onToggleFetched() },
            )
            // Yakuyomi：清除全域篩選（只在有啟用時顯示）。
            if (favorite != TriState.DISABLED || read != TriState.DISABLED || fetched != TriState.DISABLED) {
                ListItem(
                    modifier = Modifier.clickable(onClick = onClear),
                    leadingContent = { Icon(Icons.Outlined.FilterAltOff, contentDescription = null) },
                    headlineContent = { Text(stringResource(MR.strings.action_clear_filters)) },
                )
            }
            if (hasSourceFilters && onOpenSourceFilters != null) {
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable(onClick = onOpenSourceFilters),
                    leadingContent = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
                    headlineContent = { Text(stringResource(MR.strings.action_source_filters)) },
                )
            }
        }
    }
}
