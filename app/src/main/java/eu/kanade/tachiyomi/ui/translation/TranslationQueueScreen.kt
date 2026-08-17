package eu.kanade.tachiyomi.ui.translation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.Tab
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.data.translation.ModelDownloadManager
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object TranslationQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<TranslationQueueViewModel>(factory = TranslationQueueViewModel.Factory)
        TranslationQueueContent(viewModel, navigateUp = navigator::pop)
    }
}

/**
 * 翻譯佇列導覽列分頁（取代「更新」分頁的位置）。點＝開佇列；長按由 [eu.kanade.tachiyomi.ui.home.HomeScreen]
 * 攔截 → 顯示引擎狀態對話框（[TranslationEngineStatusDialog]，卸下/預載）。
 */
data object TranslationTab : Tab {

    // Yakuyomi：再點一次「翻譯」分頁 → 三態循環（見 TranslationQueueViewModel.cycleEngineState）。
    private val reselectChannel = Channel<Unit>()

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 1u,
            // 導覽列標籤用短版「翻譯」（與書櫃/記錄/探索/其他等長）；頁面標題仍是「翻譯佇列」。
            title = stringResource(MR.strings.label_translation),
            icon = rememberVectorPainter(Icons.Outlined.Translate),
        )

    override suspend fun onReselect(navigator: Navigator) {
        reselectChannel.send(Unit)
    }

    @Composable
    override fun Content() {
        val viewModel = viewModel<TranslationQueueViewModel>(factory = TranslationQueueViewModel.Factory)
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            reselectChannel.receiveAsFlow().collectLatest {
                context.toast(viewModel.cycleEngineState())
            }
        }
        TranslationQueueContent(viewModel, navigateUp = null)
    }
}

/** 佇列頁的「漫畫分組」（同一本的章收成一組；queueState 仍是扁平章快照，UI 在此 group by manga）。 */
private data class MangaGroup(
    val manga: Manga,
    val chapters: List<TranslationItem>,
)

