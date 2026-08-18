package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.os.PowerManager
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.crash.TraceLog
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import eu.kanade.tachiyomi.util.system.powerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.ZipWriter
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * 翻譯佇列（與下載 worker 解耦）。背景一章一章翻、就地覆蓋（§11），UI 觀察 [queueState]/[isPaused]。
 *
 * 排入的兩條來源都走這裡：
 *  - **自動**：章下載完、進 cache 後由 `Downloader` 呼叫 [translate]（gate＝[isReady]）。
 *  - **手動**：漫畫頁的翻譯鈕（`MangaViewModel`）。
 *
 * **跟隨磁碟實際格式**（CBZ 還是鬆散資料夾由 mihon `saveChaptersAsCBZ` 決定）：
 *  - 鬆散資料夾 → 原地翻（[PageTranslator.translateChapter]，無重壓、無掉檔風險）。
 *  - CBZ → 解壓→翻→重壓回 CBZ，**§11-安全順序**：新 zip 寫好前原檔完好，最後才 delete+rename。
 *
 * 失敗矩陣（§11）：單章失敗 → 標 ERROR 留佇列可重試、原檔不動；翻成功 → 離開佇列。
 *
 * 背景可靠性：翻譯跑在自有 in-process [scope]，由 [TranslationJob] 前景服務保活（app 退背景時不被回收）。
 * 行程仍可能被系統 / 激進 OEM 殺掉而中斷——但佇列會持久化（[translationStore]），下次啟動或 worker 重啟時
 * [ensureRestored] 重建佇列自動續傳（已翻頁由 manifest 跳過、不重翻），對齊下載 `DownloadStore` 的續傳行為。
 */
class TranslationManager(private val context: Context) {

    private val pageTranslator = PageTranslator(context)
    private val downloadProvider: DownloadProvider = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val translationCache: TranslationCache = Injekt.get()
    private val translationPreferences: TranslationPreferences = Injekt.get()

    /** 佇列持久化：行程被殺 / 重開機後 [restore] 續傳（對照下載 [eu.kanade.tachiyomi.data.download.DownloadStore]）。 */
    private val translationStore = TranslationStore(context)

    // 「改去字法後升級重繪」掃全庫用：列收藏書 + 各書章節（只在 reRenderAllUpgradable 用）。
    private val getFavorites: GetFavorites = Injekt.get()
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get()

    /** 常駐（warm）翻譯引擎服務：佇列翻完且**即時翻關著**時釋放它，別讓 ~100MB 閒置（即時翻開著則保 warm）。 */
    private val engineService: TranslationEngineService = Injekt.get()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainMutex = Mutex()

    /** 合作式中止旗標：true → 正在翻的章在下一頁邊界停下（暫停/取消/清空用）。在 [lock] 下寫、@Volatile 供逐頁迴圈無鎖讀。 */
    @Volatile
    private var stopActive = false

    /** 佇列是否已從磁碟還原過（至多一次）。還原完成前不回寫，避免覆蓋掉尚未讀出的持久佇列。 */
    @Volatile
    private var restored = false
    private val restoreMutex = Mutex()

    /**
     * 內部可變佇列項；對外只發 [TranslationItem] 不可變快照。所有欄位存取都在 [lock] 下。
     *
     * [reRenderMethod]：null＝一般翻譯（偵測/OCR/翻譯/去字全跑）；非 null＝重繪
     * （復用素材、只換這個去字法字串重做去字+排版，不跑 OCR/翻譯，見 [PageTranslator.reRenderChapter]）。
     *
     * [method]：一般翻譯項的去字方法原始字串（boxfill / auto_whole / auto_tile），於 [translate] 排入當下
     * 從 [TranslationPreferences.inpaintMethod] 擷取（讓佇列裡每章各帶當下偏好、之後改全域偏好不影響已排隊的章）。
     * QUEUE 狀態可由 [setItemMethod] 改、傳給 [PageTranslator.translateChapter]。重繪項用 [reRenderMethod]、此欄不用。
     * 「生效去字法」＝`reRenderMethod ?: method`。
     */
    private class Entry(
        val manga: Manga,
        val chapter: Chapter,
        var status: TranslationItem.Status = TranslationItem.Status.QUEUE,
        var done: Int = 0,
        var total: Int = 0,
        val reRenderMethod: String? = null,
        var method: String = "",
    ) {
        /** 生效去字法：重繪項＝[reRenderMethod]，翻譯項＝[method]。 */
        val effectiveMethod: String get() = reRenderMethod ?: method
    }

    private val lock = Any()
    private val entries = mutableListOf<Entry>()

    /** Yakuyomi（佇列分組）：被「整本暫停」的漫畫 id 集合。drain 跳過這些本的章、做下一本；在 [lock] 下存取。 */
    private val pausedMangaIds = mutableSetOf<Long>()

