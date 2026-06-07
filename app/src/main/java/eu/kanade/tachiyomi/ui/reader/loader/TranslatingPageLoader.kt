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
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 即時翻譯（reader 邊讀邊翻）loader——**包**在另一個 [PageLoader] 外面（本里程碑＝[DownloadPageLoader]，已下載章）。
 *
 * **設計（顯示層、不自己翻）**：開章時把**整章**排入受管理的翻譯佇列（[TranslationManager.translate]，與
 * 「下載時翻譯」「漫畫頁翻譯鈕」同一條），由佇列在背景用 [PageTranslator.translateChapter] 整章翻、就地覆蓋頁圖 +
 * 存重繪素材 + 記 manifest。本 loader **不再進引擎翻**任何頁，只負責：
 *  - 顯示原圖，直到某頁被佇列翻好（manifest 命中）才**換上**譯圖。
 *  - 觀察佇列狀態（[TranslationManager.queueState]），每次變動掃一次 manifest，把新翻好的頁批次換頁。
 *
 * 這把舊版「loader 自己逐頁進引擎翻、只翻讀到/預取的 2-3 頁」改成「整章持續翻、且在受管理佇列裡」：
 *  - 整章都會被翻（不只讀到的幾頁）。
 *  - 進佇列 ⇒ 可暫停/取消/重試、在「翻譯佇列」畫面可見。
 *  - 章節清單指示器觀察同一個 [TranslationManager.queueState] ⇒ 自動顯示「排隊中／翻譯中」（免額外接線）。
 *
 * **持久化 / resume**：全交給佇列的 [PageTranslator.translateChapter]——譯圖覆蓋下載檔 + 存素材（`.yakuyomi/`，
 * 即時翻時 [PageTranslator] 強制存）+ 記 manifest（`.yakuyomi_translated`）。退出再進章節 → manifest 命中、
 * 本 loader 直接換上已覆蓋的譯圖（不重翻）；page-level resume 只補沒翻的頁。
 *
 * **換頁機制（不擋讀：翻譯期間維持原圖、譯好才換）**（與 [DownloadPageLoader.retryPage] 同套）：
 * holder 同時 `loadPage(page)` 並 `statusFlow.collectLatest { Ready -> setImage() }`；已下載頁一開始就是 Ready →
 * 先 decode 一次原圖（可讀）。本 loader 在佇列把某頁翻好後，用 [swapToFile] 驅動一次 [Page.State.Queue] →
 * （`yield()` 後）[Page.State.Ready] 真轉換，觸發 holder 重 decode `page.stream`（重開已被覆蓋的下載檔）讀到譯圖。
 * 這一閃只發生在「真的換了檔」的頁、且只在翻好的當下，故不擋讀。
 *
 * §11：翻譯失敗/略過 → 下載檔維持原圖（[PageTranslator.translateChapter] 只覆蓋成功頁、只把成功頁記進 manifest），
 * 本 loader 對未命中 manifest 的頁**不換頁** → 原圖持續可讀，絕不顯示空白/更糟的頁。
 *
 * **佇列優先序（正在讀的章插隊）**：開章＝把這章插到佇列**最前**（[TranslationManager.translate] `atFront=true`），
 * 搶在其他排隊章之前翻 → 讀者不必盯著原圖等前面整批章翻完。
 * **仍有的限制**：若**另一章正在翻中**（TRANSLATING），那章不被中途搶占（會翻完當前章才輪到本章）；本章會排到所有 QUEUE 項之前。
 *
 * TODO(live): 線上（未下載）路徑——包 [HttpPageLoader]，把下載到的原圖落地後排入佇列翻（本里程碑只做下載路徑）。
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
     * null＝解析不到（理論上即時翻只在已下載章啟用、不該 null；保險起見 null 時退回純顯示原圖、不換頁）。
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

    /** 是否已把整章排入佇列 + 啟動佇列觀察者（只做一次）。在 [this] 鎖下寫、@Volatile 供 [loadPage] 無鎖快檢。 */
    @Volatile
    private var enqueued = false

    /**
     * 還在顯示原圖、等佇列翻好的頁（`page.index → ReaderPage`）。佇列觀察者每次掃 manifest，把命中的頁
     * [swapToFile] 換上譯圖後從這移除。存取都在 synchronized([tracked]) 下。
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
     * 第一次需要時：把**整章**排入翻譯佇列、並啟動佇列觀察者（只做一次）。
     *
     * 走 [TranslationManager.translate]（與下載 hook / 翻譯鈕同一條）：
     *  - 佇列在背景整章翻、覆蓋頁圖 + 記 manifest（loader 不自己翻）。
     *  - 章節清單指示器觀察同一 [TranslationManager.queueState] → 自動顯示「翻譯中」。
     *  - [translate] **不**綁「下載時翻譯」開關（gate 只在呼叫端；這裡的呼叫端＝[ChapterLoader.shouldTranslateLive]
     *    已確認引擎就緒），故即時翻使用者就算關掉「下載時翻譯」也能排入。
     *
     * toDomainChapter null（無 id）時排不了——理論上已下載章必有 id，保險起見只記 log、跳過排入（仍顯示原圖）。
     */
    private fun enqueueOnce() {
        if (enqueued) return
        synchronized(this) {
            if (enqueued) return
            enqueued = true
            val domainChapter = chapter.chapter.toDomainChapter()
            if (domainChapter == null) {
                logcat(LogPriority.WARN) { "即時翻：章無 id、無法排入佇列（維持顯示原圖）" }
                return
            }
            // 整章排入受管理佇列（背景翻、可暫停/取消/重試、清單顯示翻譯中）。
            // atFront＝true：正在讀的章插隊到佇列最前（搶在其他排隊章之前翻），讀者不用盯著原圖等前面章翻完
            // （已修上面「已知限制」段的 FIFO 等待問題；唯一仍會等的是「另一章正在翻中」，那章不被中途搶占）。
            translationManager.translate(manga, listOf(domainChapter), atFront = true)
            startQueueObserver()
        }
    }

    /**
     * 佇列觀察者（單一 coroutine、跟著 [scope]）：每次 [TranslationManager.queueState] 變動 →
     * 讀**一次** [PageTranslator.donePages]（manifest 快照）→ 把 [tracked] 裡「檔名已在 done-set」的頁 [swapToFile]
     * 換上譯圖、再從 tracked 移除。佇列每翻好一頁就刷一批 → 頁面隨翻譯進度逐步換成譯圖。
     */
    private fun startQueueObserver() {
        scope.launch {
            translationManager.queueState.collect {
                val dir = chapterDir ?: return@collect
                // 沒有頁在等（都換完了）就不必讀 manifest。
                val pending = synchronized(tracked) { tracked.isNotEmpty() }
                if (!pending) return@collect
                val done = pageTranslator.donePages(dir) // 一次讀檔、給這批所有 tracked 頁共用
                // 蒐集這輪命中的頁（在鎖內挑出 + 移除），鎖外再 swap（swapToFile 內有 yield、不宜持鎖）。
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
     * 顯示這一頁：本 loader **不翻**，只決定「直接換上譯圖」還是「先顯示原圖、登記等佇列翻好」。
     *
     * 流程（背景 IO，狀態預設不動 → 維持 holder 已在顯示的可讀原圖）：
     *  1. 先排入整章 + 啟動觀察者（[enqueueOnce]，只做一次）。
     *  2. 讓被包 loader 完成前置（CBZ：把該頁解出來確保 stream 可讀；目錄頁＝no-op）。
     *  3. 對不上章目錄/該頁檔（CBZ / 外部改動）→ 不插手、原圖留著（不換頁）。
     *  4. **manifest 命中**（該頁已翻、下載檔已是譯圖）→ [swapToFile] 一次 Queue→Ready 讓 holder 重 decode 譯檔
     *     （涵蓋退出再進的 resume、重繪後刷新、佇列已先翻好這頁）。
     *  5. 否則 → 登記進 [tracked]、**維持原圖**（不轉圈、可讀），等佇列翻到這頁時觀察者換頁。
     */
    override suspend fun loadPage(page: ReaderPage) {
        if (isRecycled) return

        // 不在開頭壓 Queue：已下載頁進來就是 Ready、holder 正顯示可讀原圖。翻譯整段維持原圖（不轉圈），
        // 只有在該頁被佇列翻好（manifest 命中）後才用 [swapToFile] 驅動 Queue→Ready 換上譯圖。
        scope.launch {
            // 開章即排入整章 + 啟動觀察者（只做一次）。
            enqueueOnce()

            // 讓被包 loader 完成其前置（CBZ：把該頁從封存解出來、確保 stream 可讀；目錄頁＝no-op）。狀態不動。
            try {
                delegate.loadPage(page)
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "delegate.loadPage 失敗，維持顯示原圖" }
            }

            ensureResolved()
            val dir = chapterDir
            val pageFile = pageFiles.getOrNull(page.index)
            if (dir == null || pageFile == null) {
                // 解析不到章目錄/該頁檔（CBZ 即時翻 / 外部改動）→ 原圖已在畫面、不插手（不換頁）。
                return@launch
            }

            // manifest 命中：下載檔已是譯圖 → [swapToFile] 一次 Queue→Ready 讓 holder decode 譯檔（不進引擎、不長轉圈）。
            // 涵蓋：佇列已先翻好這頁、退出再進的 page-level resume、重繪後刷新。
            if (pageTranslator.isPageTranslated(dir, pageFile.name.orEmpty())) {
                swapToFile(page)
                return@launch
            }

            // 尚未翻好 → 登記等佇列翻（維持原圖可讀）；觀察者在該頁落盤後換頁。本 loader 絕不在此進引擎翻。
            synchronized(tracked) { tracked[page.index] = page }
        }
    }

    /**
     * 換上落盤後的檔（譯圖）：驅動一次 **[Page.State.Queue] →（`yield()` 後）[Page.State.Ready]** 真轉換，
     * 觸發 holder 重 decode `page.stream`（重開檔案）讀到已覆蓋的譯圖。
     *
     * 為何要先 Queue：holder 進來時頁已是 Ready（顯示原圖檔位元組），[Page.State.Ready] 是 `data object` 單例，
     * 直接再設 Ready 屬同值 → StateFlow 不重發 → holder 不重 decode、換不上譯圖。先 Queue 再（`yield()` 讓 holder collect 到後）Ready
     * 才是真轉換。這一閃只發生在「真的換了檔」的頁、且只在翻好當下，故不擋讀。回收後不動。
     */
    private suspend fun swapToFile(page: ReaderPage) {
        if (isRecycled) return
        page.status = Page.State.Queue
        // 讓 holder 先 collect 到 Queue，下面設 Ready 才是真轉換（StateFlow conflate：太快連設可能吞掉 Queue）。
        yield()
        if (!isRecycled) page.status = Page.State.Ready
    }

    /**
     * 重試（錯誤重試鈕 / 重繪後刷新）：本 loader 不翻，只做顯示層的對應動作。
     *  - 已翻（manifest 命中）→ 只重 decode（[swapToFile] 一次 Queue→Ready，同 [DownloadPageLoader.retryPage]）。
     *    重繪後刷新靠這條：[eu.kanade.tachiyomi.ui.reader.ReaderViewModel.reRenderPage] 重繪覆蓋檔後 → `retryPage` → 重 decode 顯示新圖。
     *  - 未翻 → 重新登記進 [tracked] + 確保整章已排入佇列（[enqueueOnce]）；佇列翻到時觀察者換頁。
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
        // 未翻 → 重新登記等佇列、確保整章已排入（佇列負責翻，本 loader 不翻）。
        synchronized(tracked) { tracked[page.index] = page }
        enqueueOnce()
    }

    /**
     * 取消本 loader 的佇列觀察 + 回收被包 loader、最後標記 recycled。
     * **不**取消翻譯佇列——整章要繼續在背景翻完（這正是本次改動的重點）；也**不**關共用引擎（佇列自管）。
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
