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
 *  - 觀察每頁完成事件（[TranslationManager.donePageEvents]），收到本章某頁翻好就換上該頁譯圖。
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

    /**
     * 已在「變成當前頁」時強制補換過譯圖的頁 index（[refreshSelected] 用，去重避免每次滑回都重 decode）。
     * synchronized([selectedSwapped]) 下存取。
     */
    private val selectedSwapped = mutableSetOf<Int>()

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
            // 即時翻用即時去字法（預設 AI 去字），與下載/手動翻的去字法分開＝弱機可單獨改回 boxfill 求低延遲。
            translationManager.translate(
                manga,
                listOf(domainChapter),
                atFront = true,
                method = translationManager.liveInpaintMethod(),
            )
            startQueueObserver()
        }
    }

    /**
     * 啟動「每頁翻好就換譯圖」的觀察者（由 [onActivated] 啟動、只一次）。
     *
     * 用 [TranslationManager.donePageEvents]（SharedFlow：佇列每翻好一頁推一筆 (章 id, 頁名)，有緩衝、不 conflate）：
     * 收到本章某頁完成 → 找 [tracked] 裡該頁 → [swapToFile]（呼叫 [ReaderPage.reload] 叫 holder 重 decode 譯圖）。
     * 取代舊的「觀察 conflated 的 queueState + 每次讀 manifest 檔輪詢」——那會 conflate 丟中間值 + 讀檔慢，
     * 導致某頁翻好卻要等「後面頁的 emit」才被順便比中、更新延遲（即此 bug）。
     *
     * 事件當下不在 [tracked] 的頁（holder 還沒綁）＝跳過；之後該頁 holder 綁定時 [handleDownloadedPage] 的即時
     * manifest 檢查會補上（已翻→直接換）。故 push + 綁定即時檢查涵蓋所有情況。
     */
    private fun startQueueObserver() {
        scope.launch {
            translationManager.donePageEvents.collect { (chapterId, name) ->
                if (chapterId != chapter.chapter.id) return@collect
                if (chapterDir == null) return@collect
                val page = synchronized(tracked) {
                    val hit = tracked.entries.firstOrNull { (_, p) -> pageFiles.getOrNull(p.index)?.name == name }
                    if (hit != null) tracked.remove(hit.key)
                    hit?.value
                }
                page?.let { swapToFile(it) }
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
        // **先登記、再查 manifest**（順序很重要，修「有時某頁沒換成譯圖」的競態）：
        // 若先查(miss)再登記，佇列可能在「查完、還沒登記」的空檔把這頁翻好並 emit，觀察者那輪見 tracked 沒這頁就略過；
        // 之後若沒有新 emission（尤其正在看的頁 / 章末頁），這頁就一直停在原圖。登記在先 → 之後任何 emission 都看得到它。
        synchronized(tracked) { tracked[page.index] = page }
        // 登記後立即查一次：涵蓋「登記前就已翻好」（page-level resume / 重繪後 / 佇列搶先翻好）與「登記瞬間剛翻好」。
        // 命中就原子地從 tracked 取出再換（與觀察者互斥、同頁不重複換）。
        if (pageTranslator.isPageTranslated(dir, pageFile.name.orEmpty())) {
            val hit = synchronized(tracked) { tracked.remove(page.index) }
            if (hit != null) swapToFile(page)
        }
    }

    /**
     * 換上落盤後的譯圖：呼叫 [ReaderPage.reload]（單調遞增計數）→ 顯示本頁的 holder（pager/webtoon）的 reload 觀察者
     * 重 decode `page.stream`（重開檔案）讀到就地覆蓋的譯圖。
     *
     * 為何不用 status `Ready→Queue→Ready`：[Page.State.Ready] 是單例、回到 Ready 屬同值 → StateFlow 同值不重發 →
     * holder 不重 decode（尤其滑動中 Main 忙、collector 採樣不到中間那一下 Queue），正是「翻完某頁卡原文」的根因。
     * reload 計數每次都是新值、**永不**被 conflate 成 no-op → 可靠。只影響「真的換了檔」的頁、回收後不動。
     */
    private fun swapToFile(page: ReaderPage) {
        if (isRecycled) return
        page.reload()
    }

    /**
     * 重試（錯誤重試鈕 / 重繪後刷新）：本 loader 不翻，只做顯示層的對應動作。
     *  - 已翻（manifest 命中）→ 只重 decode（[swapToFile] 呼叫 [ReaderPage.reload] 叫 holder 重畫當前檔）。
     *    重繪後刷新靠這條：[eu.kanade.tachiyomi.ui.reader.ReaderViewModel.reRenderPage] 重繪覆蓋檔後 → `retryPage` → 重 decode 顯示新圖。
     *  - 未翻 → 重新登記進 [tracked]；佇列翻到時觀察者換頁。**不**在此排入翻譯（排入只在 [onActivated]）。
     */
    override fun retryPage(page: ReaderPage) {
        if (isRecycled) return
        ensureResolved()
        // 先登記再查（與 [handleDownloadedPage] 同：避免查→翻好→登記的競態漏換頁）。
        synchronized(tracked) { tracked[page.index] = page }
        val dir = chapterDir
        val pageFile = pageFiles.getOrNull(page.index)
        if (dir != null && pageFile != null && pageTranslator.isPageTranslated(dir, pageFile.name.orEmpty())) {
            // 已翻（含剛重繪覆蓋）→ 原子取出再 [swapToFile] 重 decode 那個檔（與 [DownloadPageLoader.retryPage] 等效）。
            val hit = synchronized(tracked) { tracked.remove(page.index) }
            if (hit != null) swapToFile(page)
        }
    }

    /**
     * 頁變成「當前頁」時呼叫（[eu.kanade.tachiyomi.ui.reader.ReaderViewModel.onPageSelected]）：
     * 若該頁已翻好（manifest 命中）就強制補一次 [swapToFile]（重 decode 換譯圖），至多一次（[selectedSwapped] 去重）。
     *
     * 為何需要：被預載的相鄰頁（典型＝第 2 頁）若在 **off-screen / 預載期間** 翻好，它的 reloadFlow 變更不一定被當下
     * 那個（off-screen）holder 重 decode（換頁的 reload 在「當前頁」最可靠——baseline 的第 1 頁正是靠這條）。於是它變成
     * 當前頁時仍卡原圖。這裡在「剛變當前頁」對它補一次 reload()：此刻 holder 在螢幕上、collector 可靠 → 換上譯圖。
     * 未翻好的頁不動（維持原圖；之後在當前頁翻好時的就地 reload 仍會換）。
     */
    fun refreshSelected(page: ReaderPage) {
        if (isRecycled) return
        if (synchronized(selectedSwapped) { page.index in selectedSwapped }) return
        ensureResolved()
        val dir = chapterDir ?: return
        val pageFile = pageFiles.getOrNull(page.index) ?: return
        if (pageTranslator.isPageTranslated(dir, pageFile.name.orEmpty())) {
            synchronized(tracked) { tracked.remove(page.index) }
            synchronized(selectedSwapped) { selectedSwapped.add(page.index) }
            swapToFile(page)
        }
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
    }
}