    /**
     * 「待翻譯」標記集合（章 id）：線上即時翻 / reader 控制鈕觸發下載時先標記，
     * 由 [eu.kanade.tachiyomi.data.download.Downloader] 在該章下載完成（章目錄 + cache 都就緒）後查此集合、
     * 決定要不要把它排入翻譯（即使「下載時翻譯」總開關關著也能翻——讀到/手動觸發的章是使用者明確意圖）。
     *
     * 為何要這個：[Downloader] 下載完的預設 gate 是 [isReady]（含 translationEnabled 總開關），
     * 但即時翻 / 控制鈕要繞過該總開關只翻「被標記」的章 ⇒ 多一個 OR 條件。標記在 [lock] 下存取（與 [entries] 同鎖）。
     */
    private val pendingTranslate = mutableSetOf<Long>()

    /** 標記某章「下載完成後要翻」（線上即時翻 / reader 控制鈕用）。 */
    fun markForTranslate(chapterId: Long) {
        synchronized(lock) { pendingTranslate.add(chapterId) }
    }

    /** 該章是否被標記為「下載完成後要翻」（[Downloader] 下載完成 hook 查此判斷）。 */
    fun isPendingTranslate(chapterId: Long): Boolean = synchronized(lock) { chapterId in pendingTranslate }

    /** 清掉某章的「待翻譯」標記（已排入後由 [Downloader] 呼叫，避免殘留）。 */
    fun clearPending(chapterId: Long) {
        synchronized(lock) { pendingTranslate.remove(chapterId) }
    }

    private val _queueState = MutableStateFlow<List<TranslationItem>>(emptyList())
    val queueState: StateFlow<List<TranslationItem>> = _queueState.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    /** Yakuyomi（佇列分組）：被整本暫停的漫畫 id 快照，給佇列 UI 顯示每本的暫停/繼續狀態。 */
    private val _pausedMangas = MutableStateFlow<Set<Long>>(emptySet())
    val pausedMangas: StateFlow<Set<Long>> = _pausedMangas.asStateFlow()

    private val _translatedIds = MutableStateFlow<Set<Long>>(emptySet())

    /** 本 session 翻成功的章 id（給 UI 標「已翻」；跨重啟的持久標記另由 manifest 補）。 */
    val translatedIds: StateFlow<Set<Long>> = _translatedIds.asStateFlow()

    /**
     * 每翻好一頁就推一次（chapterId, pageName）。給即時翻 [eu.kanade.tachiyomi.ui.reader.loader.TranslatingPageLoader]
     * **直接重畫該頁**，取代「觀察 conflated 的 [queueState] + 每次讀 manifest 檔」那條——後者會 conflate 丟中間值 +
     * 檔案讀慢，導致某頁翻完當下沒被即時比中、要等之後某頁 emit 才順便補上（更新延遲）。SharedFlow 有緩衝、不丟事件。
     */
    private val _donePageEvents = MutableSharedFlow<Pair<Long, String>>(extraBufferCapacity = 128)
    val donePageEvents: SharedFlow<Pair<Long, String>> = _donePageEvents.asSharedFlow()

    /** 翻譯開關開 + key 有設 + 模型 3 顆齊，才排得了（給下載 hook 判斷）。 */
    fun isReady(): Boolean = pageTranslator.isReady()

    /**
     * 此書的來源是否在「不自動翻譯來源」排除集（per-source 開關）。命中＝自動翻（下載時 + 即時）一律跳過；
     * 手動翻不查此。供 [eu.kanade.tachiyomi.data.download.Downloader] 下載 hook 與 ChapterLoader 共用同一判定。
     */
    fun isSourceExcluded(manga: Manga): Boolean =
        manga.source.toString() in translationPreferences.translationSourcesExclude.get()

    /**
     * 翻譯總開關（[TranslationPreferences.translationMasterEnabled]）是否開啟。
     * 這是**硬總開關**：關閉時自動/手動翻一律不排入、佇列一律不 drain、引擎不建——把關責任集中在佇列這層
     * （入口各 gate 之外的最後一道），避免「持久佇列還原/手動排入」繞過總開關。
     */
    private fun masterEnabled(): Boolean = translationPreferences.translationMasterEnabled.get()

    /**
     * 是否還有「該被翻」的章（QUEUE 或正在 TRANSLATING、排除整本暫停者），且總開關開、未使用者暫停。
     * 給 [TranslationHeartbeatJob] 判斷要不要把前景服務拉回來（vivo 這類 OEM 硬殺前景服務後的自動恢復）。
     */
    fun hasPendingWork(): Boolean {
        if (_isPaused.value || !masterEnabled()) return false
        return synchronized(lock) {
            entries.any {
                (it.status == TranslationItem.Status.QUEUE || it.status == TranslationItem.Status.TRANSLATING) &&
                    it.manga.id !in pausedMangaIds
            }
        }
    }

    private fun publish() {
        _queueState.value = synchronized(lock) {
            entries.map {
                TranslationItem(it.manga, it.chapter, it.status, it.done, it.total, it.effectiveMethod)
            }
        }
    }

