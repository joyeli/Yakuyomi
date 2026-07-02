package eu.kanade.tachiyomi.data.browse

import android.app.Notification
import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSnapshot
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

/**
 * Yakuyomi：探索「自動載入到錨點」的常駐背景管理器。
 *
 * **設計取捨（實測：某來源約 24 頁 burst 就被 ban）**：一次坐下猛翻幾十頁必被 ban，無論頁間怎麼延遲。唯一可行＝
 * **把請求攤到牆鐘時間**——由 [BrowseAnchorLoadJob]（單一**常駐前景服務**）跑整個迴圈：每批只抓極少頁
 * （[SourcePreferences.browseAnchorChunkPages]，預設 5、頁間 4–6s）、寫進 DB + 存快照/續傳頁、`delay` 隔數十分鐘
 * （[SourcePreferences.browseAnchorIntervalMinutes]，預設 15）再跑下一批，到錨點/到底/被停才結束。來源每個時間窗只看到
 * 5 頁、像一次正常瀏覽。**前景服務**是為了在 vivo/小米這類會殺背景的 OEM 上活得夠久（WorkManager 延遲鏈會被凍結）。
 *
 * - **快照要能顯示 → 每批寫進本地 DB**（[NetworkToLocalManga]，對照 SourcePagingSource）：快照顯示是「對每個 url 查 DB、
 *   查無跳過」，只存 url 不寫 DB 會讓清單遠短於實際筆數。
 * - **跨行程持久**：正在跑哪個來源存 [SourcePreferences.browseAnchorCrawlActive]（單一全域槽）；行程被殺/重開機後
 *   WorkManager 重啟前景服務、[ensureRestored] 補排，從續傳頁接著跑。
 * - **可停**：[cancel]（畫面按鈕 / 通知停止鈕）。續傳頁碼留著，之後再開＝從斷點續。
 * - **被 ban 自保**：連續 [MAX_FAIL_STREAK] 批一頁都抓不到（多半被 ban）→ 停下、發可續通知（附「繼續」鈕）。
 *
 * 只做 Latest listing（自動載入到錨點本就只在「最新」提供）。已有任務時 [start] 回 false。
 */
class BrowseAnchorLoadManager(private val context: Context) {

    private val sourceManager: SourceManager = Injekt.get()
    private val sourcePreferences: SourcePreferences = Injekt.get()
    private val json: Json = Injekt.get()
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get()

    data class State(
        val running: Boolean = false,
        val sourceId: Long = -1L,
        val page: Int = 0,
        val loaded: Int = 0,
    )

    /** 完成結果（供對應來源的畫面回看）。done＝到錨點或到底（已完成、清續傳）；否則＝手動停/連續失敗（可續）。 */
    data class Result(
        val sourceId: Long,
        val loaded: Int,
        val found: Boolean,
        val done: Boolean,
    )

    enum class ChunkOutcome { CONTINUE, STOP }

