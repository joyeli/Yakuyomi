package eu.kanade.tachiyomi.ui.translation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
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
import eu.kanade.tachiyomi.data.translation.model.QueuePoolKey
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
import tachiyomi.presentation.core.i18n.pluralStringResource
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

    // Yakuyomi：再點一次「佇列」分頁 → 翻譯引擎三態循環（見 TranslationQueueViewModel.cycleEngineState）。
    private val reselectChannel = Channel<Unit>()

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 1u,
            // 導覽列標籤「佇列」（2026-09-27 從「翻譯」改名：這條佇列同時跑翻譯與夜讀兩個 pool，圖示也換成排隊清單、
            // 與「翻譯」設定頁的 文A 脫鉤，免得被當成翻譯設定）。
            title = stringResource(MR.strings.label_translation),
            icon = rememberVectorPainter(Icons.AutoMirrored.Outlined.PlaylistPlay),
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

/**
 * 佇列頁的「分組」＝同一本 × 同一個 pool（翻譯／重繪 vs 夜讀）的章收成一組；queueState 仍是扁平章快照，UI 在此
 * group by [QueuePoolKey]。同一本若既有翻譯項又有夜讀項會是兩張卡：兩個 pool 是獨立消費者，整本暫停／搶翻／取消
 * 各管各的，混在一張卡上一顆 ⏸ 說不清停的是哪邊。
 */
private data class MangaGroup(
    val manga: Manga,
    val night: Boolean,
    val chapters: List<TranslationItem>,
) {
    val key: QueuePoolKey get() = QueuePoolKey(manga.id, night)

    /** LazyColumn／展開狀態用的字串鍵（rememberSaveable 存得了）。 */
    val id: String get() = "${manga.id}:${if (night) 1 else 0}"
}