    /** 發佈「整本暫停」集合快照（與 [publish] 分開：暫停集合變動才發）。 */
    private fun publishPaused() {
        _pausedMangas.value = synchronized(lock) { pausedMangaIds.toSet() }
    }

    /**
     * 挑下一個要翻的章（在 [lock] 下呼叫）。以漫畫為單位：跳過「整本暫停」的漫畫，按**漫畫在佇列的順序**、
     * 再按**話號小→大**取（同一本接續翻；搶翻/拖曳改變漫畫順序即生效）。無可翻章回 null。
     */
    private fun pickNextQueuedLocked(): Entry? {
        val active = entries.filter {
            it.status == TranslationItem.Status.QUEUE && it.manga.id !in pausedMangaIds
        }
        if (active.isEmpty()) return null
        val order = HashMap<Long, Int>()
        entries.forEach { if (it.manga.id !in order) order[it.manga.id] = order.size }
        return active.minWithOrNull(
            compareBy({ order[it.manga.id] ?: Int.MAX_VALUE }, { it.chapter.chapterNumber }),
        )
    }

    /**
     * 把目前佇列＋暫停狀態寫回磁碟（[translationStore]）。只在**結構性變動**（排入 / 移除 / 狀態 / 方法 / 暫停）後呼叫，
     * **不**放進 [publish]（那會被逐頁進度更新打爆 I/O）。還原完成前（[restored]=false）跳過，避免覆蓋掉持久佇列。
     */
    private fun persist() {
        if (!restored) return
        val snapshot = synchronized(lock) {
            entries.map {
                TranslationStore.Saved(
                    mangaId = it.manga.id,
                    chapterId = it.chapter.id,
                    method = it.method,
                    reRenderMethod = it.reRenderMethod,
                    errored = it.status == TranslationItem.Status.ERROR,
                    mangaPaused = it.manga.id in pausedMangaIds,
                )
            }
        }
        translationStore.save(snapshot, _isPaused.value)
    }

    /** Fire-and-forget 還原（給 [eu.kanade.tachiyomi.App] 啟動時呼叫；非 suspend、不卡啟動）。 */
    fun restoreAsync() {
        scope.launch { ensureRestored() }
    }

    /**
     * 從磁碟還原佇列（至多一次、idempotent）。兩條觸發：app 啟動（[restoreAsync]）與 [TranslationJob.doWork]（await）——
     * 後者讓「行程被殺後 WorkManager 重啟 worker」也能在檢查佇列前先把持久佇列讀回來。
     *
     * 還原後若有排隊章且未暫停 → [ensureDrain] 開跑 ＋ [TranslationJob.start] 重啟前景服務（自動續傳）。
     * 被打斷的 TRANSLATING 章存成 QUEUE、一律重跑，已翻頁由 manifest 跳過。
     */
    suspend fun ensureRestored() {
        if (!restored) {
            restoreMutex.withLock {
                if (!restored) {
                    val recovered = translationStore.restore()
                    if (recovered.isNotEmpty()) {
                        synchronized(lock) {
                            val present = entries.mapTo(HashSet()) { it.chapter.id }
                            recovered.forEach { r ->
                                if (r.chapter.id !in present) {
                                    entries.add(
                                        Entry(
                                            r.manga,
                                            r.chapter,
                                            status = if (r.errored) {
                                                TranslationItem.Status.ERROR
                                            } else {
                                                TranslationItem.Status.QUEUE
                                            },
                                            reRenderMethod = r.reRenderMethod,
                                            method = r.method,
                                        ),
                                    )
                                }
                            }
                            // 還原「整本暫停」：收集 mangaPaused 的 mangaId（向後相容：舊資料無此欄→不暫停）。
                            recovered.filter { it.mangaPaused }.forEach { pausedMangaIds.add(it.manga.id) }
                        }
                        _isPaused.value = translationStore.restorePaused()
                        publish()
                        publishPaused()
                    }
                    restored = true
                    persist() // 回寫合併後佇列（含還原前 race 進來的新項）
                }
            }
        }
        val hasQueued = synchronized(lock) {
            entries.any { it.status == TranslationItem.Status.QUEUE }
        }
        // 硬總開關：master 關時只還原佇列快照、**不**自動 drain / 不重啟前景服務 / 不建引擎（總開關再開時由 [resumeForMasterOn] 續跑）。
        if (hasQueued && !_isPaused.value && masterEnabled()) {
            ensureDrain()
            TranslationJob.start(context)
        }
    }

    /**
     * 即時翻（邊讀邊翻/reader 情境）用的去字方法＝使用者可自訂的 [TranslationPreferences.liveInpaintMethod]，
     * 預設 auto_whole（AI 去字，AOT-GAN 已夠快）。與下載/手動翻的 [TranslationPreferences.inpaintMethod] 分開＝弱機可單獨調快。
     */
    fun liveInpaintMethod(): String = translationPreferences.liveInpaintMethod.get()