@Composable
private fun TranslationQueueContent(
    viewModel: TranslationQueueViewModel,
    navigateUp: (() -> Unit)?,
) {
    val navigator = LocalNavigator.currentOrThrow
    val items by viewModel.queueState.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val pausedMangas by viewModel.pausedMangas.collectAsState()
    // 硬總開關：關閉時藏引擎面板（不讓手動預載繞過總開關）、佇列空則顯示「翻譯已關閉」。佇列非空（殘留）仍可看/清。
    val masterEnabled by remember { Injekt.get<TranslationPreferences>() }.translationMasterEnabled.collectAsState()

    // 以漫畫分組（groupBy 用 LinkedHashMap → 保留漫畫首次出現順序＝佇列漫畫順序）。
    val groups = remember(items) {
        items.groupBy { it.manga.id }.map { (_, list) -> MangaGroup(list.first().manga, list) }
    }

    // 每本展開的 mangaId（預設摺疊）。用 rememberSaveable：折疊機折/展是 config change，否則展開狀態會丟、全合起來。
    val expandedIds = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableStateListOf<Long>() }
    val allExpanded = groups.isNotEmpty() && groups.all { it.manga.id in expandedIds }
    // 佇列是否有失敗章節（平板工具列「重試全部失敗」鈕的顯示條件）。
    val hasFailed = groups.any { g -> g.chapters.any { it.status == TranslationItem.Status.ERROR } }

    // 平板/折疊機展開＝主從雙欄（左清單 + 右選中本章節）；直板＝手風琴。選中本（跨重啟保留；失效則回退第一本）。
    val isTablet = isTabletUi()
    var selectedMangaId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(groups) {
        if (groups.none { it.manga.id == selectedMangaId }) {
            selectedMangaId = groups.firstOrNull()?.manga?.id
        }
    }

    // 拖曳重排（以「本」為單位）：本地鏡像漫畫順序 + reorderable；拖曳中本地先行、同時回寫 manager（持久化、drain 立即生效）。
    val lazyListState = rememberLazyListState()
    val reorderGroups = remember { groups.toMutableStateList() }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val moved = reorderGroups.removeAt(from.index)
        reorderGroups.add(to.index, moved)
        viewModel.reorderMangas(reorderGroups.map { it.manga.id })
    }
    LaunchedEffect(groups) {
        if (!reorderableState.isAnyItemDragging) {
            reorderGroups.clear()
            reorderGroups.addAll(groups)
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
                        if (groups.isNotEmpty()) {
                            val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
                            Pill(
                                text = "${groups.size}",
                                modifier = Modifier.padding(start = 4.dp),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                                fontSize = 14.sp,
                            )
                        }
                    }
                },
                navigateUp = navigateUp,
                actions = {
                    if (groups.isNotEmpty()) {
                        if (isTablet) {
                            // 平板＝主從雙欄、無收合概念 → 把「展開/收合全部」換成「重試全部失敗」（有失敗才顯示）。
                            if (hasFailed) {
                                IconButton(onClick = { viewModel.retryAllFailed() }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = stringResource(MR.strings.action_retry_all_failed),
                                    )
                                }
                            }
                        } else {
                            // 手機＝手風琴：全部展開 / 全部收合。
                            IconButton(
                                onClick = {
                                    if (allExpanded) {
                                        expandedIds.clear()
                                    } else {
                                        expandedIds.clear()
                                        expandedIds.addAll(groups.map { it.manga.id })
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (allExpanded) {
                                        Icons.Outlined.UnfoldLess
                                    } else {
                                        Icons.Outlined.UnfoldMore
                                    },
                                    contentDescription = stringResource(
                                        if (allExpanded) {
                                            MR.strings.action_collapse_all
                                        } else {
                                            MR.strings.action_expand_all
                                        },
                                    ),
                                )
                            }
                        }
                        AppBarActions(
                            persistentListOf(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_cancel_all),
                                    onClick = viewModel::clearQueue,
                                ),
                            ),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            // 全域暫停/繼續（最上層；與每本暫停獨立）。
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
                onClick = { if (isPaused) viewModel.resume() else viewModel.pause() },
                expanded = true,
                modifier = Modifier.animateFloatingActionButton(
                    visible = groups.isNotEmpty(),
                    alignment = Alignment.BottomEnd,
                ),
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding)) {
            // 引擎狀態面板（#7）：常駐顯示在佇列頁頂，可卸下 / 預載。總開關關時藏起（引擎本就不該載）。
            // 模型不可用時面板會改顯示「更新/下載模型」→ 導去 設定→翻譯。
            if (masterEnabled) {
                EngineStatusPanel(
                    onOpenModelSettings = {
                        navigator.push(SettingsScreen(SettingsScreen.Destination.Translation))
                    },
                )
            }
            if (groups.isEmpty()) {
                EmptyScreen(
                    stringRes = if (masterEnabled) {
                        MR.strings.information_no_translations
                    } else {
                        MR.strings.translation_master_off_message
                    },
                )
            } else if (isTablet) {
                // 主從雙欄：左清單（可選中、不展開、無拖曳）＋右選中本的章節細節。
                TwoPanelBox(
                    startContent = {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(groups, key = { it.manga.id }) { group ->
                                MangaGroupCard(
                                    group = group,
                                    paused = group.manga.id in pausedMangas,
                                    expanded = false,
                                    selected = group.manga.id == selectedMangaId,
                                    showExpandIcon = false,
                                    onToggleExpand = { selectedMangaId = group.manga.id },
                                    onClickCover = { navigator.push(MangaScreen(group.manga.id)) },
                                    onStartNow = { viewModel.startMangaNow(group.manga.id) },
                                    onPauseManga = { viewModel.pauseManga(group.manga.id) },
                                    onResumeManga = { viewModel.resumeManga(group.manga.id) },
                                    onRetryManga = { viewModel.retryManga(group.manga.id) },
                                    onCancelManga = { viewModel.cancelManga(group.manga.id) },
                                    onSetMethod = { method -> viewModel.setMangaMethod(group.manga.id, method) },
                                    onCancelChapter = { id -> viewModel.cancelChapter(id) },
                                )
                            }
                        }
                    },
                    endContent = {
                        DetailPane(
                            group = groups.firstOrNull { it.manga.id == selectedMangaId },
                            onCancelChapter = { id -> viewModel.cancelChapter(id) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            } else {
                LazyColumn(state = lazyListState, modifier = Modifier.fillMaxWidth()) {
                    items(reorderGroups, key = { it.manga.id }) { group ->
                        ReorderableItem(reorderableState, key = group.manga.id) {
                            MangaGroupCard(
                                modifier = Modifier.longPressDraggableHandle(),
                                group = group,
                                paused = group.manga.id in pausedMangas,
                                expanded = group.manga.id in expandedIds,
                                onToggleExpand = {
                                    if (group.manga.id in expandedIds) {
                                        expandedIds.remove(group.manga.id)
                                    } else {
                                        expandedIds.add(group.manga.id)
                                    }
                                },
                                onClickCover = { navigator.push(MangaScreen(group.manga.id)) },
                                onStartNow = { viewModel.startMangaNow(group.manga.id) },
                                onPauseManga = { viewModel.pauseManga(group.manga.id) },
                                onResumeManga = { viewModel.resumeManga(group.manga.id) },
                                onRetryManga = { viewModel.retryManga(group.manga.id) },
                                onCancelManga = { viewModel.cancelManga(group.manga.id) },
                                onSetMethod = { method -> viewModel.setMangaMethod(group.manga.id, method) },
                                onCancelChapter = { id -> viewModel.cancelChapter(id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一本漫畫的佇列卡：表頭（封面 + 標題 + 進度副標 + 展開箭頭）＋ 控制列（去字 chip / ⏫搶翻 / ⏸暫停⇄▶繼續 /
 * ↻重試 / ✕刪除，全對整本）＋ 展開時的章列（每章狀態 + ✕ 刪單章）。長按整卡＝以本拖曳重排。
 */
@Composable
private fun MangaGroupCard(
    group: MangaGroup,
    paused: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onClickCover: () -> Unit,
    onStartNow: () -> Unit,
    onPauseManga: () -> Unit,
    onResumeManga: () -> Unit,
    onRetryManga: () -> Unit,
    onCancelManga: () -> Unit,
    onSetMethod: (String) -> Unit,
    onCancelChapter: (Long) -> Unit,
    selected: Boolean = false,
    showExpandIcon: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val translating = group.chapters.firstOrNull { it.status == TranslationItem.Status.TRANSLATING }
    val errors = group.chapters.count { it.status == TranslationItem.Status.ERROR }
    val method = group.chapters.firstOrNull { it.method.isNotBlank() }?.method

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            ),
    ) {
        // 表頭：封面左，右側欄（標題 + 副標 + 沉底的控制列）以 fillMaxHeight 撐滿封面高度 → 控制列下緣貼齊封面下緣。
        // 點整列切換展開；點封面 → 跳作品頁。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover.Book(
                data = group.manga.thumbnailUrl,
                modifier = Modifier
                    .width(56.dp)
                    .clickable(onClick = onClickCover),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Text(
                    text = group.manga.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = groupSubtitle(group, paused, translating, errors),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f)) // 把控制列推到欄底，下緣對齊封面下緣
                // 控制列（整本）：去字 chip + ⏫搶翻 / ⏸暫停⇄▶繼續 / ↻重試 / ✕刪除，靠左緊接。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (method != null) {
                        MethodChip(method = method, editable = true, onSetMethod = onSetMethod)
                        Spacer(Modifier.width(4.dp))
                    }
                    GroupActionButton(
                        Icons.Outlined.KeyboardDoubleArrowUp,
                        MR.strings.action_priority_translate,
                        onStartNow,
                    )
                    if (paused) {
                        GroupActionButton(Icons.Filled.PlayArrow, MR.strings.action_resume, onResumeManga)
                    } else {
                        GroupActionButton(Icons.Outlined.Pause, MR.strings.action_pause, onPauseManga)
                    }
                    if (errors > 0) {
                        GroupActionButton(Icons.Outlined.Refresh, MR.strings.action_retry, onRetryManga)
                    }
                    GroupActionButton(Icons.Outlined.Close, MR.strings.action_cancel, onCancelManga)
                }
            }
            if (showExpandIcon) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            }
        }
        // 展開：章列（每章狀態 + ✕ 刪單章）。
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                group.chapters.forEach { ch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = statusLine(ch),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onCancelChapter(ch.chapter.id) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(MR.strings.action_cancel),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 主從雙欄的右欄：選中那本的章節清單（標題 header + 每章狀態 + ✕ 刪單章）。沒選中本則顯示空提示。 */
@Composable
private fun DetailPane(
    group: MangaGroup?,
    onCancelChapter: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (group == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(MR.strings.information_no_translations),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier = modifier) {
        item {
            Text(
                text = group.manga.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        items(group.chapters, key = { it.chapter.id }) { ch ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = statusLine(ch),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onCancelChapter(ch.chapter.id) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(MR.strings.action_cancel),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupActionButton(
    icon: ImageVector,
    contentDescriptionRes: dev.icerock.moko.resources.StringResource,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(contentDescriptionRes),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 摺疊狀態的副標：翻譯中那話 + 頁進度、剩 N 話、失敗數、已暫停。 */
@Composable
private fun groupSubtitle(
    group: MangaGroup,
    paused: Boolean,
    translating: TranslationItem?,
    errors: Int,
): String = buildString {
    if (translating != null) {
        append(stringResource(MR.strings.translation_status_translating))
        if (translating.total > 0) append(" ${translating.done}/${translating.total}")
        append(" · ")
    }
    append(stringResource(MR.strings.queue_remaining_chapters, group.chapters.size))
    if (errors > 0) {
        append(" · ")
        append(stringResource(MR.strings.queue_chapters_failed, errors))
    }
    if (paused) {
        append(" · ")
        append(stringResource(MR.strings.queue_manga_paused))
    }
}

/**
 * 常駐翻譯引擎狀態列（#6/#7）：顯示載入中 / 已預載 / 未預載，並提供卸下（釋放 ~100MB）/ 預載按鈕。
 *
 * **模型不可用時**（舊版 v1 / 未下載，[TranslationEngineConfig.modelsResolvable]＝false）：不再讓「預載引擎」靜默失敗，
 * 改顯示 ⚠️「模型需更新 / 未下載」＋按鈕「更新模型 / 下載模型」→ 點了 [onOpenModelSettings] 導去設定的模型區。
 *
 * @param onOpenModelSettings 導去 設定→翻譯（模型下載/更新）。
 */
@Composable
internal fun EngineStatusPanel(onOpenModelSettings: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engineService = remember { Injekt.get<TranslationEngineService>() }
    val loading by engineService.loading.collectAsState()
    val warm by engineService.warm.collectAsState()
    // 模型下載狀態（同一 singleton，設定頁下載也走它）：Done → 這裡的模型檢查重跑，避免更新完仍卡「更新模型」。
    val modelDownloadManager = remember { Injekt.get<ModelDownloadManager>() }
    val downloadState by modelDownloadManager.state.collectAsState()
    // 模型是否可被引擎載入（strict）＋是否有舊檔待更新。key 在 warm/loading/downloadState → 引擎或模型狀態變就重查。
    val modelState by produceState<Pair<Boolean, Boolean>?>(initialValue = null, warm, loading, downloadState) {
        value = withContext(Dispatchers.IO) {
            TranslationEngineConfig.modelsResolvable(context) to TranslationEngineConfig.modelsOutdated(context)
        }
    }
    val outdated = modelState?.second ?: false
    val needsModels = modelState?.let { !it.first } ?: false // 查完且不可用 → 顯示更新/下載引導（查完前不顯示、避免一閃）
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (loading) Icons.Outlined.Sync else Icons.Outlined.Translate,
            contentDescription = null,
            tint = when {
                warm -> MaterialTheme.colorScheme.primary
                needsModels -> MaterialTheme.colorScheme.error // 模型不可用 → 醒目 error 色
                else -> LocalContentColor.current.copy(alpha = 0.5f)
            },
        )
        Text(
            text = when {
                loading -> stringResource(MR.strings.engine_status_loading)
                warm -> stringResource(MR.strings.engine_status_warm)
                needsModels && outdated -> stringResource(MR.strings.engine_status_models_outdated)
                needsModels -> stringResource(MR.strings.engine_status_models_missing)
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
            // 模型不可用 → 別讓「預載引擎」空轉（resolveModelSet 回 null、停在未預載）→ 導去設定更新/下載模型。
            needsModels -> TextButton(onClick = onOpenModelSettings) {
                val label = if (outdated) {
                    MR.strings.pref_translation_update_models
                } else {
                    MR.strings.pref_translation_download_models
                }
                Text(stringResource(label))
            }
            else -> TextButton(onClick = { engineService.warmUpAsync() }) {
                Text(stringResource(MR.strings.engine_preload_action))
            }
        }
    }
}

/**
 * 去字方法小標籤：
 *  - [editable]＝可點的 [AssistChip]，點開 [DropdownMenu] 列 2 選項（快速去字 / AI 去字），
 *    選後呼叫 [onSetMethod]（傳原始字串 boxfill / auto_whole）。
 *  - 否則＝靜態文字（方法已鎖、不可改）。
 *
 * [method] 空字串（理論上不會發生：翻譯項都帶 method）→ 不畫任何東西。
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

/** 去字方法原始字串 → 友善標籤（對齊 MangaScreen/ReaderPageActionsDialog 的 2 門別命名）。 */
@Composable
private fun methodLabel(raw: String): String = when (raw) {
    "boxfill" -> stringResource(MR.strings.rerender_boxfill)
    "auto_tile" -> stringResource(MR.strings.rerender_auto_tile) // 退役：只給舊存值顯示用，非可選項
    else -> stringResource(MR.strings.rerender_auto_whole) // auto_whole（預設）；舊存的 lama_* 等也落這
}

/** 可選的去字方法原始字串，順序＝2 門別 快速去字 / AI 去字（顯示名走 [methodLabel]）。 */
private val METHOD_IDS = listOf("boxfill", "auto_whole")

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

private class TranslationQueueViewModel(
    private val translationManager: TranslationManager = Injekt.get(),
    private val engineService: TranslationEngineService = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
) : ViewModel() {

    companion object {
        // Yakuyomi：本類別是 private（JVM package-private）→ AndroidX 預設 factory 的反射建不出來，
        // 必須給明確 factory（對照上游 WorkerInfoScreen/TrackInfoDialog 的私有 Model 寫法）。
        val Factory = viewModelFactory {
            initializer { TranslationQueueViewModel() }
        }
    }

    val queueState: StateFlow<List<TranslationItem>> = translationManager.queueState
    val isPaused: StateFlow<Boolean> = translationManager.isPaused
    val pausedMangas: StateFlow<Set<Long>> = translationManager.pausedMangas

    /**
     * Yakuyomi：「翻譯」分頁再點一次的三態循環（總開關 × 引擎是否載入），一直切換循環：
     *  A 總開關開 + 引擎已載入 → （有任務先暫停）卸載引擎（釋放 ~100MB）。
     *  B 總開關開 + 引擎未載入 → 關總開關。
     *  C 總開關關 → 開總開關 + 載入引擎。
     * 回傳要 toast 的提示字串。
     */
    fun cycleEngineState(): dev.icerock.moko.resources.StringResource {
        val master = translationPreferences.translationMasterEnabled.get()
        val warm = engineService.warm.value
        return when {
            master && warm -> {
                val hadTasks = translationManager.queueState.value.isNotEmpty()
                if (hadTasks) translationManager.pause()
                engineService.shutdownAsync()
                if (hadTasks) MR.strings.translation_retap_paused_unloaded else MR.strings.translation_retap_unloaded
            }
            master -> {
                translationPreferences.translationMasterEnabled.set(false)
                MR.strings.translation_retap_master_off
            }
            else -> {
                translationPreferences.translationMasterEnabled.set(true)
                engineService.warmUpAsync()
                MR.strings.translation_retap_master_on
            }
        }
    }

    fun clearQueue() = translationManager.clearQueue()
    fun pause() = translationManager.pause()
    fun resume() = translationManager.resume()

    // 單章刪除（展開列）。
    fun cancelChapter(chapterId: Long) = translationManager.cancel(listOf(chapterId))

    // 以「本」為單位的操作。
    fun startMangaNow(mangaId: Long) = translationManager.startMangaNow(mangaId)
    fun pauseManga(mangaId: Long) = translationManager.pauseManga(mangaId)
    fun resumeManga(mangaId: Long) = translationManager.resumeManga(mangaId)
    fun retryManga(mangaId: Long) = translationManager.retryManga(mangaId)
    fun cancelManga(mangaId: Long) = translationManager.cancelManga(mangaId)

    /** Yakuyomi：重試佇列中所有含失敗（ERROR）章節的漫畫（逐本 retryManga）。 */
    fun retryAllFailed() {
        queueState.value
            .filter { it.status == TranslationItem.Status.ERROR }
            .map { it.manga.id }
            .distinct()
            .forEach { translationManager.retryManga(it) }
    }
    fun setMangaMethod(mangaId: Long, method: String) = translationManager.setMangaMethod(mangaId, method)
    fun reorderMangas(orderedMangaIds: List<Long>) = translationManager.reorderMangas(orderedMangaIds)
}
