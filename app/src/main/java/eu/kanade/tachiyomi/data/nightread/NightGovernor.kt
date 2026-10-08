package eu.kanade.tachiyomi.data.nightread

import eu.kanade.tachiyomi.data.nightread.NightConcurrency.MB
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import li.joye.yakuyomi.engine.NcnnLowPriorityHook
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** 夜讀一章用的模型組：LOCKED＝ncnn 預設緒數、進全域鎖（翻譯一來就讓它先）；FREE＝1 緒、不進鎖（從不擋翻譯）。 */
enum class NetKind(val tag: String) { LOCKED("L"), FREE("F") }

/**
 * 夜讀的自適應併發控制（[eu.kanade.tachiyomi.data.translation.TranslationManager] 持有一份）。
 *
 * **兩種模式**，由翻譯忙碌訊號 [busy] 決定：
 *  - [Mode.EXCLUSIVE]：只有夜讀在跑。nice 0、同時 [pagesCap] 頁（再受實測 heap、溫控閘門）、模型組 LOCKED（多緒）。
 *  - [Mode.YIELD]：翻譯／即時翻／reader 單頁翻譯或重繪在跑，或引擎在建構。一次一頁、nice [YIELD_NICE]；YIELD 持續
 *    [SWAP_TO_FREE_AFTER_MS] 後模型組換成 FREE（1 緒、不進鎖）。
 *  - busy 一變 true 立刻 YIELD（在飛頁的執行緒當場降 nice）；busy 變 false 持續 [EXCLUSIVE_HOLD_MS] 才回 EXCLUSIVE
 *    （吸收章與章之間的幾毫秒空窗，也免得短暫的單頁翻譯讓模型組來回換）。
 *
 * **執行緒**：每頁在夜讀 worker（[NightWorkers]）上跑，進出時 [enter]／[exit] 登記；模式改變時對登記中的執行緒
 * `setThreadPriority`，正在持鎖前向（[lockedForward]）的跳過、由它自己結束時套當下模式的 nice。**ceiling**：YIELD 下用
 * LOCKED 組、**拿著全域鎖**的整段期間暫時拉回 nice 0——SimpleOMP 靜態平分，呼叫端自己算第 0 份，nice 9 的它拿著全域鎖
 * 會把整個前向拖長，翻譯只能在鎖外等。引擎的 [forwardHook] 在**取全域鎖之前**進、**放鎖之後**才出（含等監視器、拿到鎖後的
 * 放棄檢查）：Java 監視器沒有優先權繼承，拿到鎖才拉回、還原完才放鎖的話，頭尾各有一小段 nice 9 持鎖；等監視器是阻塞、
 * 不吃 CPU，蓋進來沒有代價。翻譯優先閘的等待（等翻譯讓出鎖）與 Kotlin 前後處理維持讓路的 nice。標記、ceiling、還原、
 * 改優先權迴圈全在 [lock] 內，不會交錯。
 *
 * **引擎掛鉤**（[forwardHook]，夜讀模型組的 [li.joye.yakuyomi.engine.NcnnFlavor.hook]）：同一組 Net 由多頁共用，掛鉤靠
 * 執行緒區域的 [Work]（[workElement] 掛進協程 context）找到這條執行緒的 slot 與中止條件——頁的中止＝暫停／取消、讓路時
 * 不是最早放行的那頁，或用 LOCKED 組而讓路夠久該換 FREE（見 [NightPump]）；開組暖機的中止＝暫停／取消。等翻譯讓出鎖期間
 * 也會問，所以暫停／讓路／換組不必等到翻譯的空檔。
 *
 * **不能 ≥ 10**：Android 對 nice ≥ 10 的執行緒套背景 task profile（cgroup），廠商常壓得很重（真機曾量到每頁 60 s 以上）。
 *
 * 不碰 Android API：優先權設定、時鐘、tid、heap、溫控、GC 都由建構子注入，JVM 單測可控（見 NightGovernorTest）。
 */
