package eu.kanade.presentation.browse.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import tachiyomi.presentation.core.components.Badge

@Composable
internal fun InLibraryBadge(enabled: Boolean) {
    if (enabled) {
        Badge(
            imageVector = Icons.Outlined.CollectionsBookmark,
        )
    }
}

@Composable
internal fun AnchorBadge(enabled: Boolean, filteredOut: Boolean = false) {
    if (enabled) {
        // Yakuyomi：探索錨點旗標（「上次處理到這」）。旗標放大 + 封面紅框/紅染（見 grid item）一起凸顯。
        // filteredOut＝該本不符當前篩選、僅因錨點被強制留下 → 旗標轉灰（搭配封面暗化）以資區別。
        Badge(
            imageVector = Icons.Outlined.Flag,
            color = if (filteredOut) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            iconColor = if (filteredOut) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onError
            },
            iconSize = 20.sp,
        )
    }
}
