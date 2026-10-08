package eu.kanade.tachiyomi.data.nightread

import eu.kanade.tachiyomi.data.nightread.NightConcurrency.MB
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.NcnnForwardAbortedException
import li.joye.yakuyomi.engine.NightReadRenderer
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext

class NightGovernorTest {

    /** 注入時鐘、優先權設定、tid、heap 的 governor（全在測試排程器上跑）。 */
    private class Env(scope: TestScope) {
        val busy = MutableStateFlow(false)
        var free = 1024 * MB
        var cap = 2
        var tid = 1
        val nices = HashMap<Int, Int>()
        var gcs = 0
        var onGc: () -> Unit = {}
        val governor = NightGovernor(
            scope = scope.backgroundScope,
            busy = busy,
            pagesCap = { cap },
            heapFree = { free },
            maxMemory = 512 * MB,
            thermalThrottled = { false },
            setNice = { t, n -> nices[t] = n },
            myTid = { tid },
            now = { scope.testScheduler.currentTime },
            log = {},
            gc = {
                gcs++
                onGc()
            },
        )

        fun enter(tid: Int): NightGovernor.Slot {
            this.tid = tid
            return governor.enter()
        }
    }

    private class FakeNets(val kind: NetKind) : AutoCloseable {
        var closed = 0

        override fun close() {
            closed++
        }
    }

