package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 即時翻譯（reader 邊讀邊翻）loader——**包**在 [DownloadPageLoader] 外面（**只服務已下載章**）。
 *
 * **線上（未下載）章不再由本 loader 處理**：舊版「包住 [HttpPageLoader]、開章觸發下載、同 session 串流改指」這條
 * 在實機不可靠（下載完同 session 不換、要退出重進），已整段移除。線上的即時翻改由 [ReaderViewModel] 在
 * 「當前正在讀的章」上：下載該章 → 完成後**重載章節**進入可靠的「已下載路徑」（reload 後章變已下載 →
 * [ChapterLoader.shouldTranslateLive] 才把它包進本 loader）。淨效果不變，但靠成熟的下載路徑、可靠。
 *
 * **設計（顯示層、不自己翻）**：**被啟動時**（[onActivated]，只有「正在讀的章」會被 [ReaderViewModel] 啟動）把
 * **整章**排入受管理的翻譯佇列（[TranslationManager.translate]，與「下載時翻譯」「漫畫頁翻譯鈕」同一條），由佇列
 * 在背景用 [PageTranslator.translateChapter] 整章翻、就地覆蓋頁圖 + 存重繪素材 + 記 manifest。本 loader **不進引擎翻**
 * 任何頁，只負責：
 *  - 顯示下載原檔，直到某頁被佇列翻好（manifest 命中）才**換上**譯圖。
 *  - 觀察佇列狀態（[TranslationManager.queueState]），每次變動掃一次 manifest，把新翻好的頁批次換頁。
 *
 * **只有「正在讀的章」會觸發**（修「讀第 N 話卻把預取的 N±1 話也自動下載/翻譯」的 bug）：
 *  - [loadPage] **不再**自動排入翻譯——它只登記頁 + 顯示（manifest 命中換譯圖、否則顯示下載原圖）。
 *  - 排入翻譯只發生在 [onActivated]，而 [onActivated] 只由 [ReaderViewModel] 對**當前章**呼叫；
 *    預取/相鄰章的 loader 會被建立、會 [getPages]/[loadPage]，但**永不被啟動** → 永不排入翻譯佇列。
 *
 * **持久化 / resume**：全交給佇列的 [PageTranslator.translateChapter]——譯圖覆蓋下載檔 + 存素材（`.yakuyomi/`，
 * 即時翻時 [PageTranslator] 強制存）+ 記 manifest（`.yakuyomi_translated`）。退出再進章節 → manifest 命中、
 * 本 loader 直接換上已覆蓋的譯圖（不重翻）；page-level resume 只補沒翻的頁。
 *
 * **換頁機制（不擋讀：翻譯期間維持原圖、譯好才換）**（與 [DownloadPageLoader.retryPage] 同套）：
 * holder 同時 `loadPage(page)` 並 `statusFlow.collectLatest { Ready -> setImage() }`。本 loader 在「翻好的譯圖」落盤時
 * 用 [swapToFile] 驅動一次 [Page.State.Queue] →（`delay()` 後）[Page.State.Ready] 真轉換，
 * 觸發 holder 重 decode `page.stream` 讀到譯圖。這一閃只發生在「真的換了檔」的頁、且只在該當下，故不擋讀。
 *
 * §11：永不顯示比來源更糟的東西——維持下載原檔、譯好才換譯圖；翻譯失敗/略過 → 下載檔維持原圖
 * （[PageTranslator.translateChapter] 只覆蓋成功頁、只把成功頁記進 manifest），本 loader 對未命中 manifest 的頁
 * **不換頁** → 原圖持續可讀，絕不顯示空白/更糟的頁。
 *
 * **佇列優先序（正在讀的章插隊）**：[onActivated] ＝把這章插到佇列**最前**（[TranslationManager.translate] `atFront=true`），
 * 搶在其他排隊章之前翻 → 讀者不必盯著原圖等其他排隊章翻完。
 * **仍有的限制**：若**另一章正在翻中**（TRANSLATING），那章不被中途搶占（會翻完當前章才輪到本章）；本章會排到所有 QUEUE 項之前。
 */
