package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.crash.TraceLog
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.nightread.NightConcurrency
import eu.kanade.tachiyomi.data.nightread.NightGovernor
import eu.kanade.tachiyomi.data.nightread.NightPages
import eu.kanade.tachiyomi.data.nightread.NightStorageService
import eu.kanade.tachiyomi.data.translation.model.QueuePoolKey
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import eu.kanade.tachiyomi.util.system.powerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.ZipWriter
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * 翻譯佇列（與下載 worker 解耦）。背景一章一章翻、就地覆蓋（§11），UI 觀察 [queueState]/[isTranslatePaused]/[isNightPaused]。
 *
 * 排入的兩條來源都走這裡：
 *  - **自動**：章下載完、進 cache 後由 `Downloader` 呼叫 [translate]（gate＝[isReady]）。
 *  - **手動**：漫畫頁的翻譯鈕（`MangaViewModel`）。
 *
 * 同一條佇列也載「重繪」（[reRender]）與「夜讀版」（[nightRender]）工作：三種都是對已下載章的離線重活，
 * 共用進度 / 暫停 / 取消 / 重試 / 持久化 / 前景服務；差別只在 [translateOne] 分派到 [PageTranslator] 的哪條。
 *
 * **一條佇列、兩個消費者**：翻譯／重繪走 [drainLoop]（[drainMutex]、翻譯總開關 gate），夜讀走 [drainLoopNight]
 * （[nightDrainMutex]、夜讀總開關 gate）。兩條各挑自己那類的 QUEUE 項、各有合作式中止旗標（[stopActiveTranslate]／
 * [stopActiveNight]），可同時跑：夜讀一章要好幾分鐘，不該卡住即時翻／新下載章的翻譯。
 * **夜讀自動讓路**（[nightGovernor]）：只有夜讀在跑時一般優先權、多頁多緒；翻譯／即時翻／reader 單頁翻譯或重繪在跑時
 * （[TranslationBusy]＋引擎建構中）夜讀降為一次一頁、單執行緒、低優先權。夜讀的頁跑在專用執行緒池
 * （[eu.kanade.tachiyomi.data.nightread.NightWorkers]），不在 [scope] 的 IO 執行緒上。
 * **兩個 pool 各自暫停**（[isTranslatePaused]／[isNightPaused]，佇列頁各一顆暫停/繼續鈕）：使用者想「先讓翻譯跑、夜讀晚點」
 * 或反過來，用各自的暫停就能調度；佇列挑章不做自動優先權／搶佔，CPU 讓路由 [nightGovernor] 自動處理。取消／清空依命中項的
 * 種類設對應旗標。
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

    // 掃全庫用：列收藏書 + 各書章節（「改去字法後升級重繪」reRenderAllUpgradable 系列、「重新產生舊版夜讀頁」scanOutdatedNight）。
    private val getFavorites: GetFavorites = Injekt.get()
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get()

    /** 常駐（warm）翻譯引擎服務：佇列翻完且**即時翻關著**時釋放它，別讓 ~100MB 閒置（即時翻開著則保 warm）。 */
    private val engineService: TranslationEngineService = Injekt.get()
    private val getCategories: GetCategories = Injekt.get()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 翻譯／重繪消費者的單消費者鎖（[drainLoop]）。 */
    private val drainMutex = Mutex()

    /** 夜讀消費者的單消費者鎖（[drainLoopNight]）；與 [drainMutex] 各自獨立 → 兩條可同時跑。 */
    private val nightDrainMutex = Mutex()

    /**
     * 夜讀的模式與放行控制（見 [NightGovernor]）。忙碌訊號＝翻譯入口在跑（[TranslationBusy]）或引擎在建構／暖機
     * （[TranslationEngineService.loading]）。優先權用 `Process.setThreadPriority(tid, nice)` 改夜讀 worker（同行程可改）。
     */
    private val nightGovernor = NightGovernor(
        scope = scope,
        busy = combine(TranslationBusy.active, engineService.loading) { active, loading -> active > 0 || loading },
        pagesCap = { NightConcurrency.pagesCap(translationPreferences.nightReadPageConcurrency.get()) },
        heapFree = { Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) } },
        maxMemory = Runtime.getRuntime().maxMemory(),
        thermalThrottled = {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                context.powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        },
        setNice = { tid, nice -> Process.setThreadPriority(tid, nice) },
        myTid = Process::myTid,
        now = SystemClock::elapsedRealtime,
        log = { TraceLog.log("night", it) },
    )

    /**
     * 合作式中止旗標（兩條消費者各一）：true → 該條正在跑的章在下一頁邊界停下（暫停/取消/清空/改法/總開關關用）。
     * 在 [lock] 下寫（[setStop]）、@Volatile 供逐頁迴圈無鎖讀（[isStop]）。分開才能「取消排隊中的夜讀項」而不停同章
     * 正在翻的翻譯項（反之亦然）。
     */
    @Volatile
    private var stopActiveTranslate = false

    @Volatile
    private var stopActiveNight = false

    /** 讀對應類別的中止旗標（[night]：true＝夜讀那條、false＝翻譯／重繪那條）。逐頁迴圈無鎖讀。 */
    private fun isStop(night: Boolean): Boolean = if (night) stopActiveNight else stopActiveTranslate

    /** 寫對應類別的中止旗標（在 [lock] 下呼叫）。 */
    private fun setStop(night: Boolean, value: Boolean) {
        if (night) stopActiveNight = value else stopActiveTranslate = value
    }

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
     *
     * [nightRender]：true＝夜讀項（[PageTranslator.renderNightChapter]：另存 `.yakuyomi/<頁>.night.std.webp` 等兩檔、
     * 不翻譯、不動原圖、與去字法無關 → [method]/[reRenderMethod] 皆不用）。
     *
     * [nightForce]：夜讀項的「以目前設定重新產生」（[NIGHT_NO_FORCE]＝一般；[NIGHT_FORCE_PENDING]＝已要求、下一輪開跑時
     * 記下時間；>0＝那一輪的開跑時間，完成標記早於它的頁一律重做）。時間在**開跑時**才定：要求當下若有舊的一輪正在跑，它停下前
     * 落地的頁（用舊參數）也早於這個時間、會被重做；之後被暫停再續跑，這輪已做好的頁晚於它、不會白做。持久化。
     */
    private class Entry(
        val manga: Manga,
        val chapter: Chapter,
        var status: TranslationItem.Status = TranslationItem.Status.QUEUE,
        var done: Int = 0,
        var total: Int = 0,
        val reRenderMethod: String? = null,
        var method: String = "",
        val nightRender: Boolean = false,
        /** 單章暫停（[pauseChapter]）：QUEUE 時 drain 跳過、TRANSLATING 時停頁邊界回 QUEUE 留著。持久化。 */
        var paused: Boolean = false,
        /** 夜讀強制重做（見類別說明）。持久化。 */
        var nightForce: Long = NIGHT_NO_FORCE,
    ) {
        /**
         * 夜讀項正在跑的時候又被要求補頁（[enqueueNight] 的 TRANSLATING 分支、[cancelTranslation]）：這一輪照常做完，
         * 做完不離開佇列、改回 QUEUE 再跑一輪（resume 只補被作廢的頁）。不中斷這一輪：中斷的話在飛的頁丟回待做、下一輪
         * 又要重開模型組暖機，reader 裡連續重繪幾頁就重來幾次。不持久化：行程被殺時 TRANSLATING 項本來就會還原成 QUEUE 重跑。
         * 在 [lock] 下存取。
         */
        var nightRerun: Boolean = false

        /** 這項所屬的「整本 × pool」鍵（整本暫停／搶翻／取消／重試／重排的單位）。 */
        val poolKey: QueuePoolKey get() = QueuePoolKey(manga.id, nightRender)

        /** 生效去字法：重繪項＝[reRenderMethod]，翻譯項＝[method]（夜讀項恆為空字串）。 */
        val effectiveMethod: String get() = reRenderMethod ?: method

        /** 對外快照的工作種類（給佇列 UI 分辨要畫去字法晶片還是「夜讀」標籤）。 */
        val kind: TranslationItem.Kind
            get() = when {
                nightRender -> TranslationItem.Kind.NIGHT
                reRenderMethod != null -> TranslationItem.Kind.RERENDER
                else -> TranslationItem.Kind.TRANSLATE
            }
    }

    private val lock = Any()
    private val entries = mutableListOf<Entry>()

    /**
     * Yakuyomi（佇列分組）：被「整本暫停」的 (漫畫, pool) 鍵集合——同一本在翻譯 pool 與夜讀 pool 各是一組、各自暫停
     * （使用者要的是「夜讀那組先停、翻譯照跑」這種調度）。drain 跳過這些組的章、做下一組；在 [lock] 下存取。
     */
    private val pausedMangaKeys = mutableSetOf<QueuePoolKey>()

    /**
     * 「待翻譯」標記集合（章 id）：線上即時翻 / reader 控制鈕觸發下載時先標記，
     * 由 [eu.kanade.tachiyomi.data.download.Downloader] 在該章下載完成（章目錄 + cache 都就緒）後查此集合、
     * 決定要不要把它排入翻譯（即使「下載時翻譯」總開關關著也能翻——讀到/手動觸發的章是使用者明確意圖）。
     *
     * 為何要這個：[Downloader] 下載完的預設 gate 是 [isReady]（含 translationEnabled 總開關），
     * 但即時翻 / 控制鈕要繞過該總開關只翻「被標記」的章 ⇒ 多一個 OR 條件。標記在 [lock] 下存取（與 [entries] 同鎖）。
     * 值＝標記的時間（epoch ms）：標記跟佇列一起持久化（[persist]），行程在下載途中被殺、下載續完時照樣接得上；還原時丟掉
     * 超過 [PENDING_MARK_TTL_MS] 的（下載早就被取消的章，別讓多年後的一次下載還被當成「要翻」）。
     */
    private val pendingTranslate = HashMap<Long, Long>()

    /** 標記某章「下載完成後要翻」（線上即時翻 / reader 控制鈕用）。 */
    fun markForTranslate(chapterId: Long) {
        synchronized(lock) { pendingTranslate[chapterId] = System.currentTimeMillis() }
        persist()
    }

    /** 該章是否被標記為「下載完成後要翻」（[Downloader] 下載完成 hook 查此判斷）。 */
    fun isPendingTranslate(chapterId: Long): Boolean = synchronized(lock) { chapterId in pendingTranslate }

    /** 清掉某章的「待翻譯」標記（已排入後由 [Downloader] 呼叫，避免殘留）。 */
    fun clearPending(chapterId: Long) {
        val removed = synchronized(lock) { pendingTranslate.remove(chapterId) != null }
        if (removed) persist()
    }

    /**
     * 「下載完要產生夜讀版」標記（章 id → 標記時間）：閱讀器裡對**線上章**按了「這話要先下載才能產生夜讀版，要下載嗎？」的
     * 「下載」（`ReaderViewModel.startOnlineNightDownload`）時先標記，章下載完由 [onDownloadedForNight] 接手排入夜讀——使用者
     * 中途離開閱讀器、甚至行程被殺（標記跟佇列一起持久化，同 [pendingTranslate]）也照樣做。在 [lock] 下存取。
     * 標記中的章下載時一律存成鬆散資料夾（[Downloader] 查 [isPendingNight]），夜讀才做得了。
     *
     * 標記一直留到**這話的夜讀版真的做完、而且沒有翻譯在等著覆寫它**為止（[settlePendingNightLocked]）：
     *  - 下載完已經排了翻譯 → 先等翻譯（頁圖要先貼好譯文，否則翻譯覆寫會把剛做好的夜讀版整章作廢），翻完由 [queueNightAfter] 排。
     *  - 翻譯沒做成（失敗、被取消或清空、被暫停、翻譯總開關被關）→ 不再等，現在就排（[releasePendingNight]）。
     *  - 夜讀先排了、之後才排翻譯（例如重載後即時翻譯接手）→ 標記還在，翻完再排一次，作廢的頁補回來。
     * 做不了（夜讀總開關關、缺模型、不是鬆散資料夾）或使用者自己取消了這話的夜讀項 → 丟掉標記。
     */
    private val pendingNight = HashMap<Long, Long>()

    /** 標記某章「下載完要產生夜讀版」（閱讀器線上章的「下載」）。 */
    fun markForNight(chapterId: Long) {
        synchronized(lock) { pendingNight[chapterId] = System.currentTimeMillis() }
        persist()
    }

    /** 該章是否被標記為「下載完要產生夜讀版」。 */
    fun isPendingNight(chapterId: Long): Boolean = synchronized(lock) { chapterId in pendingNight }

    /** 丟掉「下載完要產生夜讀版」標記（做不了，或使用者取消了）。 */
    private fun dropPendingNight(chapterId: Long, why: String) {
        val removed = synchronized(lock) { pendingNight.remove(chapterId) != null }
        if (!removed) return
        TraceLog.log("queue", "night request dropped c=$chapterId: $why")
        persist()
    }

    /**
     * 這章的翻譯／重繪項接下來會自己跑（在 [lock] 下呼叫）：翻譯總開關開、翻譯 pool 沒暫停，而且有一個排隊中或翻譯中、沒被
     * 單章或整本暫停的項。[masterOn]＝翻譯總開關（[haltForMasterOff] 傳切換後的值：那時偏好可能還沒寫進去）。
     */
    private fun translationWillRunLocked(chapterId: Long, masterOn: Boolean = masterEnabled()): Boolean =
        masterOn && !_isTranslatePaused.value && entries.any {
            !it.nightRender && it.chapter.id == chapterId && !it.paused && it.poolKey !in pausedMangaKeys &&
                (it.status == TranslationItem.Status.QUEUE || it.status == TranslationItem.Status.TRANSLATING)
        }

    /**
     * 夜讀項做完一章（[runDrain]，在 [lock] 下）：沒有翻譯在等著覆寫這章 → 使用者要的夜讀版做好了，丟掉標記；有翻譯在等 →
     * 留著，翻完由 [queueNightAfter] 再排一次。回傳是否丟了（呼叫端那輪收尾會 [persist]）。
     */
    private fun settlePendingNightLocked(chapterId: Long): Boolean =
        chapterId in pendingNight && !translationWillRunLocked(chapterId) && pendingNight.remove(chapterId) != null

    /**
     * 章下載完（[Downloader] 的完成 hook；或閱讀器發現這章其實已經下載好了）：這章有「下載完要產生夜讀版」標記的話排入夜讀。
     *  - 這章的翻譯接下來會跑（下載完成 hook 先排翻譯、再呼叫這裡）→ 先不排，翻完由 [queueNightAfter] 接；翻譯沒做成由
     *    [releasePendingNight] 接。
     *  - 否則現在就排（[queuePendingNightNow]）。
     * IO（找章節夾），呼叫端在 IO 跑。
     */
    fun onDownloadedForNight(manga: Manga, chapter: Chapter) {
        val wait = synchronized(lock) {
            if (chapter.id !in pendingNight) return
            translationWillRunLocked(chapter.id)
        }
        if (wait) return
        queuePendingNightNow(manga, chapter)
    }

    /**
     * 有「下載完要產生夜讀版」標記的章現在就排夜讀：是使用者明確要的，走 [nightRender]（不看「自動產生」與分類／來源範圍、解除
     * 夜讀暫停）；仍要夜讀總開關、夜讀模型與鬆散資料夾，缺就丟掉標記、只記一行（閱讀器按「下載」前已經檢查過，這裡是下載期間
     * 才被關掉或刪掉的情形；之後閱讀器點灰色檔位鈕會照常提示）。排進去了標記照留（見 [pendingNight]）。IO。
     */
    private fun queuePendingNightNow(manga: Manga, chapter: Chapter) {
        if (!nightEnabled()) return dropPendingNight(chapter.id, "night disabled")
        if (!nightModelsReady()) return dropPendingNight(chapter.id, "models missing")
        val queued = nightRender(manga, listOf(chapter))
        TraceLog.log("queue", "night request queued c=${chapter.id} queued=$queued")
        if (!queued) dropPendingNight(chapter.id, "not a loose folder")
    }

    /**
     * 有「下載完要產生夜讀版」標記、在等翻譯的章，翻譯卻不會（馬上）跑了：翻譯失敗、被暫停（pool／整本／單章）、翻譯總開關被關，
     * 或翻譯項剛被取消、清空（[removed]）。這些章現在就排夜讀，不再等（標記留著：之後翻譯真的做成會再排一次，見 [queueNightAfter]）。
     * 已有排隊中或產生中的夜讀項的章不動。在 [scope] 上做（找章節夾是 IO），馬上返回；不可在 [lock] 內呼叫。
     */
    private fun releasePendingNight(removed: List<Entry> = emptyList(), masterOn: Boolean = masterEnabled()) {
        val targets = synchronized(lock) {
            if (pendingNight.isEmpty()) return
            (removed + entries)
                .filter { !it.nightRender && it.chapter.id in pendingNight }
                .distinctBy { it.chapter.id }
                .filter { c ->
                    !translationWillRunLocked(c.chapter.id, masterOn) &&
                        // 排隊中或產生中（狀態只有三種，不是 ERROR 就是這兩種）
                        entries.none {
                            it.nightRender && it.chapter.id == c.chapter.id && it.status != TranslationItem.Status.ERROR
                        }
                }
                .map { it.manga to it.chapter }
        }
        if (targets.isEmpty()) return
        scope.launch {
            targets.forEach { (manga, chapter) ->
                try {
                    queuePendingNightNow(manga, chapter)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(LogPriority.WARN, e) { "翻譯沒做成後排夜讀失敗 c=${chapter.id}" }
                }
            }
        }
    }

    /** 夜讀模型齊（偵測器 dbnet 與人物分割至少一顆）。IO（看檔案在不在）。 */
    private fun nightModelsReady(): Boolean =
        TranslationEngineConfig.detectorResolvable(context) && TranslationEngineConfig.charSegResolvable(context)

    private val _queueState = MutableStateFlow<List<TranslationItem>>(emptyList())
    val queueState: StateFlow<List<TranslationItem>> = _queueState.asStateFlow()

    /**
     * 兩個 pool 各自的「使用者暫停」旗標：翻譯／重繪一個、夜讀一個。分開的理由：夜讀一章要好幾分鐘，
     * 使用者要能「先讓翻譯跑、夜讀晚點」或反過來——各自暫停就能調度，佇列挑章不必做自動優先權/搶佔（CPU 讓路由
     * [nightGovernor] 自動處理）。
     * 明確要求某類工作（排入/重試/搶翻/整本繼續）只解除**該類** pool 的暫停，另一個 pool 的暫停不動。
     */
    private val _isTranslatePaused = MutableStateFlow(false)
    val isTranslatePaused: StateFlow<Boolean> = _isTranslatePaused.asStateFlow()
    private val _isNightPaused = MutableStateFlow(false)
    val isNightPaused: StateFlow<Boolean> = _isNightPaused.asStateFlow()

    private fun isPoolPaused(night: Boolean): Boolean = if (night) _isNightPaused.value else _isTranslatePaused.value

    private fun setPoolPaused(night: Boolean, paused: Boolean) {
        if (night) _isNightPaused.value = paused else _isTranslatePaused.value = paused
    }

    /** Yakuyomi（佇列分組）：被整本暫停的 (漫畫, pool) 鍵快照，給佇列 UI 顯示每組的暫停/繼續狀態。 */
    private val _pausedMangas = MutableStateFlow<Set<QueuePoolKey>>(emptySet())
    val pausedMangas: StateFlow<Set<QueuePoolKey>> = _pausedMangas.asStateFlow()

    private val _translatedIds = MutableStateFlow<Set<Long>>(emptySet())

    /** 本 session 翻成功的章 id（給 UI 標「已翻」；跨重啟的持久標記另由 manifest 補）。 */
    val translatedIds: StateFlow<Set<Long>> = _translatedIds.asStateFlow()

    private val _nightDoneIds = MutableStateFlow<Set<Long>>(emptySet())

    private val _nightVersion = MutableStateFlow(0)

    /**
     * 夜讀版磁碟狀態的版本計數：夜讀做完一章、或翻譯／重繪覆寫頁圖（連帶作廢舊夜讀版）時 +1。章節列把它當
     * 重建來源之一（`MangaViewModel` 的 combine），讓 `hasNightPages` 重掃磁碟——否則「只增不覆蓋」的持久旗標會把
     * 已被刪掉的夜讀版一直顯示成 DONE。
     */
    val nightVersion: StateFlow<Int> = _nightVersion.asStateFlow()

    /**
     * 本 session 夜讀版產生成功的章 id（給章節列的夜讀指示器標「已有」；跨重啟的持久狀態另由 [hasNightPages] 掃檔補）。
     * 與 [translatedIds] 分開：夜讀版不是翻譯、不動「已翻」徽章。
     */
    val nightDoneIds: StateFlow<Set<Long>> = _nightDoneIds.asStateFlow()

    /**
     * 這些章的檔案整個換掉了（刪下載，或重新下載完成、還沒進下載快取前；見 DownloadManager.deleteChapters、Downloader）：
     * 本 session 記下的「翻成」「夜讀做完」作廢。不清的話，同一個 session 刪掉再重新下載，章節列會把新下載的原圖亮成
     * 已翻／已有夜讀版（這兩個集合原本只會變多）。佇列裡的項不動：沒下載的章跑到時照樣標 ERROR、重新下載的章照常做。
     */
    fun forgetChapterOutputs(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        val ids = chapterIds.toSet()
        _translatedIds.update { it - ids }
        _nightDoneIds.update { it - ids }
        // 設定 › 夜讀「儲存空間」的結果裡這些章的夜讀檔已經不在（刪掉了，或換成新下載的原圖）：下次進設定頁時拿掉（不重掃全庫）
        nightStorage.forgetChapters(ids)
    }

    /**
     * 每翻好一頁就推一次（chapterId, pageName）。給即時翻 [eu.kanade.tachiyomi.ui.reader.loader.TranslatingPageLoader]
     * **直接重畫該頁**，取代「觀察 conflated 的 [queueState] + 每次讀 manifest 檔」那條——後者會 conflate 丟中間值 +
     * 檔案讀慢，導致某頁翻完當下沒被即時比中、要等之後某頁 emit 才順便補上（更新延遲）。SharedFlow 有緩衝、不丟事件。
     */
    private val _donePageEvents = MutableSharedFlow<Pair<Long, String>>(extraBufferCapacity = 128)
    val donePageEvents: SharedFlow<Pair<Long, String>> = _donePageEvents.asSharedFlow()

    /**
     * 夜讀項每做完一章（成功離開佇列）就推一次章 id。給 reader 重掃該章的夜讀檔、重畫已載入的頁。
     * [nightDoneIds] 是只增的集合、同一章做第二次（重新產生、以目前設定重新產生）不會再發，所以另開這條事件。
     */
    private val _nightChapterDone = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    val nightChapterDone: SharedFlow<Long> = _nightChapterDone.asSharedFlow()

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
     * 夜讀總開關（[TranslationPreferences.nightReadEnabled]，表層「其他」頁那顆）：夜讀那條消費者的硬 gate，
     * 與翻譯總開關**獨立**——關閉時夜讀項不排入（[nightRender]）、不自動排（[queueNightAfter]）、[drainLoopNight] 不取新章；
     * 正在跑的夜讀項停在頁邊界回 QUEUE 留著，再開時由 [onNightEnabledChanged] 續跑。
     */
    private fun nightEnabled(): Boolean = translationPreferences.nightReadEnabled.get()

    /** 某類別的總開關：翻譯／重繪項看 [masterEnabled]、夜讀項看 [nightEnabled]。 */
    private fun kindEnabled(night: Boolean): Boolean = if (night) nightEnabled() else masterEnabled()

    /** 兩個總開關任一開：共用操作（暫停/繼續/重試/搶翻）的 gate——至少有一條消費者能動才有意義。 */
    private fun anyEnabled(): Boolean = masterEnabled() || nightEnabled()

    /**
     * 是否還有「該被跑」的項（QUEUE 或正在 TRANSLATING、排除整本暫停者），且該類別的總開關開、未使用者暫停。
     * 給 [TranslationHeartbeatJob] 判斷要不要把前景服務拉回來（vivo 這類 OEM 硬殺前景服務後的自動恢復）。
     */
    fun hasPendingWork(): Boolean = hasRunnableWork(master = masterEnabled(), night = nightEnabled())

    /**
     * [hasPendingWork] 的本體，開關值由呼叫端傳入：總開關切換的副作用（[haltForMasterOff]／[onNightEnabledChanged]）
     * 要以「切換後」的值判斷該不該停前景服務，不能依賴 pref 是否已寫入（設定頁是 callback 回 true 之後才寫）。
     * [master]＝翻譯／重繪項算不算、[night]＝夜讀項算不算。
     */
    private fun hasRunnableWork(master: Boolean, night: Boolean): Boolean {
        // 各 pool：總開關開 且 該 pool 未被使用者暫停，才算「能跑」
        val translateRunnable = master && !_isTranslatePaused.value
        val nightRunnable = night && !_isNightPaused.value
        if (!translateRunnable && !nightRunnable) return false
        return synchronized(lock) {
            entries.any {
                (it.status == TranslationItem.Status.QUEUE || it.status == TranslationItem.Status.TRANSLATING) &&
                    it.poolKey !in pausedMangaKeys && !it.paused &&
                    (if (it.nightRender) nightRunnable else translateRunnable)
            }
        }
    }

    /** 發佈佇列快照。賦值也在 [lock] 內：兩條消費者（夜讀多頁）同時 publish 時，舊快照不會蓋掉新進度。 */
    private fun publish() {
        synchronized(lock) {
            _queueState.value = entries.map {
                TranslationItem(
                    it.manga,
                    it.chapter,
                    it.status,
                    it.done,
                    it.total,
                    it.effectiveMethod,
                    it.kind,
                    it.paused,
                )
            }
        }
    }

    /** 發佈「整本暫停」集合快照（與 [publish] 分開：暫停集合變動才發）。 */
    private fun publishPaused() {
        _pausedMangas.value = synchronized(lock) { pausedMangaKeys.toSet() }
    }

    /**
     * 挑該類別下一個要跑的章（在 [lock] 下呼叫；[night]：true＝只挑夜讀項、false＝只挑翻譯／重繪項——兩條消費者
     * 各挑各的，互不相干）。以漫畫為單位：跳過「整本暫停」的漫畫，按**漫畫在佇列的順序**、再按**話號小→大**取
     * （同一本接續跑；搶翻/拖曳改變漫畫順序即生效）。無可跑章回 null。
     */
    private fun pickNextQueuedLocked(night: Boolean): Entry? {
        val active = entries.filter {
            it.status == TranslationItem.Status.QUEUE && it.nightRender == night &&
                it.poolKey !in pausedMangaKeys && !it.paused
        }
        if (active.isEmpty()) return null
        val order = groupOrderLocked()
        return active.minWithOrNull(
            compareBy({ order[it.poolKey] ?: Int.MAX_VALUE }, { it.chapter.chapterNumber }),
        )
    }

    /**
     * 「本 × pool」組在佇列裡的先後（[QueuePoolKey] → 首次出現的序號；在 [lock] 下呼叫）。[pickNextQueuedLocked] 的
     * 第一排序鍵——與佇列頁的卡片順序同單位：同本在另一個 pool 的組被拖到哪，不影響這個 pool 的順序。
     */
    private fun groupOrderLocked(): Map<QueuePoolKey, Int> {
        val order = HashMap<QueuePoolKey, Int>()
        entries.forEach { if (it.poolKey !in order) order[it.poolKey] = order.size }
        return order
    }

    /** 丟掉已經沒有任何項的組的「整本暫停」鍵（在 [lock] 下）：否則下次同本自動排入的章會被殘留的鍵無聲 hold 住。 */
    private fun pruneOrphanPausedKeysLocked() {
        pausedMangaKeys.retainAll { key -> entries.any { it.poolKey == key } }
    }

    /**
     * 把目前佇列＋暫停狀態寫回磁碟（[translationStore]）。只在**結構性變動**（排入 / 移除 / 狀態 / 方法 / 暫停）後呼叫，
     * **不**放進 [publish]（那會被逐頁進度更新打爆 I/O）。還原完成前（[restored]=false）跳過，避免覆蓋掉持久佇列。
     */
    private fun persist() {
        if (!restored) return
        // 快照與寫入同一把鎖（persistLock → lock，永遠這個順序）：兩條 drain 執行緒＋UI 同時 persist 時，較舊的快照
        // 不能寫在後面，否則行程被殺後還原出已取消／已完成的章、或丟掉 ERROR／暫停旗標。
        synchronized(persistLock) { persistLocked() }
    }

    private val persistLock = Any()

    private fun persistLocked() {
        var marks = TranslationStore.PendingMarks()
        val snapshot = synchronized(lock) {
            marks = TranslationStore.PendingMarks(translate = HashMap(pendingTranslate), night = HashMap(pendingNight))
            entries.map {
                TranslationStore.Saved(
                    mangaId = it.manga.id,
                    chapterId = it.chapter.id,
                    method = it.method,
                    reRenderMethod = it.reRenderMethod,
                    errored = it.status == TranslationItem.Status.ERROR,
                    mangaPaused = it.poolKey in pausedMangaKeys,
                    nightRender = it.nightRender,
                    paused = it.paused,
                    nightForce = it.nightForce,
                )
            }
        }
        translationStore.save(snapshot, _isTranslatePaused.value, _isNightPaused.value, marks)
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
                            // 去重鍵＝(章, 工作種類)：同章的翻譯項與夜讀項可並存（先翻、再產夜讀是常態），
                            // 只比章 id 會讓還原前 race 進來的翻譯項把持久化的夜讀項丟掉（反之亦然）。
                            val present = entries.mapTo(HashSet()) { it.chapter.id to it.kind }
                            recovered.forEach { r ->
                                val entry = Entry(
                                    r.manga,
                                    r.chapter,
                                    status = if (r.errored) {
                                        TranslationItem.Status.ERROR
                                    } else {
                                        TranslationItem.Status.QUEUE
                                    },
                                    reRenderMethod = r.reRenderMethod,
                                    method = r.method,
                                    nightRender = r.nightRender,
                                    paused = r.paused,
                                    nightForce = r.nightForce,
                                )
                                if ((entry.chapter.id to entry.kind) !in present) entries.add(entry)
                            }
                            // 還原「整本暫停」：每項各自記它那組 (漫畫, pool)（向後相容：舊資料無此欄→不暫停）。
                            recovered.filter { it.mangaPaused }
                                .forEach { pausedMangaKeys.add(QueuePoolKey(it.manga.id, it.nightRender)) }
                        }
                        _isTranslatePaused.value = translationStore.restorePaused()
                        _isNightPaused.value = translationStore.restoreNightPaused()
                        publish()
                        publishPaused()
                    }
                    // 「下載完要翻／要產生夜讀版」標記（行程在下載途中被殺：下載續完時照樣接得上）。太舊的丟掉；還原前剛標的留著。
                    val marks = translationStore.restorePendingMarks(System.currentTimeMillis() - PENDING_MARK_TTL_MS)
                    synchronized(lock) {
                        marks.translate.forEach { (id, at) -> pendingTranslate.putIfAbsent(id, at) }
                        marks.night.forEach { (id, at) -> pendingNight.putIfAbsent(id, at) }
                    }
                    restored = true
                    persist() // 回寫合併後佇列（含還原前 race 進來的新項）
                }
            }
        }
        // 硬總開關（各自）：master 關時翻譯項只還原快照、**不**自動 drain / 不建引擎（再開時由 [resumeForMasterOn] 續跑）；
        // 夜讀總開關關時夜讀項同理（[onNightEnabledChanged]）。任一類有可跑的 QUEUE 項 → 兩條都 ensureDrain（各自 gate）。
        val master = masterEnabled()
        val night = nightEnabled()
        val translateRunnable = master && !_isTranslatePaused.value
        val nightRunnable = night && !_isNightPaused.value
        val hasQueued = synchronized(lock) {
            entries.any {
                it.status == TranslationItem.Status.QUEUE && it.poolKey !in pausedMangaKeys && !it.paused &&
                    (if (it.nightRender) nightRunnable else translateRunnable)
            }
        }
        if (hasQueued) {
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
     *   **注意**：只重排 QUEUE 項——正在翻（TRANSLATING）的翻譯/重繪章不會被中途搶占（會翻完當前章才換下一章），
     *   此為已知限制。夜讀項與此無關：它由另一條消費者在自己的執行緒跑，翻譯排入不必等它、也不用搶占它。
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
                    // 夜讀項不算「同章已排」：它不翻譯，抓到它改 method/搬到最前只會讓這章永遠翻不到。
                    val existing = entries.firstOrNull { it.chapter.id == chapter.id && !it.nightRender }
                    when {
                        // 已在翻的章不動（不中途搶占）；它的 method 已鎖、留原處翻完。
                        existing?.status == TranslationItem.Status.TRANSLATING -> Unit
                        // 已排隊（QUEUE/ERROR）→ 從原位移除、改放到 batch 最前（搬到佇列前段）。
                        existing != null -> {
                            entries.remove(existing)
                            existing.method = m // 插隊＝使用者剛要讀，刷新成當下去字法
                            existing.status = TranslationItem.Status.QUEUE // ERROR 重排也算重試
                            existing.paused = false // 明確要讀這章 → 解除單章暫停（與解除 pool 暫停同理）
                            batch.add(existing)
                        }
                        // 全新章 → 建項加進 batch。
                        else -> batch.add(Entry(manga, chapter, method = m))
                    }
                }
                entries.addAll(0, batch)
            } else {
                val ids = chapters.mapTo(HashSet()) { it.id }
                val present = HashSet<Long>()
                entries.forEach {
                    if (!it.nightRender && it.chapter.id in ids) { // 夜讀項不擋翻譯
                        present.add(it.chapter.id)
                        it.paused = false // 明確再排＝解除單章暫停（否則章節列點「翻譯」對被 ⏸ 的章是無聲 no-op）
                    }
                }
                chapters.forEach { if (it.id !in present) entries.add(Entry(manga, it, method = m)) }
            }
        }
        publish()
        _isTranslatePaused.value = false // 明確要求翻譯 → 解除翻譯 pool 暫停、直接開跑（對照下載：排入即啟動）；夜讀 pool 不動
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
        _isTranslatePaused.value = false // 明確要求重繪 → 解除翻譯 pool 暫停、直接開跑；夜讀 pool 不動
        ensureDrain()
        TranslationJob.start(context)
        persist()
    }

    /**
     * 排入「產生夜讀版」（[PageTranslator.renderNightChapter]：人物分割 + 白底/白泡變暗另存
     * `.yakuyomi/<頁>.night.std.webp`＋與標準不同時的 `.night.more.webp`，見 NightPages；不翻譯、不動原圖）。走同一條翻譯佇列（進度 / 暫停 / 取消 / 重試 / 持久化共用）。
     *
     * 兩條來源：章節多選底部選單「產生夜讀版」（手動，`MangaViewModel`）；翻譯/重繪成功後 [drainLoop] 自動
     * （`nightReadGenerate` 開著時，走 [enqueueNight] 不經此處，避免動到使用者的暫停狀態）。
     *
     * 去重**只看既有夜讀項**：翻譯/重繪項可與之並存（同章「先翻、再產夜讀」是常態）。一律排到佇列尾、不插隊；
     * 已有夜讀版的章也可再排（resume 只補不新鮮的頁、全新鮮就秒過；只有舊版單檔的頁算不新鮮 → 升級成兩檔）。
     * 只支援鬆散夾：CBZ／線上章不排。
     * Gate＝**夜讀總開關**（[nightEnabled]，與翻譯總開關獨立）：關著直接 return。
     *
     * [force]＝「以目前設定重新產生這一話」（改了亮度之後）：連新鮮的頁也重做（見 [Entry.nightForce]）。不先刪檔——
     * 重做前 reader 照樣顯示舊夜讀版（夜裡不會突然整章變回亮的原圖），每頁照寫入順序換新。
     *
     * IO（判鬆散夾要找章節夾），呼叫端在 IO 跑。回傳是否真的排進去（false＝總開關關、或沒有鬆散夾章——呼叫端據此提示）。
     */
    fun nightRender(manga: Manga, chapters: List<Chapter>, force: Boolean = false): Boolean {
        if (chapters.isEmpty()) return false
        if (!nightEnabled()) return false // 夜讀總開關：關閉時不排入（UI 此時也不畫夜讀按鈕，這是最後一道）
        val loose = chapters.filter { isLooseChapter(manga, it) } // 壓縮章排了也只會紅 ERROR，直接不排（呼叫端另提示）
        if (loose.isEmpty()) return false
        enqueueNight(manga, loose, force, explicit = true)
        publishPaused()
        publish()
        _isNightPaused.value = false // 明確要求產生夜讀 → 解除夜讀 pool 暫停、直接開跑；翻譯 pool 不動
        ensureDrain()
        TranslationJob.start(context)
        persist()
        return true
    }

    /**
     * 把章排入夜讀（在 [lock] 下），依既有夜讀項的狀態處理、不是一律跳過：
     *  - 沒有 → 新增 QUEUE 項到佇列尾。
     *  - ERROR → 回 QUEUE（手動再排／翻完自動排＝重試；否則紅色夜讀指示器點了沒反應、只能去佇列頁重試）。被使用者單章暫停的
     *    ERROR 項只有明確要求才解除暫停；自動路徑改回 QUEUE 但留著暫停，使用者按繼續時才補做。
     *  - TRANSLATING → 記 [Entry.nightRerun]：正在產生的那次可能已做完的頁剛被翻譯／重繪的 [PageTranslator.invalidateNightPage]
     *    刪掉，它做完會 done>0 當成功離開佇列、缺頁永遠沒人補。這一輪照常做完、改回 QUEUE 再跑一輪，resume 只補缺／過期頁
     *    （不中斷這一輪：在飛的頁不白做、不重開模型組）。
     *  - QUEUE → 不動（之後跑時整章重驗新鮮度）。
     * [force]：各狀態另把 [Entry.nightForce] 設成「已要求」；TRANSLATING 那輪改設 [stopActiveNight] 停在頁邊界重挑（剩下的頁
     * 用舊設定做也是白做），新一輪才定時間。
     * [explicit]＝使用者明確要求（[nightRender]：reader、章節多選）：連這些章的單章暫停與這本在夜讀 pool 的整本暫停一起
     * 解除（同 [nightRender] 解除 pool 暫停的道理），否則 drain 永遠挑不到、reader 卻已提示「已排入」。
     * 自動路徑（翻完／下載完／單頁操作後補頁）不傳，不動使用者的暫停。
     * 回傳是否有需要叫醒夜讀那條的變動（全部已在排隊＝false）。
     */
    private fun enqueueNight(
        manga: Manga,
        chapters: List<Chapter>,
        force: Boolean = false,
        explicit: Boolean = false,
    ): Boolean = synchronized(lock) {
        val existing = entries.filter { it.nightRender }.associateBy { it.chapter.id }
        val forceValue = if (force) NIGHT_FORCE_PENDING else NIGHT_NO_FORCE
        var changed = false
        if (explicit && pausedMangaKeys.remove(QueuePoolKey(manga.id, true))) changed = true
        chapters.forEach { ch ->
            val entry = existing[ch.id]
            if (explicit && entry?.paused == true) {
                entry.paused = false
                changed = true
            }
            when (entry?.status) {
                null -> {
                    entries.add(Entry(manga, ch, nightRender = true, nightForce = forceValue))
                    changed = true
                }
                TranslationItem.Status.ERROR -> {
                    // 重試。單章暫停只有明確要求才解除（上面已處理）；自動路徑留著，免得把使用者停住的章偷偷跑起來
                    entry.status = TranslationItem.Status.QUEUE
                    if (force) entry.nightForce = NIGHT_FORCE_PENDING
                    changed = true
                }
                TranslationItem.Status.TRANSLATING -> {
                    if (force) {
                        entry.nightForce = NIGHT_FORCE_PENDING
                        stopActiveNight = true
                    } else {
                        entry.nightRerun = true
                    }
                    changed = true
                }
                TranslationItem.Status.QUEUE -> {
                    if (force) {
                        entry.nightForce = NIGHT_FORCE_PENDING
                        changed = true
                    }
                }
            }
        }
        changed
    }

    /**
     * 翻譯/重繪成功後的自動夜讀（翻譯那條 [runDrain] 呼叫）：夜讀總開關開 + `nightReadGenerate` 開 + 非「原圖」重繪 +
     * 夜讀模型齊 + 該章是鬆散夾 → 排一個夜讀項到佇列尾、回 true（呼叫端接著 [ensureNightDrain] 叫醒夜讀那條——
     * 它可能早因沒事做退出了）。**不**走 [nightRender]：那條會解除暫停/重啟前景服務，drain 進行中不該碰使用者的暫停狀態；
     * publish/persist 由呼叫端那輪收尾一起做。
     *
     * 「原圖（還原未翻）」重繪不排：使用者是在退回原圖、不是要新的成品，別順手再排一趟夜讀。
     * 模型缺時不排：否則每翻一章就紅一個「缺夜讀模型」錯誤；手動排入那條（[nightRender] 的呼叫端）另以提示告知。
     * CBZ 不排：夜讀檔要逐檔落在 `<章>/.yakuyomi/`，壓縮檔內取不到（同重繪素材的限制）。
     */
    private suspend fun queueNightAfter(entry: Entry): Boolean {
        // 使用者在閱讀器對線上章要過夜讀版（[markForNight]，下載完先翻譯的章留到這裡）：照明確要求排——不看「自動產生」與
        // 分類／來源範圍，解除這章與夜讀 pool 的暫停（同 [nightRender]）；仍要夜讀總開關、模型、鬆散夾，缺就丟掉標記。
        // 排進去了標記照留，夜讀做完才丟（[settlePendingNightLocked]）：產生中的那一輪會被叫再跑一輪，補回剛被翻譯作廢的頁。
        if (isPendingNight(entry.chapter.id)) {
            val dir = if (nightEnabled() && nightModelsReady()) {
                chapterDir(entry.manga, entry.chapter)?.takeIf { it.isDirectory }
            } else {
                null
            }
            if (dir != null && canStoreNightFiles(dir, entry.chapter)) {
                val changed = enqueueNight(entry.manga, listOf(entry.chapter), explicit = true)
                val wasPaused = _isNightPaused.value
                _isNightPaused.value = false
                publishPaused()
                TraceLog.log("queue", "night request after translate c=${entry.chapter.id}")
                // 已在排隊、但夜讀 pool 原本暫停著：也要叫醒夜讀那條（呼叫端看回傳值決定）
                return changed || wasPaused
            }
            dropPendingNight(entry.chapter.id, "cannot render after translate")
        }
        if (!autoNightEligible()) return false
        if (entry.reRenderMethod == PageTranslator.ORIGINAL_METHOD) return false
        if (!nightAutoAllowed(entry.manga)) return false
        val dir = chapterDir(entry.manga, entry.chapter) ?: return false
        if (!dir.isDirectory) return false
        if (!canStoreNightFiles(dir, entry.chapter)) return false
        return enqueueNight(entry.manga, listOf(entry.chapter))
    }

    /**
     * 自動路徑排夜讀前先確定 `.yakuyomi/` 建得出來（[PageTranslator.ensureMaterialsDir]）：建不出來的儲存位置每排一章就紅一個
     * 「無法建立 .yakuyomi 子夾」錯誤，跟缺模型一樣自動路徑不排、只記一行；手動排入（[nightRender]）照排，由
     * [PageTranslator.renderNightChapter] 報錯告訴使用者原因。素材夾反正是夜讀一開跑就要建的，先建好沒有多餘的副作用。IO。
     */
    private fun canStoreNightFiles(dir: UniFile, chapter: Chapter): Boolean {
        val ok = pageTranslator.ensureMaterialsDir(dir)
        if (!ok) TraceLog.log("queue", "night skipped c=${chapter.id}: cannot create ${NightPages.DIR}")
        return ok
    }

    /**
     * reader 單頁重繪／單頁翻譯**之前**呼叫：這章現在有沒有夜讀版（[chapterDir]＝已確定是鬆散圖夾的章節夾）。結果交給
     * [queueNightAfterPage]。要在覆寫前問：覆寫會刪掉這頁的夜讀檔，事後再看的話「整章只有這頁有夜讀版」（其他頁被略過彩色頁
     * 跳過、或單頁章）會看成沒有、不補排，這頁的夜讀版就永久消失。夜讀總開關關著不掃（反正不會排）。查不到（儲存位置出錯）
     * 當成沒有，別因為這個附帶檢查讓重繪／翻譯本身失敗。IO。
     */
    fun chapterHasNightBeforePage(chapterDir: UniFile): Boolean =
        nightEnabled() &&
            runCatching { looseNightSummaryOf(chapterDir).state != NightPages.ChapterState.NONE }.getOrDefault(false)

    /**
     * reader 單頁重繪（含還原原圖）／單頁翻譯成功之後：那頁的夜讀版已被覆寫端作廢（[PageTranslator.invalidateNightPage]），
     * 這章其他頁還是暗的，夜讀模式翻到這頁卻變回亮的。這裡把這章排回夜讀佇列補那一頁（resume 只做缺的／過期的頁，章已齊的話
     * 就只做這一頁）。
     *
     * 只補**本來就有夜讀版**（[hadNight]，呼叫端在覆寫前用 [chapterHasNightBeforePage] 問的）**或已在夜讀佇列**的章：單頁操作
     * 不該替從沒產生過夜讀版的章開一整章的工作。這是維護已有的夜讀版，不是自動產生，所以不看「自動產生夜讀版」偏好與自動夜讀
     * 的分類／來源範圍——使用者關掉自動、用月亮鈕手動做的章也要補，否則指示器照樣亮「已有」、這頁卻是亮的。仍要夜讀總開關、
     * 夜讀模型、鬆散夾、素材夾建得出來。還原原圖也排（和整章還原不同）：其他頁還是暗的，原圖這頁也該有暗的版本。走
     * [enqueueNight] 的自動路徑：不動使用者的暫停；正在產生這章的那一輪做完再跑一輪把這頁補回。
     *
     * 在 [scope] 上跑、馬上返回：reader 呼叫完就可能被關掉（viewModelScope 取消），補排不能跟著被取消——頁圖已經覆寫、
     * 夜讀版已經刪了，沒排到的話這頁就一直是亮的。
     */
    fun queueNightAfterPage(manga: Manga, chapter: Chapter, hadNight: Boolean) {
        scope.launch {
            try {
                queueNightAfterPageNow(manga, chapter, hadNight)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "單頁處理後排夜讀失敗" }
            }
        }
    }

    private fun queueNightAfterPageNow(manga: Manga, chapter: Chapter, hadNight: Boolean) {
        if (!nightEnabled()) return
        val modelsReady = TranslationEngineConfig.detectorResolvable(context) &&
            TranslationEngineConfig.charSegResolvable(context)
        if (!modelsReady) return
        val inQueue = synchronized(lock) { entries.any { it.nightRender && it.chapter.id == chapter.id } }
        if (!hadNight && !inQueue) return
        val dir = chapterDir(manga, chapter)?.takeIf { it.isDirectory } ?: return
        if (!canStoreNightFiles(dir, chapter)) return
        if (!enqueueNight(manga, listOf(chapter))) return
        TraceLog.log("queue", "night queued after page m=${manga.id} c=${chapter.id}")
        publish()
        ensureNightDrain()
        TranslationJob.start(context)
        persist()
    }

    /**
     * 自動產生夜讀版的共同 gate（翻譯成功後 [queueNightAfter]、與下載完成的非翻譯路徑 [nightRenderAuto]）：
     * 夜讀總開關 + 「自動產生夜讀版」偏好 + 模型齊（偵測器 dbnet 與人物分割至少一顆）。缺模型不排：否則每下載一章
     * 就紅一個「缺夜讀模型」；手動排入那條（[nightRender] 的呼叫端）另以提示告知。
     */
    fun autoNightEligible(): Boolean =
        nightEnabled() && translationPreferences.nightReadGenerate.get() &&
            TranslationEngineConfig.detectorResolvable(context) &&
            TranslationEngineConfig.charSegResolvable(context)

    /**
     * 這本的已下載章「讀到時會被即時翻接手」（鏡射 `ChapterLoader.shouldTranslateLive` 的靜態部分：翻譯總開關 + 即時翻開 +
     * 引擎就緒 + 來源未排除 + 分類過濾）→ 下載完成時**不要**先排夜讀：即時翻會覆寫頁圖、把剛做的夜讀版整章作廢再重做
     * 一遍。這種章的夜讀由翻完後 [queueNightAfter] 接。suspend：分類要查 DB。
     */
    suspend fun willLiveTranslate(manga: Manga): Boolean {
        if (!masterEnabled() || !translationPreferences.liveTranslate.get()) return false
        if (!engineService.isReady() || isSourceExcluded(manga)) return false
        val cats = getCategories.await(manga.id).map { it.id.toString() }.toSet()
        val include = translationPreferences.liveTranslateCategories.get()
        val exclude = translationPreferences.liveTranslateCategoriesExclude.get()
        return (include.isEmpty() || cats.any { it in include }) && cats.none { it in exclude }
    }

    /** 這本的來源是否在「不自動做夜讀的來源」清單（設定 › 夜讀）。只管自動路徑。 */
    fun isNightSourceExcluded(manga: Manga): Boolean =
        manga.source.toString() in translationPreferences.nightReadSourcesExclude.get()

    /**
     * 自動夜讀的範圍 gate（設定 › 夜讀 的來源排除 + 分類包含／排除，語義同即時翻譯分類）。只管**自動**路徑
     * （下載完 [nightRenderAuto]、翻完後 [queueNightAfter]）；手動月亮鈕與 reader 長壓不查。suspend：分類要查 DB。
     */
    suspend fun nightAutoAllowed(manga: Manga): Boolean {
        if (isNightSourceExcluded(manga)) return false
        val include = translationPreferences.nightReadCategories.get()
        val exclude = translationPreferences.nightReadCategoriesExclude.get()
        if (include.isEmpty() && exclude.isEmpty()) return true
        val cats = getCategories.await(manga.id).map { it.id.toString() }.toSet()
        return (include.isEmpty() || cats.any { it in include }) && cats.none { it in exclude }
    }

    /** 章是否為鬆散圖夾（夜讀只支援鬆散夾；CBZ／rar／7z／epub → false）。IO，呼叫端在 IO 跑。 */
    fun isLooseChapter(manga: Manga, chapter: Chapter): Boolean = chapterDir(manga, chapter)?.isDirectory == true

    /**
     * 章有沒有重繪素材（[PageTranslator.storedInpaintMethod] 非 null：至少一頁有完整素材 json）：章節多選「重繪」與「重繪全本」
     * 排入前先篩，沒有的章不排（排了只會重繪 0 頁、整章標錯誤）。只存夜讀素材的章（遮罩＋夜讀文字框）、沒存素材的章、
     * 壓縮檔章（打包時跳過素材夾）→ false。與改去字法升級全庫（[reRenderAllUpgradable]）同一個判準。IO，呼叫端在 IO 跑。
     */
    fun hasReRenderMaterials(manga: Manga, chapter: Chapter): Boolean {
        val dir = chapterDir(manga, chapter)?.takeIf { it.isDirectory } ?: return false
        return pageTranslator.storedInpaintMethod(dir) != null
    }

    /**
     * 自動排入夜讀——[eu.kanade.tachiyomi.data.download.Downloader] 下載完成、且這章**不會**進翻譯佇列時呼叫
     * （「下載時翻譯」關著／來源被排除／沒 key）：**非翻譯本也能用夜讀**（原文本、母語本）。會翻的章不走這裡，
     * 由翻完後 [queueNightAfter] 自動接（頁圖要先貼好譯文）。
     * 走 [enqueueNight] 不經 [nightRender]：自動路徑**不動**使用者的暫停狀態。只鬆散夾（CBZ 取不到 `.yakuyomi/`）。
     */
    suspend fun nightRenderAuto(manga: Manga, chapters: List<Chapter>) {
        if (chapters.isEmpty() || !autoNightEligible()) return
        if (!nightAutoAllowed(manga)) return
        val loose = chapters.filter { ch ->
            chapterDir(manga, ch)?.takeIf { it.isDirectory }?.let { canStoreNightFiles(it, ch) } == true
        }
        if (loose.isEmpty()) return
        if (!enqueueNight(manga, loose)) return
        publish()
        ensureNightDrain()
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
     * 正在翻（TRANSLATING）改方法 → 設 [stopActiveTranslate]（**不**設 _isTranslatePaused）：當前章停在下一頁邊界、回 QUEUE，
     * drain 立刻重挑、`translateChapter` 以新 [Entry.method] 從 manifest **續傳**——已翻頁（manifest 已記）跳過、
     * 保留舊去字結果；**剩餘頁用新去字**。例：4/14 改 → 1-4 維持舊法、5-14 用新法。（代價：續傳會重載引擎 ~100MB。）
     */
    fun setItemMethod(chapterId: Long, method: String) {
        synchronized(lock) {
            val entry = entries.firstOrNull {
                it.chapter.id == chapterId && !it.nightRender && // 夜讀項無去字法可改；別讓它遮住同章的翻譯項
                    (it.status == TranslationItem.Status.QUEUE || it.status == TranslationItem.Status.TRANSLATING)
            } ?: return
            if (entry.reRenderMethod != null) return // 重繪項方法不可改（reRenderMethod 為 val）
            entry.method = method
            // 正在翻 → 停在頁邊界回 QUEUE、立刻被重挑，以新方法續傳剩餘頁（見上）。只停翻譯那條。
            if (entry.status == TranslationItem.Status.TRANSLATING) {
                stopActiveTranslate = true
            }
        }
        publish()
        persist()
    }

    /**
     * 取消指定章（含正在翻的那項：中止後移除）。
     *
     * @param kind 只移除這種工作（佇列頁的單列 ✕：同章可同時有翻譯項與夜讀項，✕ 只該刪它那一列）；
     *   null＝該章所有種類一起移除（reader「中止這話翻譯」走 [cancelTranslation]，不用 null）。只有被移除的項裡正在跑的才設**它那條**的中止旗標
     *   （[setStop]）——刪掉排隊中的夜讀項不會停同章正在翻的翻譯項，反之亦然。
     */
    fun cancel(chapterIds: List<Long>, kind: TranslationItem.Kind? = null) =
        cancelWhere(chapterIds) { kind == null || it.kind == kind }

    /**
     * reader「中止這話翻譯」：只移除該章的翻譯／重繪項（角落指示器顯示的那些）；同章的夜讀項**不動**——它不是翻譯、
     * 按鈕文案也沒提它。夜讀項若正在跑則記 [Entry.nightRerun]，這一輪做完再跑一輪（同 [enqueueNight] 的 TRANSLATING
     * 分支）：翻到一半被中止的頁已被覆寫、舊夜讀版被 invalidateNightPage 刪掉，再跑一輪 resume 才補得回。
     */
    fun cancelTranslation(chapterIds: List<Long>) {
        val ids = chapterIds.toHashSet()
        synchronized(lock) {
            entries.forEach {
                if (it.nightRender && it.chapter.id in ids && it.status == TranslationItem.Status.TRANSLATING) {
                    it.nightRerun = true
                }
            }
        }
        cancelWhere(chapterIds) { !it.nightRender }
    }

    private fun cancelWhere(chapterIds: List<Long>, match: (Entry) -> Boolean) {
        val ids = chapterIds.toHashSet()
        val removed = synchronized(lock) {
            val hit: (Entry) -> Boolean = { it.chapter.id in ids && match(it) }
            // 正在跑的項被取消 → 它那條消費者的逐頁迴圈下一頁停下、移除
            entries.forEach {
                if (it.status == TranslationItem.Status.TRANSLATING && hit(it)) setStop(it.nightRender, true)
            }
            val removed = entries.filter(hit)
            entries.removeAll(hit)
            pruneOrphanPausedKeysLocked() // 單章取消把某組刪空 → 那組的整本暫停鍵也丟掉
            dropPendingNightForCancelledLocked(removed)
            removed
        }
        publishPaused()
        publish()
        ensureDrain()
        persist()
        releasePendingNight(removed) // 翻譯項被取消、在等它的夜讀標記不再等
    }

    /**
     * 使用者取消了某章的夜讀項（單列 ✕、整本取消、清空）：連「下載完要產生夜讀版」標記一起丟，別等翻完又把它排回來。
     * 在 [lock] 下；呼叫端之後會 [persist]。
     */
    private fun dropPendingNightForCancelledLocked(removed: List<Entry>) {
        removed.forEach { if (it.nightRender) pendingNight.remove(it.chapter.id) }
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

    /**
     * 搶翻：把該組（[mangaId] 在 [night] 那個 pool 的所有章）移到佇列最前、解除其整本暫停與該 pool 的暫停；
     * 若該 pool 的消費者正在跑別本則停在頁邊界回 QUEUE、重挑此組。只動這個 pool——另一個 pool 正在做的不受影響。
     */
    fun startMangaNow(mangaId: Long, night: Boolean) {
        if (!kindEnabled(night)) return
        val key = QueuePoolKey(mangaId, night)
        synchronized(lock) {
            pausedMangaKeys.remove(key)
            val (mine, rest) = entries.partition { it.poolKey == key }
            if (mine.isEmpty()) return
            entries.clear()
            entries.addAll(mine)
            entries.addAll(rest)
            // 這個 pool 正在跑的是別本、且此組有排隊項 → 停那條回 QUEUE（頁邊界），它重挑最前的搶翻組。
            val wantsRun = mine.any { it.status == TranslationItem.Status.QUEUE && !it.paused }
            entries.forEach {
                val runningOther = it.status == TranslationItem.Status.TRANSLATING && it.nightRender == night &&
                    it.manga.id != mangaId
                if (runningOther && wantsRun) setStop(night, true)
            }
        }
        setPoolPaused(night, false)
        publishPaused()
        publish()
        ensureDrain()
        TranslationJob.start(context)
        persist()
    }

    /** 整本暫停（只該 pool 那組）：drain 輪到時跳過、做下一組；該 pool 正在跑此本則停它在頁邊界回 QUEUE。 */
    fun pauseManga(mangaId: Long, night: Boolean) {
        val key = QueuePoolKey(mangaId, night)
        synchronized(lock) {
            pausedMangaKeys.add(key)
            entries.forEach {
                val runningThis = it.status == TranslationItem.Status.TRANSLATING && it.poolKey == key
                if (runningThis) setStop(night, true)
            }
        }
        publishPaused()
        publish()
        ensureDrain() // 重挑下一本（當前若被停會回 QUEUE）
        persist()
        if (!night) releasePendingNight() // 翻譯被停住：在等它的夜讀標記不再等
    }

    /** 解除該組的整本暫停 → 重新納入 drain（明確要求 → 連該 pool 的暫停一起解除；另一個 pool 不動）。 */
    fun resumeManga(mangaId: Long, night: Boolean) {
        if (!kindEnabled(night)) return
        synchronized(lock) { pausedMangaKeys.remove(QueuePoolKey(mangaId, night)) }
        setPoolPaused(night, false)
        publishPaused()
        ensureDrain()
        TranslationJob.start(context)
        persist()
    }

    /** 整本改去字方法（翻譯項；QUEUE 立即生效、TRANSLATING 停頁邊界以新法續傳）。重繪項、夜讀項不動。 */
    fun setMangaMethod(mangaId: Long, method: String) {
        synchronized(lock) {
            entries.filter {
                it.manga.id == mangaId && it.reRenderMethod == null && !it.nightRender &&
                    (it.status == TranslationItem.Status.QUEUE || it.status == TranslationItem.Status.TRANSLATING)
            }.forEach {
                it.method = method
                if (it.status == TranslationItem.Status.TRANSLATING) stopActiveTranslate = true
            }
        }
        publish()
        persist()
    }

    /** 整本取消（只該 pool 那組）：移除該組所有章（含正在跑的：停它那條在頁邊界後移除）、清其整本暫停旗標。 */
    fun cancelManga(mangaId: Long, night: Boolean) {
        val key = QueuePoolKey(mangaId, night)
        val removed = synchronized(lock) {
            entries.forEach {
                val runningThis = it.status == TranslationItem.Status.TRANSLATING && it.poolKey == key
                if (runningThis) setStop(night, true)
            }
            val removed = entries.filter { it.poolKey == key }
            entries.removeAll { it.poolKey == key }
            pausedMangaKeys.remove(key)
            dropPendingNightForCancelledLocked(removed)
            removed
        }
        publishPaused()
        publish()
        ensureDrain()
        persist()
        releasePendingNight(removed) // 翻譯項被取消、在等它的夜讀標記不再等
    }

    /** 整本重試（只該 pool 那組）：該組所有 ERROR 章回 QUEUE、解除該 pool 的暫停（總開關關著會留在 QUEUE 等它再開）。 */
    fun retryManga(mangaId: Long, night: Boolean) {
        if (!anyEnabled()) return
        val key = QueuePoolKey(mangaId, night)
        synchronized(lock) {
            entries.filter { it.poolKey == key && it.status == TranslationItem.Status.ERROR }
                .forEach {
                    it.status = TranslationItem.Status.QUEUE
                    it.paused = false // 明確重試 → 連單章暫停一起解除
                }
        }
        publish()
        setPoolPaused(night, false)
        ensureDrain()
        TranslationJob.start(context)
        persist()
    }

    /** 以「整本 × pool」組為單位拖曳重排：依 UI 給的新組順序重排 entries（每組的章保持內部順序）。 */
    fun reorderGroups(orderedKeys: List<QueuePoolKey>) {
        synchronized(lock) {
            val byKey = entries.groupBy { it.poolKey }
            val seen = LinkedHashSet<QueuePoolKey>()
            val reordered = mutableListOf<Entry>()
            orderedKeys.forEach { key ->
                byKey[key]?.let {
                    reordered.addAll(it)
                    seen.add(key)
                }
            }
            entries.forEach { if (it.poolKey !in seen) reordered.add(it) } // 沒被列到的（保險）接後面
            entries.clear()
            entries.addAll(reordered)
        }
        publish()
        persist()
    }

    /** 重試失敗的章（重新排隊；含夜讀項，總開關關著的那類會留在 QUEUE、等它再開才跑）。 */
    fun retry(chapterIds: List<Long>) {
        if (!anyEnabled()) return // 兩個總開關都關：沒有消費者能動、不重試
        val ids = chapterIds.toHashSet()
        val kinds = synchronized(lock) {
            entries.filter { it.chapter.id in ids && it.status == TranslationItem.Status.ERROR }
                .onEach {
                    it.status = TranslationItem.Status.QUEUE
                    it.paused = false // 明確重試 → 連單章暫停一起解除
                }
                .mapTo(HashSet()) { it.nightRender }
        }
        publish()
        kinds.forEach { setPoolPaused(it, false) } // 重試 → 只解除被重試那類 pool 的暫停、直接開跑
        ensureDrain()
        TranslationJob.start(context)
        persist()
    }

    /**
     * 單章暫停（佇列頁章列的 ⏸）：只 hold 這一項（同章的另一種工作不受影響）。QUEUE → drain 跳過它做下一章；
     * TRANSLATING → 它那條停在頁邊界回 QUEUE、標記留著（已做的頁保留、之後 resume 續補）。不動 pool／整本的暫停。
     */
    fun pauseChapter(chapterId: Long, kind: TranslationItem.Kind) {
        synchronized(lock) {
            entries.filter { it.chapter.id == chapterId && it.kind == kind }.forEach {
                it.paused = true
                if (it.status == TranslationItem.Status.TRANSLATING) setStop(it.nightRender, true)
            }
        }
        publish()
        ensureDrain() // 這條可能正在跑被停的章 → 回 QUEUE 後重挑下一章
        persist()
        if (!kind.night) releasePendingNight() // 翻譯被停住：在等它的夜讀標記不再等
    }

    /** 解除單章暫停（章列的 ▶）：明確要求 → 連該 pool 的暫停一起解除；整本暫停不動（它的鈕就在同一張卡上）。 */
    fun resumeChapter(chapterId: Long, kind: TranslationItem.Kind) {
        if (!kindEnabled(kind.night)) return
        synchronized(lock) {
            entries.filter { it.chapter.id == chapterId && it.kind == kind }.forEach { it.paused = false }
        }
        publish()
        setPoolPaused(kind.night, false)
        if (kind.night) ensureNightDrain() else ensureTranslateDrain()
        TranslationJob.start(context)
        persist()
    }

    /** 清空佇列（含兩條正在跑的章：各自中止後移除）。 */
    fun clearQueue() {
        synchronized(lock) {
            entries.forEach { if (it.status == TranslationItem.Status.TRANSLATING) setStop(it.nightRender, true) }
            // 使用者清空整個佇列＝這些章什麼都不要了：在等翻譯的夜讀標記一起丟（還在下載、不在佇列裡的章不受影響）
            entries.forEach { pendingNight.remove(it.chapter.id) }
            entries.clear()
            pausedMangaKeys.clear() // 組都沒了，整本暫停鍵一起清（殘留會 hold 住下次自動排入的章）
        }
        publishPaused()
        publish()
        persist()
    }

    /**
     * 暫停一個 pool（[night]：true＝夜讀、false＝翻譯／重繪）：該條正在跑的章停在逐頁迴圈下一頁邊界回 QUEUE，
     * 暫停才即時生效。另一個 pool 不受影響（各自的鈕在佇列頁）。
     */
    fun pause(night: Boolean) {
        setPoolPaused(night, true)
        synchronized(lock) { setStop(night, true) }
        persist()
        if (!night) releasePendingNight() // 翻譯被停住：在等它的夜讀標記不再等
    }

    /** 繼續一個 pool。該類總開關關著時不動（維持暫停、待總開關再開）。 */
    fun resume(night: Boolean) {
        if (!kindEnabled(night)) return
        setPoolPaused(night, false)
        if (night) ensureNightDrain() else ensureTranslateDrain()
        TranslationJob.start(context)
        persist()
    }

    /**
     * 翻譯總開關關閉（硬總開關）：中止正在翻的章（停在下一頁邊界、回 QUEUE）；翻譯那條 drain 因 [masterEnabled] gate
     * 退出、引擎於 drain 尾自然釋放。**不**動 [_isTranslatePaused]（總開關與使用者暫停獨立）；佇列保留待總開關再開時由
     * [resumeForMasterOn] 續跑。**不碰夜讀那條**：它獨立於翻譯總開關，還有活就繼續跑、前景服務也跟著留住。
     */
    fun haltForMasterOff() {
        synchronized(lock) { stopActiveTranslate = true }
        // 主動停前景服務（否則殘留「翻譯中」通知；翻譯 drain 自身已被 master gate 擋住不取新章）——但夜讀那條若還有活就別停。
        // 以「切換後」的開關值判斷（設定頁在 callback 之後才寫 pref，此刻讀 pref 可能還是舊值）。
        if (!hasRunnableWork(master = false, night = nightEnabled())) TranslationJob.stop(context)
        // 翻譯不會跑了：在等它的夜讀標記不再等（用切換後的值：偏好此刻可能還是舊值）
        releasePendingNight(masterOn = false)
    }

    /** 翻譯總開關開啟：若有排隊的翻譯／重繪章且未（使用者）暫停 → 續跑翻譯那條（drain 的 master gate 此時已放行）。 */
    fun resumeForMasterOn() {
        val hasQueued = synchronized(lock) {
            entries.any {
                it.status == TranslationItem.Status.QUEUE && !it.nightRender &&
                    it.poolKey !in pausedMangaKeys && !it.paused
            }
        }
        if (hasQueued && !_isTranslatePaused.value) {
            ensureTranslateDrain()
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
                engineService.warmUpAsync(forLive = true)
            }
        } else {
            haltForMasterOff()
            engineService.shutdownAsync()
        }
    }

    /**
     * 夜讀總開關（[TranslationPreferences.nightReadEnabled]）切換的副作用，供「翻譯設定頁」與「More 頁快捷開關」共用
     * （對照 [onMasterEnabledChanged]）：
     *  - 開 → 有排隊的夜讀項且未（使用者）暫停 → 起夜讀那條消費者 + 前景服務（[drainLoopNight] 的 gate 讀 pref 是在
     *    夜讀執行緒上、稍後才發生，呼叫端寫 pref 的先後不影響）。
     *  - 關 → 正在跑的夜讀項停在頁邊界回 QUEUE **留著**（不標 ERROR、不移除）、消費者因 [nightEnabled] gate 退出、
     *    翻完不再自動排（[queueNightAfter]）；翻譯那條若也沒活才停前景服務；設定 › 夜讀 的舊版頁掃描取消。
     * **不**負責 set pref（呼叫端寫）；**不**動 [_isNightPaused]（與使用者暫停獨立）。
     */
    fun onNightEnabledChanged(enabled: Boolean) {
        if (enabled) {
            val hasQueued = synchronized(lock) {
                entries.any {
                    it.status == TranslationItem.Status.QUEUE && it.nightRender &&
                        it.poolKey !in pausedMangaKeys && !it.paused
                }
            }
            if (hasQueued && !_isNightPaused.value) {
                ensureNightDrain()
                TranslationJob.start(context)
            }
        } else {
            synchronized(lock) { stopActiveNight = true }
            // 設定 › 夜讀 的舊版頁掃描也停（大書庫要掃好幾分鐘，關了夜讀就別再佔儲存的 IO）；再開時重掃
            cancelOutdatedNightScan()
            // 以「切換後」的開關值判斷（同 haltForMasterOff）：翻譯那條還有活 → 前景服務留住。
            if (!hasRunnableWork(master = masterEnabled(), night = false)) TranslationJob.stop(context)
        }
    }

    /**
     * 確保兩條消費者都有在跑：翻譯／重繪一條（[ensureTranslateDrain]）、夜讀一條（[ensureNightDrain]）。
     * 各自的 Mutex 保證每種單一消費者；重複呼叫會排隊後再掃一遍（掃到沒事／總開關關就退出）。
     */
    private fun ensureDrain() {
        ensureTranslateDrain()
        ensureNightDrain()
    }

    /** 起翻譯／重繪那條消費者（[scope] 的 IO dispatcher）。 */
    private fun ensureTranslateDrain() {
        scope.launch { drainLoop() }
    }

    /**
     * 起夜讀那條消費者（[scope] 的 IO dispatcher；消費者與逐頁放行都很輕，重活在夜讀執行緒池）。夜讀總開關的 gate 在
     * [runDrain] 的迴圈條件裡（非這裡同步檢查）：總開關剛切換時 pref 可能還沒寫進去（設定頁是 callback 回 true 之後才寫），
     * 稍後讀才準；關著時多起一次也只是取鎖、掃一下、退出，成本可忽略。
     */
    private fun ensureNightDrain() {
        scope.launch { drainLoopNight() }
    }

    /** 翻譯／重繪消費者（[drainMutex] 單消費者）。 */
    private suspend fun drainLoop() = drainMutex.withLock { runDrain(night = false) }

    /**
     * 夜讀消費者（[nightDrainMutex] 單消費者）。不設優先權：頁在夜讀執行緒池上跑，nice 由 [nightGovernor] 依模式設
     * （只有夜讀時 0、翻譯在跑時讓路）。
     */
    private suspend fun drainLoopNight() = nightDrainMutex.withLock { runDrain(night = true) }

    /**
     * 一條消費者的主迴圈：反覆挑該類別（[night]：true＝夜讀項、false＝翻譯／重繪項）的下一個 QUEUE 項跑，
     * 直到沒有、暫停、或該類別的總開關關。兩條的 active entry／進度 publish／persist／錯誤處理完全一致，差別只在：
     * 挑哪類（[pickNextQueuedLocked]）、看哪個中止旗標（[isStop]）、哪個總開關（[kindEnabled]）、成功後做什麼
     * （翻譯→[translatedIds]+書庫徽章+自動排夜讀；夜讀→[nightDoneIds]）、尾聲要不要釋放翻譯引擎（只有翻譯那條）。
     */
    private suspend fun runDrain(night: Boolean) {
        // 翻譯/夜讀都是 CPU 密集（ONNX/NCNN 推論）：螢幕關閉時若不持 WakeLock，CPU 會休眠 → 推論暫停（前景服務只保
        // 「行程不被回收」、不保「CPU 不睡」）。整個 drain 期間持 partial WakeLock、結束（佇列空/暫停/總開關關）即放，
        // 修「前景但關螢幕就停」。兩條各持一把（各自 finally 放）。acquire 逾時只是洩漏保險、正常由 finally 放。
        val wakeLock = context.powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            if (night) WAKELOCK_TAG_NIGHT else WAKELOCK_TAG,
        ).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
        try {
            // 硬總開關（各自）：關時一律不取新章（一道 gate 收掉所有 drain 來源——還原/下載/手動/重繪/自動夜讀）。
            while (!isPoolPaused(night) && kindEnabled(night)) {
                // 挑章、改 TRANSLATING、清這條的中止旗標＝同一把鎖下一氣完成，且鎖內重讀暫停：pause() 是「先寫 paused、
                // 再取鎖設 stop」，若它搶在前面這裡必看到 paused 退出；若它在後面，其 setStop(true) 必落在這裡的清旗標之後
                // （章會在下一頁邊界停）。分兩段鎖時 pause 可落在中間 → 旗標被清掉、整章跑完才停。cancel/clear 同理。
                val entry = synchronized(lock) {
                    if (isPoolPaused(night)) return@synchronized null
                    pickNextQueuedLocked(night)?.also {
                        it.status = TranslationItem.Status.TRANSLATING
                        it.done = 0
                        it.total = 0
                        it.nightRerun = false
                        setStop(night, false)
                    }
                } ?: break
                publish()
                TraceLog.log("queue", "chapter start m=${entry.manga.id} c=${entry.chapter.id} ${entry.chapter.name}")
                val ok = try {
                    // 翻譯／重繪項算進忙碌訊號（夜讀讓路）；夜讀項自己不算
                    if (night) translateOne(entry) else TranslationBusy.track { translateOne(entry) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "翻譯章失敗 ${entry.chapter.name}（原檔保留）" }
                    TraceLog.log("queue", "chapter fail m=${entry.manga.id} c=${entry.chapter.id} ${e.message}")
                    synchronized(lock) {
                        entry.status = TranslationItem.Status.ERROR // 失敗 → 留佇列可重試
                        entry.nightRerun = false // 例外（缺模型等）再跑一輪也一樣，等使用者重試
                    }
                    nightStorage.chapterChanged(entry.manga, entry.chapter) // 做到一半的頁也可能動了夜讀檔
                    publish()
                    persist()
                    if (!night) releasePendingNight() // 翻譯沒做成：在等它的夜讀標記不再等
                    continue
                }
                // 這章的夜讀檔可能變了（夜讀做了頁；翻譯／重繪覆寫頁圖時作廢了舊夜讀版）：設定 › 夜讀「儲存空間」下次只重算這章
                nightStorage.chapterChanged(entry.manga, entry.chapter)
                // 停止判斷與離開佇列在同一把鎖裡：分兩段時，enqueueNight 的 TRANSLATING 分支（設 stop／再跑一輪／強制重做）
                // 可能落在中間，這項卻照樣以成功被移除 → 補頁或強制重做的要求消失、stop 旗標殘留到下次挑章。
                val stopped = synchronized(lock) {
                    val rerun = entry.nightRerun
                    entry.nightRerun = false
                    when {
                        // 被暫停/取消/清空/改法/總開關關打斷：還在佇列(暫停/改法/開關關)→回 QUEUE 等續傳；已被移除(取消/清空)→不動。
                        isStop(night) -> {
                            if (entries.contains(entry)) entry.status = TranslationItem.Status.QUEUE
                            true
                        }
                        // 這一輪跑的時候有頁被作廢（見 [Entry.nightRerun]）→ 不離開佇列、回 QUEUE 再跑一輪補那些頁。成功的話
                        // 下面照樣發「做完」（章節列／reader 先看到這輪做好的頁）；這輪沒做成也再跑一輪（例如單頁章那頁剛好在飛時
                        // 被覆寫、這輪 0 頁），不標 ERROR。
                        rerun && entries.contains(entry) -> {
                            entry.status = TranslationItem.Status.QUEUE
                            false
                        }
                        ok -> {
                            entries.remove(entry) // 真的做成 → 離開佇列
                            // 使用者在閱讀器要的夜讀版做好了、也沒有翻譯在等著覆寫它 → 「下載完要產生夜讀版」標記功成身退
                            if (night) settlePendingNightLocked(entry.chapter.id)
                            false
                        }
                        else -> {
                            // 沒下載 / 沒做成（部分失敗）→ 標 ERROR 留佇列可重試，不誤標「已翻」
                            entry.status = TranslationItem.Status.ERROR
                            false
                        }
                    }
                }
                if (stopped) {
                    publish()
                    persist()
                    if (isPoolPaused(night)) break else continue
                }
                // 翻譯／重繪沒做成（ERROR）：在等它的夜讀標記不再等（[releasePendingNight] 自己會看這章還會不會再翻）
                if (!ok && !night) releasePendingNight()
                if (ok) {
                    if (night) {
                        // 夜讀版不是翻譯：不進 translatedIds、不動書庫「已翻」徽章；只進 nightDoneIds 給章節列夜讀指示器——
                        // 但要真的有檔才算（全章彩頁被「略過彩色頁」跳掉＝成功離開佇列卻一張夜讀版都沒有，別亮 DONE）。
                        if (hasNightPages(entry.manga, entry.chapter)) {
                            _nightDoneIds.update { it + entry.chapter.id }
                        }
                        _nightVersion.update { it + 1 }
                        _nightChapterDone.tryEmit(entry.chapter.id)
                        TraceLog.log("queue", "night done m=${entry.manga.id} c=${entry.chapter.id}")
                    } else {
                        _translatedIds.update { it + entry.chapter.id }
                        translationCache.invalidate(entry.manga.id) // 已翻章數變 → 失效該本、刷新書庫徽章
                        // 頁圖剛被覆寫、舊夜讀版已被 invalidateNightPage 刪掉 → 這章的 session「已有夜讀版」作廢、
                        // 並叫章節列重掃磁碟（否則指示器留在 DONE、夜讀模式卻端出原圖）；接著自動排的夜讀項會再把它做回來。
                        _nightDoneIds.update { it - entry.chapter.id }
                        _nightVersion.update { it + 1 }
                        TraceLog.log("queue", "chapter done m=${entry.manga.id} c=${entry.chapter.id}")
                        // 翻譯/重繪成功 → 開關開著就接一個夜讀項（排佇列尾；重繪後頁圖 mtime 變新，舊夜讀檔會被判過期重做），
                        // 並叫醒夜讀那條（它可能早因沒事做退出了）。
                        if (queueNightAfter(entry)) {
                            TraceLog.log("queue", "night queued m=${entry.manga.id} c=${entry.chapter.id}")
                            ensureNightDrain()
                        }
                    }
                }
                publish()
                persist()
            }
            // 迴圈退出（因「無 QUEUE 項」自然做完，或「總開關關」使 while 條件 false 退出；暫停則走另一分支不到這）：
            // 只有翻譯那條管 warm 引擎（夜讀不用它、模型每章自開自關）：「總開關開 + 即時翻開」才保 warm（reader 隨時要讀
            // 下一章）；總開關關或即時翻關 → 釋放 ~100MB，之後下次 translatePage lazy 重建。shutdown 走服務自己的 Mutex
            // （與 drainMutex 不同鎖、不死鎖）。
            // 例外：這個行程從沒到過前景（背景工作把它叫起來的）→ 使用者沒在看，也放掉（約 1.3–2 GB 原生）；之後使用者真的把
            // app 開到前景，StartupEnginePrewarm 會在第一個畫面出來後再預暖（2026-10-07 審查：背景行程做完佇列一直留著引擎，
            // 等於繞過「背景叫起來不預暖」）。使用者開過 app、只是退到背景的行程照舊留著（讀到下一章不用重載）。
            val liveWarm = masterEnabled() && translationPreferences.liveTranslate.get()
            if (!night && !_isTranslatePaused.value && !(liveWarm && StartupEnginePrewarm.everForeground)) {
                if (liveWarm) TraceLog.log("queue", "engine released reason=background-process")
                engineService.shutdown()
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            // 這條收工（佇列空/暫停/總開關關）且另一條也沒活 → 停背景心跳（沒事做就別每 15 分醒來）。
            if (!hasPendingWork()) TranslationHeartbeatJob.stop(context)
        }
    }

    /**
     * 回傳「該章是否確實處理成」；沒下載/沒做成→false，drain 據此標 ERROR、不誤標已翻。
     *
     * 依 [Entry.nightRender] / [Entry.reRenderMethod] 分三條：
     *  - 夜讀：跑 [PageTranslator.renderNightChapter]（只鬆散夾），成功判準＝跑完後有夜讀版的頁數 >0。
     *    缺夜讀模型時它拋 IllegalStateException、CBZ/非目錄時這裡拋 → 皆由 [drainLoop] 的 catch 標 ERROR、訊息進日誌。
     *  - null＝翻譯：跑 [PageTranslator.translateChapter]，成功判準＝manifest 覆蓋全頁。
     *  - 非 null＝重繪：跑 [PageTranslator.reRenderChapter]（復用素材換去字法），成功判準＝有重繪到頁（count>0）。
     * 三條共用同一組 [onProgress]/[shouldStop]（佇列進度 + 合作式中止對重繪/夜讀一樣生效）；[shouldStop] 讀的是
     * **這項所屬那條消費者**的旗標（夜讀項→[stopActiveNight]、其餘→[stopActiveTranslate]）。
     * 夜讀項的 [onProgress] 會從多條夜讀 worker 呼叫（多頁並行）：它只在 [lock] 內寫進度再 [publish]，本來就可重入。
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
        val night = entry.nightRender
        val shouldStop: () -> Boolean = { isStop(night) }
        if (night) {
            // 夜讀版只支援鬆散夾：夜讀檔要逐檔落在 <章>/.yakuyomi/，CBZ 內取不到（同重繪素材的限制）。
            // 非目錄→拋出（不是靜默回 false）：走 drainLoop 的 catch 標 ERROR 並把原因記進日誌，查得到為何變紅。
            check(dir.isDirectory) { "夜讀只支援鬆散圖夾（CBZ 不支援）：${entry.chapter.name}" }
            // 強制重做：這一輪開跑才定時間（見 [Entry.nightForce]），之後被暫停續跑沿用同一個時間
            val (forceBefore, started) = synchronized(lock) {
                val started = entry.nightForce == NIGHT_FORCE_PENDING
                if (started) entry.nightForce = System.currentTimeMillis()
                entry.nightForce.coerceAtLeast(0L) to started
            }
            if (started) persist()
            return pageTranslator.renderNightChapter(dir, nightGovernor, onProgress, shouldStop, forceBefore) > 0
        }
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
                // 夜讀素材不存：打包跳過 .yakuyomi/、壓縮檔章也不能夜讀，存了白做
                val n = pageTranslator.translateChapter(
                    tmpU,
                    entry.method,
                    onProgress,
                    shouldStop,
                    nightMaterials = false,
                )
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

    /**
     * 已下載章是否已有夜讀版：`<章>/.yakuyomi/` 內有任一頁的完成標記（兩檔的 std 或三檔格式的 l1）或舊版單檔
     * （[NightPages.chapterState] 不是 NONE）。**孤兒（沒有 std 的 more、沒有 l1 的 l2／l3，產生中被殺留下的）不算**——
     * reader 也不認它們，章節列不能亮「已有」。
     * 只鬆散夾（CBZ 不支援夜讀→false）。給章節列的夜讀指示器當**跨重啟的持久「已有」狀態**（本 session 剛做完的由
     * [nightDoneIds] 補），呼叫方式同 [isTranslated]。判「任一頁有」而非「全頁齊」：指示器只需知道這章有沒有夜讀版可看，
     * 補齊由重排負責（resume 只補不新鮮的頁）。IO（findChapterDir + listFiles）→ 呼叫端在 IO 跑。
     */
    fun hasNightPages(manga: Manga, chapter: Chapter): Boolean =
        nightSummary(manga, chapter).state != NightPages.ChapterState.NONE

    /**
     * 已下載章的夜讀摘要（[NightPages.summarize]）：有沒有夜讀版（同 [hasNightPages]）＋有沒有舊規則產生的頁
     * （`outdated`＝章節列的「可更新」、設定頁「重新產生舊版夜讀頁」的計數）。一次列 `.yakuyomi/`，兩件事同一次掃描。
     * 沒下載／不是鬆散夾／沒有素材夾 → NONE、不舊。IO（findChapterDir + listFiles）→ 呼叫端在 IO 跑。
     */
    fun nightSummary(manga: Manga, chapter: Chapter): NightPages.ChapterSummary =
        chapterDir(manga, chapter)?.let(::nightSummaryOf) ?: NO_NIGHT

    /**
     * 章節列一章的夜讀資訊：[loose]＝章節夾是鬆散圖夾（做得了夜讀）；[summary] 同 [nightSummary]。找不到章節夾（沒下載、
     * 本機 epub）或是壓縮檔章 → loose＝false、沒有夜讀版。
     */
    data class ChapterNightInfo(val loose: Boolean, val summary: NightPages.ChapterSummary)

    /** 章節列一章的磁碟狀態：[translated] 同 [isTranslated]、[night] 見 [ChapterNightInfo]。 */
    data class ChapterDiskInfo(val translated: Boolean, val night: ChapterNightInfo)

    /**
     * 章節列用：[isTranslated] 與夜讀資訊（[nightSummary]＋做不做得了夜讀）合在一起，整章只找一次章節夾、問一次
     * isDirectory（SAF 上找章節夾要列目錄、isDirectory 也是一次查詢；分開問的話每個已下載章各做兩次，整列重掃時很可觀）。IO。
     */
    fun chapterDiskInfo(manga: Manga, chapter: Chapter): ChapterDiskInfo {
        val dir = chapterDir(manga, chapter) ?: return ChapterDiskInfo(false, ChapterNightInfo(false, NO_NIGHT))
        return if (dir.isDirectory) {
            ChapterDiskInfo(pageTranslator.isChapterTranslated(dir), ChapterNightInfo(true, looseNightSummaryOf(dir)))
        } else {
            ChapterDiskInfo(archiveHasMarker(dir), ChapterNightInfo(false, NO_NIGHT))
        }
    }

    /** [nightSummary] 的本體：[dir]＝章節夾（不是資料夾＝壓縮檔章 → 沒有夜讀版）。IO。 */
    private fun nightSummaryOf(dir: UniFile): NightPages.ChapterSummary =
        if (dir.isDirectory) looseNightSummaryOf(dir) else NO_NIGHT

    /** [nightSummaryOf] 不再問 isDirectory 的版本：[dir] 已確定是鬆散圖夾。IO。 */
    private fun looseNightSummaryOf(dir: UniFile): NightPages.ChapterSummary {
        val matDir = dir.findFile(NightPages.DIR) ?: return NO_NIGHT
        // 只比檔名、不問 isFile：SAF 上 isFile 是每檔一次 DocumentsContract 查詢，每章都掃會拖慢章節列
        val names = matDir.listFiles()?.mapNotNull { it.name } ?: return NO_NIGHT
        return NightPages.summarize(names)
    }

    /** 設定 › 夜讀「重新產生舊版夜讀頁」的掃描結果：一本書裡有舊版夜讀頁的已下載鬆散章。 */
    data class OutdatedNight(val manga: Manga, val chapters: List<Chapter>)

    /** 設定 › 夜讀「重新產生舊版夜讀頁」那一列的狀態（[outdatedNightScan]）。 */
    sealed interface OutdatedNightScan {
        /** 還沒掃（這次開 app 沒進過設定 › 夜讀，或夜讀總開關剛被關掉）。 */
        data object Idle : OutdatedNightScan

        /** 掃描中：[done]／[total] 本（[total]＝0：還在讀書庫，總數未知）。 */
        data class Scanning(val done: Int, val total: Int) : OutdatedNightScan

        /** 掃完：[targets] 是有舊版夜讀頁的章（空＝都是目前的規則）。 */
        data class Done(val targets: List<OutdatedNight>) : OutdatedNightScan {
            val chapterCount: Int get() = targets.sumOf { it.chapters.size }
        }

        /** 剛把 [count] 話排進佇列（下次掃描前一直顯示這個）。 */
        data class Queued(val count: Int) : OutdatedNightScan

        /** 掃描出錯（例如儲存位置的權限被收回）。 */
        data object Failed : OutdatedNightScan
    }

    private val _outdatedNightScan = MutableStateFlow<OutdatedNightScan>(OutdatedNightScan.Idle)

    /** 設定 › 夜讀「重新產生舊版夜讀頁」的掃描狀態（[refreshOutdatedNight] 更新、[nightRenderOutdated] 排入後改成 Queued）。 */
    val outdatedNightScan: StateFlow<OutdatedNightScan> = _outdatedNightScan.asStateFlow()

    private var outdatedScanJob: Job? = null

    /** 上次掃描開始時的 [nightVersion]（-1＝沒有可沿用的結果）：沒變就沿用上次的結果，不再掃全庫。 */
    private var outdatedScanVersion = -1

    /**
     * 背景掃一次舊版夜讀頁（[scanOutdatedNight]），結果放進 [outdatedNightScan]。設定 › 夜讀 頁**真的打開時**呼叫
     * （不在 getPreferences 裡：設定搜尋也會組它）；使用者點「掃完沒有／排入了／失敗」那一列時以 [force] 呼叫。
     *
     * 掃全庫在大書庫（SAF）上要好幾分鐘，所以**沿用上次的結果**：上次掃完（或排入）之後 [nightVersion] 沒變就不重掃。
     * 舊版頁只會因為這幾件事變少：夜讀做完一章、翻譯／重繪覆寫頁圖（兩者都會讓 [nightVersion] 加 1），或章被刪掉
     * （排入時用下載快取濾掉，見 [nightRenderOutdated]）；同一個行程裡不會變多（規則版本是編譯時的常數），所以沿用的結果
     * 最多多算、不會漏。失敗、或剛被關掉總開關（Idle）一律重掃。
     * 跑在 manager 自己的 scope：離開設定頁不中斷（下次進來直接看結果）；夜讀總開關關掉時取消（[cancelOutdatedNightScan]）。
     * 已在掃就不重開。
     */
    @Synchronized
    fun refreshOutdatedNight(force: Boolean = false) {
        if (outdatedScanJob?.isActive == true) return
        val version = _nightVersion.value
        val current = _outdatedNightScan.value
        val reusable = current is OutdatedNightScan.Done || current is OutdatedNightScan.Queued
        if (!force && reusable && outdatedScanVersion == version) return
        outdatedScanVersion = version
        _outdatedNightScan.value = OutdatedNightScan.Scanning(0, 0)
        outdatedScanJob = scope.launch {
            val result = try {
                val targets = scanOutdatedNight { done, total ->
                    _outdatedNightScan.value = OutdatedNightScan.Scanning(done, total)
                }
                OutdatedNightScan.Done(targets)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "掃描舊版夜讀頁失敗" }
                OutdatedNightScan.Failed
            }
            _outdatedNightScan.value = result
        }
    }

    /** 取消進行中的舊版夜讀頁掃描、丟掉結果（夜讀總開關關掉時：之後再開會重掃）。 */
    @Synchronized
    private fun cancelOutdatedNightScan() {
        outdatedScanJob?.cancel()
        outdatedScanJob = null
        outdatedScanVersion = -1
        _outdatedNightScan.value = OutdatedNightScan.Idle
    }

    /**
     * 設定 › 夜讀「儲存空間」（佔用空間、清除全部／已讀的話），見 [eu.kanade.tachiyomi.data.nightread.NightStorageService]。
     * 跑在 [scope]；佇列狀態（正在產生的章、佇列裡的書）從這裡給，清完由 [onNightStorageCleared] 收尾。
     * 夜讀檔變了的章由 [runDrain]（每章跑完）與 [forgetChapterOutputs]（刪章、重新下載）通知它，下次進設定頁只重算那幾章。
     * 全庫掃描等「重新產生舊版夜讀頁」的掃描跑完才開始（兩個一起掃全庫，SAF 上只會更慢）。
     */
    val nightStorage: NightStorageService = NightStorageService(
        context = context,
        scope = scope,
        runningNightChapters = {
            synchronized(lock) {
                entries.filter { it.nightRender && it.status == TranslationItem.Status.TRANSLATING }
                    .mapTo(HashSet()) { it.chapter.id }
            }
        },
        queuedMangas = { synchronized(lock) { entries.map { it.manga } } },
        chapterDirOf = { manga, chapter -> chapterDir(manga, chapter)?.takeIf { it.isDirectory } },
        awaitOtherScan = { outdatedScanJob?.join() },
        onCleared = ::onNightStorageCleared,
    )

    /**
     * 清掉夜讀檔之後：章節列重掃磁碟（[nightVersion] 加 1）、本 session 的「夜讀做完」作廢——[all]＝全部清除（只留 [keepIds]：
     * 正在產生、沒動的章），否則只拿掉 [clearedIds]。舊版夜讀頁的掃描結果可能還列著剛清掉的章（照它排入會把清掉的又做回來），
     * 有結果就重掃。翻譯素材、原圖、manifest 都沒動。
     */
    private fun onNightStorageCleared(clearedIds: Set<Long>, all: Boolean, keepIds: Set<Long>) {
        _nightDoneIds.update { ids -> if (all) ids.filterTo(HashSet()) { it in keepIds } else ids - clearedIds }
        _nightVersion.update { it + 1 }
        if (_outdatedNightScan.value != OutdatedNightScan.Idle) {
            cancelOutdatedNightScan()
            if (nightEnabled()) refreshOutdatedNight(force = true)
        }
    }

    /**
     * 掃全庫（收藏的書）已下載的鬆散章，找出有舊規則產生的夜讀頁的章（[nightSummary] 的 `outdated`）。只掃不排。
     * 先用下載快取篩掉沒有下載的書（不碰檔案）；有下載的書列一次書的下載夾、照章節夾名對上（不逐章從下載根目錄往下找——
     * SAF 上每次 findFile 都是列一次資料夾），再對每個章節夾列 `.yakuyomi/`。本機來源的書不在下載快取，照章逐一看。
     * 已在夜讀佇列、而且自己會跑的章（產生中，或排隊中又沒被任何一層暫停）不算：它們這一輪本來就會補舊版頁。
     * 失敗待重試的、排隊中但被暫停的（夜讀 pool、這本在夜讀 pool 的整本、或單章暫停）照算——不算的話按鈕說「都是目前的
     * 規則」，那些章卻一直卡著；[nightRenderOutdated] 排入時會連暫停一起解除。
     * 收藏以外、只有下載的書不掃（同 [reRenderAllUpgradable]）。每話都檢查取消（大書庫一本可能有幾千話）。
     * [onProgress]＝(已掃本數, 總本數)。IO。
     */
    suspend fun scanOutdatedNight(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<OutdatedNight> =
        withContext(Dispatchers.IO) {
            val downloadCache: DownloadCache = Injekt.get()
            val selfRunning = synchronized(lock) {
                val poolPaused = _isNightPaused.value
                entries.filter {
                    it.nightRender && when (it.status) {
                        TranslationItem.Status.TRANSLATING -> true
                        TranslationItem.Status.QUEUE -> !poolPaused && !it.paused && it.poolKey !in pausedMangaKeys
                        else -> false
                    }
                }.mapTo(HashSet()) { it.chapter.id }
            }
            val mangas = getFavorites.await()
            val out = ArrayList<OutdatedNight>()
            onProgress(0, mangas.size)
            mangas.forEachIndexed { i, manga ->
                ensureActive()
                val found = when {
                    manga.isLocal() ->
                        getChaptersByMangaId.await(manga.id).filter { ch ->
                            ensureActive()
                            nightSummary(manga, ch).outdated
                        }
                    downloadCache.getDownloadCount(manga) <= 0 -> emptyList()
                    else -> {
                        val source = sourceManager.getOrStub(manga.source)
                        val children = downloadProvider.findMangaDir(manga.title, source)?.listFiles().orEmpty()
                            .mapNotNull { f -> f.name?.let { it to f } }
                            .toMap()
                        if (children.isEmpty()) {
                            emptyList()
                        } else {
                            getChaptersByMangaId.await(manga.id).filter { ch ->
                                ensureActive()
                                val dir = downloadProvider.getValidChapterDirNames(ch.name, ch.scanlator, ch.url)
                                    .firstNotNullOfOrNull { children[it] }
                                dir != null && nightSummaryOf(dir).outdated
                            }
                        }
                    }
                }
                val chapters = found.filter { it.id !in selfRunning }
                if (chapters.isNotEmpty()) out += OutdatedNight(manga, chapters)
                onProgress(i + 1, mangas.size)
            }
            out
        }

    /**
     * 把 [targets]（[scanOutdatedNight] 的結果）全部排入夜讀佇列：每章一個一般夜讀項（不強制——舊版頁本來就不新鮮，
     * 已是新版的頁照樣跳過，見 [PageTranslator.renderNightChapter]）。跟章節列點月亮一樣是使用者明確要求：解除這些章的
     * 單章暫停、這本在夜讀 pool 的整本暫停與夜讀 pool 暫停（確認框有說），照一般夜讀項排在佇列尾、讓路與兩 pool 規則不變。
     * 正在產生的章不動（它這一輪本來就會補舊版頁；[enqueueNight] 的 TRANSLATING 分支會停掉重來，白做）。
     * 掃描結果可能是沿用的（[refreshOutdatedNight]）：掃完之後被刪掉的章用下載快取（記憶體，不碰檔案）濾掉，免得排進去只會
     * 紅一個找不到章的錯誤；本機來源的書不在下載快取，照排。
     * 夜讀總開關關 → 不排、回 0。回傳排入（或已在排隊）的章數。
     */
    fun nightRenderOutdated(targets: List<OutdatedNight>): Int {
        if (!nightEnabled()) return 0
        val downloadCache: DownloadCache = Injekt.get()
        val stillThere = targets.map { t ->
            if (t.manga.isLocal()) {
                t
            } else {
                t.copy(
                    chapters = t.chapters.filter { ch ->
                        downloadCache.isChapterDownloaded(
                            ch.name,
                            ch.scanlator,
                            ch.url,
                            t.manga.title,
                            t.manga.source,
                            false,
                        )
                    },
                )
            }
        }
        var count = 0
        synchronized(lock) {
            val running = entries.filter { it.nightRender && it.status == TranslationItem.Status.TRANSLATING }
                .mapTo(HashSet()) { it.chapter.id }
            stillThere.forEach { t ->
                val chapters = t.chapters.filter { it.id !in running }
                if (chapters.isEmpty()) return@forEach
                enqueueNight(t.manga, chapters, explicit = true)
                count += chapters.size
            }
        }
        if (count == 0) return 0
        publishPaused()
        publish()
        _isNightPaused.value = false
        ensureDrain()
        TranslationJob.start(context)
        persist()
        _outdatedNightScan.value = OutdatedNightScan.Queued(count)
        return count
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

        /** drain 期間持有的 partial WakeLock 標籤（翻譯那條／夜讀那條各一把）+ 逾時上限（洩漏保險；正常由 finally 釋放）。見 [runDrain]。 */
        private const val WAKELOCK_TAG = "yakuyomi:translation"
        private const val WAKELOCK_TAG_NIGHT = "yakuyomi:nightread"
        private const val WAKELOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000

        /** [Entry.nightForce]：不強制。 */
        private const val NIGHT_NO_FORCE = -1L

        /** [Entry.nightForce]：已要求強制重做、下一輪開跑時記下時間。 */
        private const val NIGHT_FORCE_PENDING = 0L

        /** 沒有夜讀版（沒下載／壓縮檔章／沒有素材夾）的摘要。 */
        private val NO_NIGHT = NightPages.ChapterSummary(NightPages.ChapterState.NONE, outdated = false)

        /**
         * 持久化的「下載完要翻／要產生夜讀版」標記還原時的有效期：超過就當作下載早被取消（標記只在下載完成、或這話的夜讀版
         * 做完時才拿掉）。一般下載幾分鐘內就完成；3 天足夠涵蓋「只在 Wi‑Fi 下載」等很久才續傳的情形。
         */
        private const val PENDING_MARK_TTL_MS = 3L * 24 * 60 * 60 * 1000
    }
}
