package eu.kanade.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Yakuyomi：探索長按漫畫的動作選單（取代「長按直接收藏」）。加入/移除書庫 + 設/清錨點 + 開啟。
 */
@Composable
fun BrowseMangaActionsDialog(
    favorite: Boolean,
    isAnchor: Boolean,
    onToggleLibrary: () -> Unit,
    onToggleAnchor: () -> Unit,
    onOpenManga: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenManga),
                leadingContent = { Icon(Icons.Outlined.OpenInNew, contentDescription = null) },
                headlineContent = { Text(stringResource(MR.strings.action_open_entry)) },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onToggleLibrary),
                leadingContent = { Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null) },
                headlineContent = {
                    Text(
                        stringResource(
                            if (favorite) MR.strings.remove_from_library else MR.strings.add_to_library,
                        ),
                    )
                },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onToggleAnchor),
                leadingContent = { Icon(Icons.Outlined.Flag, contentDescription = null) },
                headlineContent = {
                    Text(
                        stringResource(
                            if (isAnchor) MR.strings.action_clear_anchor else MR.strings.action_set_anchor,
                        ),
                    )
                },
            )
        }
    }
}
