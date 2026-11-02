package eu.kanade.tachiyomi.data.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 合併處理一輪的排程（「翻譯＋夜讀」一條線，v2）。不碰 Android：[PageTranslator.translateChapterCombined] 把它接到 UniFile、
 * 翻譯引擎與 NightPump，JVM 測試（CombinedLineTest）用假的翻譯與夜讀直接跑。
 *
 * 每頁照 [plans] 走（[CombinedNight.planPage]）：
 *  - [CombinedNight.PagePlan.DONE]：不取線位，開頭就算完整輸出。
 *  - [CombinedNight.PagePlan.NIGHT]：取線位 → 直接送夜讀段。
 *  - [CombinedNight.PagePlan.TRANSLATE]：取線位 → 取翻譯併發閘（[translateDepth]）→ [translate] → 定案的頁送夜讀段。
 *
 * **線位**（[slotCount]）：每頁從取線位佔到完整輸出（夜讀結果確定）或確定不往下走（翻譯失敗、讀不出來、過期、夜讀段關了）。
 * 翻譯因此最多領先夜讀「線位數 − 夜讀同時頁數」頁，被 hold 著等夜讀的頁數有上限。每個取過線位的頁恰好放一次（[releaseSlot]
 * 冪等）；夜讀段關掉時被它佔著的線位立刻全部放掉；主迴圈取線位前、等線位時、取到後都看 [jobStop]，所以不會卡死。
 *
 * **夜讀段**（[NightStage]）：開跑就 launch（模型組在章開頭開、跟第一頁翻譯重疊），頁靠 [NightStage.submit] 送進去，結果由
 * 呼叫端從夜讀 worker 回報 [nightFinal]／[nightStale]。夜讀段在這些情況**放棄**（之後的頁落地即算完整輸出、完成數改算翻譯
 * 清單頁、落地不再 hold，見 [holdAllowed]）：[nightWanted] 變 false（夜讀總開關關、工作被取消）、夜讀段自己提早結束或拋例外
 * （[onNightFailed]）。工作被要求停（[jobStop]）時夜讀段也收工、放掉線位，但**不算放棄**：在飛的翻譯照樣落地、照樣 hold
 * （那幾頁的夜讀下一輪先做），完成數不改算。
 *
 * **收尾**（翻譯階段的頁都送出、在飛的翻譯都結束之後）：先 [afterTranslatePhase]（呼叫端寫翻譯錯誤檔與統計），沒被要求停而且
 * 夜讀段還開著時：q4（[CombinedNight.q4Pages]：翻譯失敗的頁用原圖做夜讀版，整話全失敗不做；夜讀版本來就新鮮的頁不重做、
 * 直接算完整輸出）→ 等夜讀段閒下來、補掃
 * （[CombinedNight.sweepPages]，最多 [CombinedNight.MAX_SWEEPS] 次）→ 關掉夜讀段的頁來源、等它把手上的做完。
 *
 * 鎖的順序：這裡的 [lock] → 呼叫端的進度回呼（[onProgress] 在鎖內呼叫，保證進度不倒退）。其他回呼都在鎖外。
 */
