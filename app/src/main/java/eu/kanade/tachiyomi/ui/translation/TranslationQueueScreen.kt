package eu.kanade.tachiyomi.ui.translation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.StateFlow
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
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
        TranslationQueueContent(screenModel, navigateUp = navigator::pop)
    }
}

/**
 * 翻譯佇列導覽列分頁（取代「更新」分頁的位置）。點＝開佇列；長按由 [eu.kanade.tachiyomi.ui.home.HomeScreen]
 * 攔截 → 顯示引擎狀態對話框（[TranslationEngineStatusDialog]，卸下/預載）。
 */
data object TranslationTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 1u,
            title = stringResource(MR.strings.label_translation_queue),
            icon = rememberVectorPainter(Icons.Outlined.Translate),
        )

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { TranslationQueueScreenModel() }
        TranslationQueueContent(screenModel, navigateUp = null)
    }
}

@Composable
private fun TranslationQueueContent(
    screenModel: TranslationQueueScreenModel,
    navigateUp: (() -> Unit)?,
) {
    val navigator = LocalNavigator.currentOrThrow
    val items by screenModel.queueState.collectAsState()
    val isPaused by screenModel.isPaused.collectAsState()

    // 拖曳重排（#1）：本地鏡像 + reorderable。拖曳中本地先行重排（順手）、同時回寫 manager（持久化、drain 立即生效）；
    // 非拖曳中以最新佇列（含進度更新）重新同步。
    val lazyListState = rememberLazyListState()
    val reorderItems = remember { items.toMutableStateList() }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val moved = reorderItems.removeAt(from.index)
        reorderItems.add(to.index, moved)
        screenModel.reorderQueue(moved.chapter.id, to.index)
    }
    LaunchedEffect(items) {
        if (!reorderableState.isAnyItemDragging) {
            reorderItems.clear()
            reorderItems.addAll(items)
        }
    }

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
                navigateUp = navigateUp,
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
        Column(modifier = Modifier.padding(contentPadding)) {
            // 引擎狀態面板（#7）：常駐顯示在佇列頁頂，可卸下 / 預載。
            EngineStatusPanel()
            if (items.isEmpty()) {
                EmptyScreen(stringRes = MR.strings.information_no_translations)
            } else {
                LazyColumn(state = lazyListState, modifier = Modifier.fillMaxWidth()) {
                    items(reorderItems, key = { it.chapter.id }) { item ->
                        ReorderableItem(reorderableState, key = item.chapter.id) {
                            TranslationQueueRow(
                                modifier = Modifier.longPressDraggableHandle(),
                                item = item,
                                onClickManga = { navigator.push(MangaScreen(item.manga.id)) },
                                onCancel = { screenModel.cancel(item.chapter.id) },
                                onRetry = { screenModel.retry(item.chapter.id) },
                                onSetMethod = { method -> screenModel.setItemMethod(item.chapter.id, method) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 常駐翻譯引擎狀態列（#6/#7）：顯示載入中 / 已預載 / 未預載，並提供卸下（釋放 ~450MB）/ 預載按鈕。
 * 由佇列頁頂與導覽列長按對話框（[TranslationEngineStatusDialog]）共用。
 */
@Composable
internal fun EngineStatusPanel(modifier: Modifier = Modifier) {
    val engineService = remember { Injekt.get<TranslationEngineService>() }
    val loading by engineService.loading.collectAsState()
    val warm by engineService.warm.collectAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (loading) Icons.Outlined.Sync else Icons.Outlined.Translate,
            contentDescription = null,
            tint = if (warm) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = 0.5f),
        )
        Text(
            text = when {
                loading -> stringResource(MR.strings.engine_status_loading)
                warm -> stringResource(MR.strings.engine_status_warm)
                else -> stringResource(MR.strings.engine_status_cold)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        )
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            warm -> TextButton(onClick = { engineService.shutdownAsync() }) {
                Text(stringResource(MR.strings.engine_unload))
            }
            else -> TextButton(onClick = { engineService.warmUpAsync() }) {
                Text(stringResource(MR.strings.engine_preload_action))
            }
        }
    }
}

@Composable
private fun TranslationQueueRow(
    item: TranslationItem,
    onClickManga: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSetMethod: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 點作品名稱區 → 跳到該作品目錄（MangaScreen）；長按整列＝拖曳重排（互不衝突）。
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClickManga),
        ) {
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
        // 去字方法：QUEUE 或 TRANSLATING 皆可改（點 chip 開選單）；翻譯中改＝停在頁邊界、以新法續傳剩餘頁。ERROR 鎖定、顯示靜態標籤。
        MethodChip(
            method = item.method,
            editable = item.status == TranslationItem.Status.QUEUE ||
                item.status == TranslationItem.Status.TRANSLATING,
            onSetMethod = onSetMethod,
        )
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

/**
 * 去字方法小標籤：
 *  - [editable]（QUEUE 項）＝可點的 [AssistChip]，點開 [DropdownMenu] 列 3 選項（BoxFill / Auto-整頁 / Auto-逐格），
 *    選後呼叫 [onSetMethod]（傳原始字串 boxfill / auto_whole / auto_tile）。
 *  - 否則（TRANSLATING/ERROR）＝靜態文字（方法已鎖、不可改）。
 *
 * [method] 空字串（理論上不會發生：翻譯項都帶 method、重繪項帶 reRenderMethod）→ 不畫任何東西。
 */
@Composable
private fun MethodChip(
    method: String,
    editable: Boolean,
    onSetMethod: (String) -> Unit,
) {
    if (method.isBlank()) return
    val label = methodLabel(method)
    if (!editable) {
        // 鎖定：純標籤（淡色），不可互動。
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(text = label, style = MaterialTheme.typography.labelSmall) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            METHOD_IDS.forEach { raw ->
                DropdownMenuItem(
                    text = { Text(methodLabel(raw)) },
                    onClick = {
                        expanded = false
                        if (raw != method) onSetMethod(raw)
                    },
                )
            }
        }
    }
}

/** 去字方法原始字串 → 友善標籤（對齊 MangaScreen/ReaderPageActionsDialog 的 3 階梯命名）。 */
@Composable
private fun methodLabel(raw: String): String = when (raw) {
    "boxfill" -> stringResource(MR.strings.rerender_boxfill)
    "auto_tile" -> stringResource(MR.strings.rerender_auto_tile)
    else -> stringResource(MR.strings.rerender_auto_whole) // auto_whole（預設）；舊存的 lama_* 等也落這
}

/** 可選的去字方法原始字串，順序＝3 階梯 BoxFill / Auto-整頁 / Auto-逐格（顯示名走 [methodLabel]）。 */
private val METHOD_IDS = listOf("boxfill", "auto_whole", "auto_tile")

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
    fun reorderQueue(chapterId: Long, toIndex: Int) = translationManager.reorderQueue(chapterId, toIndex)
    fun clearQueue() = translationManager.clearQueue()
    fun pause() = translationManager.pause()
    fun resume() = translationManager.resume()

    /** 改某章去字方法（QUEUE 或 TRANSLATING；委派 [TranslationManager.setItemMethod]，翻譯中改＝以新法續傳剩餘頁）。 */
    fun setItemMethod(chapterId: Long, method: String) = translationManager.setItemMethod(chapterId, method)
}
