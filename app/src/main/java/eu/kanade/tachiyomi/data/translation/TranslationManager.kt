package eu.kanade.tachiyomi.data.translation

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.core.archive.ZipWriter
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * 翻譯佇列（與下載 worker 解耦）。背景一章一章翻、就地覆蓋（§11），UI 觀察 [queueState]/[isPaused]。
 *
 * 排入的兩條來源都走這裡：
 *  - **自動**：章下載完、進 cache 後由 `Downloader` 呼叫 [translate]（gate＝[isReady]）。
 *  - **手動**：漫畫頁的翻譯鈕（`MangaScreenModel`）。
 *
 * **跟隨磁碟實際格式**（CBZ 還是鬆散資料夾由 mihon `saveChaptersAsCBZ` 決定）：
 *  - 鬆散資料夾 → 原地翻（[PageTranslator.translateChapter]，無重壓、無掉檔風險）。
 *  - CBZ → 解壓→翻→重壓回 CBZ，**§11-安全順序**：新 zip 寫好前原檔完好，最後才 delete+rename。
 *
 * 失敗矩陣（§11）：單章失敗 → 標 ERROR 留佇列可重試、原檔不動；翻成功 → 離開佇列。
 *
 * ⚠️ 背景可靠性：目前跑在自有 in-process [scope]，app 被系統回收會中斷。
 *    TODO：搬到 WorkManager 前景服務（對照 `DownloadJob`）才能在背景穩定跑完。
 */
class TranslationManager(private val context: Context) {

    private val pageTranslator = PageTranslator(context)
    private val downloadProvider: DownloadProvider = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val translationCache: TranslationCache = Injekt.get()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainMutex = Mutex()

    /** 合作式中止旗標：true → 正在翻的章在下一頁邊界停下（暫停/取消/清空用）。在 [lock] 下寫、@Volatile 供逐頁迴圈無鎖讀。 */
    @Volatile
    private var stopActive = false

    /**
     * 內部可變佇列項；對外只發 [TranslationItem] 不可變快照。所有欄位存取都在 [lock] 下。
     *
     * [reRenderMethod]：null＝一般翻譯（偵測/OCR/翻譯/去字全跑）；非 null＝重繪
     * （復用素材、只換這個去字法字串重做去字+排版，不跑 OCR/翻譯，見 [PageTranslator.reRenderChapter]）。
     */
    private class Entry(
        val manga: Manga,
        val chapter: Chapter,
        var status: TranslationItem.Status = TranslationItem.Status.QUEUE,
        var done: Int = 0,
        var total: Int = 0,
        val reRenderMethod: String? = null,
    )

    private val lock = Any()
    private val entries = mutableListOf<Entry>()

    private val _queueState = MutableStateFlow<List<TranslationItem>>(emptyList())
    val queueState: StateFlow<List<TranslationItem>> = _queueState.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _translatedIds = MutableStateFlow<Set<Long>>(emptySet())

    /** 本 session 翻成功的章 id（給 UI 標「已翻」；跨重啟的持久標記另由 manifest 補）。 */
    val translatedIds: StateFlow<Set<Long>> = _translatedIds.asStateFlow()

    /** 翻譯開關開 + key 有設 + 模型 3 顆齊，才排得了（給下載 hook 判斷）。 */
    fun isReady(): Boolean = pageTranslator.isReady()

    private fun publish() {
        _queueState.value = synchronized(lock) {
            entries.map { TranslationItem(it.manga, it.chapter, it.status, it.done, it.total) }
        }
    }

    /** 排入翻譯（已下載章）。已在佇列裡的章 id 不重排。 */
    fun translate(manga: Manga, chapters: List<Chapter>) {
        if (chapters.isEmpty()) return
        synchronized(lock) {
            val present = entries.mapTo(HashSet()) { it.chapter.id }
            chapters.forEach { if (it.id !in present) entries.add(Entry(manga, it)) }
        }
        publish()
        _isPaused.value = false // 明確要求翻譯 → 解除暫停、直接開跑（對照下載：排入即啟動）
        ensureDrain()
        TranslationJob.start(context)
    }

    /**
     * 排入「重繪」（換去字法重做去字+排版，復用素材、不跑 OCR/翻譯）。[method]＝去字法原始字串
     * （boxfill / auto_whole / auto_tile）。走同一條翻譯佇列（顯示「翻譯中」帶進度、可暫停/取消）。
     *
     * 與 [translate] 不同：重繪是使用者明確動作 → **即使該章已有翻譯項也允許再排**（換個方法重來）；
     * 只擋「同章、同樣是重繪」的重複排隊（避免連點塞滿佇列）。
     */
    fun reRender(manga: Manga, chapters: List<Chapter>, method: String) {
        if (chapters.isEmpty()) return
        synchronized(lock) {
            // 已排隊的重繪章 id（含進行中）；一般翻譯項不算，重繪可與其並存
            val pending = entries.filter { it.reRenderMethod != null }.mapTo(HashSet()) { it.chapter.id }
            chapters.forEach { if (it.id !in pending) entries.add(Entry(manga, it, reRenderMethod = method)) }
        }
        publish()
        _isPaused.value = false // 明確要求重繪 → 解除暫停、直接開跑
        ensureDrain()
        TranslationJob.start(context)
    }

