package eu.kanade.tachiyomi.data.nightread

import eu.kanade.tachiyomi.data.nightread.NightConcurrency.MB
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 頁內偵測‖人物分割的分支（S11，[NightGovernor.inferenceBranch]）：分支執行緒綁上同中止條件的工作、以自己的 tid 登記
 * （當下模式的 nice、持鎖前向的 ceiling 套在分支自己身上）、跑完登出；不在夜讀工作裡呼叫＝不放棄。[NightPump] 只給多緒組分支，
 * FREE 組給 null（依序）。
 */
class NightGovernorBranchTest {

    private class Env(scope: TestScope) {
        val busy = MutableStateFlow(false)
        val nices = ConcurrentHashMap<Int, Int>()
        private val nextTid = AtomicInteger(100)
        private val tids = ConcurrentHashMap<Thread, Int>()
        fun tidOf(t: Thread): Int = tids.getOrPut(t) { nextTid.incrementAndGet() }

        /** 分支跑在一條真的新執行緒上（測試等它結束）。 */
        val branchThreads = ArrayList<Thread>()
        val governor = NightGovernor(
            scope = scope.backgroundScope,
            busy = busy,
            pagesCap = { 2 },
            heapFree = { 1024 * MB },
            maxMemory = 512 * MB,
            thermalThrottled = { false },
            setNice = { t, n -> nices[t] = n },
            myTid = { tidOf(Thread.currentThread()) },
            now = { scope.testScheduler.currentTime },
            log = {},
            branchPool = Executor { r ->
                val t = Thread(r, "branch")
                synchronized(branchThreads) { branchThreads += t }
                t.start()
            },
        )

        fun joinBranches() {
            synchronized(branchThreads) { branchThreads.toList() }.forEach { it.join(5_000) }
        }
    }

    private class FakeNets(val kind: NetKind) : AutoCloseable {
        override fun close() = Unit
    }

    @Test
    fun `branch thread gets its own slot, the page's abort condition and its own ceiling`() = runTest {
        val env = Env(this)
        runCurrent()
        env.busy.value = true
        runCurrent()
        env.governor.mode.value shouldBe NightGovernor.Mode.YIELD
        var abort = false
        val work = env.governor.newWork { abort }
        val pageTid = env.tidOf(Thread.currentThread())
        val hook = env.governor.forwardHook

        var branchTid = -1
        var niceAtStart = -1
        val abortSeen = mutableListOf<Boolean>()
        var niceInForward = -1
        var niceAfterForward = -1
        val ran = CountDownLatch(1)
        val firstChecked = CountDownLatch(1)
        val goOn = CountDownLatch(1)

        withContext(env.governor.workElement(work)) {
            val slot = env.governor.enter()
            work.slot = slot
            env.nices[pageTid] shouldBe NightGovernor.YIELD_NICE
            // 引擎在頁的執行緒上呼叫 execute
            env.governor.inferenceBranch.execute {
                branchTid = env.tidOf(Thread.currentThread())
                niceAtStart = env.nices[branchTid] ?: -1
                abortSeen += hook.shouldAbort()
                firstChecked.countDown()
                goOn.await(5, TimeUnit.SECONDS)
                abortSeen += hook.shouldAbort()
                niceInForward = hook.aroundLockedForward { env.nices[branchTid] ?: -1 }
                niceAfterForward = env.nices[branchTid] ?: -1
                ran.countDown()
            }
            firstChecked.await(5, TimeUnit.SECONDS) shouldBe true
            abort = true
            goOn.countDown()
            ran.await(5, TimeUnit.SECONDS) shouldBe true
            env.joinBranches()
            // ceiling 只拉分支自己，頁那條不動
            env.nices[pageTid] shouldBe NightGovernor.YIELD_NICE
            slot.ceil shouldBe 0
            env.governor.exit(slot)
        }
        (branchTid != pageTid) shouldBe true
        niceAtStart shouldBe NightGovernor.YIELD_NICE
        abortSeen shouldBe listOf(false, true)
        niceInForward shouldBe 0
        niceAfterForward shouldBe NightGovernor.YIELD_NICE

        // 分支已登出：回到 EXCLUSIVE 時不再改它的 nice
        env.busy.value = false
        runCurrent()
        advanceTimeBy(NightGovernor.EXCLUSIVE_HOLD_MS + 1)
        runCurrent()
        env.governor.mode.value shouldBe NightGovernor.Mode.EXCLUSIVE
        env.nices[branchTid] shouldBe NightGovernor.YIELD_NICE
    }

    @Test
    fun `branch outside night work never aborts and gets no ceiling`() = runTest {
        val env = Env(this)
        runCurrent()
        val hook = env.governor.forwardHook
        var abort: Boolean? = null
        var inForward = -2
        val ran = CountDownLatch(1)
        env.governor.inferenceBranch.execute {
            abort = hook.shouldAbort()
            inForward = hook.aroundLockedForward { 5 }
            ran.countDown()
        }
        ran.await(5, TimeUnit.SECONDS) shouldBe true
        env.joinBranches()
        abort shouldBe false
        inForward shouldBe 5
    }

    @Test
    fun `pump gives the branch to locked pages and none to free pages`() = runTest {
        val env = Env(this)
        runCurrent()
        val workers = StandardTestDispatcher(testScheduler)
        val seen = ArrayList<Pair<NetKind, Executor?>>()
        launch {
            delay(1_500)
            env.busy.value = true // 讓路夠久 → 換 FREE 組
        }
        NightPump<Int, FakeNets>(env.governor, workers).run(
            pages = (1..20).toList(),
            open = { kind -> FakeNets(kind) },
            estimate = { 10 * MB },
            page = { _, ticket ->
                seen += ticket.kind to ticket.inferenceBranch
                delay(1_000)
                if (ticket.keep()) NightPageOutcome.DONE else NightPageOutcome.DROPPED
            },
            onError = { _, t -> throw AssertionError(t) },
            onOomFinal = { throw AssertionError("no OOM expected") },
            shouldStop = { false },
        )
        seen.any { it.first == NetKind.LOCKED } shouldBe true
        seen.any { it.first == NetKind.FREE } shouldBe true
        seen.filter { it.first == NetKind.LOCKED }.all { it.second === env.governor.inferenceBranch } shouldBe true
        seen.filter { it.first == NetKind.FREE }.all { it.second == null } shouldBe true
    }
}