    /**
     * 排入翻譯（已下載章）。已在佇列裡的章 id 不重排。
     *
     * 每個新項擷取**當下**的去字方法（[TranslationPreferences.inpaintMethod]）存進 [Entry.method]——
     * 之後改全域偏好不影響已排隊的章；QUEUE 項可再經 [setItemMethod] 改。
     *
     * @param atFront true＝插隊到佇列最前（正在讀的章即時翻時用，見 [eu.kanade.tachiyomi.ui.reader.loader.TranslatingPageLoader]）：
     *   新章排到既有排隊項之前；若該章已在佇列（且尚未開始翻）則**移到最前**而非加重複項。
     *   **注意**：只重排 QUEUE 項——正在翻（TRANSLATING）的那章不會被中途搶占（會翻完當前章才換下一章），此為已知限制。
     */
    fun translate(manga: Manga, chapters: List<Chapter>, atFront: Boolean = false, method: String? = null) {
        if (chapters.isEmpty()) return
        if (!masterEnabled()) return // 硬總開關：關閉時自動/手動翻一律不排入
        // method 非 null＝呼叫端指定去字法（即時翻/reader 情境傳 [liveInpaintMethod]，使用者可自訂、預設 AI 去字）；
        // null＝用設定的去字法（下載時翻、詳情頁手動翻——可慢可高品質）。
        val m = method ?: translationPreferences.inpaintMethod.get()
        synchronized(lock) {
            if (atFront) {
                // 插隊：依輸入順序，把每章放到佇列最前（已排隊則搬到最前、不加重複）。
                // 逐章插到 index 0 會使整批反序，故先收集成 batch（保持輸入順序）再一次性插到最前。
                val batch = mutableListOf<Entry>()
                chapters.forEach { chapter ->
                    val existing = entries.firstOrNull { it.chapter.id == chapter.id }
                    when {
                        // 已在翻的章不動（不中途搶占）；它的 method 已鎖、留原處翻完。
                        existing?.status == TranslationItem.Status.TRANSLATING -> Unit
                        // 已排隊（QUEUE/ERROR）→ 從原位移除、改放到 batch 最前（搬到佇列前段）。
                        existing != null -> {
                            entries.remove(existing)
                            existing.method = m // 插隊＝使用者剛要讀，刷新成當下去字法
                            existing.status = TranslationItem.Status.QUEUE // ERROR 重排也算重試
                            batch.add(existing)
                        }
                        // 全新章 → 建項加進 batch。
                        else -> batch.add(Entry(manga, chapter, method = m))
                    }
                }
                entries.addAll(0, batch)
            } else {
                val present = entries.mapTo(HashSet()) { it.chapter.id }
                chapters.forEach { if (it.id !in present) entries.add(Entry(manga, it, method = m)) }
            }
        }
        publish()
        _isPaused.value = false // 明確要求翻譯 → 解除暫停、直接開跑（對照下載：排入即啟動）
        ensureDrain()
        TranslationJob.start(context)
        persist()
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
        if (!masterEnabled()) return // 硬總開關：關閉時不排入重繪
        synchronized(lock) {
            // 已排隊的重繪章 id（含進行中）；一般翻譯項不算，重繪可與其並存
            val pending = entries.filter { it.reRenderMethod != null }.mapTo(HashSet()) { it.chapter.id }
            chapters.forEach { if (it.id !in pending) entries.add(Entry(manga, it, reRenderMethod = method)) }
        }
        publish()
        _isPaused.value = false // 明確要求重繪 → 解除暫停、直接開跑
        ensureDrain()
        TranslationJob.start(context)
        persist()
    }

    /**
     * 「改去字方法後」升級重繪：掃全庫已翻章，用**目前設定**把「去字法不會降級」的章排入重繪。回傳排入章數。
     *
     * 規則（對齊使用者需求「只去字法向上才重去字、向下不動、保留最好結果」）：
     *   只重繪 `已存去字法 rank ≤ 目前 rank` 的章——升級或持平（套新排版）才重、降級則保留既有較佳結果。
     * 範圍限制：
     *   - 只鬆散下載章（CBZ 素材在壓縮檔內、不便宜讀 → 跳過）。
     *   - 只「有保留素材」的章（[PageTranslator.storedInpaintMethod] 回 null＝無素材＝不能便宜重繪 → 跳過；需先開「保留重繪素材」）。
     *   - 先用 [TranslationCache] 預篩有已翻章的書，避免掃整庫每章。
     * IO 重（findChapterDir + 讀素材 json）→ 整段在 IO 跑。排入後走同一條翻譯佇列（顯示進度、可暫停/取消）。
     */
    suspend fun reRenderAllUpgradable(): Int = withContext(Dispatchers.IO) {
        val newMethod = translationPreferences.inpaintMethod.get()
        val newRank = TranslationEngineConfig.inpaintMethodRank(newMethod)
        var count = 0
        for (manga in getFavorites.await()) {
            if (translationCache.getTranslatedCount(manga) <= 0) continue // 這本沒已翻章 → 跳過
            val eligible = getChaptersByMangaId.await(manga.id).filter { ch ->
                val dir = chapterDir(manga, ch) ?: return@filter false // 沒下載
                if (!dir.isDirectory) return@filter false // CBZ：素材在壓縮檔內、不便宜讀 → 跳過
                val stored = pageTranslator.storedInpaintMethod(dir) ?: return@filter false // 無素材 → 不可便宜重繪
                TranslationEngineConfig.inpaintMethodRank(stored) <= newRank // 向上/持平才重、向下保留
            }
            if (eligible.isNotEmpty()) {
                reRender(manga, eligible, newMethod)
                count += eligible.size
            }
        }
        count
    }

