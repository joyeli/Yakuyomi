package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.net.Uri
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
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
import kotlin.getValue
import kotlin.time.Clock

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
            val item = queue.firstOrNull { it.chapter.id == currentChapterId } ?: return@combine null
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
        val source = sourceManager.getOrStub(manga.source)

        logcat { "線上即時翻：觸發下載 + 等完成後重載 ${chapter.chapter.url}" }
        translationManager.markForTranslate(domainChapter.id)
        downloadManager.downloadChapters(manga, listOf(domainChapter))

        viewModelScope.launchIO {
            // 有界輪詢章目錄出現（權威信號、無漏接）。VM cleared（使用者離開 reader）→ coroutine 取消、自動停。
            var waited = 0L
            while (waited < ONLINE_DOWNLOAD_TIMEOUT_MS) {
                // 使用者已換到別章 → 放棄重載（下載仍會在背景完成 + 翻、靠 markForTranslate）。
                if (getCurrentChapter()?.chapter?.id != chapter.chapter.id) {
                    logcat { "線上即時翻：使用者已離開此章，停止等待重載（下載/翻譯仍在背景進行）" }
                    return@launchIO
                }
                val dir = downloadProvider.findChapterDir(
                    chapter.chapter.name,
                    chapter.chapter.scanlator,
                    chapter.chapter.url,
                    manga.title,
                    source,
                )
                if (dir != null) {
                    if (dir.isDirectory) {
                        // 鬆散資料夾 → 下載完成、重載當前章進已下載路徑（保留閱讀位置）。
                        reloadCurrentChapterPreservingPage()
                    } else {
                        // CBZ：即時翻本里程碑不支援 → 維持線上原圖（下載仍完成、退出重進可讀）。
                        logcat(LogPriority.WARN) { "線上即時翻：下載為 CBZ、即時翻暫不支援，維持線上原圖" }
                    }
                    return@launchIO
                }
                delay(ONLINE_DOWNLOAD_POLL_MS)
                waited += ONLINE_DOWNLOAD_POLL_MS
            }
            logcat(LogPriority.WARN) { "線上即時翻：等下載完成逾時，維持線上原圖（下載/翻譯仍可能在背景完成）" }
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
    fun onPageSelected(page: ReaderPage) {
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
    }

    /** 開「重繪當頁」去字法選擇對話框（由頁動作對話框的「重繪」鈕觸發、帶著該頁）。 */
    fun openReRenderDialog(page: ReaderPage) {
        mutableState.update { it.copy(dialog = Dialog.ReRenderMethod(page)) }
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

                val ok = pageTranslator.reRenderPage(chapterDir, pageFileName, method)
                finish(ok)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "重繪當頁失敗" }
                finish(false)
            }
        }
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
            suspend fun finish(ok: Boolean) {
                withUIContext { page.chapter.pageLoader?.retryPage(page) }
                eventChannel.send(Event.TranslatePageResult(ok))
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
                val ok = pageTranslator.translateSinglePage(chapterDir, pageFileName, method)
                finish(ok)
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
        translationManager.cancel(listOf(chapterId))
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

        /** 開始翻譯當頁（IO 約需數秒）：給 UI 提示「翻譯中…」；頁面同時會顯示 per-page 轉圈圈。 */
        data object TranslatePageStarted : Event

        /** 翻譯當頁結果（true＝成功覆蓋並刷新／false＝略過/失敗），給 UI 提示。 */
        data class TranslatePageResult(val success: Boolean) : Event

        /** 已把當前章排入翻譯（已下載＝排佇列／線上＝觸發下載 + 標記待翻），給 UI 提示「已開始」。 */
        data object ChapterTranslateStarted : Event

        /** 已中止當前章翻譯（取消佇列項 / 中止進行中），給 UI 提示「已中止」。 */
        data object ChapterTranslateStopped : Event

        /** 模型不可用（缺 / 舊版 v1 → v2 引擎載不動）→ 提示去設定下載/更新，別靜默或用 generic 失敗冒充。outdated=true＝有舊檔待更新。 */
        data class TranslateModelsUnavailable(val outdated: Boolean) : Event
    }

    companion object {
        /** 線上即時翻：偵測「下載完成（章目錄出現）」的輪詢間隔。權威信號＝目錄存在，1.5s 一次足夠即時又不耗 CPU。 */
        private const val ONLINE_DOWNLOAD_POLL_MS = 1_500L

        /** 線上即時翻：等下載完成的上限（逾時放棄重載、維持線上原圖；下載/翻譯仍可能在背景完成）。 */
        private const val ONLINE_DOWNLOAD_TIMEOUT_MS = 5 * 60 * 1_000L
    }
}
