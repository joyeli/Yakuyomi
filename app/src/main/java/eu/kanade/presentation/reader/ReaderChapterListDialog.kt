package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Yakuyomi：reader 內章節清單對話框——列出本作所有（已過濾/排序的）章節，點章直接跳轉不離開 reader。
 * 開啟時自動捲到當前章；當前章以主色 +「正在讀」箭頭標示，已讀章淡化。
 */
@Composable
fun ReaderChapterListDialog(
    onDismissRequest: () -> Unit,
    chapters: List<ReaderChapter>,
    currentChapterId: Long?,
    onSelectChapter: (Long) -> Unit,
) {
    val currentIndex = remember(chapters, currentChapterId) {
        chapters.indexOfFirst { it.chapter.id == currentChapterId }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (currentIndex > 0) listState.scrollToItem(currentIndex)
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = MaterialTheme.padding.small),
        ) {
            item {
                Text(
                    text = stringResource(MR.strings.reader_chapter_list),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.padding.medium,
                        vertical = MaterialTheme.padding.small,
                    ),
                )
            }
            items(
                items = chapters,
                key = { it.chapter.url },
            ) { readerChapter ->
                val chapter = readerChapter.chapter
                val isCurrent = chapter.id == currentChapterId
                val titleColor = when {
                    isCurrent -> MaterialTheme.colorScheme.primary
                    chapter.read -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    else -> MaterialTheme.colorScheme.onSurface
                }
                ListItem(
                    modifier = Modifier.clickable {
                        chapter.id?.let(onSelectChapter)
                        onDismissRequest()
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = if (isCurrent) {
                        {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    headlineContent = {
                        Text(
                            text = chapter.name,
                            color = titleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    supportingContent = chapter.scanlator?.takeIf { it.isNotBlank() }?.let { scanlator ->
                        {
                            Text(
                                text = scanlator,
                                color = titleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                )
            }
        }
    }
}