class CombinedLine<P : Any>(
    /** 章內順序的頁。 */
    private val pages: List<P>,
    private val plans: Map<P, CombinedNight.PagePlan>,
    /** 線位數（[CombinedNight.slots]）。 */
    private val slotCount: Int,
    /** 同時在翻譯的頁數上限（去字法決定的流水線深度）。 */
    translateDepth: Int,
    /** 翻譯一頁（進場檢查、解碼、引擎、落地）。拋一般例外＝[TranslateOutcome.FAILED]。 */
    private val translate: suspend (P) -> TranslateOutcome,
    /** 工作要停（暫停、取消、重挑、總開關）：主迴圈停在頁邊界、夜讀段在下一個檢查點停。 */
    private val jobStop: () -> Boolean,
    /** 夜讀還要不要做（佇列端：這一輪夜讀階段開著、工作還在佇列、夜讀總開關開）。 */
    private val nightWanted: () -> Boolean,
    /** 這一輪收尾要多補掃一次（讀了就清）。 */
    private val sweepRequested: () -> Boolean,
    /** 現在的翻譯清單（q4 與補掃用；IO）。 */
    private val manifestPages: () -> Set<P>,
    /** 現在夜讀版新鮮的頁（補掃用；IO）。 */
    private val nightFreshPages: () -> Set<P>,
    /** 翻譯階段結束（在飛的翻譯都落地）之後、夜讀收尾之前呼叫一次。 */
    private val afterTranslatePhase: suspend () -> Unit,
    /** 完整輸出頁數／總頁數。 */
    private val onProgress: (done: Int, total: Int) -> Unit,
    /** 夜讀段拋例外放棄（例如兩組模型都開不起來）：這一輪後面只翻譯。 */
    private val onNightFailed: (Throwable) -> Unit,
    private val log: (String) -> Unit = {},
) {
    /** 一頁翻譯的結果（呼叫端把引擎與落地的結果歸成這幾種）。 */
    enum class TranslateOutcome {
        /** 翻譯定案（落地、無字略過、太大略過、別的路徑已落地）：接著做夜讀。 */
        SETTLED,

        /** 翻譯定案而且頁檔沒變、夜讀版本來就新鮮：直接算完整輸出。 */
        SETTLED_FRESH,

        /** 翻譯失敗（引擎失敗、例外）：不送夜讀，整話翻完後看 q4。 */
        FAILED,

        /** 讀不出來、中斷復原失敗：不送夜讀，q4 也不補。 */
        UNRELIABLE,

        /** 沒開始（被要求停、找不到頁）。 */
        NOT_RUN,
    }

    /** 夜讀段（呼叫端實作：PageTranslator 接 NightPump＋ChannelNightFeed；測試用假的）。 */
    interface NightStage<P> {
        /** 送一頁（不等待）。回 false＝收不了（頁來源已關、找不到頁）。 */
        fun submit(page: P): Boolean

        /** 不會再送頁：把手上的做完就結束。 */
        fun close()

        /** 夜讀段本體：跑到結束（頁來源關了而且做完、[shouldStop] 成立、或放棄拋例外）。 */
        suspend fun run(shouldStop: () -> Boolean)
    }

    /** 一輪的結果。 */
    data class LineResult(
        /** 夜讀段開到最後（沒放棄、沒被關、沒被要求停，送出的頁都有結果）。 */
        val stageCompleted: Boolean,
        /** 夜讀段拋例外放棄。 */
        val stageGaveUp: Boolean,
        /** 夜讀 DONE＋SKIPPED。 */
        val nightDone: Int,
        val nightFailed: Int,
        /** 完整輸出頁數。 */
        val output: Int,
        /** 同時佔著線位的最多頁數（診斷與測試用）。 */
        val maxHeld: Int,
    )

    private val lock = Any()

    /** 線位放掉、夜讀結果、夜讀段關掉時遞增：等待者比對後重查條件（另有 [POLL_MS] 輪詢兜底停止與夜讀開關）。 */
    private val signal = MutableStateFlow(0L)
    private val translateGate = Semaphore(translateDepth.coerceAtLeast(1))
    private val total = pages.size

    // 以下都在 [lock] 下存取
    private val held = HashSet<P>()
    private val inNight = HashSet<P>()
    private val output = HashSet<P>()
    private val settled = HashSet<P>()
    private val failed = HashSet<P>()
    private val unreliable = HashSet<P>()
    private val nightFailedPages = HashSet<P>()
    private val nightSkippedPages = HashSet<P>()
    private var nightDone = 0
    private var nightFailed = 0
    private var maxHeld = 0

    @Volatile
    private var nightOpen = false

    @Volatile
    private var stageGaveUp = false

    /** 這一輪有夜讀段（[run] 帶了 [NightStage]）。 */
    @Volatile
    private var nightStarted = false

    /** 夜讀段被放棄（夜讀不要了、拋例外、自己提早結束）；被要求停而收工不算。 */
    @Volatile
    private var nightAbandoned = false

    /** 這一輪的頁都送完了、是本類別主動關掉頁來源（之後夜讀段結束不算「提早結束」）。 */
    @Volatile
    private var closedByLine = false

    /** 夜讀段還開著（還在收頁）。 */
    val isNightOpen: Boolean get() = nightOpen

    /**
     * 落地的頁可以 hold（落地時要不要 hold 的依據之一，另一半是佇列端的夜讀還要不要）：這一輪有夜讀段、而且沒被放棄。
     * 工作被要求停（暫停、重挑）時照樣可以——在飛的翻譯落地時那頁的夜讀還沒做，下一輪會先做它，閱讀器在那之前繼續送原圖。
     */
    val holdAllowed: Boolean get() = nightStarted && !nightAbandoned

    /** 目前佔著線位的頁數。 */
    val heldCount: Int get() = synchronized(lock) { held.size }

    /** 跑完這一輪。[night]＝null：只翻譯（行為同只翻譯的輪，只是用線位排程）。 */
    suspend fun run(night: NightStage<P>?): LineResult = coroutineScope {
        synchronized(lock) {
            for (p in pages) if (plans[p] == CombinedNight.PagePlan.DONE) output += p
            nightOpen = night != null
            nightStarted = night != null
            reportLocked()
        }
        val nightJob = night?.let { n -> launch { runNight(n) } }
        val translations = ArrayList<Job>()
        for (p in pages) {
            val plan = plans[p] ?: continue
            if (plan == CombinedNight.PagePlan.DONE) continue
            if (jobStop()) break
            checkNight()
            if (!acquireSlot(p)) break
            if (jobStop()) {
                releaseSlot(p)
                break
            }
            when (plan) {
                CombinedNight.PagePlan.NIGHT -> handToNight(p, night)
                CombinedNight.PagePlan.TRANSLATE -> {
                    translateGate.acquire()
                    if (jobStop()) {
                        translateGate.release()
                        releaseSlot(p)
                        break
                    }
                    translations += launch {
                        val outcome = try {
                            translate(p)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            log("$p translate EXCEPTION ${t.javaClass.simpleName}")
                            TranslateOutcome.FAILED
                        } finally {
                            translateGate.release()
                        }
                        onTranslated(p, outcome, night)
                    }
                }
                CombinedNight.PagePlan.DONE -> Unit
            }
        }
        translations.joinAll()
        afterTranslatePhase()
        if (night != null) {
            if (!jobStop() && nightAlive()) tail(night)
            closedByLine = true
            runCatching { night.close() }
            nightJob?.join()
        }
        synchronized(lock) {
            LineResult(
                stageCompleted = night != null && nightOpen && !stageGaveUp && !jobStop() && inNight.isEmpty(),
                stageGaveUp = stageGaveUp,
                nightDone = nightDone,
                nightFailed = nightFailed,
                output = output.size,
                maxHeld = maxHeld,
            )
        }
    }

    /** 一頁的夜讀結果確定（從夜讀 worker 呼叫）：算完整輸出、放線位。 */
    fun nightFinal(page: P, result: NightPageResult) {
        synchronized(lock) {
            inNight -= page
            when (result) {
                NightPageResult.DONE -> nightDone++
                NightPageResult.SKIPPED -> {
                    nightDone++
                    nightSkippedPages += page
                }
                NightPageResult.FAILED -> {
                    nightFailed++
                    nightFailedPages += page
                }
            }
            output += page
            reportLocked()
        }
        releaseSlot(page)
        bump()
    }

    /** 一頁夜讀過期（產生期間頁被覆寫；從夜讀 worker 呼叫）：不算完成、放線位，收尾補掃再送。 */
    fun nightStale(page: P) {
        synchronized(lock) { inNight -= page }
        releaseSlot(page)
        bump()
    }

    private fun onTranslated(p: P, outcome: TranslateOutcome, night: NightStage<P>?) {
        when (outcome) {
            TranslateOutcome.SETTLED -> {
                synchronized(lock) { settled += p }
                checkNight()
                handToNight(p, night)
            }
            TranslateOutcome.SETTLED_FRESH -> {
                synchronized(lock) { settled += p }
                settleWithoutNight(p)
            }
            TranslateOutcome.FAILED -> {
                synchronized(lock) { failed += p }
                releaseSlot(p)
            }
            TranslateOutcome.UNRELIABLE -> {
                synchronized(lock) { unreliable += p }
                releaseSlot(p)
            }
            TranslateOutcome.NOT_RUN -> releaseSlot(p)
        }
    }

    /**
     * 佔著線位的頁交給夜讀段；夜讀段放棄了（或收不了）→ 直接算完整輸出、放線位。夜讀段是因為工作被要求停而收工的
     * → 只放線位、不算完整輸出（這頁的夜讀下一輪做）。
     */
    private fun handToNight(p: P, night: NightStage<P>?) {
        val give = synchronized(lock) {
            if (night != null && nightOpen) {
                inNight += p
                true
            } else {
                false
            }
        }
        if (give && runCatching { night!!.submit(p) }.getOrDefault(false)) return
        if (give) synchronized(lock) { inNight -= p }
        if (night != null && !nightAbandoned && jobStop()) {
            releaseSlot(p)
            return
        }
        settleWithoutNight(p)
    }

    private fun settleWithoutNight(p: P) {
        synchronized(lock) {
            output += p
            reportLocked()
        }
        releaseSlot(p)
    }

    /** 收尾送夜讀的頁（q4、補掃）：不佔線位（翻譯已經做完，不必再限領先）。 */
    private fun submitTail(p: P, night: NightStage<P>) {
        val give = synchronized(lock) {
            if (nightOpen && p !in inNight) {
                inNight += p
                true
            } else {
                false
            }
        }
        if (!give) return
        if (!runCatching { night.submit(p) }.getOrDefault(false)) {
            synchronized(lock) { inNight -= p }
            bump()
        }
    }

    private suspend fun acquireSlot(p: P): Boolean {
        while (true) {
            val seen = signal.value
            synchronized(lock) {
                if (held.size < slotCount) {
                    held += p
                    if (held.size > maxHeld) maxHeld = held.size
                    return true
                }
            }
            if (jobStop()) return false
            checkNight()
            withTimeoutOrNull(POLL_MS) { signal.first { it != seen } }
        }
    }

    /** 放這頁的線位（沒佔著＝什麼都不做，所以可重複呼叫）。 */
    private fun releaseSlot(p: P) {
        val released = synchronized(lock) { held.remove(p) }
        if (released) bump()
    }

    private fun bump() = signal.update { it + 1 }

    /** 夜讀段開著、但夜讀已經不要了 → 放棄。 */
    private fun checkNight() {
        if (nightOpen && !nightWanted()) closeNight("night off", abandon = true)
    }

    private fun nightAlive(): Boolean {
        checkNight()
        return nightOpen
    }

    /**
     * 關掉夜讀段（冪等）：之後的頁不再送夜讀；被它佔著的線位立刻全部放掉。[abandon]＝放棄（夜讀不要了、拋例外、提早結束）：
     * 之後的頁落地即算完整輸出、落地不再 hold（[holdAllowed]），完成數改算翻譯清單頁（開頭就在清單裡的頁＋這一輪定案的頁）。
     * 不是放棄（工作被要求停）：完成數不動，這一輪反正要結束了。
     */
    private fun closeNight(why: String, abandon: Boolean) {
        val freed = synchronized(lock) {
            if (abandon) nightAbandoned = true
            if (!nightOpen) return
            nightOpen = false
            if (abandon) {
                output += settled
                for (p in pages) if (plans[p] == CombinedNight.PagePlan.NIGHT) output += p
            }
            val r = inNight.toList()
            inNight.clear()
            if (abandon) reportLocked()
            r
        }
        freed.forEach(::releaseSlot)
        bump()
        log("night stage closed why=$why abandon=$abandon released=${freed.size}")
    }

    private suspend fun runNight(n: NightStage<P>) {
        try {
            n.run(shouldStop = { jobStop() || !nightAlive() })
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            stageGaveUp = true
            log("night stage failed ${t.javaClass.simpleName}")
            closeNight("failed", abandon = true)
            onNightFailed(t)
        } finally {
            // 頁還沒送完、夜讀段就結束了：放掉它佔著的線位，主迴圈別卡著等。被要求停而結束的不算放棄（在飛的翻譯照樣 hold）；
            // 沒被要求停卻自己結束（不該發生）＝放棄
            if (!closedByLine) {
                val stopped = jobStop()
                closeNight(if (stopped) "stopped" else "ended", abandon = !stopped)
            }
        }
    }

    /** q4 與補掃（翻譯階段結束之後，夜讀段還開著時）。 */
    private suspend fun tail(night: NightStage<P>) {
        val manifestNow = runCatching { manifestPages() }.getOrDefault(emptySet())
        val (failedInOrder, unreliableNow) = synchronized(lock) {
            pages.filter { it in failed } to HashSet(unreliable)
        }
        val q4All = CombinedNight.q4Pages(failedInOrder, unreliableNow, manifestNow.size)
        // 翻譯失敗的頁檔沒變：夜讀版本來就新鮮（上一輪 q4 做過、或分開模式做過）的不再送，直接算完整輸出（否則每次重試又失敗
        // 都白做一頁夜讀、閱讀器那頁又閃一下）
        val freshNow = if (q4All.isEmpty()) emptySet() else runCatching { nightFreshPages() }.getOrDefault(emptySet())
        val (q4Fresh, q4) = q4All.partition { it in freshNow }
        if (q4Fresh.isNotEmpty()) {
            synchronized(lock) {
                output += q4Fresh
                reportLocked()
            }
            log("q4 already fresh n=${q4Fresh.size}")
        }
        if (q4.isNotEmpty()) {
            log("q4 night from original n=${q4.size}")
            q4.forEach { submitTail(it, night) }
        } else if (failedInOrder.isNotEmpty() && q4Fresh.isEmpty()) {
            log("q4 skipped failed=${failedInOrder.size} manifest=${manifestNow.size}")
        }
        var sweeps = 0
        var lastPicked = false
        while (sweeps < CombinedNight.MAX_SWEEPS) {
            if (!awaitNightIdle()) return
            val requested = sweepRequested()
            if (sweeps > 0 && !requested && !lastPicked) break
            sweeps++
            val manifest = runCatching { manifestPages() }.getOrNull() ?: break
            val fresh = runCatching { nightFreshPages() }.getOrNull() ?: break
            val (inFlight, noRetry) = synchronized(lock) {
                HashSet(inNight) to (nightFailedPages + nightSkippedPages)
            }
            // 這一輪已有結果的彩頁略過也不再送（它本來就沒有夜讀檔，會一直看起來不新鮮）
            val picks = CombinedNight.sweepPages(pages, manifest, fresh, inFlight, noRetry)
            log("sweep #$sweeps picks=${picks.size} requested=$requested")
            picks.forEach { submitTail(it, night) }
            lastPicked = picks.isNotEmpty()
        }
    }

    /** 等夜讀段手上的頁都有結果（或過期）。被要求停、夜讀段關了 → false。 */
    private suspend fun awaitNightIdle(): Boolean {
        while (true) {
            val seen = signal.value
            if (jobStop() || !nightAlive()) return false
            if (synchronized(lock) { inNight.isEmpty() }) return true
            withTimeoutOrNull(POLL_MS) { signal.first { it != seen } }
        }
    }

    /** 在 [lock] 下：報進度。 */
    private fun reportLocked() {
        onProgress(output.size, total)
    }

    companion object {
        /** 等待者看停止旗標與夜讀開關的間隔。 */
        const val POLL_MS = 250L
    }
}
