package eu.kanade.tachiyomi.ui.translation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object TranslationQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { TranslationQueueScreenModel() }
        val items by screenModel.queueState.collectAsState()
        val isPaused by screenModel.isPaused.collectAsState()

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

        Scaffold(
            topBar = {
                AppBar(
                    titleContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(MR.strings.label_translation_queue),
                                maxLines = 1,
                                modifier = Modifier.weight(1f, false),
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (items.isNotEmpty()) {
                                val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
                                Pill(
                                    text = "${items.size}",
                                    modifier = Modifier.padding(start = 4.dp),
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        if (items.isNotEmpty()) {
                            AppBarActions(
                                persistentListOf(
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_cancel_all),
                                        onClick = screenModel::clearQueue,
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = {
                        Text(
                            text = stringResource(
                                if (isPaused) MR.strings.action_resume else MR.strings.action_pause,
                            ),
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Outlined.Pause,
                            contentDescription = null,
                        )
                    },
                    onClick = { if (isPaused) screenModel.resume() else screenModel.pause() },
                    expanded = true,
                    modifier = Modifier.animateFloatingActionButton(
                        visible = items.isNotEmpty(),
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
        ) { contentPadding ->
            if (items.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.information_no_translations,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }
            LazyColumn(
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(items, key = { it.chapter.id }) { item ->
                    TranslationQueueRow(
                        item = item,
                        onCancel = { screenModel.cancel(item.chapter.id) },
                        onRetry = { screenModel.retry(item.chapter.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TranslationQueueRow(
    item: TranslationItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.manga.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusLine(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.status == TranslationItem.Status.ERROR) {
            IconButton(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(MR.strings.action_retry))
            }
        }
        if (item.status != TranslationItem.Status.TRANSLATING) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.strings.action_cancel))
            }
        }
    }
}

@Composable
private fun statusLine(item: TranslationItem): String {
    val chapter = item.chapter.name
    val status = when (item.status) {
        TranslationItem.Status.QUEUE -> stringResource(MR.strings.translation_status_queued)
        TranslationItem.Status.TRANSLATING ->
            if (item.total > 0) {
                "${stringResource(MR.strings.translation_status_translating)} ${item.done}/${item.total}"
            } else {
                stringResource(MR.strings.translation_status_translating)
            }
        TranslationItem.Status.ERROR -> stringResource(MR.strings.translation_status_error)
    }
    return "$chapter • $status"
}

private class TranslationQueueScreenModel(
    private val translationManager: TranslationManager = Injekt.get(),
) : ScreenModel {
    val queueState: StateFlow<List<TranslationItem>> = translationManager.queueState
    val isPaused: StateFlow<Boolean> = translationManager.isPaused

    fun cancel(chapterId: Long) = translationManager.cancel(listOf(chapterId))
    fun retry(chapterId: Long) = translationManager.retry(listOf(chapterId))
    fun clearQueue() = translationManager.clearQueue()
    fun pause() = translationManager.pause()
    fun resume() = translationManager.resume()
}