internal class TranslatingPageLoader(
    private val delegate: PageLoader,
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: Source,
    private val downloadProvider: DownloadProvider,
    private val translationManager: TranslationManager,
) : PageLoader() {

    // PageTranslator 未在 DI 註冊（全 codebase 都直接 new、見 ReaderViewModel）→ 比照以 Application context 自建，避免 Injekt 取不到。
    // 本 loader 只用它讀 manifest（[PageTranslator.isPageTranslated]/[PageTranslator.donePages]）——實際翻譯在佇列。
    private val pageTranslator = PageTranslator(Injekt.get<Application>())

    /** 佇列觀察 / 換頁用的 scope（背景 IO）；[recycle] 時取消（只停觀察，**不**停佇列——整章要繼續在背景翻）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 章目錄（下載夾）：lazy 解析一次（[ensureResolved]），之後查 manifest / 換頁都用它。
     * null＝解析不到（CBZ 即時翻不支援）→ 退回純顯示原圖、不換頁。
     */
    @Volatile
    private var chapterDir: UniFile? = null

    /**
     * `page.index → 下載檔` 對映：以**與 [DownloadPageLoader] 相同的排序**建立
     * （`DownloadManager.buildPageList`＝圖檔 `sortedBy { name }` 後 `mapIndexed` 給 index）。
     * 故第 `index` 個 = 名稱排序後第 index 個圖檔，與 holder 顯示的頁一一對應、查 manifest 用正確的檔名。
     */
    @Volatile
    private var pageFiles: List<UniFile> = emptyList()

    /** 章目錄是否已嘗試解析過（避免每頁重掃；解析不到也只試一次）。 */
    @Volatile
    private var resolved = false

    /** 是否已把整章排入佇列 + 啟動佇列觀察者（只做一次）。在 [this] 鎖下寫、@Volatile 供無鎖快檢。 */
    @Volatile
    private var enqueued = false

    /**
     * 仍在顯示下載原檔、等佇列翻好換譯圖的頁（`page.index → ReaderPage`）。
     * 佇列觀察者把命中 manifest 的頁換譯圖後從這移除。存取都在 synchronized([tracked]) 下。
     */
    private val tracked = mutableMapOf<Int, ReaderPage>()

    /** 與 [delegate] 保持一致：[eu.kanade.tachiyomi.ui.reader.ReaderViewModel.preload] 會依 isLocal 分流。 */
    override var isLocal: Boolean = delegate.isLocal

    /** 頁清單沿用 [delegate]（同一批頁、同一份檔案串流）；把每頁的 chapter 綁回本章（與 ChapterLoader 一致）。 */
    override suspend fun getPages(): List<ReaderPage> =
        delegate.getPages().onEach { it.chapter = chapter }

    /**
     * 解析章目錄 + `page.index → 檔` 對映（lazy、只試一次）。
     * 與 [DownloadPageLoader] 同源：[DownloadProvider.findChapterDir] 找章目錄、圖檔副檔名過濾 + `sortedBy { name }`。
     */
    private fun ensureResolved() {
        if (resolved) return
        synchronized(this) {
            if (resolved) return
            resolved = true
            val dbChapter = chapter.chapter
            val dir = downloadProvider.findChapterDir(
                dbChapter.name,
                dbChapter.scanlator,
                dbChapter.url,
                manga.title,
                source,
            )
            // 鬆散資料夾才有逐檔可覆蓋的頁圖；CBZ（isFile）即時翻不支援（本里程碑）→ 留空、退回純顯示原圖。
            if (dir == null || !dir.isDirectory) {
                logcat(LogPriority.WARN) { "即時翻找不到鬆散章目錄，回退顯示原圖（CBZ 即時翻暫不支援）" }
                return
            }
            chapterDir = dir
            pageFiles = dir.listFiles()
                ?.filter { f -> f.isFile && (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXT }
                ?.sortedBy { it.name.orEmpty() }
                .orEmpty()
        }
    }

    /**
     * **啟動本 loader**：把整章排入翻譯佇列 + 啟動佇列觀察者（冪等、只做一次）。
     *
     * **只由 [eu.kanade.tachiyomi.ui.reader.ReaderViewModel] 對「當前正在讀的章」呼叫**（修預取章被自動翻的 bug）：
     *  - 預取/相鄰章的 loader 雖會 [getPages]/[loadPage]，但 [ReaderViewModel] 不會對它們呼叫本方法 → 永不排入翻譯。
     *  - [loadPage] 本身**不**排入翻譯（只顯示），故「載了頁」≠「會翻」；只有「被啟動」才會翻。
     *
     * 走 [TranslationManager.translate]（與下載 hook / 翻譯鈕同一條）：
     *  - 佇列在背景整章翻、覆蓋頁圖 + 記 manifest（loader 不自己翻）。
     *  - 章節清單指示器觀察同一 [TranslationManager.queueState] → 自動顯示「翻譯中」。
     *  - atFront＝true：正在讀的章插隊到佇列最前（搶在其他排隊章之前翻）。
     *  - [translate] **不**綁「下載時翻譯」開關（gate 只在呼叫端＝[ChapterLoader.shouldTranslateLive] 已確認引擎就緒）。
     *
     * toDomainChapter null（無 id）時排不了——理論上有 id，保險起見只記 log、跳過排入（仍顯示原圖）。
     */
    fun onActivated() {
        if (enqueued) return
        synchronized(this) {
            if (enqueued) return
            enqueued = true
            val domainChapter = chapter.chapter.toDomainChapter()
            if (domainChapter == null) {
                logcat(LogPriority.WARN) { "即時翻：章無 id、無法排入佇列（維持顯示原圖）" }
                return
            }
            // 整章排入受管理佇列（背景翻、可暫停/取消/重試、清單顯示翻譯中）。atFront＝true：正在讀的章插隊到最前。
            translationManager.translate(manga, listOf(domainChapter), atFront = true)
            startQueueObserver()
        }
    }

    /**
     * 佇列觀察者（單一 coroutine、跟著 [scope]）：每次 [TranslationManager.queueState] 變動 →
     * 讀**一次** [PageTranslator.donePages]（manifest 快照）→ 把 [tracked] 裡「檔名已在 done-set」的頁 [swapToFile]
     * 換上譯圖、再從 tracked 移除。佇列每翻好一頁就刷一批 → 頁面隨翻譯進度逐步換成譯圖。
     *
     * 由 [onActivated] 啟動（已下載章 → [tracked] 裡的頁串流本就指向下載檔，佇列翻好覆蓋的就是它）。
     */
    private fun startQueueObserver() {
        scope.launch {
            translationManager.queueState.collect {
                val dir = chapterDir ?: return@collect
                // 沒有頁在等（都換完了）就不必讀 manifest。
                val pending = synchronized(tracked) { tracked.isNotEmpty() }
                if (!pending) return@collect
                val done = pageTranslator.donePages(dir) // 一次讀檔、給這批所有 tracked 頁共用
                // 蒐集這輪命中的頁（在鎖內挑出 + 移除），鎖外再 swap（swapToFile 內有 delay、不宜持鎖）。
                val ready = synchronized(tracked) {
                    val hit = tracked.filter { (_, page) ->
                        pageFiles.getOrNull(page.index)?.name?.let { it in done } == true
                    }
                    hit.keys.forEach { tracked.remove(it) }
                    hit.values.toList()
                }
                ready.forEach { swapToFile(it) }
            }
        }
    }

    /**
     * 顯示這一頁（已下載章）。本 loader **不翻、也不排入翻譯**——只決定顯示原圖還是換譯圖。
     * （排入翻譯改由 [onActivated]，且只對「正在讀的章」觸發 → 修預取章被自動翻的 bug。）
     *
     * 流程（背景 IO，狀態預設不動 → 維持 holder 已在顯示的可讀原圖）：
     *  1. 讓 delegate 完成前置（CBZ 解頁；目錄頁＝no-op）。狀態不動。
     *  2. 解析章目錄；對不上章目錄/該頁檔 → 不插手、原圖留著（不換頁）。
     *  3. manifest 命中 → [swapToFile] 換譯圖。
     *  4. 否則 → 登記 [tracked]、維持原圖，等佇列翻好（由 [onActivated] 排入）後換頁。
     */
    override suspend fun loadPage(page: ReaderPage) {
        if (isRecycled) return

        scope.launch {
            // 讓被包 loader 完成其前置（CBZ：把該頁從封存解出來、確保 stream 可讀；目錄頁＝no-op）。狀態不動。
            try {
                delegate.loadPage(page)
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "delegate.loadPage 失敗，維持顯示原圖" }
            }
            handleDownloadedPage(page)
        }
    }

    /**
     * 逐頁處理：解析章目錄 → manifest 命中換譯圖、否則登記等佇列。**不**在此排入翻譯（見 [onActivated]）。
     */
    private suspend fun handleDownloadedPage(page: ReaderPage) {
        ensureResolved()
        val dir = chapterDir
        val pageFile = pageFiles.getOrNull(page.index)
        if (dir == null || pageFile == null) {
            // 解析不到章目錄/該頁檔（CBZ 即時翻 / 外部改動）→ 原圖已在畫面、不插手（不換頁）。
            return
        }
        // manifest 命中：下載檔已是譯圖 → [swapToFile] 一次 Queue→Ready 讓 holder decode 譯檔（不進引擎、不長轉圈）。
        // 涵蓋：佇列已先翻好這頁、退出再進的 page-level resume、重繪後刷新。
        if (pageTranslator.isPageTranslated(dir, pageFile.name.orEmpty())) {
            swapToFile(page)
            return
        }
        // 尚未翻好 → 登記等佇列翻（維持原圖可讀）；觀察者在該頁落盤後換頁。本 loader 絕不在此進引擎翻。
        synchronized(tracked) { tracked[page.index] = page }
    }

    /**
     * 換上落盤後的譯圖：驅動一次 **[Page.State.Queue] →（`delay()` 後）[Page.State.Ready]** 真轉換，
     * 觸發 holder 重 decode `page.stream`（重開檔案）讀到譯圖。
     *
     * 為何要先 Queue：holder 進來時頁已是 Ready（顯示舊圖位元組），[Page.State.Ready] 是 `data object` 單例，
     * 直接再設 Ready 屬同值 → StateFlow 不重發 → holder 不重 decode、換不上。先 Queue 再（`delay()` 撐住真實時間
     * 讓 holder collect 到後）Ready 才是真轉換。這一閃只發生在「真的換了檔」的頁、且只在換檔當下，故不擋讀。回收後不動。
     */
    private suspend fun swapToFile(page: ReaderPage) {
        if (isRecycled) return
        page.status = Page.State.Queue
        // 撐住 Queue 一小段真實時間，確保 holder collector（可能在別 dispatcher）接到這次 Queue：
        // Ready→Queue→Ready 太快會被 StateFlow conflate（collector 只見 Ready＝原值、同值不重發→不重 decode→不刷新，
        // 正是「翻完沒重新顯示、要退出重進」的根因）。yield() 跨 dispatcher 不夠；delay 是真實等待、任何 collector 都來得及。
        delay(SWAP_QUEUE_HOLD_MS)
        if (!isRecycled) page.status = Page.State.Ready
    }

    /**
     * 重試（錯誤重試鈕 / 重繪後刷新）：本 loader 不翻，只做顯示層的對應動作。
     *  - 已翻（manifest 命中）→ 只重 decode（[swapToFile] 一次 Queue→Ready，同 [DownloadPageLoader.retryPage]）。
     *    重繪後刷新靠這條：[eu.kanade.tachiyomi.ui.reader.ReaderViewModel.reRenderPage] 重繪覆蓋檔後 → `retryPage` → 重 decode 顯示新圖。
     *  - 未翻 → 重新登記進 [tracked]；佇列翻到時觀察者換頁。**不**在此排入翻譯（排入只在 [onActivated]）。
     */
    override fun retryPage(page: ReaderPage) {
        if (isRecycled) return
        ensureResolved()
        val dir = chapterDir
        val pageFile = pageFiles.getOrNull(page.index)
        if (dir != null && pageFile != null && pageTranslator.isPageTranslated(dir, pageFile.name.orEmpty())) {
            // 已翻（含剛重繪覆蓋）→ 只需 [swapToFile] 一次 Queue→Ready 重 decode 那個檔（與 [DownloadPageLoader.retryPage] 等效）。
            scope.launch { swapToFile(page) }
            return
        }
        // 未翻 → 重新登記等佇列（佇列負責翻，本 loader 不翻；排入在 onActivated）。
        synchronized(tracked) { tracked[page.index] = page }
    }

    /**
     * 取消本 loader 的佇列觀察 + 回收被包 loader、最後標記 recycled。
     * **不**取消翻譯佇列（整章要繼續在背景翻完）；也**不**關共用引擎（佇列自管）。
     * 淨效果：即使讀者離開，已啟動的章仍會自動翻譯 + 持久化完成。
     */
    override fun recycle() {
        scope.cancel()
        delegate.recycle()
        super.recycle()
    }

    companion object {
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

        /** swap 換頁時撐住 Queue 的真實時間：給 holder collector 接到 Queue，避免 Queue→Ready 被 StateFlow conflate（換不上譯圖）。 */
        private const val SWAP_QUEUE_HOLD_MS = 150L
    }
}