    /**
     * 「改排版設定後」重繪：掃全庫已翻章，**各章用它自己原本的去字法**重繪（不升級/降級去字，只套用目前排版設定）。
     * 與 [reRenderAllUpgradable] 的差別＝method 用每章 [PageTranslator.storedInpaintMethod]、非全域去字法
     * （改排版不該順便動到去字）。同樣只鬆散 + 有素材章；按去字法分組批次排入（reRender 一次吃一個 method）。
     * 回傳排入章數。IO 重（findChapterDir + 讀素材 json）。
     */
    suspend fun reRenderAllWithStoredMethod(): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (manga in getFavorites.await()) {
            if (translationCache.getTranslatedCount(manga) <= 0) continue
            val byMethod = mutableMapOf<String, MutableList<Chapter>>()
            for (ch in getChaptersByMangaId.await(manga.id)) {
                val dir = chapterDir(manga, ch) ?: continue // 沒下載
                if (!dir.isDirectory) continue // CBZ：素材在壓縮檔內、不便宜讀 → 跳過
                val stored = pageTranslator.storedInpaintMethod(dir) ?: continue // 無素材 → 不可便宜重繪
                byMethod.getOrPut(stored) { mutableListOf() }.add(ch)
            }
            byMethod.forEach { (method, chs) ->
                reRender(manga, chs, method) // 各章用原去字法重繪：去字結果不變、套用目前排版
                count += chs.size
            }
        }
        count
    }

    /**
     * 改某章在佇列裡的去字方法（[method]＝boxfill / auto_whole / auto_tile）。
     * **QUEUE 或 TRANSLATING 皆可改**（ERROR / 已離開佇列的不可改）。翻譯項改 [Entry.method]；
     * 重繪項的方法在 [Entry.reRenderMethod]（val、不可改）→ 直接略過。
     *
     * 正在翻（TRANSLATING）改方法 → 設 [stopActive]（**不**設 _isPaused）：當前章停在下一頁邊界、回 QUEUE，
     * drain 立刻重挑、`translateChapter` 以新 [Entry.method] 從 manifest **續傳**——已翻頁（manifest 已記）跳過、
     * 保留舊去字結果；**剩餘頁用新去字**。例：4/14 改 → 1-4 維持舊法、5-14 用新法。（代價：續傳會重載引擎 ~100MB。）
     */
    fun setItemMethod(chapterId: Long, method: String) {
        synchronized(lock) {
            val entry = entries.firstOrNull {
                it.chapter.id == chapterId &&
                    (it.status == TranslationItem.Status.QUEUE || it.status == TranslationItem.Status.TRANSLATING)
            } ?: return
            if (entry.reRenderMethod != null) return // 重繪項方法不可改（reRenderMethod 為 val）
            entry.method = method
            // 正在翻 → 停在頁邊界回 QUEUE、立刻被重挑，以新方法續傳剩餘頁（見上）。
            if (entry.status == TranslationItem.Status.TRANSLATING) {
                stopActive = true
            }
        }
        publish()
        persist()
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
        persist()
    }

    /**
     * 佇列拖曳重排（#1）：把章 [fromChapterId] 移到目標索引 [toIndex]。drain 取「第一個 QUEUE」，
     * 故重排後優先順序立即生效。正在翻的那章不中止（只改它在清單的位置）。順序回寫 [persist]（跨重啟保留）。
     */
    fun reorderQueue(fromChapterId: Long, toIndex: Int) {
        synchronized(lock) {
            val fromIndex = entries.indexOfFirst { it.chapter.id == fromChapterId }
            if (fromIndex < 0) return
            val item = entries.removeAt(fromIndex)
            entries.add(toIndex.coerceIn(0, entries.size), item)
        }
        publish()
        persist()
    }

    // ── Yakuyomi 佇列分組：以「整本漫畫」為單位的操作（佇列頁用；queueState 仍是扁平章快照、UI 自行 group）。 ──

    /** 搶翻：把該漫畫所有章移到佇列最前、解除其暫停；若正在翻別本則停在頁邊界回 QUEUE，drain 重挑此本。 */
    fun startMangaNow(mangaId: Long) {
        if (!masterEnabled()) return
        synchronized(lock) {
            pausedMangaIds.remove(mangaId)
            val (mine, rest) = entries.partition { it.manga.id == mangaId }
            if (mine.isEmpty()) return
            entries.clear()
            entries.addAll(mine)
            entries.addAll(rest)
            // 正在翻的是別本 → 停它回 QUEUE（頁邊界），drain 重挑最前的搶翻本。
            if (entries.any { it.status == TranslationItem.Status.TRANSLATING && it.manga.id != mangaId }) {
                stopActive = true
            }
        }
        _isPaused.value = false
        publishPaused()
        publish()
        ensureDrain()
        TranslationJob.start(context)
        persist()
    }

    /** 整本暫停：drain 輪到時跳過、做下一本；若正在翻此本則停在頁邊界回 QUEUE。 */
    fun pauseManga(mangaId: Long) {
        synchronized(lock) {
            pausedMangaIds.add(mangaId)
            if (entries.any { it.status == TranslationItem.Status.TRANSLATING && it.manga.id == mangaId }) {
                stopActive = true
            }
        }
        publishPaused()
        publish()
        ensureDrain() // 重挑下一本（當前若被停會回 QUEUE）
        persist()
    }

    /** 解除整本暫停 → 重新納入 drain。 */
    fun resumeManga(mangaId: Long) {
        if (!masterEnabled()) return
        synchronized(lock) { pausedMangaIds.remove(mangaId) }
        _isPaused.value = false
        publishPaused()
        ensureDrain()
        TranslationJob.start(context)
        persist()
    }

    /** 整本改去字方法（翻譯項；QUEUE 立即生效、TRANSLATING 停頁邊界以新法續傳）。重繪項不動。 */
    fun setMangaMethod(mangaId: Long, method: String) {
        synchronized(lock) {
            entries.filter {
                it.manga.id == mangaId && it.reRenderMethod == null &&
                    (it.status == TranslationItem.Status.QUEUE || it.status == TranslationItem.Status.TRANSLATING)
            }.forEach {
                it.method = method
                if (it.status == TranslationItem.Status.TRANSLATING) stopActive = true
            }
        }
        publish()
        persist()
    }

    /** 整本取消：移除該漫畫所有章（含正在翻的：停頁邊界後移除）、清其暫停旗標。 */
    fun cancelManga(mangaId: Long) {
        synchronized(lock) {
            if (entries.any { it.status == TranslationItem.Status.TRANSLATING && it.manga.id == mangaId }) {
                stopActive = true
            }
            entries.removeAll { it.manga.id == mangaId }
            pausedMangaIds.remove(mangaId)
        }
        publishPaused()
        publish()
        ensureDrain()
        persist()
    }

    /** 整本重試：該漫畫所有 ERROR 章回 QUEUE。 */
    fun retryManga(mangaId: Long) {
        if (!masterEnabled()) return
        synchronized(lock) {
            entries.filter { it.manga.id == mangaId && it.status == TranslationItem.Status.ERROR }
                .forEach { it.status = TranslationItem.Status.QUEUE }
        }
        publish()
        _isPaused.value = false
        ensureDrain()
        TranslationJob.start(context)
        persist()
    }

    /** 以漫畫為單位拖曳重排：依 UI 給的新漫畫順序重排 entries（每本章保持內部順序）。 */
    fun reorderMangas(orderedMangaIds: List<Long>) {
        synchronized(lock) {
            val byManga = entries.groupBy { it.manga.id }
            val seen = LinkedHashSet<Long>()
            val reordered = mutableListOf<Entry>()
            orderedMangaIds.forEach { id ->
                byManga[id]?.let {
                    reordered.addAll(it)
                    seen.add(id)
                }
            }
            entries.forEach { if (it.manga.id !in seen) reordered.add(it) } // 沒被列到的（保險）接後面
            entries.clear()
            entries.addAll(reordered)
        }
        publish()
        persist()
    }

    /** 重試失敗的章（重新排隊）。 */
    fun retry(chapterIds: List<Long>) {
        if (!masterEnabled()) return // 硬總開關：關閉時不重試
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
        persist()
    }

    /** 清空佇列（含正在翻的那章：中止後移除）。 */
    fun clearQueue() {
        synchronized(lock) {
            if (entries.any { it.status == TranslationItem.Status.TRANSLATING }) stopActive = true
            entries.clear()
        }
        publish()
        persist()
    }

    fun pause() {
        _isPaused.value = true
        synchronized(lock) { stopActive = true } // 中止正在翻的章（逐頁迴圈下一頁停），暫停才即時生效
        persist()
    }

    fun resume() {
        if (!masterEnabled()) return // 硬總開關：關閉時不能 resume（佇列維持暫停待總開關再開）
        _isPaused.value = false
        ensureDrain()
        TranslationJob.start(context)
        persist()
    }

    /**
     * 翻譯總開關關閉（硬總開關）：中止正在翻的章（停在下一頁邊界、回 QUEUE）；drain 因 [masterEnabled] gate 退出、
     * 引擎於 drain 尾自然釋放。**不**動 [_isPaused]（總開關與使用者暫停獨立）；佇列保留待總開關再開時由 [resumeForMasterOn] 續跑。
     */
    fun haltForMasterOff() {
        synchronized(lock) { stopActive = true }
        TranslationJob.stop(context) // 主動停前景服務（否則殘留「翻譯中」通知；drain 自身已被 master gate 擋住不取新章）
    }

    /** 翻譯總開關開啟：若有排隊章且未（使用者）暫停 → 續跑佇列（drain 的 master gate 此時已放行）。 */
    fun resumeForMasterOn() {
        val hasQueued = synchronized(lock) { entries.any { it.status == TranslationItem.Status.QUEUE } }
        if (hasQueued && !_isPaused.value) {
            ensureDrain()
            TranslationJob.start(context)
        }
    }

    /**
     * 翻譯總開關切換的副作用，供「翻譯設定頁」與「More 頁快捷開關」**共用**（兩處綁同一 pref、自動連動）：
     * 開→續跑佇列 + 即時翻開且引擎就緒則預暖；關→中止當前章 + 釋放 warm 引擎（~100MB）。
     * **不**負責 set pref（呼叫端各自由 widget / asState 寫入）。
     */
    fun onMasterEnabledChanged(enabled: Boolean) {
        if (enabled) {
            resumeForMasterOn()
            if (translationPreferences.liveTranslate.get() && engineService.isReady()) {
                engineService.warmUpAsync()
            }
        } else {
            haltForMasterOff()
            engineService.shutdownAsync()
        }
    }

    /** 確保有一條 drain 在跑（drainMutex 保證單一消費者；重複呼叫會排隊後再掃一遍）。 */
    private fun ensureDrain() {
        scope.launch { drainLoop() }
    }

    private suspend fun drainLoop() = drainMutex.withLock {
        // 翻譯是 CPU 密集（OCR/去字 ONNX 推論）：螢幕關閉時若不持 WakeLock，CPU 會休眠 → 推論暫停（前景服務只保
        // 「行程不被回收」、不保「CPU 不睡」）。整個 drain 期間持 partial WakeLock、結束（佇列空/暫停/總開關關）即放，
        // 修「前景但關螢幕就停」。acquire 逾時只是洩漏保險、正常由 finally 放。
        val wakeLock = context.powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
        try {
            // 硬總開關：master 關時一律不取新章（一道 gate 收掉所有 drain 來源——還原/下載/手動/重繪）。
            while (!_isPaused.value && masterEnabled()) {
                val entry = synchronized(lock) { pickNextQueuedLocked() } ?: break
                synchronized(lock) {
                    entry.status = TranslationItem.Status.TRANSLATING
                    entry.done = 0
                    entry.total = 0
                    stopActive = false // 開始新章前清旗標
                }
                publish()
                TraceLog.log("queue", "chapter start m=${entry.manga.id} c=${entry.chapter.id} ${entry.chapter.name}")
                val translated = try {
                    translateOne(entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "翻譯章失敗 ${entry.chapter.name}（原檔保留）" }
                    TraceLog.log("queue", "chapter fail m=${entry.manga.id} c=${entry.chapter.id} ${e.message}")
                    synchronized(lock) { entry.status = TranslationItem.Status.ERROR } // 失敗 → 留佇列可重試
                    publish()
                    persist()
                    continue
                }
                if (stopActive) {
                    // 被暫停/取消/清空打斷：還在佇列(暫停)→回 QUEUE 等續傳；已被移除(取消/清空)→不動
                    synchronized(lock) {
                        if (entries.contains(entry)) entry.status = TranslationItem.Status.QUEUE
                    }
                    publish()
                    persist()
                    if (_isPaused.value) break else continue
                }
                if (translated) {
                    synchronized(lock) { entries.remove(entry) } // 真的翻成 → 離開佇列
                    _translatedIds.value = _translatedIds.value + entry.chapter.id
                    translationCache.invalidate(entry.manga.id) // 已翻章數變 → 失效該本、刷新書庫徽章
                    TraceLog.log("queue", "chapter done m=${entry.manga.id} c=${entry.chapter.id}")
                } else {
                    // 沒下載 / 沒翻成（部分失敗）→ 標 ERROR 留佇列可重試，不誤標「已翻」
                    synchronized(lock) { entry.status = TranslationItem.Status.ERROR }
                }
                publish()
                persist()
            }
            // 迴圈退出（因「無 QUEUE 項」自然翻完，或「總開關關」使 while 條件 false 退出；暫停則走另一分支不到這）：
            // 即時翻**關著**時釋放 warm 引擎，別讓 ~100MB 閒置；即時翻**開著**時保 warm（reader 隨時要讀下一章）。
            // 引擎之後會在下次 translatePage lazy 重建。shutdown 走服務自己的 Mutex（與 drainMutex 不同鎖、不死鎖）。
            // 釋放 warm 引擎：只有「總開關開 + 即時翻開」才保 warm（reader 要讀下一章）；總開關關或即時翻關 → 釋放 ~100MB。
            if (!_isPaused.value && !(masterEnabled() && translationPreferences.liveTranslate.get())) {
                engineService.shutdown()
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            // drain 收工（佇列空/暫停/總開關關）→ 停背景心跳（沒事做就別每 15 分醒來）。
            if (!hasPendingWork()) TranslationHeartbeatJob.stop(context)
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
            // entry.method＝排入當下擷取（可在排隊時經 setItemMethod 改）；傳給引擎用、不再讀全域 pref。
            pageTranslator.translateChapter(dir, entry.method, onProgress, shouldStop) { name ->
                _donePageEvents.tryEmit(entry.chapter.id to name) // 每翻好一頁就推給即時翻 loader 即時重畫該頁
            }
            pageTranslator.isChapterTranslated(dir)
        } else {
            // CBZ 翻譯：保留舊 translateArchiveInPlace 的雙條件——
            //   換檔＝翻有進度 or manifest 已覆蓋（持久化部分成果、避免續傳重做）；
            //   成功＝manifest 全覆蓋（部分成功仍回 false → drain 標 ERROR 留佇列、下次補剩頁）。
            processArchiveInPlace(dir, onProgress, shouldStop) { tmpU ->
                val n = pageTranslator.translateChapter(tmpU, entry.method, onProgress, shouldStop)
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
        // local 來源：章節不在下載夾，改用 LocalSource 的解析器定位 local/<manga>/<章> 的實際檔案/資料夾。
        //  - Directory＝鬆散夾 → 原地覆蓋頁圖（同已下載鬆散章）。
        //  - Archive＝cbz/cbr/7z/tar… → processArchiveInPlace 解壓→翻→重壓回**同名**（非 zip 者內部轉成 zip-content、
        //    副檔名不變、libarchive 仍可讀）；換言之 rar/7z 透明地就地翻、檔名不變。
        //  - Epub＝結構化（OPF/xhtml）→ 攤平重壓會破壞它 → v1 不支援、回 null。
        if (source is LocalSource) {
            return runCatching { source.getFormat(chapter.toSChapter()) }.getOrNull()?.let { fmt ->
                when (fmt) {
                    is Format.Directory -> fmt.file
                    is Format.Archive -> fmt.file
                    is Format.Epub -> null
                }
            }
        }
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
            // 3. 重壓到暫存 zip（原檔此時完好）。先清掉上次打包失敗殘留的孤兒暫存。
            parent.findFile("$cbzName$TMP_SUFFIX")?.delete()
            val newZip = parent.createFile("$cbzName$TMP_SUFFIX") ?: return false
            // 只壓「檔案」、跳過目錄：ZipWriter.write() 把目錄當檔案讀會丟 EISDIR（素材子夾 .yakuyomi 觸發、
            // = local 壓縮檔翻完打包失敗的真因）。壓縮檔不支援素材重繪 → 素材子夾不必進壓縮檔；
            // marker(.yakuyomi_translated) 與譯圖都是檔案、照壓。
            ZipWriter(context, newZip).use { w -> tmpU.listFiles()?.forEach { if (it.isFile) w.write(it) } }
            // 4. 換檔。優先 §11-safe 的 delete+rename（一般裝置儲存可行、原子）；某些 SAF provider delete/rename
            //    不可靠 → 退回「就地把暫存 zip 位元組覆寫回原檔」(overwriteFrom、"wt" 截斷、不靠 rename)。
            if (cbz.delete() && newZip.renameTo(cbzName)) {
                return result.success
            }
            val target = parent.findFile(cbzName) ?: parent.createFile(cbzName) ?: return false
            val overwritten = overwriteFrom(target, newZip)
            if (overwritten) newZip.delete()
            // 覆寫失敗 → 保留 .yakutmp（翻譯結果不丟、可重試）、回 false → drain 標 ERROR 留佇列可重試。
            return overwritten && result.success
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "processArchiveInPlace 失敗（原檔保留）" }
            return false
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** 把 [src] 的位元組就地覆寫進 [dst]（ContentResolver "wt" 截斷；繞過不可靠的 SAF delete/rename）。best-effort。 */
    private fun overwriteFrom(dst: UniFile, src: UniFile): Boolean = runCatching {
        val input = context.contentResolver.openInputStream(src.uri) ?: return false
        input.use { i ->
            val out = context.contentResolver.openOutputStream(dst.uri, "wt") ?: return false
            out.use { i.copyTo(it) }
        }
        true
    }.getOrDefault(false)

    /** CBZ 內是否有 marker entry（已翻指標；逐頁 coverage 細查留後續）。 */
    private fun archiveHasMarker(cbz: UniFile): Boolean = runCatching {
        cbz.archiveReader(context).use { reader ->
            reader.useEntries { seq -> seq.any { File(it.name).name == MARKER_NAME } }
        }
    }.getOrDefault(false)

    companion object {
        private const val MARKER_NAME = ".yakuyomi_translated"
        private const val TMP_SUFFIX = ".yakutmp"

        /** drain 期間持有的 partial WakeLock 標籤 + 逾時上限（洩漏保險；正常由 finally 釋放）。見 [drainLoop]。 */
        private const val WAKELOCK_TAG = "yakuyomi:translation"
        private const val WAKELOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000
    }
}