    /** 取消指定章（含正在翻的那章：中止後移除）。 */
    fun cancel(chapterIds: List<Long>) {
        val ids = chapterIds.toHashSet()
        synchronized(lock) {
            if (entries.any { it.status == TranslationItem.Status.TRANSLATING && it.chapter.id in ids }) {
                stopActive = true // 正在翻的章被取消 → 逐頁迴圈下一頁停下、移除
            }
            entries.removeAll { it.chapter.id in ids }
        }
        publish()
        ensureDrain()
    }

    /** 重試失敗的章（重新排隊）。 */
    fun retry(chapterIds: List<Long>) {
        val ids = chapterIds.toHashSet()
        synchronized(lock) {
            entries.forEach {
                if (it.chapter.id in ids && it.status == TranslationItem.Status.ERROR) {
                    it.status = TranslationItem.Status.QUEUE
                }
            }
        }
        publish()
        _isPaused.value = false // 重試 → 解除暫停、直接開跑
        ensureDrain()
        TranslationJob.start(context)
    }

    /** 清空佇列（含正在翻的那章：中止後移除）。 */
    fun clearQueue() {
        synchronized(lock) {
            if (entries.any { it.status == TranslationItem.Status.TRANSLATING }) stopActive = true
            entries.clear()
        }
        publish()
    }

    fun pause() {
        _isPaused.value = true
        synchronized(lock) { stopActive = true } // 中止正在翻的章（逐頁迴圈下一頁停），暫停才即時生效
    }

    fun resume() {
        _isPaused.value = false
        ensureDrain()
        TranslationJob.start(context)
    }

    /** 確保有一條 drain 在跑（drainMutex 保證單一消費者；重複呼叫會排隊後再掃一遍）。 */
    private fun ensureDrain() {
        scope.launch { drainLoop() }
    }

    private suspend fun drainLoop() = drainMutex.withLock {
        while (!_isPaused.value) {
            val entry = synchronized(lock) {
                entries.firstOrNull { it.status == TranslationItem.Status.QUEUE }
            } ?: break
            synchronized(lock) {
                entry.status = TranslationItem.Status.TRANSLATING
                entry.done = 0
                entry.total = 0
                stopActive = false // 開始新章前清旗標
            }
            publish()
            val translated = try {
                translateOne(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "翻譯章失敗 ${entry.chapter.name}（原檔保留）" }
                synchronized(lock) { entry.status = TranslationItem.Status.ERROR } // 失敗 → 留佇列可重試
                publish()
                continue
            }
            if (stopActive) {
                // 被暫停/取消/清空打斷：還在佇列(暫停)→回 QUEUE 等續傳；已被移除(取消/清空)→不動
                synchronized(lock) {
                    if (entries.contains(entry)) entry.status = TranslationItem.Status.QUEUE
                }
                publish()
                if (_isPaused.value) break else continue
            }
            if (translated) {
                synchronized(lock) { entries.remove(entry) } // 真的翻成 → 離開佇列
                _translatedIds.value = _translatedIds.value + entry.chapter.id
                translationCache.invalidate(entry.manga.id) // 已翻章數變 → 失效該本、刷新書庫徽章
            } else {
                // 沒下載 / 沒翻成（部分失敗）→ 標 ERROR 留佇列可重試，不誤標「已翻」
                synchronized(lock) { entry.status = TranslationItem.Status.ERROR }
            }
            publish()
        }
    }

    /**
     * 回傳「該章是否確實處理成」；沒下載/沒做成→false，drain 據此標 ERROR、不誤標已翻。
     *
     * 依 [Entry.reRenderMethod] 分兩條：
     *  - null＝翻譯：跑 [PageTranslator.translateChapter]，成功判準＝manifest 覆蓋全頁。
     *  - 非 null＝重繪：跑 [PageTranslator.reRenderChapter]（復用素材換去字法），成功判準＝有重繪到頁（count>0）。
     * 兩條共用同一組 [onProgress]/[shouldStop]（佇列進度 + 合作式中止對重繪一樣生效）。
     */
    private suspend fun translateOne(entry: Entry): Boolean {
        val dir = chapterDir(entry.manga, entry.chapter) ?: return false // 沒下載 → 不算做成
        val onProgress: (Int, Int) -> Unit = { done, total ->
            synchronized(lock) {
                entry.done = done
                entry.total = total
            }
            publish()
        }
        val shouldStop: () -> Boolean = { stopActive }
        val method = entry.reRenderMethod
        return if (method != null) {
            // 重繪：素材在 chapterDir/.yakuyomi/ 下，只換去字法重做去字+排版（不跑 OCR/翻譯）。
            // 一次性（不像翻譯有「剩頁可續傳」概念）：重繪到任一頁(count>0)＝既值得換檔、也算成功。
            if (dir.isDirectory) {
                pageTranslator.reRenderChapter(dir, method, onProgress, shouldStop) > 0 // 鬆散：原地重繪
            } else {
                processArchiveInPlace(dir, onProgress, shouldStop) { tmpU ->
                    val n = pageTranslator.reRenderChapter(tmpU, method, onProgress, shouldStop)
                    ProcessResult(swap = n > 0, success = n > 0) // CBZ：有重繪到才換檔、才算成功
                }
            }
        } else if (dir.isDirectory) {
            pageTranslator.translateChapter(dir, onProgress, shouldStop) // 鬆散：原地翻（合作式中止）
            pageTranslator.isChapterTranslated(dir)
        } else {
            // CBZ 翻譯：保留舊 translateArchiveInPlace 的雙條件——
            //   換檔＝翻有進度 or manifest 已覆蓋（持久化部分成果、避免續傳重做）；
            //   成功＝manifest 全覆蓋（部分成功仍回 false → drain 標 ERROR 留佇列、下次補剩頁）。
            processArchiveInPlace(dir, onProgress, shouldStop) { tmpU ->
                val n = pageTranslator.translateChapter(tmpU, onProgress, shouldStop)
                val done = pageTranslator.isChapterTranslated(tmpU)
                ProcessResult(swap = n > 0 || done, success = done)
            }
        }
    }