@Composable
private fun TranslationQueueContent(
    viewModel: TranslationQueueViewModel,
    navigateUp: (() -> Unit)?,
) {
    val navigator = LocalNavigator.currentOrThrow
    val items by viewModel.queueState.collectAsState()
    val isTranslatePaused by viewModel.isTranslatePaused.collectAsState()
    val isNightPaused by viewModel.isNightPaused.collectAsState()
    val pausedMangas by viewModel.pausedMangas.collectAsState()
    // 硬總開關：關閉時藏引擎面板（不讓手動預載繞過總開關）、佇列空則顯示「翻譯已關閉」。佇列非空（殘留）仍可看/清。
    val translationPreferences = remember { Injekt.get<TranslationPreferences>() }
    val masterEnabled by translationPreferences.translationMasterEnabled.collectAsState()
    val nightEnabled by translationPreferences.nightReadEnabled.collectAsState()

    // 以「本 × pool」分組（groupBy 用 LinkedHashMap → 保留首次出現順序＝佇列順序）。
    val groups = remember(items) {
        items.groupBy { it.poolKey }.map { (key, list) -> MangaGroup(list.first().manga, key.night, list) }
    }

    // 每組展開的鍵（預設摺疊）。用 rememberSaveable：折疊機折/展是 config change，否則展開狀態會丟、全合起來。
    val expandedIds = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableStateListOf<String>() }
    val allExpanded = groups.isNotEmpty() && groups.all { it.id in expandedIds }
    // 佇列是否有失敗章節（平板工具列「重試全部失敗」鈕的顯示條件）。
    val hasFailed = groups.any { g -> g.chapters.any { it.status == TranslationItem.Status.ERROR } }

    // 平板/折疊機展開＝主從雙欄（左清單 + 右選中本章節）；直板＝手風琴。選中本（跨重啟保留；失效則回退第一本）。
    val isTablet = isTabletUi()
    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(groups) {
        if (groups.none { it.id == selectedGroupId }) {
            selectedGroupId = groups.firstOrNull()?.id
        }
    }

    // 拖曳重排（以「本」為單位）：本地鏡像漫畫順序 + reorderable；拖曳中本地先行、同時回寫 manager（持久化、drain 立即生效）。
    val lazyListState = rememberLazyListState()
    val reorderGroups = remember { groups.toMutableStateList() }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val moved = reorderGroups.removeAt(from.index)
        reorderGroups.add(to.index, moved)
        viewModel.reorderGroups(reorderGroups.map { it.key })
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
                                        expandedIds.addAll(groups.map { it.id })
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
            // 兩個 pool 各自的暫停/繼續（與每本暫停獨立）：翻譯／重繪一顆、夜讀一顆，各在「該類有項 且 該類總開關開」
            // 時才顯示（總開關關著 resume 是 no-op、鈕會是死的）。使用者靠這兩顆自己調度佇列先挑哪邊（佇列挑章不做自動
            // 優先權）；CPU 讓路由 TranslationManager 的 nightGovernor 自動處理（翻譯在跑時夜讀自動降為一次一頁、低優先權）。
            val hasTranslateItems = masterEnabled && items.any { it.kind != TranslationItem.Kind.NIGHT }
            val hasNightItems = nightEnabled && items.any { it.kind == TranslationItem.Kind.NIGHT }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallExtendedFloatingActionButton(
                    text = {
                        Text(
                            text = stringResource(
                                if (isNightPaused) MR.strings.queue_resume_night else MR.strings.queue_pause_night,
                            ),
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = if (isNightPaused) Icons.Filled.PlayArrow else Icons.Outlined.DarkMode,
                            contentDescription = null,
                        )
                    },
                    onClick = { if (isNightPaused) viewModel.resume(night = true) else viewModel.pause(night = true) },
                    expanded = true,
                    modifier = Modifier.animateFloatingActionButton(
                        visible = hasNightItems,
                        alignment = Alignment.BottomEnd,
                    ),
                )
                SmallExtendedFloatingActionButton(
                    text = {
                        Text(
                            text = stringResource(
                                if (isTranslatePaused) {
                                    MR.strings.queue_resume_translate
                                } else {
                                    MR.strings.queue_pause_translate
                                },
                            ),
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = if (isTranslatePaused) Icons.Filled.PlayArrow else Icons.Outlined.Pause,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        if (isTranslatePaused) viewModel.resume(night = false) else viewModel.pause(night = false)
                    },
                    expanded = true,
                    modifier = Modifier.animateFloatingActionButton(
                        visible = hasTranslateItems,
                        alignment = Alignment.BottomEnd,
                    ),
                )
            }
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
                // 兩個總開關都關才顯示「都關了」；只關翻譯、夜讀還開著，佇列仍可有夜讀工作，顯示一般空狀態。
                EmptyScreen(
                    stringRes = if (masterEnabled || nightEnabled) {
                        MR.strings.information_no_translations
                    } else {
                        MR.strings.queue_all_off_message
                    },
                )
            } else if (isTablet) {
                // 主從雙欄：左清單（可選中、不展開、無拖曳）＋右選中本的章節細節。
                TwoPanelBox(
                    startContent = {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(groups, key = { it.id }) { group ->
                                MangaGroupCard(
                                    group = group,
                                    paused = group.key in pausedMangas,
                                    expanded = false,
                                    selected = group.id == selectedGroupId,
                                    showExpandIcon = false,
                                    onToggleExpand = { selectedGroupId = group.id },
                                    onClickCover = { navigator.push(MangaScreen(group.manga.id)) },
                                    onStartNow = { viewModel.startMangaNow(group.key) },
                                    onPauseManga = { viewModel.pauseManga(group.key) },
                                    onResumeManga = { viewModel.resumeManga(group.key) },
                                    onRetryManga = { viewModel.retryManga(group.key) },
                                    onCancelManga = { viewModel.cancelManga(group.key) },
                                    onSetMethod = { method -> viewModel.setMangaMethod(group.manga.id, method) },
                                    onCancelChapter = { ch -> viewModel.cancelChapter(ch) },
                                    onPauseChapter = { ch -> viewModel.pauseChapter(ch) },
                                    onResumeChapter = { ch -> viewModel.resumeChapter(ch) },
                                )
                            }
                        }
                    },
                    endContent = {
                        DetailPane(
                            group = groups.firstOrNull { it.id == selectedGroupId },
                            onCancelChapter = { ch -> viewModel.cancelChapter(ch) },
                            onPauseChapter = { ch -> viewModel.pauseChapter(ch) },
                            onResumeChapter = { ch -> viewModel.resumeChapter(ch) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            } else {
                LazyColumn(state = lazyListState, modifier = Modifier.fillMaxWidth()) {
                    items(reorderGroups, key = { it.id }) { group ->
                        ReorderableItem(reorderableState, key = group.id) {
                            MangaGroupCard(
                                modifier = Modifier.longPressDraggableHandle(),
                                group = group,
                                paused = group.key in pausedMangas,
                                expanded = group.id in expandedIds,
                                onToggleExpand = {
                                    if (group.id in expandedIds) {
                                        expandedIds.remove(group.id)
                                    } else {
                                        expandedIds.add(group.id)
                                    }
                                },
                                onClickCover = { navigator.push(MangaScreen(group.manga.id)) },
                                onStartNow = { viewModel.startMangaNow(group.key) },
                                onPauseManga = { viewModel.pauseManga(group.key) },
                                onResumeManga = { viewModel.resumeManga(group.key) },
                                onRetryManga = { viewModel.retryManga(group.key) },
                                onCancelManga = { viewModel.cancelManga(group.key) },
                                onSetMethod = { method -> viewModel.setMangaMethod(group.manga.id, method) },
                                onCancelChapter = { ch -> viewModel.cancelChapter(ch) },
                                onPauseChapter = { ch -> viewModel.pauseChapter(ch) },
                                onResumeChapter = { ch -> viewModel.resumeChapter(ch) },
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
    onCancelChapter: (TranslationItem) -> Unit,
    onPauseChapter: (TranslationItem) -> Unit,
    onResumeChapter: (TranslationItem) -> Unit,
    selected: Boolean = false,
    showExpandIcon: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val translating = group.chapters.firstOrNull { it.status == TranslationItem.Status.TRANSLATING }
    val errors = group.chapters.count { it.status == TranslationItem.Status.ERROR }
    val method = group.chapters.firstOrNull { it.method.isNotBlank() }?.method
    // 夜讀組 → 沒有去字法可顯示/可改，控制列改放「夜讀」靜態標籤（一組只有一種 pool，不會混）。
    val allNight = group.night

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
                    } else if (allNight) {
                        NightKindLabel()
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
        // 展開：章列（每章狀態 + ⏸/▶ 單章暫停⇄繼續 + ✕ 刪單章）。
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                group.chapters.forEach { ch ->
                    ChapterRow(
                        item = ch,
                        textStyle = MaterialTheme.typography.bodySmall,
                        startPadding = 12.dp,
                        onCancel = { onCancelChapter(ch) },
                        onPause = { onPauseChapter(ch) },
                        onResume = { onResumeChapter(ch) },
                    )
                }
            }
        }
    }
}

/**
 * 章列（手風琴展開列與平板右欄共用）：狀態文字 + ⏸/▶（單章暫停⇄繼續；三層 hold 的最細一層，只 hold 這一項）+ ✕。
 * 失敗（ERROR）的章不給 ⏸——它本來就不會跑，重試才有意義。
 */
@Composable
private fun ChapterRow(
    item: TranslationItem,
    textStyle: androidx.compose.ui.text.TextStyle,
    startPadding: androidx.compose.ui.unit.Dp,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = statusLine(item),
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.status != TranslationItem.Status.ERROR) {
            IconButton(
                onClick = if (item.paused) onResume else onPause,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (item.paused) Icons.Filled.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = stringResource(
                        if (item.paused) MR.strings.action_resume else MR.strings.action_pause,
                    ),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(
            onClick = onCancel,
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

/** 主從雙欄的右欄：選中那組的章節清單（標題 header + 每章狀態 + ⏸/▶ + ✕）。沒選中則顯示空提示。 */
@Composable
private fun DetailPane(
    group: MangaGroup?,
    onCancelChapter: (TranslationItem) -> Unit,
    onPauseChapter: (TranslationItem) -> Unit,
    onResumeChapter: (TranslationItem) -> Unit,
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
                text = if (group.night) {
                    "${group.manga.title} · ${stringResource(MR.strings.queue_kind_nightread)}"
                } else {
                    group.manga.title
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        // key 帶 kind：同章可同時有翻譯項與重繪項，只用 chapter.id 會撞 LazyColumn 重複 key。
        items(group.chapters, key = { "${it.chapter.id}:${it.kind}" }) { ch ->
            ChapterRow(
                item = ch,
                textStyle = MaterialTheme.typography.bodyMedium,
                startPadding = 16.dp,
                onCancel = { onCancelChapter(ch) },
                onPause = { onPauseChapter(ch) },
                onResume = { onResumeChapter(ch) },
            )
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

/** 摺疊狀態的副標：（全是夜讀項時先標「夜讀」）翻譯中那話 + 頁進度、剩 N 話、失敗數、已暫停。 */
@Composable
private fun groupSubtitle(
    group: MangaGroup,
    paused: Boolean,
    translating: TranslationItem?,
    errors: Int,
): String = buildString {
    if (group.night) {
        append(stringResource(MR.strings.queue_kind_nightread))
        append(" · ")
    }
    if (translating != null) {
        // 夜讀組不是在翻譯：進行中的文案用「產生中」
        append(
            stringResource(
                if (group.night) MR.strings.nightread_status_rendering else MR.strings.translation_status_translating,
            ),
        )
        if (translating.total > 0) append(" ${translating.done}/${translating.total}")
        append(" · ")
    }
    append(pluralStringResource(MR.plurals.queue_remaining_chapters, group.chapters.size, group.chapters.size))
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

/** 夜讀項的靜態標籤（取代去字法晶片；夜讀與去字法無關、無可編輯項）。樣式同不可編輯的 [MethodChip]。 */
@Composable
private fun NightKindLabel() {
    Text(
        text = stringResource(MR.strings.queue_kind_nightread),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 4.dp),
    )
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
    val status = when {
        // 單章暫停：QUEUE 但被 hold 住（TRANSLATING 被停下回 QUEUE 後也走這）
        item.paused && item.status != TranslationItem.Status.ERROR ->
            stringResource(MR.strings.queue_manga_paused)
        item.status == TranslationItem.Status.QUEUE -> stringResource(MR.strings.translation_status_queued)
        item.status == TranslationItem.Status.TRANSLATING -> {
            // 夜讀項不是在翻譯：進行中的文案用「產生中」
            val running = stringResource(
                if (item.kind == TranslationItem.Kind.NIGHT) {
                    MR.strings.nightread_status_rendering
                } else {
                    MR.strings.translation_status_translating
                },
            )
            if (item.total > 0) "$running ${item.done}/${item.total}" else running
        }
        else -> stringResource(MR.strings.translation_status_error)
    }
    // 夜讀項在章列多帶一個「夜讀」標籤：同章可能同時有翻譯項與夜讀項，沒標籤分不出哪列是哪個。
    return if (item.kind == TranslationItem.Kind.NIGHT) {
        "$chapter • ${stringResource(MR.strings.queue_kind_nightread)} • $status"
    } else {
        "$chapter • $status"
    }
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
    val isTranslatePaused: StateFlow<Boolean> = translationManager.isTranslatePaused
    val isNightPaused: StateFlow<Boolean> = translationManager.isNightPaused
    val pausedMangas: StateFlow<Set<QueuePoolKey>> = translationManager.pausedMangas

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
                // 只暫停翻譯 pool（引擎是翻譯那條在用；夜讀 pool 不用 warm 引擎、不必陪停）
                val hadTasks = translationManager.queueState.value.any { it.kind != TranslationItem.Kind.NIGHT }
                if (hadTasks) translationManager.pause(night = false)
                engineService.shutdownAsync()
                if (hadTasks) MR.strings.translation_retap_paused_unloaded else MR.strings.translation_retap_unloaded
            }
            master -> {
                translationPreferences.translationMasterEnabled.set(false)
                // 同 MoreTab／設定頁：切換副作用走 manager（中止當前章、沒活就停前景服務、釋放引擎）
                translationManager.onMasterEnabledChanged(false)
                MR.strings.translation_retap_master_off
            }
            else -> {
                translationPreferences.translationMasterEnabled.set(true)
                translationManager.onMasterEnabledChanged(true) // 有排隊的翻譯項且未暫停 → 續跑 + 前景服務
                engineService.warmUpAsync()
                MR.strings.translation_retap_master_on
            }
        }
    }

    fun clearQueue() = translationManager.clearQueue()
    fun pause(night: Boolean) = translationManager.pause(night)
    fun resume(night: Boolean) = translationManager.resume(night)

    // 單章操作（章列）。帶 kind：同章可同時有翻譯項與夜讀項，只動它那一列、不連同章的另一種一起。
    fun cancelChapter(item: TranslationItem) = translationManager.cancel(listOf(item.chapter.id), item.kind)
    fun pauseChapter(item: TranslationItem) = translationManager.pauseChapter(item.chapter.id, item.kind)
    fun resumeChapter(item: TranslationItem) = translationManager.resumeChapter(item.chapter.id, item.kind)

    // 以「本 × pool」組為單位的操作。
    fun startMangaNow(key: QueuePoolKey) = translationManager.startMangaNow(key.mangaId, key.night)
    fun pauseManga(key: QueuePoolKey) = translationManager.pauseManga(key.mangaId, key.night)
    fun resumeManga(key: QueuePoolKey) = translationManager.resumeManga(key.mangaId, key.night)
    fun retryManga(key: QueuePoolKey) = translationManager.retryManga(key.mangaId, key.night)
    fun cancelManga(key: QueuePoolKey) = translationManager.cancelManga(key.mangaId, key.night)

    /** Yakuyomi：重試佇列中所有含失敗（ERROR）章節的組（逐組 retryManga）。 */
    fun retryAllFailed() {
        queueState.value
            .filter { it.status == TranslationItem.Status.ERROR }
            .map { it.poolKey }
            .distinct()
            .forEach { translationManager.retryManga(it.mangaId, it.night) }
    }
    fun setMangaMethod(mangaId: Long, method: String) = translationManager.setMangaMethod(mangaId, method)
    fun reorderGroups(orderedKeys: List<QueuePoolKey>) = translationManager.reorderGroups(orderedKeys)
}
