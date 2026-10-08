package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.net.Uri
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.unifile.UniFile
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.nightread.NightAvailability
import eu.kanade.tachiyomi.data.nightread.NightLevel
import eu.kanade.tachiyomi.data.nightread.NightPages
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.NightPageSource
import eu.kanade.tachiyomi.ui.reader.loader.TranslatingPageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.getValue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Presenter used by the activity to perform background operations.
 */
class ReaderViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val imageSaver: ImageSaver = Injekt.get(),
    val readerPreferences: ReaderPreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val setMangaViewerFlags: SetMangaViewerFlags = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val translationManager: TranslationManager = Injekt.get(),
) : ViewModel() {

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    /**
     * Ids of the manga and chapter the reader was launched with, taken from the activity intent.
     */
    val mangaId = savedState.get<Long>("manga") ?: -1L
    private val initialChapterId = savedState.get<Long>("chapter") ?: -1L

    val hasValidArgs = mangaId != -1L && initialChapterId != -1L

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    /**
     * 重繪用的翻譯引擎入口（換去字法重繪當頁）。lazy：只有 reader 內按重繪才會建、不拖開啟 reader 的速度。
     * 取 app context（同本檔其他 Injekt.get<Application>() 用法）。sourceManager/downloadProvider 已建構子注入。
     */
    private val pageTranslator by lazy { PageTranslator(Injekt.get<Application>()) }

    /** 翻譯偏好（即時翻譯開關 [TranslationPreferences.liveTranslate] 由設定面板切，切換後重載章節套用包裝）。 */
    private val translationPreferences: TranslationPreferences = Injekt.get()

    /** 常駐翻譯引擎服務：觀察其載入狀態（[TranslationEngineService.loading]）→ reader 角落指示器顯示「引擎載入中…」。 */
    private val translationEngineService: TranslationEngineService = Injekt.get()

    /**
     * 夜讀模式開關（[ReaderPreferences.nightReadMode]）的即時值，給 reader UI（懸浮鈕的日常／夜讀狀態）。
     * 切換入口有兩處（懸浮鈕 [setNightMode] / 設定面板 checkbox）都只改偏好；重畫統一由 init 觀察 [nightEffective] 觸發。
     */
    val nightReadMode: StateFlow<Boolean> = readerPreferences.nightReadMode.stateIn(viewModelScope)

    /** 夜讀表層總開關（[TranslationPreferences.nightReadEnabled]）：關＝懸浮鈕不組合、loader 一律送原圖。 */
    val nightReadEnabled: StateFlow<Boolean> = translationPreferences.nightReadEnabled.stateIn(viewModelScope)

    /**
     * 有效夜讀模式＝總開關 AND 夜讀模式（兩個 loader 的 stream 也照這個判）。萬一出現「總開關關、模式開」（備份還原、
     * 別處漏同步），也不會卡在畫面上沒有入口能關的夜讀頁。
     */
    private val nightEffective: StateFlow<Boolean> = combine(nightReadEnabled, nightReadMode) { e, m -> e && m }
        .stateIn(viewModelScope, SharingStarted.Eagerly, nightReadEnabled.value && nightReadMode.value)

    /**
     * 目前顯示的夜讀檔位（標準／更多，偏好 `nightread_fill_level`，舊值 l1／protect／aggressive 照 [NightLevel.fromPref]
     * 對應；懸浮鈕與閱讀設定面板的 chips、設定 › 夜讀 寫的都是同一個值）。改變時 init 那條觀察者 debounce 後只重畫真的會變的頁。
     */
    val nightTier: StateFlow<NightLevel> = translationPreferences.nightReadFillLevel.changes()
        .map { NightLevel.fromPref(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            NightLevel.fromPref(translationPreferences.nightReadFillLevel.get()),
        )

    /**
     * 畫面上目前看得到的頁（換檔位後判斷「這頁有沒有變」用）：單頁＝當前頁；雙頁對開＝兩頁；分割頁的半頁換成它的 parent
     * （兩半同一個檔）。null＝看得到哪些頁不確定（條漫：一畫面可能好幾頁）→ 不發「沒有差異」提示。
     */
    @Volatile
    private var visibleNightPages: List<ReaderPage>? = null

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * The visible page index of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
        set(value) {
            savedState["page_index"] = value
            field = value
        }

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    private var loader: ChapterLoader? = null

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    private var chapterToDownload: Download? = null

    private val unfilteredChapterList by lazy {
        val manga = manga!!
        runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = false) }
    }

    /**
     * Chapter list for the active manga. It's retrieved lazily and should be accessed for the first
     * time in a background thread to avoid blocking the UI.
     */
    private val chapterList by lazy {
        val manga = manga!!
        val chapters = runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true) }

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")

        val chaptersForReader = when {
            (readerPreferences.skipRead.get() || readerPreferences.skipFiltered.get()) -> {
                val filteredChapters = chapters.filterNot {
                    when {
                        readerPreferences.skipRead.get() && it.read -> true
                        readerPreferences.skipFiltered.get() -> {
                            (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !it.read) ||
                                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && it.read) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                                        !downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                                        downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !it.bookmark) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && it.bookmark)
                        }
                        else -> false
                    }
                }

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> chapters
        }

        chaptersForReader
            .sortedWith(getChapterSort(manga, sortDescending = false))
            .run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(selectedChapter)
                } else {
                    this
                }
            }
            .run {
                if (basePreferences.downloadedOnly.get()) {
                    filterDownloaded(manga)
                } else {
                    this
                }
            }
            .map { it.toDbChapter() }
            .map(::ReaderChapter)
    }

    private val incognitoMode: Boolean by lazy { getIncognitoState.await(manga?.source) }
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading.get()

    /**
     * 已觸發過「線上即時翻（下載 + 重載）」的章 id：每章只觸發一次，避免重複進同一章（換頁回到同章 / 重載後）
     * 重複下載。在 [onCurrentChapterActivated]（單一 viewModelScope coroutine、序列化）下存取，免額外鎖。
     */
    private val onlineTriggeredChapterIds = mutableSetOf<Long>()

    /**
     * 線上章的夜讀（[OnlineNightPrompt]）：[nightDownloadAskedIds]＝這次閱讀已經問過「要先下載嗎？」的章（打開夜讀模式時
     * 每話只問一次）；[nightDownloadRequestedIds]＝已經按了「下載」、還有一條在等它下載完的章（不再問、改提示下載中）。
     * 主執行緒寫入，等待結束時在 IO 撤回，所以用並行安全的集合。
     */
    private val nightDownloadAskedIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val nightDownloadRequestedIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    init {
        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { currentChapter ->
                if (chapterPageIndex >= 0) {
                    // Restore from SavedState
                    currentChapter.requestedPage = chapterPageIndex
                } else if (!currentChapter.chapter.read) {
                    currentChapter.requestedPage = currentChapter.chapter.last_page_read
                }
                chapterId = currentChapter.chapter.id!!
            }
            .launchIn(viewModelScope)

        // 即時翻譯「啟動」：只有**正在讀的當前章**（viewerChapters.currChapter）才觸發翻譯——
        // 修「讀第 N 話卻把預取的 N±1 話也自動下載/翻譯」的 bug。預取/相鄰章只 loadChapter（建 loader、載頁），
        // **永不**成為 currChapter，故下面這條永不對它們觸發。每次當前章改變（換章 / 開章）各觸發一次：
        //  - 當前章是「已下載 + 已包 TranslatingPageLoader」→ [TranslatingPageLoader.onActivated] 把整章插隊排入翻譯佇列。
        //  - 當前章是「線上（未下載）且符合即時翻條件」→ [triggerOnlineLiveTranslate] 下載該章、完成後重載進已下載路徑。
        // 兩者都冪等（loader 端 enqueued 旗標 / VM 端 onlineTriggeredChapterIds 去重），重複進同一章不會重觸發。
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { currentChapter -> onCurrentChapterActivated(currentChapter) }
            .launchIn(viewModelScope)

        // 即時翻譯進度：把「正在讀的章 id」與翻譯佇列 [TranslationManager.queueState] 併流，
        // 找出佇列裡 chapter.id 對得上當前章的那一項 → 映成 reader 內角落小指示器的進度（QUEUE/TRANSLATING）。
        // 兩個來源任一變動都會重算（換章、佇列前進、開始/結束翻譯），找不到（沒在排隊/翻譯）→ null（不顯示）。
        combine(
            // 只關心「當前章 id」這一個維度，避免每翻一頁（state 其他欄位變）都重跑佇列比對。
            state.map { it.currentChapter?.chapter?.id }.distinctUntilChanged(),
            translationManager.queueState,
        ) { currentChapterId, queue ->
            if (currentChapterId == null) return@combine null
            // 夜讀項不是翻譯：不算即時翻進度（與 MangaViewModel 章列指示器一致），否則同章排著夜讀會誤亮「翻譯中」。
            val item = queue.firstOrNull { it.chapter.id == currentChapterId && it.kind != TranslationItem.Kind.NIGHT }
                ?: return@combine null
            when (item.status) {
                // QUEUE＝排隊中（尚未開始、done/total 還沒意義）；TRANSLATING＝翻譯中、帶 done/total 進度。
                // ERROR 不顯示（reader 內只報「進行中」狀態；失敗在章節清單/佇列頁處理）。
                TranslationItem.Status.QUEUE ->
                    LiveTranslateProgress(done = item.done, total = item.total, queued = true)
                TranslationItem.Status.TRANSLATING ->
                    LiveTranslateProgress(done = item.done, total = item.total, queued = false)
                TranslationItem.Status.ERROR -> null
            }
        }
            .distinctUntilChanged()
            .onEach { progress -> mutableState.update { it.copy(liveTranslateProgress = progress) } }
            .launchIn(viewModelScope)

        // 引擎載入狀態 → reader 角落指示器（「引擎載入中…」）。即時翻開時 app 啟動 / 首章會背景載 ~100MB，
        // 這段時間在角落顯示載入中、讓使用者知道延遲是在掛載模型（非卡死）。建好後轉「翻譯中 X/Y」。
        translationEngineService.loading
            .onEach { loading -> mutableState.update { it.copy(engineLoading = loading) } }
            .launchIn(viewModelScope)

        // 有效夜讀模式（總開關 AND 夜讀模式）切換 → 對 curr/prev/next 三章已載入的頁逐頁 reload()（holder 就地重 decode、
        // 保留縮放）。loader 的 stream 每次解碼都重讀開關 → 重 decode 就會換成夜讀版／原圖，不必離開章節。
        // drop(1) 跳過初始值（開 reader 時不重畫）；之後每次值真的改變才觸發。
        nightEffective
            .drop(1)
            .onEach { on ->
                reloadLoadedPages()
                // 在線上章打開夜讀模式：這話沒有夜讀版、也做不了，問一次要不要先下載（[OnlineNightPrompt]）
                if (on) promptOnlineNight(OnlineNightPrompt.Trigger.NIGHT_ON)
            }
            .launchIn(viewModelScope)

        observeNightTier()

        // 當前章的夜讀狀態（READY／LEGACY／NONE／UNSUPPORTED）→ 懸浮鈕的檔位可不可用、長按選單給哪個產生鈕。
        // 在 IO 掃、放進 state（不在 Composable 做 I/O）；當前章改變時重算，[openPageDialog] 時也再算一次。
        // 換章時先同步設成「還不知道」（null）：IO 掃描完成前檔位鈕不沿用上一章的狀態（否則從有夜讀版的章換到沒有的章，
        // 檔位鈕還能點；反過來點灰鈕會跳「產生夜讀版？」，而這章其實早就產生好了）。
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { currentChapter ->
                mutableState.update { it.copy(nightAvailability = null, nightOutdated = false) }
                refreshNightAvailability(currentChapter)
            }
            .launchIn(viewModelScope)

        // 當前章在夜讀佇列的進度（排隊或產生中；ERROR 不算）→ 懸浮鈕的進度環、長按選單「產生」鈕的顯示條件。
        // paused＝三層暫停任一層 hold 住（單章、這本的夜讀組、夜讀 pool）：提示改說「已暫停」，不顯示卡住的 0/0。
        combine(
            translationManager.queueState,
            state.map { it.viewerChapters?.currChapter?.chapter?.id }.distinctUntilChanged(),
            translationManager.isNightPaused,
            translationManager.pausedMangas,
        ) { queue, id, poolPaused, pausedMangas ->
            if (id == null) return@combine null
            val item = queue.firstOrNull {
                // 失敗項留佇列但不算「進行中」：讓產生鈕成為 reader 內的重試入口（enqueueNight 對 ERROR 回 QUEUE）
                it.kind == TranslationItem.Kind.NIGHT && it.chapter.id == id &&
                    it.status != TranslationItem.Status.ERROR
            } ?: return@combine null
            NightProgress(
                done = item.done,
                total = item.total,
                running = item.status == TranslationItem.Status.TRANSLATING,
                paused = item.status == TranslationItem.Status.QUEUE &&
                    (poolPaused || item.paused || item.poolKey in pausedMangas),
            )
        }
            .distinctUntilChanged()
            .onEach { progress -> mutableState.update { it.copy(nightProgress = progress) } }
            .launchIn(viewModelScope)

        // 產生中的章每做完一頁（done 變了）就讓那章 loader 的檔名快取失效：快取可能是某頁還只有 std 時列的（更多與標準相同、
        // 或還沒重做到），重新產生後多出 more，不失效的話選「更多」仍讀 std（錯檔位）；下面的 nightChapterDone 只在整章成功
        // 時才發，暫停或出錯就一直停在錯的檔位。只失效、不重畫：下次解碼重列一次（每頁 6–25 s 才一次，列目錄便宜）。
        translationManager.queueState
            .map { queue ->
                queue.filter {
                    it.kind == TranslationItem.Kind.NIGHT && it.status == TranslationItem.Status.TRANSLATING
                }.associate { it.chapter.id to it.done }
            }
            .distinctUntilChanged()
            .onEach { running ->
                if (running.isEmpty()) return@onEach
                val chapters = state.value.viewerChapters ?: return@onEach
                listOfNotNull(chapters.currChapter, chapters.prevChapter, chapters.nextChapter)
                    .filter { it.chapter.id in running.keys }
                    .forEach { (it.pageLoader as? NightPageSource)?.nightStreams?.index?.invalidate() }
            }
            .launchIn(viewModelScope)

        // 夜讀版剛做完的章若是 curr/prev/next 之一 → 讓那章 loader 的檔名快取失效、當前章重算狀態；夜讀模式開著就重畫
        // **那一章**已載入的頁（pager 預載的相鄰章也算，否則滑過去仍是舊圖）。整章重畫而非比檔名：重新產生後檔名不變、
        // 內容變了。用每次完成都會發的事件（同一章做第二次也要重畫）。
        translationManager.nightChapterDone
            .onEach { chapterId ->
                val chapters = state.value.viewerChapters ?: return@onEach
                val hit = listOfNotNull(chapters.currChapter, chapters.prevChapter, chapters.nextChapter)
                    .filter { it.chapter.id == chapterId }
                if (hit.isEmpty()) return@onEach
                hit.forEach { (it.pageLoader as? NightPageSource)?.nightStreams?.index?.invalidate() }
                if (chapters.currChapter.chapter.id == chapterId) refreshNightAvailability(chapters.currChapter)
                if (nightEffective.value) hit.flatMap { it.pages.orEmpty() }.forEach { it.reload() }
            }
            .launchIn(viewModelScope)

        if (hasValidArgs) {
            viewModelScope.launch { init() }
        }
    }

    override fun onCleared() {
        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onActivityFinish() {
        deletePendingChapters()
    }

    /**
     * Initializes this presenter with the [mangaId] and [initialChapterId] the reader was launched
     * with. This method will fetch the manga from the database and initialize the initial chapter.
     * Failures are reported through [State.initError].
     */
    private suspend fun init() {
        withIOContext {
            try {
                val manga = getManga.await(mangaId) ?: error("Requested manga of id $mangaId not found")
                sourceManager.isInitialized.first { it }
                mutableState.update { it.copy(manga = manga) }
                if (chapterId == -1L) chapterId = initialChapterId

                val context = Injekt.get<Application>()
                val source = sourceManager.getOrStub(manga.source)
                loader = ChapterLoader(context, downloadManager, downloadProvider, manga, source)

                loadChapter(loader!!, chapterList.first { chapterId == it.chapter.id })
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                mutableState.update { it.copy(initError = e) }
            }
        }
    }

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: ChapterLoader,
        chapter: ReaderChapter,
    ): ViewerChapters {
        loader.loadChapter(chapter)

        val chapterPos = chapterList.indexOf(chapter)
        val newChapters = ViewerChapters(
            chapter,
            chapterList.getOrNull(chapterPos - 1),
            chapterList.getOrNull(chapterPos + 1),
        )

        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)
                it.copy(
                    viewerChapters = newChapters,
                    bookmarked = newChapters.currChapter.chapter.bookmark,
                )
            }
        }
        return newChapters
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        val loader = loader ?: return

        viewModelScope.launchIO {
            logcat { "Loading ${chapter.chapter.url}" }

            updateHistory()
            restartReadTimer()

            try {
                loadChapter(loader, chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * 當「正在讀的當前章」被設為 active（換章 / 開章）時呼叫——即時翻譯的**唯一**觸發點。
     * **只對 currChapter 觸發**（呼叫端是 init 區塊觀察 `viewerChapters.currChapter` 的 flow）→ 預取/相鄰章
     * （永不是 currChapter）絕不被觸發，修「讀第 N 話卻自動下載/翻譯 N±1 話」的 bug。
     *
     * 兩條路徑（互斥）：
     *  - 已被包成 [TranslatingPageLoader]（＝已下載 + 符合即時翻條件、見 [ChapterLoader.shouldTranslateLive]）：
     *    呼叫 [TranslatingPageLoader.onActivated] 把整章插隊排入翻譯佇列（冪等）。
     *  - 否則為原生 loader（線上未下載 / 不符即時翻的已下載章）：若是**線上**且符合即時翻 gate → 走
     *    [triggerOnlineLiveTranslate]（下載 + 完成後重載進已下載路徑）。已下載但不符（已翻/分類排除）→ 不做事。
     */
    private suspend fun onCurrentChapterActivated(chapter: ReaderChapter) {
        val pageLoader = chapter.pageLoader
        if (pageLoader is TranslatingPageLoader) {
            // 已下載 + 已包裝：整章插隊排入翻譯佇列（冪等；loader 內 enqueued 旗標去重）。
            pageLoader.onActivated()
            // 跨章預取：背景先翻下一章（已下載 + 合格 + 未翻），等使用者翻過去時已（部分）翻好＝體感即時。
            prefetchNextChapters(chapter)
            return
        }
        // 原生 loader：只有「線上（未下載）且符合即時翻 gate」才觸發下載 + 重載。
        maybeTriggerOnlineLiveTranslate(chapter)
    }

    /**
     * 跨章預取（即時翻譯的「體感即時」真解）：讀某 live 章時，背景把**下一章**也排入翻譯佇列（不插隊、不搶當前章），
     * 翻到下一章時已（部分）翻好、page-level resume 接續。
     *
     * 章內預取早已有（[TranslatingPageLoader.onActivated] 把整章一次排入 → 讀第 1 頁時 2..N 已在翻）；這裡補的是跨「章」。
     * 跨頁併發（多頁同時推論）刻意不做：CPU 已到頂、多頁併發不加速（見 CLAUDE.md §8）。
     *
     * 範圍：只預取**已下載**的下一章（不在此自動下載未下載章——尊重資料/電量；未下載章仍由原路徑在讀到時處理）。
     * gate 與即時翻一致（開關 + 引擎就緒 + 來源/分類），且跳過已整章翻好的章。深度＝下 1 章（保守、夠用）。
     */
    private suspend fun prefetchNextChapters(current: ReaderChapter) {
        val manga = manga ?: return
        val loader = loader ?: return
        if (!translationPreferences.translationMasterEnabled.get()) return
        if (!translationPreferences.liveTranslate.get()) return
        if (!loader.engineReady()) return
        if (!loader.autoTranslateAllowed()) return
        val currentId = current.chapter.id ?: return
        val nextChapters = getNextChapters.await(manga.id, currentId, onlyUnread = false)
            .filter { it.id != currentId } // await 回傳含當前章起 → 去掉自己
            .take(1) // 預取深度＝下 1 章
        for (next in nextChapters) {
            if (translationManager.isTranslated(manga, next)) continue // 已整章翻好 → 不重排
            val downloaded = downloadManager.isChapterDownloaded(
                next.name,
                next.scanlator,
                next.url,
                manga.title,
                manga.source,
                skipCache = true,
            )
            if (downloaded) {
                // 背景翻（非 atFront）：當前章已被 onActivated 插隊到最前、優先翻完，下一章接著翻。即時翻預取用即時去字法（預設 AI 去字）。
                translationManager.translate(
                    manga,
                    listOf(next),
                    method = translationManager.liveInpaintMethod(),
                )
            }
        }
    }

    /**
     * 線上（未下載）章的即時翻入口：判斷「是否該對這條線上章即時翻」（gate 與 [ChapterLoader.shouldTranslateLive]
     * 對齊：開關 + 引擎就緒 + 分類 + 尚未翻好 + 確為線上未下載），符合則觸發 [triggerOnlineLiveTranslate]。
     * 每章只觸發一次（[onlineTriggeredChapterIds]）。
     */
    private suspend fun maybeTriggerOnlineLiveTranslate(chapter: ReaderChapter) {
        val manga = manga ?: return
        val loader = loader ?: return
        val chapterId = chapter.chapter.id ?: return
        if (chapterId in onlineTriggeredChapterIds) return

        // gate：即時翻開關 + 引擎就緒（key+模型，**不含**「下載時翻譯」開關）+ 分類（與已下載路徑 shouldTranslateLive 同一份語義）。
        // ★ 用 loader.engineReady() 而非 translationManager.isReady()——後者含 translationEnabled，
        //   「只開即時翻、沒開下載時翻」的使用者會被它擋掉（線上顯示「未啟動」即此 bug）。
        if (!translationPreferences.translationMasterEnabled.get()) return
        if (!translationPreferences.liveTranslate.get()) return
        if (!loader.engineReady()) return
        if (!loader.autoTranslateAllowed()) return

        // 必須是「線上、尚未下載」才走此路徑（已下載章由 TranslatingPageLoader 路徑處理）。
        val isDownloaded = downloadManager.isChapterDownloaded(
            chapter.chapter.name,
            chapter.chapter.scanlator,
            chapter.chapter.url,
            manga.title,
            manga.source,
            skipCache = true,
        )
        if (isDownloaded) return

        val domainChapter = chapter.chapter.toDomainChapter() ?: return
        // 已整章翻好（理論上線上章必未翻；保險檢查）→ 不重翻。
        if (translationManager.isTranslated(manga, domainChapter)) return

        onlineTriggeredChapterIds.add(chapterId)
        triggerOnlineLiveTranslate(chapter)
    }

    /**
     * 線上即時翻的可靠實作＝**下載該章 → 完成後重載進已下載路徑**（取代舊版「同 session 串流改指」的不可靠做法）：
     *  1. [TranslationManager.markForTranslate]：標記下載完要翻（讓 [eu.kanade.tachiyomi.data.download.Downloader]
     *     繞過「下載時翻譯」總開關）；同時保證即使使用者中途離開、下載完成後章仍會被翻 + 持久化。
     *  2. [DownloadManager.downloadChapters]：觸發下載（autoStart）。
     *  3. **有界輪詢** [DownloadProvider.findChapterDir] 偵測章目錄出現（＝下載完成、rename 後的權威信號，比
     *     statusFlow 可靠、無漏接）；綁在 viewModelScope，使用者離開 reader（VM cleared）即取消。
     *  4. 章目錄出現（且為鬆散資料夾）且**該章仍是當前章**（使用者沒換走）→ [reloadCurrentChapterPreservingPage]：
     *     重載當前章保留閱讀位置。重載後章已下載 → [ChapterLoader.shouldTranslateLive] 把它包成 [TranslatingPageLoader]
     *     → 隨後 currChapter flow 再次觸發 [onCurrentChapterActivated] → [TranslatingPageLoader.onActivated] 排入翻譯、
     *     走可靠的已下載換頁路徑。
     *
     * CBZ（isFile）即時翻本里程碑不支援 → 停止輪詢、維持線上原圖（下載仍會完成、退出重進可讀已翻 CBZ）。
     */
    private fun triggerOnlineLiveTranslate(chapter: ReaderChapter) {
        val manga = manga ?: return
        val domainChapter = chapter.chapter.toDomainChapter() ?: return

        logcat { "線上即時翻：觸發下載 + 等完成後重載 ${chapter.chapter.url}" }
        translationManager.markForTranslate(domainChapter.id)
        downloadManager.downloadChapters(manga, listOf(domainChapter))

        viewModelScope.launchIO { awaitDownloadThenReload(chapter, "線上即時翻") }
    }

    /**
     * 線上章按了下載之後（即時翻譯、夜讀共用）：**有界輪詢**章節夾出現（下載完成、改名成正式名稱後才有；權威信號、無漏接），
     * 每一步照 [OnlineDownloadPoll.step]：
     *  - 使用者換到別話 → 放棄重載（下載與排入的工作在背景照做，靠 markForTranslate／markForNight）。
     *  - 鬆散資料夾出現 → 重載當前章、保留頁碼（[reloadCurrentChapterPreservingPage]）。即時翻譯與夜讀可能同時在等同一話：
     *    先到的那個重載，後到的看這話已經不是線上章了就不再重載、只重算夜讀狀態。
     *  - 下載成壓縮檔 → 維持線上原圖（即時翻與夜讀都只支援鬆散資料夾）。
     *  - 等太久 → 放棄重載。
     * 綁在 viewModelScope：使用者離開閱讀器（VM cleared）就取消。[what]＝紀錄用的流程名。在 IO 呼叫。
     */
    private suspend fun awaitDownloadThenReload(chapter: ReaderChapter, what: String) {
        val manga = manga ?: return
        val source = sourceManager.getOrStub(manga.source)
        var waited = 0L
        while (true) {
            val stillCurrent = getCurrentChapter()?.chapter?.id == chapter.chapter.id
            val dir = if (stillCurrent) {
                downloadProvider.findChapterDir(
                    chapter.chapter.name,
                    chapter.chapter.scanlator,
                    chapter.chapter.url,
                    manga.title,
                    source,
                )
            } else {
                null
            }
            when (
                OnlineDownloadPoll.step(
                    stillCurrent = stillCurrent,
                    dirFound = dir != null,
                    isDirectory = dir?.isDirectory == true,
                    timedOut = waited >= ONLINE_DOWNLOAD_TIMEOUT_MS,
                )
            ) {
                OnlineDownloadPoll.Step.WAIT -> {
                    delay(ONLINE_DOWNLOAD_POLL_MS)
                    waited += ONLINE_DOWNLOAD_POLL_MS
                }
                OnlineDownloadPoll.Step.RELOAD -> {
                    if (chapter.pageLoader is HttpPageLoader) {
                        reloadCurrentChapterPreservingPage()
                    } else {
                        refreshNightAvailability(chapter)
                    }
                    return
                }
                OnlineDownloadPoll.Step.ARCHIVE -> {
                    logcat(LogPriority.WARN) { "$what：下載成壓縮檔，只支援鬆散資料夾，維持線上原圖" }
                    return
                }
                OnlineDownloadPoll.Step.LEFT -> {
                    logcat { "$what：使用者已離開此章，停止等待重載（下載與排入的工作在背景照做）" }
                    return
                }
                OnlineDownloadPoll.Step.TIMEOUT -> {
                    logcat(LogPriority.WARN) { "$what：等下載完成逾時，維持線上原圖（工作仍可能在背景完成）" }
                    return
                }
            }
        }
    }

    /**
     * 重載**當前章**並保留閱讀位置（線上下載完成後轉入已下載路徑用；復用 reader 既有的章節重載樣式）。
     *
     * 做法：
     *  1. 記下當前頁 index（[chapterPageIndex]，由 [updateChapterProgress] 持續更新；退化用 last_page_read）。
     *  2. **回收並重置** curr/prev/next 三個 [ReaderChapter] 的 loader（recycle + pageLoader=null + state=Wait）
     *     → 讓 [ChapterLoader.getPageLoader] 重跑：當前章現已下載 → 重新包成 [TranslatingPageLoader]。
     *  3. 把當前章的 [ReaderChapter.requestedPage] 設成記下的頁 → viewer `setChapters` 會定位回該頁
     *     （見 PagerViewer.setChaptersInternal 的 `moveToPage(requestedPage)`）。
     *  4. [loadChapter] 重載當前章（重建 viewerChapters）+ 送 [Event.ReloadViewerChapters] 讓 viewer 重套章節。
     *
     * 已在背景（viewModelScope.launchIO）呼叫。失敗只記 log、維持原狀（線上原圖仍可讀，§11 不變式）。
     */
    private suspend fun reloadCurrentChapterPreservingPage() {
        val loader = loader ?: return
        val chapters = state.value.viewerChapters ?: return
        val currChapter = chapters.currChapter
        // 保留閱讀位置：優先用即時追蹤的可見頁 index，退化用 last_page_read（≥0 才有意義）。
        val targetPage = chapterPageIndex.takeIf { it >= 0 } ?: currChapter.chapter.last_page_read

        try {
            // 回收 + 重置三章 loader，逼 ChapterLoader 重建（當前章已下載 → 重新包成 TranslatingPageLoader）。
            listOfNotNull(chapters.currChapter, chapters.prevChapter, chapters.nextChapter).forEach { rc ->
                rc.pageLoader?.recycle()
                rc.pageLoader = null
                rc.state = ReaderChapter.State.Wait
            }
            currChapter.requestedPage = targetPage

            loadChapter(loader, currChapter)
            eventChannel.send(Event.ReloadViewerChapters)

            // 重載後當前章已下載 → 已重新包成 TranslatingPageLoader。**必須在此直接啟動**：
            // currChapter 仍是同一個 ReaderChapter 物件，init 的 currChapter flow 經 distinctUntilChanged 會視為「未變」
            // 而不重發 → 不會自動再呼 onCurrentChapterActivated。故在這裡顯式 onActivated() 把整章排入翻譯佇列。
            (currChapter.pageLoader as? TranslatingPageLoader)?.onActivated()
            // 夜讀狀態同理要自己重算（線上章是「不支援」，重載後是鬆散資料夾章：懸浮鈕與長按選單才會給夜讀的選項）
            refreshNightAvailability(currChapter)
            logcat { "線上即時翻：已重載當前章進已下載路徑（保留第 $targetPage 頁）" }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) { "線上即時翻：重載當前章失敗，維持原狀" }
        }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the toolbar buttons.
     */
    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        logcat { "Loading adjacent ${chapter.chapter.url}" }

        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withIOContext {
                loadChapter(loader, chapter)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e)
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
            return
        }

        if (chapter.pageLoader?.isLocal == false) {
            val manga = manga ?: return
            val dbChapter = chapter.chapter
            val isDownloaded = downloadManager.isChapterDownloaded(
                dbChapter.name,
                dbChapter.scanlator,
                dbChapter.url,
                manga.title,
                manga.source,
                skipCache = true,
            )
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        val loader = loader ?: return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            loader.loadChapter(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return
        }
        eventChannel.trySend(Event.ReloadViewerChapters)
    }

    fun onViewerLoaded(viewer: Viewer?) {
        mutableState.update {
            it.copy(viewer = viewer)
        }
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage, visible: List<ReaderPage>? = listOf(page)) {
        // 夜讀換檔位的「這頁沒差異」提示看的是畫面上看得到的頁（分割頁的半頁＝parent 那個檔）
        visibleNightPages = visible?.map { (it as? InsertPage)?.parent ?: it }

        // InsertPage doesn't change page progress
        if (page is InsertPage) {
            return
        }

        // 即時翻譯：頁剛變成當前頁 → 若已翻好就補換譯圖（修「預載的相鄰頁在 off-screen 翻好、變當前頁卻卡原圖」）。
        (page.chapter.pageLoader as? TranslatingPageLoader)?.refreshSelected(page)

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return

        // Save last page read and mark as read if needed
        viewModelScope.launchNonCancellable {
            updateChapterProgress(selectedChapter, page)
        }

        if (selectedChapter != getCurrentChapter()) {
            logcat { "Setting ${selectedChapter.chapter.url} as active" }
            loadNewChapter(selectedChapter)
        }

        val inDownloadRange = page.number.toDouble() / pages.size > 0.25
        if (inDownloadRange) {
            downloadNextChapters()
        }

        eventChannel.trySend(Event.PageChanged)
    }

    private fun downloadNextChapters() {
        if (downloadAheadAmount == 0) return
        val manga = manga ?: return

        // Only download ahead if current + next chapter is already downloaded too to avoid jank
        if (getCurrentChapter()?.pageLoader !is DownloadPageLoader) return
        val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return

        viewModelScope.launchIO {
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(
                nextChapter.name,
                nextChapter.scanlator,
                nextChapter.url,
                manga.title,
                manga.source,
            )
            if (!isNextChapterDownloaded) return@launchIO

            val chaptersToDownload = getNextChapters.await(manga.id, nextChapter.id!!).run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(nextChapter.toDomainChapter()!!)
                } else {
                    this
                }
            }.take(downloadAheadAmount)

            downloadManager.downloadChapters(
                manga,
                chaptersToDownload,
            )
        }
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private fun cancelQueuedDownloads(currentChapter: ReaderChapter): Download? {
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id!!)?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots.get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        val currentChapterPosition = chapterList.indexOf(currentChapter)
        val chapterToDelete = chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete != null) {
            enqueueDeleteReadChapters(chapterToDelete)
        }
    }

    /**
     * Saves the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on.
     */
    private suspend fun updateChapterProgress(readerChapter: ReaderChapter, page: Page) {
        val pageIndex = page.index

        mutableState.update {
            it.copy(currentPage = pageIndex + 1)
        }
        readerChapter.requestedPage = pageIndex
        chapterPageIndex = pageIndex

        if (!incognitoMode && page.status !is Page.State.Error) {
            readerChapter.chapter.last_page_read = pageIndex

            if (readerChapter.pages?.lastIndex == pageIndex) {
                updateChapterProgressOnComplete(readerChapter)
            }

            updateChapter.await(
                ChapterUpdate(
                    id = readerChapter.chapter.id!!,
                    read = readerChapter.chapter.read,
                    lastPageRead = readerChapter.chapter.last_page_read.toLong(),
                ),
            )
        }
    }

    private suspend fun updateChapterProgressOnComplete(readerChapter: ReaderChapter) {
        readerChapter.chapter.read = true
        updateTrackChapterRead(readerChapter)
        deleteChapterIfNeeded(readerChapter)

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return

        val duplicateUnreadChapters = unfilteredChapterList
            .mapNotNull { chapter ->
                if (
                    !chapter.read &&
                    chapter.isRecognizedNumber &&
                    chapter.chapterNumber.toFloat() == readerChapter.chapter.chapter_number
                ) {
                    ChapterUpdate(id = chapter.id, read = true)
                } else {
                    null
                }
            }
        updateChapter.awaitAll(duplicateUnreadChapters)
    }

    fun restartReadTimer() {
        chapterReadStartTime = Clock.System.now().toEpochMilliseconds()
    }

    /**
     * Saves the chapter last read history if incognito mode isn't on.
     */
    suspend fun updateHistory() {
        val readerChapter = getCurrentChapter()
        if (readerChapter == null) {
            // Yakuyomi 診斷（#9 已讀翻譯章不進歷史）：currentChapter 為 null → 不寫歷史。
            logcat { "Yakuyomi/history: skip — currentChapter is null" }
            return
        }
        if (incognitoMode) {
            logcat { "Yakuyomi/history: skip — incognito" }
            return
        }

        val chapterId = readerChapter.chapter.id!!
        val endTime = Date()
        val sessionReadDuration = chapterReadStartTime?.let { endTime.time - it } ?: 0

        upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
        chapterReadStartTime = null
        logcat {
            "Yakuyomi/history: wrote chapterId=$chapterId name=${readerChapter.chapter.name} dur=$sessionReadDuration"
        }
    }

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    suspend fun loadNextChapter() {
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return
        loadAdjacent(nextChapter)
    }

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    suspend fun loadPreviousChapter() {
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return
        loadAdjacent(prevChapter)
    }

    /**
     * Yakuyomi：reader 內章節清單給 UI 顯示用的快照（已過濾/排序，與 reader 的章序一致）。
     * [chapterList] 早在開章時於背景初始化過，這裡是 lazy 命中的純記憶體存取。
     */
    fun getReaderChapters(): List<ReaderChapter> = chapterList

    /** Yakuyomi：當前正在讀的章 id（章節清單對話框用來標示「目前」）。 */
    val currentChapterId: Long?
        get() = state.value.currentChapter?.chapter?.id

    /** Yakuyomi：從章節清單對話框點選任一章 → 載入並設為當前章（背景跑、含載入狀態）。 */
    fun loadChapterFromList(chapterId: Long) {
        if (chapterId == currentChapterId) return
        val chapter = chapterList.firstOrNull { it.chapter.id == chapterId } ?: return
        viewModelScope.launchIO {
            loadAdjacent(chapter)
        }
    }

    /**
     * Returns the currently active chapter.
     */
    private fun getCurrentChapter(): ReaderChapter? {
        return state.value.currentChapter
    }

    fun getSource() = manga?.source?.let { sourceManager.getOrStub(it) } as? HttpSource

    fun getChapterUrl(): String? {
        val sChapter = getCurrentChapter()?.chapter ?: return null
        val source = getSource() ?: return null

        return try {
            source.getChapterUrl(sChapter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    /**
     * Bookmarks the currently active chapter.
     */
    fun toggleChapterBookmark() {
        val chapter = getCurrentChapter()?.chapter ?: return
        val bookmarked = !chapter.bookmark
        chapter.bookmark = bookmarked

        viewModelScope.launchNonCancellable {
            updateChapter.await(
                ChapterUpdate(
                    id = chapter.id!!,
                    bookmark = bookmarked,
                ),
            )
        }

        mutableState.update {
            it.copy(
                bookmarked = bookmarked,
            )
        }
    }

    /**
     * Yakuyomi：裝置當下是否為平板 UI（外觀→平板介面 設定解析後的狀態）。由 ReaderActivity 設定/更新。
     * 影響「未指定 per-manga 模式」時的預設閱讀模式（手機 vs 平板）。
     */
    var isTabletUi: Boolean = false
        private set

    /**
     * Yakuyomi：更新平板 UI 狀態（折/展時呼叫）。回傳是否需要重建 viewer（＝有效閱讀模式因此改變）。
     * 若需重建，會先把當前頁存成 requestedPage，讓重建後的 viewer 停在原頁（對齊 setMangaReadingMode）。
     */
    fun setTabletUiState(value: Boolean): Boolean {
        if (isTabletUi == value) return false
        val before = getMangaReadingMode()
        isTabletUi = value
        val after = getMangaReadingMode()
        if (before == after) return false
        val currChapters = state.value.viewerChapters ?: return false
        currChapters.currChapter.requestedPage = currChapters.currChapter.chapter.last_page_read
        return true
    }

    /**
     * Yakuyomi：自動偵測 webtoon 切到的閱讀模式（transient、不持久化）。偵測到長條圖時設成 CONTINUOUS_VERTICAL，
     * 只在該本未明確指定模式（readingMode==DEFAULT）時由 [getMangaReadingMode] 採用；換 reader session 即重置。
     */
    private var autoDetectedReadingMode: Int? = null

    /**
     * Returns the viewer position used by this manga or the default one.
     */
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        val default = run {
            val phone = readerPreferences.defaultReadingMode.get()
            if (!isTabletUi) return@run phone
            // Yakuyomi：平板/展開態 → 若有設專屬模式（非 DEFAULT）就用它，否則跟隨手機。
            val tablet = readerPreferences.tabletReadingMode.get()
            if (tablet == ReadingMode.DEFAULT.flagValue) phone else tablet
        }
        val readingMode = ReadingMode.fromPreference(manga?.readingMode?.toInt())
        return when {
            // Yakuyomi：該本未指定模式時，自動偵測到長條圖 → 連續直捲（覆寫預設，含對開預設）。
            resolveDefault && readingMode == ReadingMode.DEFAULT -> autoDetectedReadingMode ?: default
            else -> manga?.readingMode?.toInt() ?: default
        }
    }

    /**
     * Yakuyomi：本頁是否該嘗試自動偵測 webtoon（快速布林、給 [PagerPageHolder] 在解析長寬比前先 gate，省不必要的 bounds decode）。
     * 條件：總開關開 + 尚未切過 + 該本未明確指定模式 + 目前解析出的模式非 webtoon 系。
     */
    fun isAutoWebtoonEligible(): Boolean {
        if (!readerPreferences.autoDetectWebtoon.get()) return false
        if (autoDetectedReadingMode != null) return false
        if (ReadingMode.fromPreference(manga?.readingMode?.toInt()) != ReadingMode.DEFAULT) return false
        val current = ReadingMode.fromPreference(getMangaReadingMode())
        return current != ReadingMode.WEBTOON && current != ReadingMode.CONTINUOUS_VERTICAL
    }

    /**
     * Yakuyomi：[PagerPageHolder] 偵測到當前頁是長條圖時呼叫——切連續直捲並重建 viewer（存當前頁、不持久化）。
     * 二次防護（重複呼叫 / 已切過 / 該本已指定模式）由 [isAutoWebtoonEligible] 擋掉。
     */
    fun onAutoWebtoonDetected() {
        if (!isAutoWebtoonEligible()) return
        autoDetectedReadingMode = ReadingMode.CONTINUOUS_VERTICAL.flagValue
        val currChapters = state.value.viewerChapters ?: return
        currChapters.currChapter.requestedPage = currChapters.currChapter.chapter.last_page_read
        eventChannel.trySend(Event.RebuildViewer)
    }

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaReadingMode(readingMode: ReadingMode) {
        val manga = manga ?: return
        runBlocking(Dispatchers.IO) {
            setMangaViewerFlags.awaitSetReadingMode(manga.id, readingMode.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    /**
     * Returns the orientation type used by this manga or the default one.
     */
    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultOrientationType.get()
        val orientation = ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt())
        return when {
            resolveDefault && orientation == ReaderOrientation.DEFAULT -> default
            else -> manga?.readerOrientation?.toInt() ?: default
        }
    }

    /**
     * Updates the orientation type for the open manga.
     */
    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val manga = manga ?: return
        viewModelScope.launchIO {
            setMangaViewerFlags.awaitSetOrientation(manga.id, orientation.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.SetOrientation(getMangaOrientation()))
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    fun toggleCropBorders(): Boolean {
        val isPagerType = ReadingMode.isPagerType(getMangaReadingMode())
        return if (isPagerType) {
            readerPreferences.cropBorders.toggle()
        } else {
            readerPreferences.cropBordersWebtoon.toggle()
        }
    }

    /**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.number}"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    fun showLoadingDialog() {
        mutableState.update { it.copy(dialog = Dialog.Loading) }
    }

    fun openReadingModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.ReadingModeSelect) }
    }

    fun openOrientationModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.OrientationModeSelect) }
    }

    fun openPageDialog(page: ReaderPage) {
        mutableState.update { it.copy(dialog = Dialog.PageActions(page)) }
        // 開對話框時再掃一次夜讀版：本章可能在閱讀期間才被夜讀佇列產出（章進來時掃的結果已過期）。
        refreshNightAvailability(page.chapter)
    }

    /**
     * 切換日常／夜讀（懸浮鈕）：只改偏好；重畫由 init 觀察 [nightEffective] 那條統一處理（設定面板 checkbox 也是同一條路）。
     */
    fun setNightMode(on: Boolean) {
        readerPreferences.nightReadMode.set(on)
    }

    /** 切換夜讀檔位（懸浮鈕／閱讀設定面板的 chips 寫同一個偏好）：重畫由 init 觀察 [nightTier] 那條 debounce 後處理。 */
    fun setNightTier(tier: NightLevel) {
        translationPreferences.nightReadFillLevel.set(tier.prefValue)
    }

    /**
     * 懸浮鈕上點了**不可用**的檔位（這章沒有可切檔位的夜讀版）：不支援的章提示要先下載；正在產生就報進度；否則跳確認框
     * 「為這一話產生夜讀版？」（確認後 [startChapterNightRender]）。
     */
    fun requestChapterNightRender() {
        val s = state.value
        when {
            // 換章後還沒掃完：不知道這章有沒有夜讀版，先不反應（免得對早就產生好的章跳「產生夜讀版？」）
            s.nightAvailability == null -> Unit
            // 線上章：問要不要先下載（[promptOnlineNight]）；壓縮檔章照舊提示「需先下載成鬆散資料夾」
            s.nightAvailability == NightAvailability.UNSUPPORTED -> {
                if (!promptOnlineNight(OnlineNightPrompt.Trigger.EXPLICIT)) eventChannel.trySend(Event.NightUnsupported)
            }
            s.nightProgress != null -> eventChannel.trySend(
                when {
                    s.nightProgress.paused -> Event.NightQueuePaused
                    !s.nightProgress.running -> Event.NightQueued
                    else -> Event.NightGenerating(done = s.nightProgress.done, total = s.nightProgress.total)
                },
            )
            else -> mutableState.update {
                it.copy(dialog = Dialog.NightGenerateConfirm(legacy = s.nightAvailability == NightAvailability.LEGACY))
            }
        }
    }

    /**
     * 把當前章排入夜讀佇列（[TranslationManager.nightRender]，明確要求 → 解除夜讀 pool 暫停）。入口：長按選單的「為這一話
     * 產生夜讀版」／「重新產生夜讀版」、懸浮鈕的確認框，以及 [force]＝「以目前設定重新產生這一話」（改了亮度後：
     * 新鮮的頁也重做）。非翻譯本也能用。做完由 init 觀察 [TranslationManager.nightChapterDone] 那條重掃／重畫。
     * 缺模型（偵測器／人物分割）→ 提示去下載；沒排進去（不是鬆散資料夾章）→ 提示要先下載，不送假「已排入」。
     */
    fun startChapterNightRender(force: Boolean = false) {
        closeDialog()
        if (!translationPreferences.nightReadEnabled.get()) return
        val manga = manga ?: return
        val readerChapter = getCurrentChapter() ?: return
        val domainChapter = readerChapter.chapter.toDomainChapter() ?: return
        viewModelScope.launchIO {
            val ctx = Injekt.get<Application>()
            if (!TranslationEngineConfig.detectorResolvable(ctx) || !TranslationEngineConfig.charSegResolvable(ctx)) {
                eventChannel.send(Event.NightModelsUnavailable)
                return@launchIO
            }
            if (translationManager.nightRender(manga, listOf(domainChapter), force = force)) {
                eventChannel.send(Event.ChapterNightStarted)
            } else {
                eventChannel.send(Event.NightUnsupported)
            }
        }
    }

    /**
     * 線上章要夜讀（[OnlineNightPrompt]）：該問就跳「這話要先下載才能產生夜讀版，要下載嗎？」（[Dialog.NightDownloadConfirm]），
     * 已經按過「下載」就提示下載中。回傳是否處理了（false＝不是線上章、或夜讀總開關關著：呼叫端照原本的路走）。
     * 打開夜讀模式（[OnlineNightPrompt.Trigger.NIGHT_ON]）時若有別的對話框開著（例如從閱讀設定面板打開），先不問、也不算
     * 問過——不蓋掉使用者正在用的面板；之後點灰色的檔位鈕會再問。主執行緒呼叫。
     */
    private fun promptOnlineNight(trigger: OnlineNightPrompt.Trigger): Boolean {
        val chapter = getCurrentChapter() ?: return false
        val id = chapter.chapter.id ?: return false
        val action = OnlineNightPrompt.decide(
            trigger = trigger,
            online = chapter.pageLoader is HttpPageLoader,
            nightEnabled = translationPreferences.nightReadEnabled.get(),
            asked = id in nightDownloadAskedIds,
            requested = id in nightDownloadRequestedIds,
        )
        return when (action) {
            OnlineNightPrompt.Action.NONE -> false
            OnlineNightPrompt.Action.SKIP -> true
            OnlineNightPrompt.Action.DOWNLOADING -> {
                eventChannel.trySend(Event.NightDownloadWaiting)
                true
            }
            OnlineNightPrompt.Action.ASK -> {
                if (trigger == OnlineNightPrompt.Trigger.NIGHT_ON && state.value.dialog != null) return true
                nightDownloadAskedIds += id
                mutableState.update { it.copy(dialog = Dialog.NightDownloadConfirm) }
                true
            }
        }
    }

    /** 長按選單在線上章的「為這一話產生夜讀版」：同點灰色檔位鈕，問要不要先下載。 */
    fun requestOnlineChapterNight() {
        closeDialog()
        if (!promptOnlineNight(OnlineNightPrompt.Trigger.EXPLICIT)) eventChannel.trySend(Event.NightUnsupported)
    }

    /**
     * [Dialog.NightDownloadConfirm] 按了「下載」：照即時翻譯的線上路徑——
     *  1. 夜讀模型先查（缺就提示去下載，不下載這話）。
     *  2. [TranslationManager.markForNight] 標記「下載完要產生夜讀版」，再下載這話（標記中的章一律存成鬆散資料夾）；章下載完由
     *     下載完成 hook 排入夜讀（要翻譯的章翻完才排），使用者中途離開閱讀器也照做。先標記再看下載狀態：已經下載好的話（例如即時
     *     翻譯先下載了）直接交給 [TranslationManager.onDownloadedForNight] 排入。
     *  3. [awaitDownloadThenReload] 等章節夾出現、重載這一話（保留頁碼）；之後夜讀版做好，`nightChapterDone` 那條照常重畫。
     * 只對當前章；fire-and-forget（不卡 UI）。
     * 「下載中」（[nightDownloadRequestedIds]）只在真的有一條在等的時候成立：等待一結束（重載了、下載成壓縮檔、換到別話、
     * 逾時、缺模型）就拿掉。否則換到別話再回來（條漫捲過章尾就算），點灰色檔位鈕只會一直說「還在下載」、卻沒有人在等、
     * 永遠不重載；拿掉之後會再問一次，按「下載」時這話若已下載好就直接排夜讀並重載。
     */
    fun startOnlineNightDownload() {
        closeDialog()
        if (!translationPreferences.nightReadEnabled.get()) return
        val manga = manga ?: return
        val readerChapter = getCurrentChapter() ?: return
        val id = readerChapter.chapter.id ?: return
        val domainChapter = readerChapter.chapter.toDomainChapter() ?: return
        if (!nightDownloadRequestedIds.add(id)) return
        viewModelScope.launchIO {
            try {
                val ctx = Injekt.get<Application>()
                if (!TranslationEngineConfig.detectorResolvable(ctx) ||
                    !TranslationEngineConfig.charSegResolvable(ctx)
                ) {
                    eventChannel.send(Event.NightModelsUnavailable)
                    return@launchIO
                }
                translationManager.markForNight(id)
                val downloaded = downloadManager.isChapterDownloaded(
                    readerChapter.chapter.name,
                    readerChapter.chapter.scanlator,
                    readerChapter.chapter.url,
                    manga.title,
                    manga.source,
                    skipCache = true,
                )
                if (downloaded) {
                    translationManager.onDownloadedForNight(manga, domainChapter)
                    if (!translationManager.isLooseChapter(manga, domainChapter)) {
                        // 早就下載成壓縮檔了：夜讀做不了
                        eventChannel.send(Event.NightUnsupported)
                        return@launchIO
                    }
                    eventChannel.send(Event.ChapterNightStarted)
                } else {
                    downloadManager.downloadChapters(manga, listOf(domainChapter))
                    eventChannel.send(Event.NightDownloadStarted)
                }
                logcat { "線上夜讀：下載後產生夜讀版 ${readerChapter.chapter.url} downloaded=$downloaded" }
                awaitDownloadThenReload(readerChapter, "線上夜讀")
            } finally {
                nightDownloadRequestedIds.remove(id)
            }
        }
    }

    /**
     * 對 curr/prev/next 三章所有已載入的頁呼叫 [ReaderPage.reload]（holder 就地重 decode `page.stream`、保留縮放）。
     * 三章都重畫＝pager 預載的相鄰頁（含跨章邊界）也一起換，不會滑過去才看到舊模式的圖。
     * 尚未載入/未綁 holder 的頁不受影響（它們之後首次解碼時本來就會讀當時的開關）。
     */
    private fun reloadLoadedPages() {
        val chapters = state.value.viewerChapters ?: return
        listOfNotNull(chapters.currChapter, chapters.prevChapter, chapters.nextChapter)
            .flatMap { it.pages.orEmpty() }
            .forEach { it.reload() }
    }

    /**
     * 解析 [chapter] 的章節夾（頁圖所在的鬆散資料夾）：本機來源目錄章＝[DirectoryPageLoader.file]；
     * 已下載章（含即時翻包裝 [TranslatingPageLoader]）＝下載夾（與 [reRenderPage] 同源）。線上／封存章 → null。
     * 夜讀版存在章節夾的 `.yakuyomi/` 下，所以這是夜讀查找的根。
     */
    private fun resolveChapterDir(chapter: ReaderChapter): UniFile? {
        val manga = manga ?: return null
        return when (val pageLoader = chapter.pageLoader) {
            is DirectoryPageLoader -> pageLoader.file
            is DownloadPageLoader, is TranslatingPageLoader -> downloadProvider.findChapterDir(
                chapter.chapter.name,
                chapter.chapter.scanlator,
                chapter.chapter.url,
                manga.title,
                sourceManager.getOrStub(manga.source),
            )
            else -> null
        }
    }

    /**
     * 背景（IO）重算 [chapter] 的夜讀狀態 → [State.nightAvailability]，順便更新那章 loader 的檔名快取（同一次列目錄）。
     * 只在 [chapter] 仍是當前章時才寫回（換章期間舊掃描完成不得蓋掉新章的結果）；失敗視為沒有（只影響檔位鈕可不可用）。
     */
    private fun refreshNightAvailability(chapter: ReaderChapter) {
        viewModelScope.launchIO {
            // 同一次列目錄順便判「舊規則產生的頁」（檔名快取裡含規則版本記號，見 NightPages.listFinal）
            val (availability, outdated) = try {
                val streams = (chapter.pageLoader as? NightPageSource)?.nightStreams
                val dir = if (streams != null) resolveChapterDir(chapter)?.takeIf { it.isDirectory } else null
                if (streams == null || dir == null) {
                    NightPages.availability(supported = false, names = emptyList()) to false
                } else {
                    val names = streams.index.refresh(dir).keys
                    NightPages.availability(supported = true, names = names) to NightPages.summarize(names).outdated
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "掃描夜讀版失敗" }
                NightAvailability.NONE to false
            }
            mutableState.update { s ->
                if (s.currentChapter?.chapter?.id == chapter.chapter.id) {
                    s.copy(nightAvailability = availability, nightOutdated = outdated)
                } else {
                    s
                }
            }
        }
    }

    /**
     * 檔位切換 → debounce 150 ms（連點只算最後一下）→ 只重畫「上次送出的檔 ≠ 新檔位下會送的檔」的頁（由 loader 判，
     * 它記得每頁實際送出的檔名）。夜讀模式沒開時不動（之後開了本來就整批重畫）。init 呼叫一次。
     */
    private fun observeNightTier() {
        nightTier
            .drop(1)
            .debounce(NIGHT_TIER_DEBOUNCE)
            .onEach { if (nightEffective.value) reloadChangedNightPages() }
            .launchIn(viewModelScope)
    }

    /**
     * 換檔位後只重畫真的會變的頁：對 curr/prev/next 三章，請 loader 重列一次 `.yakuyomi/`、逐頁比「上次實際送出的檔」與
     * 「新檔位下會送的檔」（[eu.kanade.tachiyomi.ui.reader.loader.NightPageStreams.pagesToReload]），只 reload 不同的頁
     * （標準↔更多在 47 頁裡約一半（25 頁）會變；沒變的頁不重新解碼、也不閃）。畫面上看得到的頁（[visibleNightPages]：雙頁對開＝
     * 兩頁）**全都**沒變 → 發 [Event.NightTierNoDifference] 讓懸浮鈕短暫提示，免得使用者以為鈕壞了；條漫不確定看得到
     * 哪幾頁，不發。
     */
    private fun reloadChangedNightPages() {
        val chapters = state.value.viewerChapters ?: return
        val visible = visibleNightPages
        viewModelScope.launchIO {
            var visibleChanged: Boolean? = null
            for (chapter in listOfNotNull(chapters.currChapter, chapters.prevChapter, chapters.nextChapter)) {
                val streams = (chapter.pageLoader as? NightPageSource)?.nightStreams ?: continue
                val pages = chapter.pages ?: continue
                val changed = try {
                    streams.pagesToReload(pages)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    logcat(LogPriority.WARN, e) { "比對夜讀檔位失敗，整章重畫" }
                    pages
                }
                changed.forEach { it.reload() }
                val mine = visible?.filter { it.chapter === chapter }.orEmpty()
                if (mine.isNotEmpty()) {
                    val hit = changed.any { c -> mine.any { it.index == c.index } }
                    visibleChanged = (visibleChanged ?: false) || hit
                }
            }
            if (visibleChanged == false && state.value.nightAvailability == NightAvailability.READY) {
                eventChannel.send(Event.NightTierNoDifference)
            }
        }
    }

    /**
     * 開「重繪當頁」去字法選擇對話框（由頁動作對話框的「重繪」鈕觸發、帶著該頁）。先查這頁有沒有重繪素材
     * （[PageTranslator.hasReRenderMaterials]：完整素材 json＋原圖）：沒有（翻譯時沒開「保留重繪素材」、只存了夜讀素材）
     * 就不開對話框、提示一句（[Event.ReRenderNoMaterials]）——重繪與「原圖（還原未翻）」都做不到，別讓使用者選了方法、
     * 等模型載完才看到「重繪失敗」。查不到章節夾／頁檔時照舊開（交給重繪本身回報）。
     */
    fun openReRenderDialog(page: ReaderPage) {
        viewModelScope.launchIO {
            val target = try {
                resolveDownloadedPageFile(page)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "查重繪素材失敗" }
                null
            }
            if (target != null && !pageTranslator.hasReRenderMaterials(target.first, target.second)) {
                closeDialog()
                eventChannel.send(Event.ReRenderNoMaterials)
                return@launchIO
            }
            mutableState.update { it.copy(dialog = Dialog.ReRenderMethod(page)) }
        }
    }

    /**
     * 已下載章 [page] 的章節夾與頁檔名（與 [reRenderPage] 同源：下載夾裡的頂層圖檔依檔名排序，取第 index 個）。
     * 找不到章節夾或對不上 → null。IO。
     */
    private fun resolveDownloadedPageFile(page: ReaderPage): Pair<UniFile, String>? {
        val manga = manga ?: return null
        val chapter = page.chapter
        val chapterDir = downloadProvider.findChapterDir(
            chapter.chapter.name,
            chapter.chapter.scanlator,
            chapter.chapter.url,
            manga.title,
            sourceManager.getOrStub(manga.source),
        ) ?: return null
        val imageExt = setOf("jpg", "jpeg", "png", "webp")
        val name = chapterDir.listFiles()
            ?.filter { f -> f.isFile && (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in imageExt }
            ?.sortedBy { it.name.orEmpty() }
            ?.getOrNull(page.index)
            ?.name
            ?: return null
        return chapterDir to name
    }

    fun openSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.Settings) }
    }

    /** Yakuyomi：開 reader 內章節清單對話框。 */
    fun openChapterListDialog() {
        mutableState.update { it.copy(dialog = Dialog.ChapterList) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun setBrightnessOverlayValue(value: Int) {
        mutableState.update { it.copy(brightnessOverlayValue = value) }
    }

    /**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val filename = generateFilename(manga, page)

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga.get()) {
            DiskUtil.buildValidFilename(
                manga.title,
            )
        } else {
            ""
        }

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Pictures.create(relativePath),
                    ),
                )
                withUIContext {
                    notifier.onComplete(uri)
                    eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    /**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(copyToClipboard: Boolean) {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val destDir = context.cacheImageDir

        val filename = generateFilename(manga, page)

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, page))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                manga.editCover(Injekt.get(), stream())
                if (manga.isLocal() || manga.favorite) {
                    SetAsCoverResult.Success
                } else {
                    SetAsCoverResult.AddToLibraryFirst
                }
            } catch (e: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    /**
     * 換 [method] 去字法重繪「當頁」（待重繪頁取自 [Dialog.ReRenderMethod]）：復用該章 `.yakuyomi/` 素材
     * （原圖 + 遮罩 + 文字區），不重跑偵測/OCR/翻譯、無網路、只載 lama 一顆。重繪會就地覆蓋頁圖。
     *
     * 流程：
     * 1. 先關對話框 → 立刻把該頁狀態設成 [Page.State.Queue]（holder 顯示 per-page 轉圈圈當「重繪中」指示）
     *    + 送 [Event.ReRenderStarted]（toast「重繪中…」）。
     * 2. [launchIO] 背景重繪（reader 保持可動；IO 約 2–8s，期間 Queue 必被 collect 到）。
     * 3. **不論成功或失敗都** 回 UI thread 呼叫 [PageLoader.retryPage]（[DownloadPageLoader] 會驅動 `→ Ready`）
     *    → holder `collectLatest { Ready -> setImage() }` 重 decode：成功＝讀到覆蓋後新圖、失敗＝讀回原圖
     *    （未被改動），兩者都會把轉圈圈換回可看的圖、不會卡在 spinner（§11 不變式：絕不留比原圖更糟的狀態）。
     * 4. 送 [Event.ReRenderResult] 給 UI 提示成敗。
     *
     * 全程 try/catch、絕不讓 reader crash。只對已下載章（[DownloadPageLoader]、頁圖在磁碟）有意義；
     * 線上/封存頁無素材 → reRenderPage 回 false → 走「重新顯示原圖 + 提示失敗」路徑。
     */
    fun reRenderPage(method: String) {
        val page = (state.value.dialog as? Dialog.ReRenderMethod)?.page ?: return
        closeDialog()
        if (!translationPreferences.translationMasterEnabled.get()) return // 硬總開關：關時不重繪、不載引擎
        val manga = manga ?: return
        val chapter = page.chapter

        viewModelScope.launchIO {
            // 收尾：成功 → page.reload() 驅動 holder **就地**重 decode 譯圖（與即時翻 swapToFile 同機制、保留縮放，
            // 不走「轉圈→retry→重 fit」→ 避免上下黑邊閃動）；失敗/無素材 → 不動（頁面從未進載入態、維持原顯示）。
            suspend fun finish(ok: Boolean) {
                if (ok) withUIContext { page.reload() }
                eventChannel.send(Event.ReRenderResult(ok))
            }

            // 提示「重繪中…」。不再把頁面設成載入態 → 處理期間維持原圖、好了才就地換、不閃黑邊。
            eventChannel.send(Event.ReRenderStarted)

            try {
                val source = sourceManager.getOrStub(manga.source)
                val chapterDir = downloadProvider.findChapterDir(
                    chapter.chapter.name,
                    chapter.chapter.scanlator,
                    chapter.chapter.url,
                    manga.title,
                    source,
                )
                if (chapterDir == null) {
                    finish(false)
                    return@launchIO
                }
                // page.index → 檔名：以與下載頁列表相同的排序（DownloadManager.buildPageList 的 sortedBy{name}）
                // 取第 index 個圖檔；對不上（資料夾被外部改動）→ 放棄、重新顯示原圖。
                val imageExt = setOf("jpg", "jpeg", "png", "webp")
                val sortedImages = chapterDir.listFiles()
                    ?.filter { f ->
                        f.isFile && (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in imageExt
                    }
                    ?.sortedBy { it.name.orEmpty() }
                    .orEmpty()
                val pageFileName = sortedImages.getOrNull(page.index)?.name
                if (pageFileName == null) {
                    finish(false)
                    return@launchIO
                }

                // 覆寫前先問這章有沒有夜讀版（覆寫會刪掉這頁的夜讀檔，事後問會漏掉「只有這頁有」的章）
                val hadNight = translationManager.chapterHasNightBeforePage(chapterDir)
                val ok = pageTranslator.reRenderPage(chapterDir, pageFileName, method)
                // 補排夜讀放在 finish 前：finish 會等 UI 收事件，使用者這時退出 reader 的話後面的程式碼不會跑
                if (ok) requeueNightAfterPage(manga, chapter, hadNight)
                finish(ok)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "重繪當頁失敗" }
                finish(false)
            }
        }
    }

    /**
     * 單頁重繪（含還原原圖）／單頁翻譯成功之後：那頁的夜讀版已被覆寫端作廢，這章本來有夜讀版（[hadNight]，覆寫前問的）
     * 或已在夜讀佇列的話排回夜讀佇列把這頁補回（[TranslationManager.queueNightAfterPage]，不動暫停）。在 TranslationManager
     * 自己的 scope 上跑、馬上返回，reader 關掉也照樣排。做完由 init 觀察 [TranslationManager.nightChapterDone] 那條重掃／重畫。
     */
    private fun requeueNightAfterPage(manga: Manga, chapter: ReaderChapter, hadNight: Boolean) {
        val domainChapter = chapter.chapter.toDomainChapter() ?: return
        translationManager.queueNightAfterPage(manga, domainChapter, hadNight)
    }

    /**
     * 「翻譯這頁」（reader 頁動作對話框 → 已下載章）：對 [page] 解析下載章目錄 + 頁檔（與 [reRenderPage] 同源），
     * 透過 [PageTranslator.translateSinglePage] 翻單頁、就地覆蓋落地，成功後刷新該頁顯示譯圖。
     *
     * 與佇列共用引擎鎖 + manifest 鎖（[PageTranslator.translateSinglePage] 內走 [eu.kanade.tachiyomi.data.translation.TranslationEngineService]
     * 的 Mutex + manifestMutex）→ 不會與背景整章翻併發壞檔。§11：失敗/略過留原圖、只提示。
     *
     * 流程同 [reRenderPage]：先把該頁設 [Page.State.Queue]（轉圈圈當「翻譯中」）+ toast，背景翻完不論成敗都
     * [PageLoader.retryPage] 刷新（成功＝譯圖、失敗＝原圖），再 toast 成敗。線上章不提供此鈕（呼叫端 gate）。
     */
    fun translateThisPage() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page ?: return
        closeDialog()
        if (!translationPreferences.translationMasterEnabled.get()) return // 硬總開關：關時不翻、不載引擎
        val manga = manga ?: return
        val chapter = page.chapter
        val method = translationPreferences.inpaintMethod.get()

        viewModelScope.launchIO {
            // 模型不可用（缺 / 舊版 v1）→ 明確提示去設定更新，別轉圈圈後用 generic「翻譯失敗」冒充網路錯（修 0.16.0 舊模型靜默）。
            val ctx = Injekt.get<Application>()
            if (!TranslationEngineConfig.modelsResolvable(ctx)) {
                eventChannel.send(Event.TranslateModelsUnavailable(TranslationEngineConfig.modelsOutdated(ctx)))
                return@launchIO
            }
            // 收尾：回 UI thread 刷新該頁（retryPage → Ready → holder 重 decode，把 spinner 換回圖）並回報成敗。
            suspend fun finish(ok: Boolean, event: Event = Event.TranslatePageResult(ok)) {
                withUIContext { page.chapter.pageLoader?.retryPage(page) }
                eventChannel.send(event)
            }

            withUIContext { page.status = Page.State.Queue }
            eventChannel.send(Event.TranslatePageStarted)

            try {
                val source = sourceManager.getOrStub(manga.source)
                val chapterDir = downloadProvider.findChapterDir(
                    chapter.chapter.name,
                    chapter.chapter.scanlator,
                    chapter.chapter.url,
                    manga.title,
                    source,
                )
                if (chapterDir == null) {
                    finish(false)
                    return@launchIO
                }
                // page.index → 檔名：與下載頁列表相同排序（DownloadManager.buildPageList 的 sortedBy{name}）取第 index 個。
                val imageExt = setOf("jpg", "jpeg", "png", "webp")
                val pageFileName = chapterDir.listFiles()
                    ?.filter { f ->
                        f.isFile && (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in imageExt
                    }
                    ?.sortedBy { it.name.orEmpty() }
                    ?.getOrNull(page.index)
                    ?.name
                if (pageFileName == null) {
                    finish(false)
                    return@launchIO
                }
                // 覆寫前先問這章有沒有夜讀版（同 reRenderPage）
                val hadNight = translationManager.chapterHasNightBeforePage(chapterDir)
                when (pageTranslator.translateSinglePage(chapterDir, pageFileName, method)) {
                    PageTranslator.SinglePageResult.TRANSLATED -> {
                        requeueNightAfterPage(manga, chapter, hadNight) // finish 前：見 reRenderPage
                        finish(true)
                    }
                    PageTranslator.SinglePageResult.NOT_TRANSLATED -> finish(false)
                    // 已翻過、沒有原圖素材（只存夜讀素材的頁）：什麼都沒動，說清楚為什麼不翻、別冒充「翻譯失敗」
                    PageTranslator.SinglePageResult.NO_ORIGINAL -> finish(false, Event.TranslatePageNoOriginal)
                    // 頁圖超過像素上限：沒翻、原圖沒動，說清楚原因
                    PageTranslator.SinglePageResult.TOO_LARGE -> finish(false, Event.TranslatePageTooLarge)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "翻譯這頁失敗" }
                finish(false)
            }
        }
    }

    /**
     * 「開始翻譯這話」（reader 頁動作對話框，當前章**未**在翻譯佇列時顯示）——手動觸發，與自動（讀到當前章）
     * 共用同一條可靠路徑：
     *  - **已下載**：直接把當前章插隊排入翻譯佇列（已包 [TranslatingPageLoader] → [TranslatingPageLoader.onActivated]；
     *    否則 [TranslationManager.translate] `atFront=true`）。
     *  - **線上（未下載）**：走與自動相同的 [triggerOnlineLiveTranslate]——下載該章、完成後重載進已下載路徑、再排入翻譯。
     *    （取代舊版只 markForTranslate + 下載、同 session 不顯示的做法 → 手動線上翻也會在本 session 顯示。）
     *    手動是明確意圖 → 不過自動的 liveTranslate/分類 gate；但仍登記 [onlineTriggeredChapterIds] 與自動互斥去重。
     *
     * 排入後 reader 角落即時翻指示器（[State.liveTranslateProgress]，併流自 [TranslationManager.queueState]）會自動亮起，
     * 故無需在此另外更新 UI。toast 提示「已開始」。
     */
    fun startChapterTranslate() {
        closeDialog()
        if (!translationPreferences.translationMasterEnabled.get()) return // 硬總開關：關時不翻、不送「已開始」假回饋
        val manga = manga ?: return
        val readerChapter = getCurrentChapter() ?: return
        val domainChapter = readerChapter.chapter.toDomainChapter() ?: return

        viewModelScope.launchIO {
            // 模型不可用（缺 / 舊版 v1）→ 明確提示去設定更新，別送假「已開始」再默默變紅（修 0.16.0 舊模型靜默）。
            val ctx = Injekt.get<Application>()
            if (!TranslationEngineConfig.modelsResolvable(ctx)) {
                eventChannel.send(Event.TranslateModelsUnavailable(TranslationEngineConfig.modelsOutdated(ctx)))
                return@launchIO
            }
            try {
                val isDownloaded = downloadManager.isChapterDownloaded(
                    readerChapter.chapter.name,
                    readerChapter.chapter.scanlator,
                    readerChapter.chapter.url,
                    manga.title,
                    manga.source,
                    skipCache = true,
                )
                if (isDownloaded) {
                    // 已下載：插隊排入翻譯佇列。已包裝 → 走 loader 的 onActivated（與自動同入口、冪等）；否則直接 translate。
                    val pageLoader = readerChapter.pageLoader
                    if (pageLoader is TranslatingPageLoader) {
                        pageLoader.onActivated()
                    } else {
                        // reader 控制鈕「翻這話」＝即時情境 → 即時去字法（預設 AI 去字，與自動即時翻一致）。
                        translationManager.translate(
                            manga,
                            listOf(domainChapter),
                            atFront = true,
                            method = translationManager.liveInpaintMethod(),
                        )
                    }
                } else {
                    // 線上：走與自動相同的「下載 + 完成後重載進已下載路徑」流程（本 session 也會顯示）。
                    // 登記去重集合，避免隨後 currChapter flow 的自動路徑重觸發同一章。
                    readerChapter.chapter.id?.let { onlineTriggeredChapterIds.add(it) }
                    triggerOnlineLiveTranslate(readerChapter)
                }
                eventChannel.send(Event.ChapterTranslateStarted)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "開始翻譯這話失敗" }
            }
        }
    }

    /**
     * 「中止這話翻譯」（reader 頁動作對話框，當前章**正在**翻譯佇列時顯示）：
     * [TranslationManager.cancel] 取消當前章——涵蓋 QUEUE（直接移除）與 TRANSLATING（設合作式中止旗標
     * [TranslationManager] `stopActive`，正在翻的章在下一頁邊界停下後移除），兩態皆生效。
     * 取消後角落指示器（併流自 queueState）自動消失。
     */
    fun stopChapterTranslate() {
        closeDialog()
        val chapterId = getCurrentChapter()?.chapter?.id ?: return
        translationManager.cancelTranslation(listOf(chapterId)) // 只中止翻譯／重繪項；同章夜讀項不動
        viewModelScope.launchIO { eventChannel.send(Event.ChapterTranslateStopped) }
    }

    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack.get()) return

        val manga = manga ?: return
        val context = Injekt.get<Application>()

        viewModelScope.launchNonCancellable {
            trackChapter.await(context, manga.id, readerChapter.chapter.chapter_number.toDouble())
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read) return
        val manga = manga ?: return

        viewModelScope.launchNonCancellable {
            downloadManager.enqueueChaptersToDelete(listOf(chapter.chapter.toDomainChapter()!!), manga)
        }
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingChapters()
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val initError: Throwable? = null,
        val viewerChapters: ViewerChapters? = null,
        val bookmarked: Boolean = false,
        val isLoadingAdjacentChapter: Boolean = false,
        val currentPage: Int = -1,

        /**
         * Viewer used to display the pages (pager, webtoon, ...).
         */
        val viewer: Viewer? = null,
        val dialog: Dialog? = null,
        val menuVisible: Boolean = false,
        @IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,

        /**
         * 正在讀的這一章的即時翻譯進度（在 reader 角落顯示小指示器）。
         * null＝當前章不在翻譯佇列（沒排隊也沒在翻）→ 不顯示。
         * 由 [translationManager] 的佇列與當前章 id 併流算得（見 init 區塊）。
         */
        val liveTranslateProgress: LiveTranslateProgress? = null,
        /** 引擎是否正在載入（~100MB）：reader 角落指示器顯示「引擎載入中…」（[eu.kanade.presentation.reader.ReaderLiveTranslateIndicator]）。 */
        val engineLoading: Boolean = false,

        /**
         * 當前章的夜讀狀態（有可切檔位的夜讀版／只有舊版單檔／沒有／不支援）。懸浮鈕據此決定檔位鈕可不可用，長按選單據此給
         * 「產生」或「以目前設定重新產生」。IO 掃描結果、由 [refreshNightAvailability] 更新。null＝還不知道（進閱讀器、
         * 換章後掃描完成前）：檔位鈕畫成灰、點了不反應，長按選單不給產生鈕。
         */
        val nightAvailability: NightAvailability? = null,
        /**
         * 當前章的夜讀版裡有舊規則產生的頁（app 更新改了夜讀規則；見 NightPages 的規則版本）：長按選單多給「更新夜讀版」。
         * 與 [nightAvailability] 同一次掃描；還不知道＝false。
         */
        val nightOutdated: Boolean = false,
        /** 當前章在夜讀佇列的進度（排隊或產生中）；null＝不在佇列（或失敗待重試）。 */
        val nightProgress: NightProgress? = null,
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        val totalPages: Int
            get() = currentChapter?.pages?.size ?: -1
    }

    /**
     * 當前章在翻譯佇列中的進度快照（給 reader 內角落小指示器）。
     * [queued]＝true 表示僅排隊中（尚未開始翻、[done]/[total] 還未生效）；false＝翻譯中（[done]/[total] 有效）。
     */
    @Immutable
    data class LiveTranslateProgress(
        val done: Int,
        val total: Int,
        val queued: Boolean,
    )

    /**
     * 當前章在夜讀佇列的進度快照（[running]＝產生中、false＝排隊中，[done]/[total] 還沒意義時都是 0）。
     * [paused]＝排隊中且被暫停 hold 住（單章、這本的夜讀組或夜讀 pool）。
     */
    @Immutable
    data class NightProgress(
        val done: Int,
        val total: Int,
        val running: Boolean,
        val paused: Boolean = false,
    ) {
        /**
         * 懸浮鈕進度環：null＝不畫（排隊中、暫停中：沒在動，畫一個停住的環像卡死）；[Float.NaN]＝不定進度（剛開跑、
         * 總頁數還不知道）；其餘 0..1。
         */
        val ring: Float?
            get() = when {
                !running -> null
                total <= 0 -> Float.NaN
                else -> (done.toFloat() / total).coerceIn(0f, 1f)
            }
    }

    sealed interface Dialog {
        data object Loading : Dialog
        data object Settings : Dialog
        data object ReadingModeSelect : Dialog
        data object OrientationModeSelect : Dialog
        data class PageActions(val page: ReaderPage) : Dialog

        /** 重繪當頁去字法選擇對話框（帶著要重繪的頁）。 */
        data class ReRenderMethod(val page: ReaderPage) : Dialog

        /** Yakuyomi：reader 內章節清單（點章跳轉，不離開 reader）。 */
        data object ChapterList : Dialog

        /** 夜讀懸浮鈕點了不可用的檔位：「為這一話產生夜讀版？」[legacy]＝這章只有舊版單檔（文字改「重新產生」）。 */
        data class NightGenerateConfirm(val legacy: Boolean) : Dialog

        /** 線上章要夜讀：「這話要先下載才能產生夜讀版，要下載嗎？」（確認後 [startOnlineNightDownload]）。 */
        data object NightDownloadConfirm : Dialog
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event

        /** Yakuyomi：自動偵測 webtoon 切閱讀模式後，重建 viewer（updateViewer + setChapters）。 */
        data object RebuildViewer : Event
        data object PageChanged : Event
        data class SetOrientation(val orientation: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(val uri: Uri, val page: ReaderPage) : Event
        data class CopyImage(val uri: Uri) : Event

        /** 開始重繪當頁（IO 約需數秒）：給 UI 提示「重繪中…」；頁面同時會顯示 per-page 轉圈圈。 */
        data object ReRenderStarted : Event

        /** 重繪當頁結果（true＝成功覆蓋並刷新／false＝無素材或失敗），給 UI 提示。 */
        data class ReRenderResult(val success: Boolean) : Event

        /** 這頁沒有重繪素材（翻譯時沒開保留素材、只存了夜讀素材）：不開重繪對話框，提示重繪與還原原圖都做不到。 */
        data object ReRenderNoMaterials : Event

        /** 「翻譯這頁」拒絕：這頁已翻過、沒有原圖素材（頁檔就是譯圖），再翻沒有意義。什麼都沒動。 */
        data object TranslatePageNoOriginal : Event

        /** 「翻譯這頁」沒翻：頁圖超過像素上限（[eu.kanade.tachiyomi.data.translation.PageSizeCap]），原圖沒動。 */
        data object TranslatePageTooLarge : Event

        /** 開始翻譯當頁（IO 約需數秒）：給 UI 提示「翻譯中…」；頁面同時會顯示 per-page 轉圈圈。 */
        data object TranslatePageStarted : Event

        /** 翻譯當頁結果（true＝成功覆蓋並刷新／false＝略過/失敗），給 UI 提示。 */
        data class TranslatePageResult(val success: Boolean) : Event

        /** 已把當前章排入翻譯（已下載＝排佇列／線上＝觸發下載 + 標記待翻），給 UI 提示「已開始」。 */
        data object ChapterTranslateStarted : Event

        /** 已中止當前章翻譯（取消佇列項 / 中止進行中），給 UI 提示「已中止」。 */
        data object ChapterTranslateStopped : Event

        /** 已把當前章排入夜讀佇列（頁動作對話框「為這一話產生夜讀版」），給 UI 提示「已排入」。 */
        data object ChapterNightStarted : Event

        /** 夜讀模型（偵測器／人物分割）缺，給 UI 提示去下載模型。 */
        data object NightModelsUnavailable : Event

        /** 這章不支援夜讀（壓縮檔章、線上章）：提示要先下載成鬆散資料夾。 */
        data object NightUnsupported : Event

        /** 這章的夜讀版正在產生（點了不可用的檔位）：提示進度（[total] 為 0＝剛開跑、還不知道頁數，不顯示數字）。 */
        data class NightGenerating(val done: Int, val total: Int) : Event

        /** 這章已排入夜讀佇列、還沒輪到（點了不可用的檔位）。 */
        data object NightQueued : Event

        /** 這章在夜讀佇列裡、但被暫停 hold 住（點了不可用的檔位）。 */
        data object NightQueuePaused : Event

        /** 換了檔位、當前頁卻沒變（這頁在這一檔跟上一檔一樣）：懸浮鈕短暫提示。 */
        data object NightTierNoDifference : Event

        /** 線上章按了「下載」：開始下載，下載完會自動產生夜讀版。 */
        data object NightDownloadStarted : Event

        /** 線上章已經按過「下載」、還在下載（又點了灰色檔位鈕）：下載完會自動產生夜讀版。 */
        data object NightDownloadWaiting : Event

        /** 模型不可用（缺 / 舊版 v1 → v2 引擎載不動）→ 提示去設定下載/更新，別靜默或用 generic 失敗冒充。outdated=true＝有舊檔待更新。 */
        data class TranslateModelsUnavailable(val outdated: Boolean) : Event
    }

    companion object {
        /** 線上即時翻：偵測「下載完成（章目錄出現）」的輪詢間隔。權威信號＝目錄存在，1.5s 一次足夠即時又不耗 CPU。 */
        private const val ONLINE_DOWNLOAD_POLL_MS = 1_500L

        /** 線上即時翻：等下載完成的上限（逾時放棄重載、維持線上原圖；下載/翻譯仍可能在背景完成）。 */
        private const val ONLINE_DOWNLOAD_TIMEOUT_MS = 5 * 60 * 1_000L

        /** 夜讀檔位連點：只算最後一下（停 150 ms 才重畫）。 */
        private val NIGHT_TIER_DEBOUNCE = 150.milliseconds
    }
}