    /**
     * 包一層測試 dispatcher：[onNextDispatch] 在下一次派工的當下（工作排進 worker、還沒跑之前）被呼叫一次。
     * delay 照樣走測試排程器的虛擬時間（[Delay] 委派給內層）。
     */
    @OptIn(InternalCoroutinesApi::class)
    private class InterceptingDispatcher(
        private val inner: CoroutineDispatcher,
    ) : CoroutineDispatcher(), Delay by (inner as Delay) {
        var onNextDispatch: (() -> Unit)? = null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            onNextDispatch?.let {
                onNextDispatch = null
                it()
            }
            inner.dispatch(context, block)
        }
    }

    @Test
    fun `busy yields immediately and returns to exclusive only after the hold`() = runTest {
        val env = Env(this)
        runCurrent()
        env.governor.mode.value shouldBe NightGovernor.Mode.EXCLUSIVE

        env.busy.value = true
        runCurrent()
        env.governor.mode.value shouldBe NightGovernor.Mode.YIELD

        env.busy.value = false
        runCurrent()
        advanceTimeBy(NightGovernor.EXCLUSIVE_HOLD_MS - 1)
        runCurrent()
        env.governor.mode.value shouldBe NightGovernor.Mode.YIELD

        // 遲滯期間又忙起來 → 重新計時
        env.busy.value = true
        runCurrent()
        env.busy.value = false
        runCurrent()
        advanceTimeBy(NightGovernor.EXCLUSIVE_HOLD_MS - 1)
        runCurrent()
        env.governor.mode.value shouldBe NightGovernor.Mode.YIELD
        advanceTimeBy(1)
        runCurrent()
        env.governor.mode.value shouldBe NightGovernor.Mode.EXCLUSIVE
    }

    @Test
    fun `mode change reprioritises workers but skips one in inference`() = runTest {
        val env = Env(this)
        runCurrent()
        val a = env.enter(11)
        val b = env.enter(12)
        env.nices shouldBe mapOf(11 to 0, 12 to 0)

        env.governor.lockedForward(b) {
            env.busy.value = true
            testScheduler.runCurrent()
            env.nices[11] shouldBe NightGovernor.YIELD_NICE
            env.nices[12] shouldBe 0 // 持鎖前向中：不動
        }
        env.nices[12] shouldBe NightGovernor.YIELD_NICE // 前向結束自己套當下模式

        // YIELD 下的持鎖前向 → ceiling 拉回 0、結束還原
        env.governor.lockedForward(a) {
            env.nices[11] shouldBe 0
        }
        env.nices[11] shouldBe NightGovernor.YIELD_NICE
        a.ceil shouldBe 1
        b.ceil shouldBe 0

        // 前向中回到 EXCLUSIVE：結束時還原成當下（0）而不是進來時的 9
        env.governor.lockedForward(a) {
            env.busy.value = false
            testScheduler.advanceTimeBy(NightGovernor.EXCLUSIVE_HOLD_MS)
            testScheduler.runCurrent()
        }
        env.governor.mode.value shouldBe NightGovernor.Mode.EXCLUSIVE
        env.nices[11] shouldBe 0
        env.nices[12] shouldBe 0
        env.governor.exit(a)
        env.governor.exit(b)
    }

    @Test
    fun `engine hook uses the slot and abort condition bound to the current work`() = runTest {
        val env = Env(this)
        runCurrent()
        env.busy.value = true
        runCurrent()
        val hook = env.governor.forwardHook
        var abort = false
        val work = env.governor.newWork { abort }

        // 不在夜讀工作裡：不放棄、不調優先權
        hook.shouldAbort() shouldBe false
        hook.aroundLockedForward { 7 } shouldBe 7

        withContext(env.governor.workElement(work)) {
            val slot = env.enter(21)
            work.slot = slot
            env.nices[21] shouldBe NightGovernor.YIELD_NICE
            hook.shouldAbort() shouldBe false
            abort = true
            hook.shouldAbort() shouldBe true
            // ceiling 包住持鎖的整段（引擎在取鎖前進、放鎖後出），出來就還原
            hook.aroundLockedForward { env.nices[21] } shouldBe 0
            env.nices[21] shouldBe NightGovernor.YIELD_NICE
            slot.ceil shouldBe 1
            // 拿到鎖後才放棄（持鎖段拋例外）：照樣還原成讓路的 nice
            shouldThrow<NcnnForwardAbortedException> {
                hook.aroundLockedForward<Unit> {
                    env.nices[21] shouldBe 0
                    throw NcnnForwardAbortedException()
                }
            }
            env.nices[21] shouldBe NightGovernor.YIELD_NICE
            slot.ceil shouldBe 2
            env.governor.exit(slot)
        }
        hook.shouldAbort() shouldBe false
    }

    @Test
    fun `first page is always admitted and the second waits for heap`() = runTest {
        val env = Env(this)
        env.free = 0 // 一點 heap 都沒有
        runCurrent()
        val gate = env.governor.openChapter()
        val first = gate.admit(150 * MB) { false }
        first shouldNotBe null

        val second = async { gate.admit(150 * MB) { false } }
        advanceTimeBy(5_000)
        runCurrent()
        second.isCompleted shouldBe false

        gate.release(first!!)
        runCurrent()
        second.isCompleted shouldBe true
        gate.running shouldBe 1

        // heap 夠了 → 第二頁不用等第一頁做完
        env.free = 1024 * MB
        val third = async { gate.admit(150 * MB) { false } }
        runCurrent()
        third.isCompleted shouldBe true
        gate.running shouldBe 2

        // 上限 2 → 第三頁等；停止旗標成立 → 回 null、不佔名額
        var stop = false
        val fourth = async { gate.admit(150 * MB) { stop } }
        runCurrent()
        fourth.isCompleted shouldBe false
        stop = true
        advanceTimeBy(NightGovernor.POLL_MS + 1)
        runCurrent()
        fourth.await() shouldBe null
        gate.running shouldBe 2
    }

    @Test
    fun `admission blocked only by measured heap triggers a gc and admits at once`() = runTest {
        val env = Env(this)
        env.free = 300 * MB // 用量 212 MB
        runCurrent()
        val gate = env.governor.openChapter()
        val first = gate.admit(100 * MB) { false }!!
        // 第一頁配置了一堆垃圾：實測用量 362 MB；「基線 212 + 在飛預估 100」放得下第二頁，只差在實測
        env.free = 150 * MB
        env.onGc = { env.free = 300 * MB } // GC 回收垃圾
        val second = async { gate.admit(100 * MB) { false } }
        runCurrent()
        second.isCompleted shouldBe true
        second.await() shouldNotBe null
        env.gcs shouldBe 1
        gate.gcCount shouldBe 1
        gate.release(first)
    }

    @Test
    fun `gc while admission is blocked by heap backs off when it does not help`() = runTest {
        val env = Env(this)
        env.free = 300 * MB
        runCurrent()
        val gate = env.governor.openChapter()
        val first = gate.admit(100 * MB) { false }!!
        env.free = 150 * MB // GC 也回收不了（真的滿了）
        val second = async { gate.admit(100 * MB) { false } }
        runCurrent()
        env.gcs shouldBe 1
        // 沒換到放行：下次間隔加倍（2 → 4 → 8 s，之後停在 8 s）；中間照樣輪詢重看
        advanceTimeBy(2 * NightGovernor.GC_MIN_INTERVAL_MS - 1)
        runCurrent()
        env.gcs shouldBe 1
        advanceTimeBy(NightGovernor.POLL_MS + 1)
        runCurrent()
        env.gcs shouldBe 2
        advanceTimeBy(NightGovernor.GC_MAX_INTERVAL_MS)
        runCurrent()
        env.gcs shouldBe 3
        advanceTimeBy(NightGovernor.GC_MAX_INTERVAL_MS)
        runCurrent()
        env.gcs shouldBe 4
        second.isCompleted shouldBe false
        gate.release(first)
        runCurrent()
        second.isCompleted shouldBe true
        gate.gcCount shouldBe 4
    }

    @Test
    fun `garbage counted in the baseline is collected before it is recorded so the next page fits`() = runTest {
        // 真機（2026-10-03 c362）：上一批頁做完、在飛＝0 時實測用量 290 MB，大半是垃圾；以前直接記成基線，「基線 290 + 在飛 151」
        // 放不下第二頁（要 151 + 96），第二頁一路等到第一頁做完。現在記基線前先 GC。
        val env = Env(this)
        env.free = 222 * MB // 用量 290 MB
        env.onGc = { env.free = 460 * MB } // 真正活著的只有 52 MB
        runCurrent()
        val gate = env.governor.openChapter()
        val first = gate.admit(151 * MB) { false }!!
        env.gcs shouldBe 1
        val second = async { gate.admit(151 * MB) { false } }
        runCurrent()
        second.isCompleted shouldBe true
        second.await()!!.inFlight shouldBe 2
        env.gcs shouldBe 1 // 第二頁不用再 GC
        gate.release(first)
        gate.release(second.await()!!)
    }

    @Test
    fun `baseline is not collected when it cannot block a second page`() = runTest {
        // 上限 1、第二頁超過靜態預算、或用量本來就低：GC 換不到什麼，不做
        val env = Env(this)
        env.free = 300 * MB // 用量 212：212 + 100 + 196 ≤ 512
        runCurrent()
        val gate = env.governor.openChapter()
        gate.release(gate.admit(100 * MB) { false }!!)
        env.free = 100 * MB
        env.cap = 1
        gate.release(gate.admit(100 * MB) { false }!!)
        env.cap = 2
        val big = NightConcurrency.estBytes(NightReadRenderer.MAX_PIXELS.toLong())
        gate.release(gate.admit(big) { false }!!)
        env.gcs shouldBe 0
    }

    @Test
    fun `heap that stays full after the baseline gc keeps the second page waiting`() = runTest {
        // 「基線 + 在飛預估」本身就放不下（不是垃圾的問題）：記基線前 GC 一次、卡住時再按間隔試，都換不到放行
        val env = Env(this)
        env.free = 250 * MB
        runCurrent()
        val gate = env.governor.openChapter()
        val a = gate.admit(150 * MB) { false }!!
        env.gcs shouldBe 1 // 基線前那次
        val b = async { gate.admit(150 * MB) { false } }
        advanceTimeBy(5_000)
        runCurrent()
        b.isCompleted shouldBe false
        env.gcs shouldBe 2 // 卡在 heap：2 s 後試一次，沒用，下次要等 4 s
        gate.release(a)
        runCurrent()
        b.isCompleted shouldBe true
        b.await()!!.waitWhy shouldBe "heap:5000"
    }

    @Test
    fun `waits for a free slot never gc and are reported as slot waits`() = runTest {
        val env = Env(this)
        env.cap = 1
        env.free = 150 * MB // 用量 362：若卡的是 heap 就會 GC
        runCurrent()
        val gate = env.governor.openChapter()
        val first = gate.admit(100 * MB) { false }!!
        val second = async { gate.admit(100 * MB) { false } }
        advanceTimeBy(3_000)
        runCurrent()
        second.isCompleted shouldBe false
        env.gcs shouldBe 0
        gate.release(first)
        runCurrent()
        val adm = second.await()!!
        adm.waitWhy shouldBe "slot:3000"
        adm.waitMs shouldBe 3_000L
        env.gcs shouldBe 0
    }

    @Test
    fun `big pages run one at a time on a 512 MB heap`() = runTest {
        val env = Env(this)
        env.free = 400 * MB
        runCurrent()
        val gate = env.governor.openChapter()
        val est = NightConcurrency.estBytes(NightReadRenderer.MAX_PIXELS.toLong())
        val first = gate.admit(est) { false }!!
        // 兩頁預估總和 > 預算（512 MB → 320 MB）：實測 heap 再多也不放第二頁
        val second = async { gate.admit(est) { false } }
        advanceTimeBy(5_000)
        runCurrent()
        second.isCompleted shouldBe false
        env.gcs shouldBe 0 // 卡在靜態預算：GC 幫不上
        gate.release(first)
        runCurrent()
        second.isCompleted shouldBe true
        second.await()!!.waitWhy shouldBe "budget:5000"
        gate.reservedEst shouldBe est
    }

    @Test
    fun `just-admitted pages count against the heap before they allocate`() = runTest {
        val env = Env(this)
        env.free = 250 * MB // 用量 262 MB
        runCurrent()
        val gate = env.governor.openChapter()
        val first = gate.admit(150 * MB) { false }!!
        // 第一頁還沒配置（實測用量沒變）：只看實測會放行（250 ≥ 150 + 96），但加上它的預估就放不下
        val second = async { gate.admit(150 * MB) { false } }
        advanceTimeBy(5_000)
        runCurrent()
        second.isCompleted shouldBe false
        gate.release(first)
        runCurrent()
        second.isCompleted shouldBe true
    }

    @Test
    fun `yield admits one page and keeps only the oldest in flight`() = runTest {
        val env = Env(this)
        env.cap = 3
        runCurrent()
        val gate = env.governor.openChapter()
        val a1 = gate.admit(10 * MB) { false }!!
        val a2 = gate.admit(10 * MB) { false }!!

        env.busy.value = true
        runCurrent()
        gate.keep(a1) shouldBe true
        gate.keep(a2) shouldBe false

        val a3 = async { gate.admit(10 * MB) { false } }
        runCurrent()
        a3.isCompleted shouldBe false

        gate.release(a1)
        runCurrent()
        gate.keep(a2) shouldBe true // 只剩它一頁
        a3.isCompleted shouldBe false // YIELD 上限 1

        gate.release(a2)
        gate.release(a2) // 重複還無害
        runCurrent()
        a3.isCompleted shouldBe true
        gate.running shouldBe 1
    }

    @Test
    fun `nets switch to free only after a sustained yield`() = runTest {
        val env = Env(this)
        runCurrent()
        env.governor.wantedNets(NetKind.FREE) shouldBe NetKind.LOCKED
        env.busy.value = true
        runCurrent()
        env.governor.wantedNets(NetKind.LOCKED) shouldBe NetKind.LOCKED
        advanceTimeBy(NightGovernor.SWAP_TO_FREE_AFTER_MS)
        runCurrent()
        env.governor.wantedNets(NetKind.LOCKED) shouldBe NetKind.FREE
    }

    @Test
    fun `nets stay put during the hysteresis after translation ends`() = runTest {
        val env = Env(this)
        runCurrent()
        env.busy.value = true
        runCurrent()
        advanceTimeBy(6_000)
        env.busy.value = false
        runCurrent()
        // 還在 YIELD（3 s 遲滯），進 YIELD 已超過 8 s，但翻譯已經結束 → 不換成 FREE
        advanceTimeBy(2_500)
        runCurrent()
        env.governor.mode.value shouldBe NightGovernor.Mode.YIELD
        env.governor.wantedNets(NetKind.LOCKED) shouldBe NetKind.LOCKED
        env.governor.wantedNets(NetKind.FREE) shouldBe NetKind.FREE
        advanceTimeBy(1_000)
        runCurrent()
        env.governor.wantedNets(NetKind.FREE) shouldBe NetKind.LOCKED
    }

    @Test
    fun `pump swaps nets only with nothing in flight`() = runTest {
        val env = Env(this)
        runCurrent()
        val workers = StandardTestDispatcher(testScheduler)
        var inFlight = 0
        val opened = ArrayList<Pair<FakeNets, Int>>()
        val done = ArrayList<Int>()
        val pages = (1..24).toList()
        launch {
            delay(1_500)
            env.busy.value = true
            delay(12_500)
            env.busy.value = false
        }
        NightPump<Int, FakeNets>(env.governor, workers).run(
            pages = pages,
            open = { kind -> FakeNets(kind).also { opened += it to inFlight } },
            estimate = { 10 * MB },
            page = { p, ticket ->
                ticket.nets.closed shouldBe 0
                inFlight++
                try {
                    delay(1_000)
                } finally {
                    inFlight--
                }
                if (ticket.keep()) {
                    done += p
                    NightPageOutcome.DONE
                } else {
                    NightPageOutcome.DROPPED
                }
            },
            onError = { _, t -> throw AssertionError(t) },
            onOomFinal = { throw AssertionError("no OOM expected") },
            shouldStop = { false },
        )
        opened.map { it.first.kind } shouldBe listOf(NetKind.LOCKED, NetKind.FREE, NetKind.LOCKED)
        opened.map { it.second } shouldBe listOf(0, 0, 0)
        opened.map { it.first.closed } shouldBe listOf(1, 1, 1)
        done.sorted() shouldBe pages
    }

    /**
     * 換組要先排空：頁 2 在 t=9 s 讓路被丟回（此時已想換 FREE），producer 等頁 1（t=30 s）跑完。
     * [stopAt] 有值＝排空期間按暫停；否則 t=12 s 翻譯結束、15 s 回 EXCLUSIVE。兩種都不該開 FREE 組。
     */
    private fun TestScope.swapDrainScenario(stopAt: Long?): Pair<List<NetKind>, List<Int>> {
        val env = Env(this)
        env.cap = 2
        var stop = false
        val workers = StandardTestDispatcher(testScheduler)
        val opened = ArrayList<NetKind>()
        val done = ArrayList<Int>()
        launch {
            delay(500)
            env.busy.value = true
            delay(11_500)
            if (stopAt == null) env.busy.value = false
        }
        stopAt?.let {
            launch {
                delay(it)
                stop = true
            }
        }
        launch {
            NightPump<Int, FakeNets>(env.governor, workers).run(
                pages = listOf(1, 2),
                open = { kind -> FakeNets(kind).also { opened += kind } },
                estimate = { 10 * MB },
                page = { p, ticket ->
                    delay(if (p == 1) 30_000 else 9_000)
                    // 推論前檢查點：不過就拋 NcnnForwardAbortedException，pump 當丟回待做
                    ticket.checkpoint()
                    done += p
                    NightPageOutcome.DONE
                },
                onError = { _, t -> throw AssertionError(t) },
                onOomFinal = { throw AssertionError("no OOM expected") },
                shouldStop = { stop },
            )
        }
        return opened to done
    }

    @Test
    fun `swap is re-checked after draining and skipped when the mode flipped back`() = runTest {
        val (opened, done) = swapDrainScenario(stopAt = null)
        advanceUntilIdle()
        opened shouldBe listOf(NetKind.LOCKED)
        done.sorted() shouldBe listOf(1, 2)
    }

    @Test
    fun `pausing while draining for a swap opens no new nets`() = runTest {
        val (opened, done) = swapDrainScenario(stopAt = 12_000)
        advanceUntilIdle()
        opened shouldBe listOf(NetKind.LOCKED)
        done shouldBe emptyList()
    }

    /**
     * 翻譯從 t=0.5 s 起一直佔著全域鎖到 60 s：唯一在飛的頁（最早那頁、LOCKED 組）等鎖期間照引擎的做法每 50 ms 問掛鉤。
     * 讓路滿 8 s 後它要放棄、丟回待做，producer 換成 FREE 組（不進鎖）重跑它——不能一路等到翻譯放鎖，也只丟這一次。
     */
    @Test
    fun `earliest locked page drops so the pump can swap to free nets while translation holds the lock`() = runTest {
        val env = Env(this)
        env.cap = 1
        runCurrent()
        val workers = StandardTestDispatcher(testScheduler)
        val hook = env.governor.forwardHook
        var lockHeld = false
        val opened = ArrayList<NetKind>()
        val attempts = ArrayList<NetKind>()
        var doneAt = -1L
        launch {
            delay(500)
            env.busy.value = true
            lockHeld = true
            delay(59_500)
            lockHeld = false
            env.busy.value = false
        }
        launch {
            NightPump<Int, FakeNets>(env.governor, workers).run(
                pages = listOf(1),
                open = { kind -> FakeNets(kind).also { opened += kind } },
                estimate = { 10 * MB },
                page = { _, ticket ->
                    attempts += ticket.kind
                    delay(1_000) // 解碼等前處理
                    // 引擎的上鎖路徑（翻譯優先閘）：LOCKED 組等翻譯讓出鎖，期間每 50 ms 問一次掛鉤
                    if (ticket.kind == NetKind.LOCKED) {
                        while (lockHeld) {
                            if (hook.shouldAbort()) throw NcnnForwardAbortedException()
                            delay(50)
                        }
                    }
                    delay(3_000) // 推論＋重繪
                    doneAt = testScheduler.currentTime
                    NightPageOutcome.DONE
                },
                onError = { _, t -> throw AssertionError(t) },
                onOomFinal = { throw AssertionError("no OOM expected") },
                shouldStop = { false },
            )
        }
        advanceUntilIdle()
        opened shouldBe listOf(NetKind.LOCKED, NetKind.FREE)
        attempts shouldBe listOf(NetKind.LOCKED, NetKind.FREE)
        (doneAt in 1..20_000) shouldBe true
    }

    @Test
    fun `opening nets is abandoned when paused during warm-up`() = runTest {
        val env = Env(this)
        runCurrent()
        val workers = StandardTestDispatcher(testScheduler)
        val hook = env.governor.forwardHook
        var stop = false
        var warmUpAborted: Boolean? = null
        val nets = ArrayList<FakeNets>()
        NightPump<Int, FakeNets>(env.governor, workers).run(
            pages = listOf(1, 2),
            open = { kind ->
                stop = true // 暖機（等鎖）期間使用者按了暫停
                warmUpAborted = hook.shouldAbort()
                FakeNets(kind).also { nets += it }
            },
            estimate = { 10 * MB },
            page = { _, _ -> throw AssertionError("paused: no page should run") },
            onError = { _, t -> throw AssertionError(t) },
            onOomFinal = { throw AssertionError("no OOM expected") },
            shouldStop = { stop },
        )
        warmUpAborted shouldBe true
        nets.single().closed shouldBe 1
    }

    @Test
    fun `nets opened while the pump is being cancelled are still closed`() = runTest {
        val env = Env(this)
        runCurrent()
        val workers = StandardTestDispatcher(testScheduler)
        val nets = ArrayList<FakeNets>()
        lateinit var job: Job
        job = launch {
            NightPump<Int, FakeNets>(env.governor, workers).run(
                pages = listOf(1),
                open = { kind ->
                    job.cancel() // 開組期間協程被取消：withContext 會丟掉回傳值
                    FakeNets(kind).also { nets += it }
                },
                estimate = { 10 * MB },
                page = { _, _ -> throw AssertionError("cancelled: no page should run") },
                onError = { _, t -> throw AssertionError(t) },
                onOomFinal = { throw AssertionError("no OOM expected") },
                shouldStop = { false },
            )
        }
        advanceUntilIdle()
        job.isCancelled shouldBe true
        nets.single().closed shouldBe 1
    }

    /**
     * 換組的 withContext 派到 worker、還沒跑之前協程就被取消：換組那段整個不跑，舊的 LOCKED 組還記在 nets，
     * 由收尾關掉——恰好一次（原本 producer 先把 nets 清成 null 再派工，這種取消會把舊組漏掉）。
     */
    @Test
    fun `swap cancelled before reaching the worker still closes the old nets exactly once`() = runTest {
        val env = Env(this)
        env.cap = 1
        runCurrent()
        val workers = InterceptingDispatcher(StandardTestDispatcher(testScheduler))
        val opened = ArrayList<FakeNets>()
        val done = ArrayList<Int>()
        launch {
            delay(500)
            env.busy.value = true
        }
        lateinit var job: Job
        val pump = NightPump<Int, FakeNets>(env.governor, workers)
        // 取消當下的狀態：證明攔到的是換組那次派工（頁 2 的 launch 派工時在飛 ≥ 1、不是 idle），否則換到別的派工上取消，
        // 舊寫法（producer 先清 nets）也會過
        var idleAtCancel: Boolean? = null
        var wantedAtCancel: NetKind? = null
        job = launch {
            pump.run(
                pages = listOf(1, 2),
                open = { kind -> FakeNets(kind).also { opened += it } },
                estimate = { 10 * MB },
                page = { p, _ ->
                    delay(10_000)
                    // 頁 1 做完（t=10 s，讓路已 9.5 s）後 producer 下一件派到 worker 的就是換成 FREE 組：派工當下取消
                    if (p == 1) {
                        workers.onNextDispatch = {
                            idleAtCancel = pump.gate.idle()
                            wantedAtCancel = env.governor.wantedNets(NetKind.LOCKED)
                            job.cancel()
                        }
                    }
                    done += p
                    NightPageOutcome.DONE
                },
                onError = { _, t -> throw AssertionError(t) },
                onOomFinal = { throw AssertionError("no OOM expected") },
                shouldStop = { false },
            )
        }
        advanceUntilIdle()
        job.isCancelled shouldBe true
        idleAtCancel shouldBe true
        wantedAtCancel shouldBe NetKind.FREE
        done shouldBe listOf(1)
        opened.map { it.kind } shouldBe listOf(NetKind.LOCKED)
        opened.single().closed shouldBe 1
    }

    /**
     * 章首開 LOCKED 組時翻譯一直持鎖到 60 s：暖機（引擎等翻譯讓出鎖、每 50 ms 問掛鉤）在讓路滿 8 s、該換 FREE 時就放棄，
     * 回迴圈頂端換成 FREE 組開始做頁——不能等到翻譯放鎖才暖完、再馬上換掉。
     */
    @Test
    fun `locked warm-up is abandoned once a sustained yield wants free nets`() = runTest {
        val env = Env(this)
        runCurrent()
        env.busy.value = true
        runCurrent()
        val workers = StandardTestDispatcher(testScheduler)
        val hook = env.governor.forwardHook
        val opened = ArrayList<FakeNets>()
        val attempts = ArrayList<NetKind>()
        var warmAbortedAt: Long? = null
        var doneAt = -1L
        launch {
            delay(60_000)
            env.busy.value = false
        }
        launch {
            NightPump<Int, FakeNets>(env.governor, workers).run(
                pages = listOf(1),
                open = { kind ->
                    if (kind == NetKind.LOCKED) {
                        // 暖機的前向：翻譯持鎖到 60 s，引擎等鎖期間每 50 ms 問一次掛鉤（open 不能 suspend，直接推虛擬時鐘）
                        while (testScheduler.currentTime < 60_000) {
                            if (hook.shouldAbort()) {
                                warmAbortedAt = testScheduler.currentTime
                                break
                            }
                            testScheduler.advanceTimeBy(50)
                        }
                    }
                    FakeNets(kind).also { opened += it }
                },
                estimate = { 10 * MB },
                page = { _, ticket ->
                    attempts += ticket.kind
                    delay(3_000)
                    doneAt = testScheduler.currentTime
                    NightPageOutcome.DONE
                },
                onError = { _, t -> throw AssertionError(t) },
                onOomFinal = { throw AssertionError("no OOM expected") },
                shouldStop = { false },
            )
        }
        advanceUntilIdle()
        warmAbortedAt shouldBe NightGovernor.SWAP_TO_FREE_AFTER_MS
        opened.map { it.kind } shouldBe listOf(NetKind.LOCKED, NetKind.FREE)
        opened.map { it.closed } shouldBe listOf(1, 1)
        attempts shouldBe listOf(NetKind.FREE)
        (doneAt in 1..20_000) shouldBe true
    }

    /**
     * 章首想要 FREE（讓路已 9 s）但 FREE 組開不起來：改開 LOCKED、這章不再換組——之後一直讓路也不再試開 FREE，
     * LOCKED 的頁與暖機也不會為了換組放棄；每頁恰好做一次。
     */
    @Test
    fun `chapter start falls back to the other nets and never swaps again`() = runTest {
        val env = Env(this)
        env.cap = 2
        runCurrent()
        env.busy.value = true
        runCurrent()
        advanceTimeBy(9_000)
        runCurrent()
        env.governor.wantedNets(NetKind.LOCKED) shouldBe NetKind.FREE
        val workers = StandardTestDispatcher(testScheduler)
        val hook = env.governor.forwardHook
        val openCalls = ArrayList<NetKind>()
        val nets = ArrayList<FakeNets>()
        var warmUpAbort: Boolean? = null
        val attempts = HashMap<Int, Int>()
        val pages = (1..6).toList()
        NightPump<Int, FakeNets>(env.governor, workers).run(
            pages = pages,
            open = { kind ->
                openCalls += kind
                if (kind == NetKind.FREE) throw IllegalStateException("FREE 組開不起來")
                warmUpAbort = hook.shouldAbort()
                FakeNets(kind).also { nets += it }
            },
            estimate = { 10 * MB },
            page = { p, ticket ->
                ticket.kind shouldBe NetKind.LOCKED
                attempts[p] = (attempts[p] ?: 0) + 1
                delay(1_000)
                hook.shouldAbort() shouldBe false // 換組已停用：LOCKED 的頁不為換組放棄
                delay(1_000)
                NightPageOutcome.DONE
            },
            onError = { _, t -> throw AssertionError(t) },
            onOomFinal = { throw AssertionError("no OOM expected") },
            shouldStop = { false },
        )
        openCalls shouldBe listOf(NetKind.FREE, NetKind.LOCKED)
        warmUpAbort shouldBe false
        nets.single().closed shouldBe 1
        attempts shouldBe pages.associateWith { 1 }
    }

    @Test
    fun `chapter start rethrows when neither nets can be opened`() = runTest {
        val env = Env(this)
        runCurrent()
        val workers = StandardTestDispatcher(testScheduler)
        val e = shouldThrow<IllegalStateException> {
            NightPump<Int, FakeNets>(env.governor, workers).run(
                pages = listOf(1),
                open = { kind -> throw IllegalStateException("open ${kind.tag}") },
                estimate = { 10 * MB },
                page = { _, _ -> throw AssertionError("no nets: no page should run") },
                onError = { _, t -> throw AssertionError(t) },
                onOomFinal = { throw AssertionError("no OOM expected") },
                shouldStop = { false },
            )
        }
        // 章首想要 LOCKED（EXCLUSIVE）→ 開不起來 → 改開 FREE 也開不起來 → 拋後者、前者掛在 suppressed
        e.message shouldBe "open F"
        e.suppressed.map { it.message } shouldBe listOf("open L")
    }

    @Test
    fun `pump isolates errors, retries OOM pages one at a time and never leaks permits`() = runTest {
        val env = Env(this)
        env.cap = 3
        runCurrent()
        val workers = StandardTestDispatcher(testScheduler)
        val errors = ArrayList<Int>()
        val oomFinal = ArrayList<Int>()
        val attempts = HashMap<Int, Int>()
        var inFlight = 0
        var maxInFlightAfterOom = 0
        var oomSeen = false
        val pump = NightPump<Int, FakeNets>(env.governor, workers)
        pump.run(
            pages = (1..8).toList(),
            open = { FakeNets(it) },
            estimate = { p -> if (p == 3) error("probe failed") else 10 * MB },
            page = { p, _ ->
                attempts[p] = (attempts[p] ?: 0) + 1
                inFlight++
                if (oomSeen) maxInFlightAfterOom = maxOf(maxInFlightAfterOom, inFlight)
                try {
                    delay(1_000)
                } finally {
                    inFlight--
                }
                when {
                    p == 2 -> error("page failed")
                    p == 4 && attempts[p] == 1 -> NightPageOutcome.OOM.also { oomSeen = true }
                    p == 6 -> NightPageOutcome.OOM // 補跑仍 OOM
                    else -> NightPageOutcome.DONE
                }
            },
            onError = { p, _ -> errors += p },
            onOomFinal = { p -> oomFinal += p },
            shouldStop = { false },
        )
        errors.sorted() shouldBe listOf(2, 3)
        attempts[4] shouldBe 2
        attempts[6] shouldBe 2
        attempts[3] shouldBe null
        oomFinal shouldBe listOf(6)
        maxInFlightAfterOom shouldBe 1
        pump.gate.running shouldBe 0

        // 下一章不受影響：名額沒洩漏、上限回到設定值
        val next = env.governor.openChapter()
        next.admit(10 * MB) { false } shouldNotBe null
        next.admit(10 * MB) { false } shouldNotBe null
    }

    @Test
    fun `pages cap follows heap size`() {
        NightConcurrency.heapPageCap(512 * MB) shouldBe 2
        NightConcurrency.heapPageCap(256 * MB) shouldBe 1
        NightConcurrency.heapPageCap(1024 * MB) shouldBe 3
        NightConcurrency.pagesCap(NightConcurrency.AUTO, 512 * MB) shouldBe 2
        NightConcurrency.pagesCap("3", 512 * MB) shouldBe 2
        NightConcurrency.pagesCap("1", 512 * MB) shouldBe 1
        NightConcurrency.pagesCap("garbage", 1024 * MB) shouldBe 3
    }

    @Test
    fun `translated pages carry the inpaint mask and two reference pages still fit a 512 MB heap`() {
        val px = NightConcurrency.REF_PX
        // 去字遮罩＝1 B/px 布林＋1/8 B/px 位元版，只加在譯後頁
        NightConcurrency.estBytes(px, inpaintMask = true) - NightConcurrency.estBytes(px) shouldBe px + px / 8
        NightConcurrency.estBytes(px, inpaintMask = false) shouldBe NightConcurrency.estBytes(px)
        // 譯後章的參考頁（2.6 MPx）在 512 MB heap 上仍是兩頁並行（靜態預算）
        (2 * NightConcurrency.estBytes(px, inpaintMask = true) <= NightConcurrency.heapBudget(512 * MB)) shouldBe true
    }
}
