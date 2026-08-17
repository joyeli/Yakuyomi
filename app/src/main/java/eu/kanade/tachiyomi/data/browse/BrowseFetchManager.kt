package eu.kanade.tachiyomi.data.browse

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

/**
 * Yakuyomi：探索批次擷取的常駐管理器（單一全域槽、不持久化）。
 *
 * 解決原本 `startBatchFetch` 跑在 `viewModelScope`、一離開探索畫面就被取消的問題：擷取改在本單例的
 * process-level scope 跑 → 送出後可離開畫面、前景繼續操作；[BrowseFetchJob] 前景服務保活（背景不被回收）。
 *
 * **單一全域槽 + 忙線硬拒**：同時只跑一份。[start] 在已有任務時回 false（UI 的送出按鈕在 Running 時本就
 * 變身成「進度＋中止」、按不到第二次送出；此為後端保險）。中止＝[cancel]（畫面按鈕 / 通知取消鈕都接這）。
 * 不做跨行程續傳：行程被殺就沒了、重送即可（擷取便宜、可重送）。
 */
class BrowseFetchManager(private val context: Context) {

    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val sourcePreferences: SourcePreferences = Injekt.get()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class State(
        val running: Boolean = false,
        val sourceId: Long = -1L,
        val done: Int = 0,
        val total: Int = 0,
    )

    /** 完成且有失敗時的結果（供對應來源的畫面回看；非該來源不彈）。 */
    data class Result(
        val sourceId: Long,
        val failedIds: List<Long>,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result.asStateFlow()

    private var job: Job? = null

    fun sourceName(sourceId: Long): String = sourceManager.getOrStub(sourceId).name

    /** 送出一份清單到背景擷取。已有任務在跑 → 回 false（忙線，不取代）。 */
    @Synchronized
    fun start(sourceId: Long, mangaList: List<Manga>): Boolean {
        if (_state.value.running || mangaList.isEmpty()) return false

        _state.value = State(running = true, sourceId = sourceId, done = 0, total = mangaList.size)
        job = scope.launch {
            val failed = mutableListOf<Long>()
            // Yakuyomi：本批成功擷取的 url（存進 browseFetchedUrls 作「已擷取」篩選的持久判準；用 url 免疫 initialized clobber）。
            val succeededUrls = mutableSetOf<String>()
            try {
                mangaList.forEachIndexed { i, manga ->
                    if (!isActive) return@forEachIndexed
                    val ok = runCatching {
                        updateMangaFromRemote(manga, fetchDetails = true, fetchChapters = true, manualFetch = false)
                            .isSuccess
                    }.getOrDefault(false)
                    if (ok) succeededUrls.add(manga.url) else failed.add(manga.id)
                    _state.update { it.copy(done = i + 1) }
                    // 節流防 ban：每本 2.0–4.0s（base 2s + 抖動 0–2s）。背景跑、不阻前景 ⇒ 取安全節奏（每本一個
                    // 詳情+章節請求、長清單可達數百，是最該放慢的路徑）。最後一筆不等。
                    if (i < mangaList.lastIndex && isActive) {
                        delay(2000L + Random.nextLong(2000L))
                        // 週期冷卻：每 COOLDOWN_EVERY 本多歇 20–40s，把「連續數百本」打斷成幾波 + 休息（對照自動載入到錨點）。
                        if ((i + 1) % COOLDOWN_EVERY == 0) {
                            delay(COOLDOWN_MIN_MS + Random.nextLong(COOLDOWN_SPAN_MS))
                        }
                    }
                }
            } finally {
                // 持久累積本批成功的 url（併集，含被中止時已抓好的部分）。用 getAndSet 併入、不覆蓋既有集合。
                if (succeededUrls.isNotEmpty()) {
                    sourcePreferences.browseFetchedUrls(sourceId).getAndSet { it + succeededUrls }
                }
                val cancelled = !isActive
                val doneCount = _state.value.done
                val totalCount = _state.value.total
                _state.value = State()
                if (!cancelled) {
                    if (failed.isNotEmpty()) {
                        _result.value = Result(sourceId, failed.toList())
                    }
                    notifyComplete(doneCount, totalCount, failed.size)
                }
                BrowseFetchJob.stop(context)
            }
        }
        BrowseFetchJob.start(context)
        return true
    }

    /** 中止背景擷取（畫面按鈕 / 通知取消鈕 / 換送新清單前）。 */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State()
        BrowseFetchJob.stop(context)
    }

    /** 對應來源的畫面消費完結果後清掉，避免重複彈出。 */
    fun consumeResult() {
        _result.value = null
    }

    private fun notifyComplete(done: Int, total: Int, failedCount: Int) {
        context.notify(Notifications.ID_BROWSE_FETCH_COMPLETE, Notifications.CHANNEL_BROWSE_FETCH) {
            setContentTitle(context.stringResource(MR.strings.browse_fetch_complete_title))
            setContentText(
                if (failedCount > 0) {
                    context.stringResource(MR.strings.browse_fetch_complete_failed, failedCount, total)
                } else {
                    context.stringResource(MR.strings.browse_fetch_complete_ok, total)
                },
            )
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setAutoCancel(true)
        }
    }

    companion object {
        // 週期冷卻：每 COOLDOWN_EVERY 本多歇 [COOLDOWN_MIN_MS, +COOLDOWN_SPAN_MS) 毫秒（20–40s），打斷連續抓取。
        private const val COOLDOWN_EVERY = 10
        private const val COOLDOWN_MIN_MS = 20_000L
        private const val COOLDOWN_SPAN_MS = 20_000L
    }
}