    /** [processArchiveInPlace] 的 callback 結果：[swap]＝是否值得重壓換檔（持久化成果）；[success]＝是否算「處理成功」（drain 據此標 ERROR/移除）。 */
    private data class ProcessResult(val swap: Boolean, val success: Boolean)

    /** 已下載章是否已翻（鬆散＝manifest 覆蓋；CBZ＝archive 內有 marker entry）。 */
    fun isTranslated(manga: Manga, chapter: Chapter): Boolean {
        val dir = chapterDir(manga, chapter) ?: return false
        return if (dir.isDirectory) pageTranslator.isChapterTranslated(dir) else archiveHasMarker(dir)
    }

    private fun chapterDir(manga: Manga, chapter: Chapter): UniFile? {
        val source = sourceManager.getOrStub(manga.source)
        return downloadProvider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source)
    }

    /**
     * CBZ：解壓暫存→[process]（翻譯或重繪）→重壓暫存 zip→驗證後才換掉原檔（§11：原檔到 rename 前都完好）。
     *
     * [process]＝對解壓後的暫存夾做實際處理、回傳 [ProcessResult]（swap＝是否重壓換檔、success＝是否算成功）。
     * 翻譯與重繪共用同一套解壓/重壓/§11-安全換檔邏輯，差別只在這個 callback。回傳 [ProcessResult.success]。
     */
    private suspend fun processArchiveInPlace(
        cbz: UniFile,
        onProgress: (Int, Int) -> Unit,
        shouldStop: () -> Boolean,
        process: suspend (UniFile) -> ProcessResult,
    ): Boolean {
        val parent = cbz.parentFile ?: return false
        val cbzName = cbz.name ?: return false
        val tmp = File(context.cacheDir, "yakutr_${System.nanoTime()}").apply { mkdirs() }
        try {
            // 1. 解壓檔案 entry 到暫存夾
            cbz.archiveReader(context).use { reader ->
                val names = reader.useEntries { seq -> seq.filter { it.isFile }.map { it.name }.toList() }
                names.forEach { entryName ->
                    reader.getInputStream(entryName)?.use { input ->
                        File(tmp, File(entryName).name).outputStream().use { input.copyTo(it) }
                    }
                }
            }
            val tmpU = UniFile.fromFile(tmp) ?: return false
            // 2. 處理（就地覆蓋暫存頁；翻譯另寫 manifest、重繪另更新素材 method）
            val result = process(tmpU)
            if (shouldStop()) return false // 被暫停/取消中止 → 丟棄暫存、原檔不動（不壓回半成品）
            if (!result.swap) return false // 全失敗（沒翻成/沒重繪到）→ 不動原檔、可重試
            // 3. 重壓到暫存 zip（原檔此時完好）
            val newZip = parent.createFile("$cbzName$TMP_SUFFIX") ?: return false
            ZipWriter(context, newZip).use { w -> tmpU.listFiles()?.forEach { w.write(it) } }
            // 4. 換檔
            cbz.delete()
            newZip.renameTo(cbzName)
            return result.success // 部分成功（swap 了但未全覆蓋）回 false → drain 標 ERROR 留佇列補剩頁
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "processArchiveInPlace 失敗（原檔保留）" }
            return false
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** CBZ 內是否有 marker entry（已翻指標；逐頁 coverage 細查留後續）。 */
    private fun archiveHasMarker(cbz: UniFile): Boolean = runCatching {
        cbz.archiveReader(context).use { reader ->
            reader.useEntries { seq -> seq.any { File(it.name).name == MARKER_NAME } }
        }
    }.getOrDefault(false)

    companion object {
        private const val MARKER_NAME = ".yakuyomi_translated"
        private const val TMP_SUFFIX = ".yakutmp"
    }
}