class NightGovernor(
    scope: CoroutineScope,
    busy: Flow<Boolean>,
    private val pagesCap: () -> Int,
    private val heapFree: () -> Long,
    private val maxMemory: Long,
    private val thermalThrottled: () -> Boolean,
    private val setNice: (tid: Int, nice: Int) -> Unit,
    private val myTid: () -> Int,
    internal val now: () -> Long,
    internal val log: (String) -> Unit,
    /**
     * 放行卡在 heap（或要記基線）時呼叫的 GC（見 [ChapterGate.admit]）。夜讀一頁配置約 2 GB、heap 只有 512 MB，實測用量大半是
     * 還沒回收的垃圾：不 GC 就把垃圾當成 app 自己的用量（基線）或在飛頁的用量，白白一次只放一頁。
     */
    private val gc: () -> Unit = { Runtime.getRuntime().gc() },
) {
    enum class Mode(val tag: String) { EXCLUSIVE("EX"), YIELD("YIELD") }

    private val lock = Any()
    private val _mode = MutableStateFlow(Mode.EXCLUSIVE)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    /** 最新的原始忙碌值（未經遲滯）：YIELD 的 3 s 遲滯期間不換成 FREE 組（見 [wantedNets]）。 */
    @Volatile
    private var busyNow = false

    /** 進入 YIELD 的時間（[now]）；在 [lock] 下讀寫。 */
    private var yieldSince = 0L

    /** 登記中的夜讀執行緒；在 [lock] 下讀寫。 */
    private val slots = ArrayList<Slot>()

    /** 放行／在飛數／模式改變時遞增：等待者比對後重查條件（另有 [POLL_MS] 的輪詢兜底 heap 與暫停旗標這類不發訊號的變化）。 */
    private val signal = MutableStateFlow(0L)

    init {
        scope.launch {
            busy.distinctUntilChanged().collectLatest { b ->
                busyNow = b
                if (b) {
                    setMode(Mode.YIELD)
                } else {
                    delay(EXCLUSIVE_HOLD_MS)
                    setMode(Mode.EXCLUSIVE)
                }
            }
        }
    }

    private fun niceFor(m: Mode): Int = if (m == Mode.YIELD) YIELD_NICE else 0

    private fun setMode(m: Mode) {
        val reprio = synchronized(lock) {
            val old = _mode.value
            if (old == m) return
            _mode.value = m
            if (m == Mode.YIELD) yieldSince = now()
            val nice = niceFor(m)
            val changed = slots.filter { !it.inInference && it.nice != nice }
                .map { s -> "${s.tid}:${s.nice}→$nice".also { applyNiceLocked(s, nice) } }
            "${old.tag}→${m.tag} reprio=$changed"
        }
        bump()
        log("mode $reprio")
    }

    /** 在 [lock] 下呼叫。執行緒可能已結束 → 吞掉例外（記下的值照樣更新，免得每次都重試）。 */
    private fun applyNiceLocked(slot: Slot, nice: Int) {
        runCatching { setNice(slot.tid, nice) }
        slot.nice = nice
    }

    private fun bump() = signal.update { it + 1 }

    /** 等 [signal] 從 [seen] 變掉，最多 [POLL_MS]。 */
    private suspend fun awaitSignal(seen: Long) {
        withTimeoutOrNull(POLL_MS) { signal.first { it != seen } }
    }

    /** 一條登記中的夜讀執行緒（頁、開關模型組都在 worker 上跑）。 */
    class Slot internal constructor(val tid: Int, val worker: String) {
        internal var nice = Int.MIN_VALUE
        internal var inInference = false

        /** 這段登記期間 ceiling 生效的次數（perf 行的 `ceil=`；拿到鎖時翻譯又來、放掉重等的那次也算一次）。 */
        var ceil = 0
            internal set
    }

    /**
     * 一段夜讀工作（一頁、開組暖機，或收尾關組）：[slot] 由 worker 在 [enter] 後填上；[abort]＝引擎問要不要放棄這次推論。
     * 經 [workElement] 綁在協程 context 上（ThreadLocal），協程在哪條執行緒上跑、那條執行緒就看得到它。
     */
    class Work internal constructor(internal val abort: () -> Boolean) {
        @Volatile
        var slot: Slot? = null
    }

    private val currentWork = ThreadLocal<Work?>()

    /** 新的一段工作（[abort] 預設不放棄：收尾關組用）。 */
    fun newWork(abort: () -> Boolean = { false }): Work = Work(abort)

    /** 把 [work] 綁進協程 context（跑在哪條執行緒上，那條的 [forwardHook] 就找得到它）。 */
    fun workElement(work: Work): CoroutineContext.Element = currentWork.asContextElement(work)

    /**
     * 夜讀模型組的引擎掛鉤（[li.joye.yakuyomi.engine.NcnnFlavor.hook]）：在發起推論的執行緒上被呼叫。
     * 不在夜讀工作裡（沒綁 [Work]）的呼叫＝不放棄、不調優先權。
     */
    val forwardHook: NcnnLowPriorityHook = object : NcnnLowPriorityHook {
        override fun shouldAbort(): Boolean = currentWork.get()?.abort?.invoke() == true

        override fun <T> aroundLockedForward(block: () -> T): T = lockedForward(currentWork.get()?.slot, block)
    }

    /** 當前執行緒進入夜讀工作：登記、套當下模式的 nice。與 [exit] 成對。 */
    fun enter(): Slot {
        val slot = Slot(myTid(), Thread.currentThread().name)
        synchronized(lock) {
            slots += slot
            applyNiceLocked(slot, niceFor(_mode.value))
        }
        return slot
    }

    fun exit(slot: Slot) {
        synchronized(lock) { slots -= slot }
    }

    /**
     * 一段**拿著全域鎖**的原生前向（LOCKED 組；引擎經 [forwardHook] 在取鎖之前進、放鎖之後出，[block] 內含等監視器、拿到鎖後
     * 的放棄檢查與前向本身）：這條執行緒當下 nice > 0 就先拉回 0（ceiling），[block] 結束（含拋例外）後才還原成**當下**模式的
     * nice——所以持鎖期間一律 nice 0。期間模式改變不動它（見類別說明）。[slot] 為 null（不在 worker 上）直接跑。
     */
    fun <T> lockedForward(slot: Slot?, block: () -> T): T {
        if (slot == null) return block()
        synchronized(lock) {
            slot.inInference = true
            if (slot.nice != 0) {
                applyNiceLocked(slot, 0)
                slot.ceil++
            }
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                slot.inInference = false
                val target = niceFor(_mode.value)
                if (slot.nice != target) applyNiceLocked(slot, target)
            }
        }
    }

    /**
     * 頁邊界要用的模型組：EXCLUSIVE → LOCKED；YIELD 持續 ≥ [SWAP_TO_FREE_AFTER_MS] **且翻譯還在跑** → FREE；其餘維持
     * [current]（短暫的單頁翻譯不值得換一次組，一次約 1–2 s；翻譯已結束、只是在等 3 s 遲滯回 EXCLUSIVE 時也不換）。
     */
    fun wantedNets(current: NetKind): NetKind = synchronized(lock) {
        when (_mode.value) {
            Mode.EXCLUSIVE -> NetKind.LOCKED
            Mode.YIELD -> if (busyNow && now() - yieldSince >= SWAP_TO_FREE_AFTER_MS) NetKind.FREE else current
        }
    }

    /** 章首診斷：heap 上限、預算、目前頁數上限。 */
    fun capacityInfo(): String =
        "heapMax=${maxMemory / MB}MB budget=${NightConcurrency.heapBudget(maxMemory) / MB}MB cap=${pagesCap()}"

    /** 一章一份的放行閘（在飛計數每章各自一份，章尾自然歸零、不會跨章洩漏）。 */
    fun openChapter(): ChapterGate = ChapterGate()

    /** 一頁的放行資訊（頁內不可變）。 */
    class Admission internal constructor(
        val seq: Int,
        val est: Long,
        val waitMs: Long,
        val inFlight: Int,
        val limit: Int,
        val heapFree: Long,
        val mode: Mode,
        /** [waitMs] 各卡在哪（[Block] 的 tag:ms，逗號隔開；沒等＝空字串）。perf 行與放行紀錄用。 */
        val waitWhy: String = "",
    ) {
        internal val released = AtomicBoolean(false)
    }

    /**
     * 放行等待卡在哪一條（[ChapterGate.admit] 的判斷順序）：
     *  - [SLOT]：在飛數已到上限（EXCLUSIVE 的頁數上限，YIELD 一次一頁）——等前面的頁做完，GC 幫不上；
     *  - [BUDGET]：在飛預估總和＋這頁超過靜態預算（大頁）；
     *  - [HEAP]：「基線＋在飛預估總和」放不下（基線含垃圾時 GC 可能有用）；
     *  - [MEASURED]：只差在實測用量（多半是垃圾）。
     */
    enum class Block(val tag: String, val gcMayHelp: Boolean) {
        SLOT("slot", false),
        BUDGET("budget", false),
        HEAP("heap", true),
        MEASURED("measured", true),
    }

    inner class ChapterGate internal constructor() {
        /** 在飛頁數；在 [lock] 下改。 */
        var running = 0
            private set

        /** 在飛頁的放行序號（最早＝最小）；在 [lock] 下改。 */
        private val inFlight = TreeSet<Int>()
        private var nextSeq = 0

        /** 在飛頁的 heap 預估總和；在 [lock] 下改。 */
        var reservedEst = 0L
            private set

        /**
         * app 本身（不含這章在飛頁）的 heap 用量上界：在飛＝0 時放行那一刻的實測用量，之後只往下收（實測用量一定 ≥ 基線）。
         * 在 [lock] 下讀寫。
         */
        private var baseUsed = 0L

        /** 上一次放行用的 GC 的時間（[now]；在 [lock] 外讀寫，只有放行的 producer 用）。 */
        private var lastGcAt = Long.MIN_VALUE / 2

        /** 兩次放行用 GC 的最短間隔：卡著的 GC 沒換到放行就加倍（最多 [GC_MAX_INTERVAL_MS]），放行一頁就回到最短。 */
        private var gcInterval = GC_MIN_INTERVAL_MS

        /** 這章放行用的 GC 次數（測試與診斷用；基線與卡住的都算）。 */
        var gcCount = 0
            private set

        /** 這章有頁 OOM 過 → 剩下的部分上限 1。 */
        @Volatile
        var oomCapped = false

        /** 目前的模型組（FREE 組的頁數另有上限 [NightConcurrency.FREE_MAX_PAGES]＝1：FREE 組不暖機，也只在讓路時用）。 */
        @Volatile
        var nets = NetKind.LOCKED

        /** EXCLUSIVE 下的頁數上限（YIELD 另外固定 1）；讀偏好與溫控，所以在鎖外算。 */
        private fun capNow(): Int {
            if (oomCapped || thermalThrottled()) return 1
            val cap = pagesCap().coerceIn(1, NightConcurrency.MAX_PAGES)
            return if (nets == NetKind.FREE) minOf(cap, NightConcurrency.FREE_MAX_PAGES) else cap
        }

        /**
         * 等到可以放行一頁：在飛數 < 上限，且在飛數＝0（第一頁一律放行，最差就是一次一頁），或以下兩條都成立：
         *  - 靜態預算：在飛頁的預估總和 + [est] ≤ [NightConcurrency.heapBudget]（大頁在 512 MB 上自動退成一次一頁）；
         *  - 實測：`maxMemory − max(實測用量, 基線 + 在飛預估總和)` ≥ [est] + [NightConcurrency.RESERVE_BYTES]。
         *    剛放行的頁多半還在解碼／推論、尖峰還沒配置出來，實測用量看不到它，所以用「基線 + 在飛預估」補上（已配置的部分
         *    兩邊取大、不重複算）。
         * YIELD 固定一次一頁、只在在飛＝0 時放行，所以不看 heap（翻譯那邊的 heap 靠讓路時丟回非最早的頁放掉）。
         *
         * **GC**（[gc]，間隔至少 [gcInterval]）：實測用量大半是還沒回收的垃圾（一頁配置約 2 GB），兩個地方會被它騙：
         *  - **基線**：在飛＝0 放行時記下的實測用量就是之後的基線（app 自己的用量上界）。上一批頁的垃圾還沒收時記下去，基線虛高、
         *    「基線＋在飛預估」就放不下第二頁——真機紀錄（2026-10-03 c362）基線 290／355 MB，第二頁一路等到第一頁做完，兩頁
         *    齊步走。所以在飛＝0、上限 ≥ 2、這個用量會擋住同樣大小的第二頁時，先 GC 一次再記。
         *  - **卡在 heap**（[Block.HEAP]、[Block.MEASURED]：名額有、靜態預算過）時 GC 一次再重看；GC 後的實測用量也讓基線往下收
         *    （基線只取小，GC 後的用量含在飛頁當下的用量，仍是 app 用量的上界）。沒換到放行，下次間隔加倍。
         * 卡在名額（[Block.SLOT]）或靜態預算（[Block.BUDGET]）時不 GC：GC 幫不上，等前面的頁做完。等待各卡在哪記在
         * [Admission.waitWhy]；放行等待超過 [ADMIT_LOG_MS] 記一行紀錄，每次放行用的 GC 也記一行（前後用量、耗時）。
         *
         * 等待中 [shouldStop] 成立 → 回 null。放行後一定要 [release]（呼叫端在子工作的 invokeOnCompletion 保證）。
         */
        suspend fun admit(est: Long, shouldStop: () -> Boolean): Admission? {
            val t0 = now()
            val budget = NightConcurrency.heapBudget(maxMemory)
            val need = est + NightConcurrency.RESERVE_BYTES
            val blockedMs = LongArray(Block.entries.size)
            var lastAt = t0
            var lastBlock: Block? = null
            while (true) {
                val t = now()
                lastBlock?.let { blockedMs[it.ordinal] += t - lastAt }
                lastAt = t
                val seen = signal.value
                val cap = capNow()
                val free = heapFree()
                val used = (maxMemory - free).coerceAtLeast(0)
                val gcDue = t - lastGcAt >= gcInterval
                var block: Block? = null
                var gcWhy: String? = null
                val admitted = synchronized(lock) {
                    val mode = _mode.value
                    val limit = if (mode == Mode.YIELD) 1 else cap
                    if (running >= limit) {
                        block = Block.SLOT
                    } else if (running == 0) {
                        // 第一頁一律放行；基線就在這一刻記——這個用量會擋住同樣大小的第二頁時先 GC 一次再記
                        // （同樣大小的第二頁靜態預算放得下，卻會被「這個用量＋第一頁預估」擋住）
                        val baselineBlocks = 2 * est <= budget && used + est + need > maxMemory
                        if (gcDue && limit >= 2 && baselineBlocks) gcWhy = "baseline"
                    } else {
                        baseUsed = minOf(baseUsed, used)
                        block = when {
                            reservedEst + est > budget -> Block.BUDGET
                            maxMemory - (baseUsed + reservedEst) < need -> Block.HEAP
                            maxMemory - used < need -> Block.MEASURED
                            else -> null
                        }
                        val b = block
                        if (b != null && b.gcMayHelp && gcDue) gcWhy = b.tag
                    }
                    if (block == null && gcWhy == null) {
                        if (running == 0) baseUsed = used
                        running++
                        reservedEst += est
                        val seq = nextSeq++
                        inFlight += seq
                        Admission(seq, est, t - t0, running, limit, free, mode, whyOf(blockedMs))
                    } else {
                        null
                    }
                }
                if (admitted != null) {
                    gcInterval = GC_MIN_INTERVAL_MS
                    if (admitted.waitMs > ADMIT_LOG_MS) {
                        log(
                            "admit wait=${admitted.waitMs}ms n=${admitted.inFlight}/${admitted.limit} " +
                                "est=${est / MB}MB heapFree=${free / MB}MB base=${baseUsedNow() / MB}MB gc=$gcCount " +
                                "blocked=${admitted.waitWhy.ifEmpty { "-" }}",
                        )
                    }
                    return admitted
                }
                lastBlock = block
                if (shouldStop()) return null
                val why = gcWhy
                if (why != null) {
                    forcedGc(why, used)
                    continue // 立刻用 GC 後的實測重看
                }
                awaitSignal(seen)
            }
        }

        /** 放行用的 GC：記時間、次數、前後用量；卡住的那種（非基線）間隔加倍（放行一頁時回到最短）。 */
        private fun forcedGc(why: String, usedBefore: Long) {
            val t = now()
            lastGcAt = t
            gcCount++
            if (why != "baseline") gcInterval = minOf(gcInterval * 2, GC_MAX_INTERVAL_MS)
            gc()
            val after = (maxMemory - heapFree()).coerceAtLeast(0)
            log("admit gc why=$why used=${usedBefore / MB}→${after / MB}MB took=${now() - t}ms n=$running")
        }

        private fun baseUsedNow(): Long = synchronized(lock) { baseUsed }

        private fun whyOf(ms: LongArray): String =
            Block.entries.filter { ms[it.ordinal] > 0 }.joinToString(",") { "${it.tag}:${ms[it.ordinal]}" }

        /** 還一個放行名額（重複呼叫無害）。 */
        fun release(a: Admission) {
            if (!a.released.compareAndSet(false, true)) return
            synchronized(lock) {
                running--
                reservedEst -= a.est
                inFlight -= a.seq
            }
            bump()
        }

        /**
         * 每次推論前、等翻譯讓出鎖時、重繪前的檢查點：YIELD 下在飛不只一頁時，除了最早放行的那頁，其餘放棄（丟回待做、
         * 把 heap 放掉）。最早那頁永遠留著，所以不會全部放棄。
         */
        fun keep(a: Admission): Boolean = synchronized(lock) {
            _mode.value != Mode.YIELD || inFlight.size <= 1 || a.seq == inFlight.first()
        }

        /** 沒有在飛頁。 */
        fun idle(): Boolean = synchronized(lock) { running == 0 }

        /** 放行計數的快照（等待者比對用；見 [await]）。 */
        fun mark(): Long = signal.value

        /** 等 [mark] 之後有放行名額歸還或模式改變（最多 [POLL_MS]）。 */
        suspend fun await(seen: Long) = awaitSignal(seen)

        /** 等在飛頁數歸零（換模型組前）；[shouldStop] 成立就提早回（呼叫端自己再看一次停止旗標）。 */
        suspend fun awaitIdle(shouldStop: () -> Boolean) {
            while (true) {
                val seen = signal.value
                if (idle() || shouldStop()) return
                awaitSignal(seen)
            }
        }
    }

    companion object {
        /** YIELD 下夜讀執行緒的 nice。**不能 ≥ 10**（見類別說明）；真機若仍太搶，只改這個常數。 */
        const val YIELD_NICE = 9

        /** busy 變 false 後持續這麼久才回 EXCLUSIVE。 */
        const val EXCLUSIVE_HOLD_MS = 3_000L

        /** YIELD 持續這麼久才把模型組換成 FREE。 */
        const val SWAP_TO_FREE_AFTER_MS = 8_000L

        /** 等待者的輪詢兜底間隔。 */
        const val POLL_MS = 250L

        /** 放行等待超過這麼久才記 TraceLog（免得洗版）。 */
        const val ADMIT_LOG_MS = 1_000L

        /** 放行用的兩次 GC 的最短間隔（GC 後仍放不下＝真的滿了，照舊等名額歸還或 heap 自己降）。 */
        const val GC_MIN_INTERVAL_MS = 2_000L

        /** 卡著的 GC 一直換不到放行時，間隔加倍到這裡為止（頁本身靠輪詢 [POLL_MS] 看到自然 GC 騰出的空間，不靠這個）。 */
        const val GC_MAX_INTERVAL_MS = 8_000L
    }
}
