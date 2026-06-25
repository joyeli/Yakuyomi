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
internal fun AnchorBadge(enabled: Boolean) {
    if (enabled) {
        // Yakuyomi：探索錨點旗標（「上次處理到這」）。旗標放大 + 封面紅框/紅染（見 grid item）一起凸顯。
        Badge(
            imageVector = Icons.Outlined.Flag,
            color = MaterialTheme.colorScheme.error,
            iconColor = MaterialTheme.colorScheme.onError,
            iconSize = 20.sp,
        )
    }
}