    // 開機時從持久旗標還原「是否有來源正在背景載入」，讓被殺/重開後畫面仍顯示執行中。
    private val _state = MutableStateFlow(
        sourcePreferences.browseAnchorCrawlActive.get().let { active ->
            if (active != -1L) State(running = true, sourceId = active) else State()
        },
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result.asStateFlow()

    fun sourceName(sourceId: Long): String = sourceManager.getOrStub(sourceId).name

    /** 開始背景載入到錨點。已有任務 / 無錨點 / 來源不可用 → 回 false。第一批立刻跑、之後由 worker 自排。 */
    @Synchronized
    fun start(sourceId: Long, anchorUrl: String): Boolean {
        if (sourcePreferences.browseAnchorCrawlActive.get() != -1L || anchorUrl.isEmpty()) return false
        sourceManager.get(sourceId) ?: return false

        sourcePreferences.browseAnchorCrawlActive.set(sourceId)
        sourcePreferences.browseAnchorFailStreak.set(0)
        val resume = sourcePreferences.browseAnchorResumePage(sourceId).get()
        _state.value =
            State(running = true, sourceId = sourceId, page = resume, loaded = readExistingUrls(sourceId).size)
        // 立刻貼進度通知（第一批要 ~25s 才抓完；不先貼會有一段「按了沒反應」的空窗）。
        notifyProgress(sourceId, resume, _state.value.loaded)
        BrowseAnchorLoadJob.startNow(context)
        return true
    }

    /** 中止（畫面按鈕 / 通知停止鈕）。清旗標、取消排程；續傳頁碼留著 → 之後可從斷點續。 */
    fun cancel() {
        val active = sourcePreferences.browseAnchorCrawlActive.get()
        val loaded = _state.value.loaded
        sourcePreferences.browseAnchorCrawlActive.set(-1L)
        sourcePreferences.browseAnchorFailStreak.set(0)
        BrowseAnchorLoadJob.stop(context)
        context.cancelNotification(Notifications.ID_ANCHOR_LOAD_PROGRESS)
        _state.value = State()
        if (active != -1L) _result.value = Result(active, loaded, found = false, done = false)
    }

    /** app 啟動時呼叫：旗標還在＝上次沒跑完 → 確保 WorkManager 鏈仍在（不重排已排定的那批）。 */
    fun ensureRestored() {
        if (sourcePreferences.browseAnchorCrawlActive.get() != -1L) {
            BrowseAnchorLoadJob.ensureScheduled(context)
        }
    }

    /** 對應來源的畫面消費完結果後清掉。 */
    fun consumeResult() {
        _result.value = null
    }

    /**
     * 修剪快照：砍掉錨點之後（feed 序更舊＝已處理過）的項，讓錨點成為快照最後一筆。
     * 無錨點 / 錨點不在快照 / 錨點已是最後一筆 → 不動。純函式、冪等，任何「快照產生後」或「錨點更新後」都可安全呼叫。
     */
    fun trimSnapshotToAnchor(sourceId: Long) {
        val anchor = sourcePreferences.browseAnchor(sourceId).get()
        if (anchor.isEmpty()) return
        val pref = sourcePreferences.browseSnapshot(sourceId)
        val raw = pref.get()
        if (raw.isEmpty()) return
        val snap = runCatching { json.decodeFromString<BrowseSnapshot>(raw) }.getOrNull() ?: return
        val idx = snap.urls.indexOf(anchor)
        if (idx < 0 || idx == snap.urls.lastIndex) return
        pref.set(json.encodeToString(snap.copy(urls = snap.urls.take(idx + 1))))
    }

    /** 下一批的延遲（分鐘 → 毫秒，±20% 抖動；夾在合理範圍）。 */
    fun nextDelayMs(): Long {
        val minutes = sourcePreferences.browseAnchorIntervalMinutes.get().coerceIn(MIN_INTERVAL_MIN, MAX_INTERVAL_MIN)
        val base = minutes * 60_000L
        return (base * (0.8 + Random.nextDouble() * 0.4)).toLong()
    }

    /**
     * 跑一批（由 [BrowseAnchorLoadJob] 呼叫）。抓 chunkPages 頁、去重累積、偵測錨點、存快照+續傳頁。
     * 回 [ChunkOutcome.CONTINUE]＝還沒完成，worker 應排下一批；[ChunkOutcome.STOP]＝已完成/放棄/未在執行，不再排。
     */
    suspend fun runChunk(): ChunkOutcome {
        val sourceId = sourcePreferences.browseAnchorCrawlActive.get()
        if (sourceId == -1L) return ChunkOutcome.STOP
        val source = sourceManager.get(sourceId)
        val anchorUrl = sourcePreferences.browseAnchor(sourceId).get()
        if (source == null || anchorUrl.isEmpty()) {
            finishDone(sourceId, loaded = _state.value.loaded, found = false)
            return ChunkOutcome.STOP
        }

        val urls = LinkedHashSet(readExistingUrls(sourceId))
        val resume = sourcePreferences.browseAnchorResumePage(sourceId).get()
        val chunkPages = sourcePreferences.browseAnchorChunkPages.get().coerceIn(1, MAX_CHUNK_PAGES)
        // 續傳重疊：往回退幾頁吸收 feed 位移。**必須 < 每批頁數**，否則重疊吃光整批進度 → 原地打轉（曾把 5 頁配 5 重疊＝淨 0）。
        val overlap = RESUME_OVERLAP.coerceAtMost(chunkPages - 1)
        var page = if (resume > 0) (resume - overlap).coerceAtLeast(1) else 1

        var found = false
        var reachedEnd = false
        var fetched = 0
        _state.value = State(running = true, sourceId = sourceId, page = page, loaded = urls.size)

        for (i in 0 until chunkPages) {
            val mangasPage = runCatching { source.getLatestUpdates(page) }.getOrNull() ?: break
            if (mangasPage.mangas.isEmpty()) {
                reachedEnd = true
                break
            }
            // 寫進本地 DB（快照顯示是「對每個 url 查 DB、查無跳過」→ 不寫入＝清單遠短於實際）。對照 SourcePagingSource.load()。
            runCatching { networkToLocalManga(mangasPage.mangas.map { it.toDomainManga(sourceId) }) }
            for (m in mangasPage.mangas) {
                urls.add(m.url)
                if (m.url == anchorUrl) found = true
            }
            fetched++
            _state.update { it.copy(page = page, loaded = urls.size) }
            if (found) break
            if (!mangasPage.hasNextPage) {
                reachedEnd = true
                break
            }
            page++
            // 頁間節流 4–6s（配合每批小頁數 → 一整批像一次正常瀏覽）。本批最後一頁後不必再等。
            if (i < chunkPages - 1) delay(PAGE_DELAY_MIN_MS + Random.nextLong(PAGE_DELAY_SPAN_MS))
        }

        val done = found || reachedEnd
        if (urls.isNotEmpty()) {
            sourcePreferences.browseSnapshot(sourceId)
                .set(json.encodeToString(BrowseSnapshot(nowMs(), urls.toList())))
        }
        sourcePreferences.browseAnchorResumePage(sourceId).set(if (done) 0 else page)

        // 整批一頁都沒抓到（多半被 ban）：累計失敗連擊，達上限就停下、通知可續。
        if (fetched == 0 && !done) {
            val streak = sourcePreferences.browseAnchorFailStreak.get() + 1
            sourcePreferences.browseAnchorFailStreak.set(streak)
            if (streak >= MAX_FAIL_STREAK) {
                finishPaused(sourceId, urls.size)
                return ChunkOutcome.STOP
            }
            notifyProgress(sourceId, page, urls.size)
            return ChunkOutcome.CONTINUE
        }
        sourcePreferences.browseAnchorFailStreak.set(0)

        if (done) {
            finishDone(sourceId, urls.size, found)
            return ChunkOutcome.STOP
        }
        _state.value = State(running = true, sourceId = sourceId, page = page, loaded = urls.size)
        notifyProgress(sourceId, page, urls.size)
        return ChunkOutcome.CONTINUE
    }

    /** 已完成（到錨點或到底）：清旗標/續傳、關進度通知、發完成通知。 */
    private fun finishDone(sourceId: Long, loaded: Int, found: Boolean) {
        sourcePreferences.browseAnchorCrawlActive.set(-1L)
        sourcePreferences.browseAnchorFailStreak.set(0)
        sourcePreferences.browseAnchorResumePage(sourceId).set(0)
        // 先修剪（砍掉錨點之後的更舊項），再用修剪後的實際筆數回報 → 通知/toast 的「已載入 XX 本」對得上快照。
        trimSnapshotToAnchor(sourceId)
        val finalLoaded = readExistingUrls(sourceId).size.takeIf { it > 0 } ?: loaded
        context.cancelNotification(Notifications.ID_ANCHOR_LOAD_PROGRESS)
        _state.value = State()
        _result.value = Result(sourceId, finalLoaded, found, done = true)
        notifyComplete(sourceId, finalLoaded, found, done = true)
    }

    /** 連續失敗放棄（可續）：清旗標、關進度通知、發帶「繼續」鈕的通知；續傳頁碼留著。 */
    private fun finishPaused(sourceId: Long, loaded: Int) {
        sourcePreferences.browseAnchorCrawlActive.set(-1L)
        sourcePreferences.browseAnchorFailStreak.set(0)
        context.cancelNotification(Notifications.ID_ANCHOR_LOAD_PROGRESS)
        _state.value = State()
        _result.value = Result(sourceId, loaded, found = false, done = false)
        notifyComplete(sourceId, loaded, found = false, done = false)
    }

    /** 讀既有快照的 url（累積用；解析失敗＝空）。 */
    private fun readExistingUrls(sourceId: Long): List<String> {
        val raw = sourcePreferences.browseSnapshot(sourceId).get()
        if (raw.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString<BrowseSnapshot>(raw).urls }.getOrDefault(emptyList())
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    /** 前景服務用：依目前 state 建進度通知（給 [BrowseAnchorLoadJob.getForegroundInfo]）。 */
    fun progressNotification(): Notification {
        val s = _state.value
        return buildProgressNotification(s.sourceId, s.page, s.loaded)
    }

    private fun buildProgressNotification(sourceId: Long, page: Int, loaded: Int): Notification =
        context.notificationBuilder(Notifications.CHANNEL_BROWSE_FETCH) {
            setContentTitle(context.stringResource(MR.strings.browse_anchor_load_running_title))
            setContentText(
                context.stringResource(
                    MR.strings.browse_anchor_load_running_text,
                    sourceName(sourceId),
                    page,
                    loaded,
                ),
            )
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
            setOnlyAlertOnce(true)
            addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.stringResource(MR.strings.action_cancel),
                NotificationReceiver.cancelAnchorLoadPendingBroadcast(context),
            )
        }.build()

    /** 更新執行中的進度通知（與前景服務同一個 id → 直接刷新前景通知；附「停止」鈕）。 */
    private fun notifyProgress(sourceId: Long, page: Int, loaded: Int) {
        context.notificationManager.notify(
            Notifications.ID_ANCHOR_LOAD_PROGRESS,
            buildProgressNotification(sourceId, page, loaded),
        )
    }

    private fun notifyComplete(sourceId: Long, loaded: Int, found: Boolean, done: Boolean) {
        context.notify(Notifications.ID_ANCHOR_LOAD_COMPLETE, Notifications.CHANNEL_BROWSE_FETCH) {
            setContentTitle(context.stringResource(MR.strings.browse_anchor_load_complete_title))
            setContentText(
                when {
                    found -> context.stringResource(MR.strings.browse_anchor_load_complete_found, loaded)
                    done -> context.stringResource(MR.strings.browse_anchor_load_complete_end, loaded)
                    else -> context.stringResource(MR.strings.browse_anchor_load_paused, loaded)
                },
            )
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setAutoCancel(true)
            // 可續（連續失敗放棄、未到錨點）→ 通知加「繼續」鈕，直接從通知續爬（不用開 app）。
            if (!done) {
                addAction(
                    android.R.drawable.ic_media_play,
                    context.stringResource(MR.strings.action_continue_auto_load),
                    NotificationReceiver.continueAnchorLoadPendingBroadcast(context, sourceId),
                )
            }
        }
    }

    companion object {
        // 每批頁數上限（保護：設定值再大也不超過）。
        private const val MAX_CHUNK_PAGES = 20

        // 續傳重疊：續抓時往回退幾頁吸收 feed 位移。對「最新」feed（新章從頭插）續同頁本就自然重疊、不會漏，故取 1 即足；
        // 執行時再夾成 < 每批頁數（見 runChunk 的 overlap）保證前進。
        private const val RESUME_OVERLAP = 1

        // 連續幾批一頁都抓不到（多半被 ban）就停下、通知可續。
        private const val MAX_FAIL_STREAK = 3

        // 頁間節流：~1s（1.0–1.5s，保留輕微抖動避免固定心跳）。實測某來源以此配 5頁/1分跑通 3000+ 本。
        private const val PAGE_DELAY_MIN_MS = 1_000L
        private const val PAGE_DELAY_SPAN_MS = 500L

        // 批次間隔可設定範圍（分鐘）。下限 1：再短就接近連續 burst、失去攤時間防 ban 的意義。
        private const val MIN_INTERVAL_MIN = 1
        private const val MAX_INTERVAL_MIN = 240
    }
}
