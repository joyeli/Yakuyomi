package eu.kanade.tachiyomi.data.nightread

import eu.kanade.tachiyomi.data.nightread.NightConcurrency.MB
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * [NightPump] 帶外部頁來源（[NightFeed]，合併處理：翻譯落地一頁就送一頁）。沒帶 feed 的行為由 NightGovernorTest 守著。
 */
class NightPumpFeedTest {

    private class FakeNets(val kind: NetKind) : AutoCloseable {
        var closed = 0

        override fun close() {
            closed++
        }
    }

    /** 測試排程器上的 governor（heap 充足、不忙＝EXCLUSIVE）。 */
    private fun TestScope.governor(cap: Int = 2) = NightGovernor(
        scope = backgroundScope,
        busy = MutableStateFlow(false),
        pagesCap = { cap },
        heapFree = { 1024 * MB },
        maxMemory = 512 * MB,
        thermalThrottled = { false },
        setNice = { _, _ -> },
        myTid = { 1 },
        now = { testScheduler.currentTime },
        log = {},
        gc = {},
    )

    /**
     * 可控的頁來源：[add] 放進新頁（[poll]／[awaitMore] 取走），[closed] 之後 [awaitMore] 沒頁就回 null。
     * [awaitMore] 每 100 ms（虛擬時間）看一次；[events] 記下第一次被呼叫。
     */
    private class FakeFeed(val events: MutableList<String> = ArrayList()) : NightFeed<Int> {
        private val pending = ArrayList<Int>()
        var closed = false
        var awaitCalls = 0

        fun add(vararg pages: Int) {
            pending += pages.toList()
        }

        private fun drain(): List<Int> = pending.toList().also { pending.clear() }

        override fun order(page: Int): Int = page

        override fun poll(): List<Int> = drain()

        override suspend fun awaitMore(shouldStop: () -> Boolean): List<Int>? {
            if (awaitCalls++ == 0) events += "await"
            while (true) {
                if (shouldStop()) return null
                val more = drain()
                if (more.isNotEmpty()) return more
                if (closed) return null
                delay(100)
            }
        }
    }

    @Test
    fun `沒有初始頁、來源開著：開組一次不關，晚到的頁依章內順序做完，來源關了才收尾關組`() = runTest {
        val gov = governor()
        val workers = StandardTestDispatcher(testScheduler)
        val feed = FakeFeed()
        val opened = ArrayList<FakeNets>()
        val started = ArrayList<Int>()
        val doneAt = HashMap<Int, Long>()
        var closedAt = -1L
        launch {
            delay(1_000)
            feed.add(3, 1) // 一次到兩頁、順序顛倒
            delay(4_000)
            feed.add(2)
            delay(3_000)
            feed.closed = true
        }
        launch {
            NightPump<Int, FakeNets>(gov, workers).run(
                pages = emptyList(),
                open = { kind -> FakeNets(kind).also { opened += it } },
                estimate = { 10 * MB },
                page = { p, ticket ->
                    ticket.nets.closed shouldBe 0 // 等新頁期間組沒被關
                    gov.openNets shouldBe NetKind.LOCKED // 開著的組有通報給 governor（合併處理的記憶體門檻看它）
                    started += p
                    delay(500)
                    doneAt[p] = testScheduler.currentTime
                    NightPageOutcome.DONE
                },
                onError = { _, t -> throw AssertionError(t) },
                onOomFinal = { throw AssertionError("no OOM expected") },
                shouldStop = { false },
                feed = feed,
            )
            closedAt = testScheduler.currentTime
        }
        advanceUntilIdle()
        opened.size shouldBe 1
        opened.single().closed shouldBe 1
        gov.openNets shouldBe null // 收工關組後通報沒有開著的組
        started shouldBe listOf(1, 3, 2)
        (doneAt.getValue(2) < 8_000) shouldBe true // 晚到的頁不必等來源關掉
        (closedAt >= 8_000) shouldBe true // 來源關了才收工
    }

    @Test
    fun `有頁在飛時新到的頁照章內順序排進去`() = runTest {
        val gov = governor(cap = 1)
        val workers = StandardTestDispatcher(testScheduler)
        val feed = FakeFeed()
        val started = ArrayList<Int>()
        launch {
            delay(500)
            feed.add(30, 20) // 第 10 頁還在飛
            delay(100)
            feed.closed = true
        }
        launch {
            NightPump<Int, FakeNets>(gov, workers).run(
                pages = listOf(10),
                open = { kind -> FakeNets(kind) },
                estimate = { 10 * MB },
                page = { p, _ ->
                    started += p
                    delay(2_000)
                    NightPageOutcome.DONE
                },
                onError = { _, t -> throw AssertionError(t) },
                onOomFinal = { throw AssertionError("no OOM expected") },
                shouldStop = { false },
                feed = feed,
            )
        }
        advanceUntilIdle()
        started shouldBe listOf(10, 20, 30)
    }

    @Test
    fun `等新頁時被中止：很快返回、組照常關`() = runTest {
        val gov = governor()
        val workers = StandardTestDispatcher(testScheduler)
        val feed = FakeFeed() // 永遠不關
        val opened = ArrayList<FakeNets>()
        var stop = false
        var returnedAt = -1L
        launch {
            delay(3_000)
            stop = true
        }
        launch {
            NightPump<Int, FakeNets>(gov, workers).run(
                pages = listOf(1),
                open = { kind -> FakeNets(kind).also { opened += it } },
                estimate = { 10 * MB },
                page = { _, _ ->
                    delay(500)
                    NightPageOutcome.DONE
                },
                onError = { _, t -> throw AssertionError(t) },
                onOomFinal = { throw AssertionError("no OOM expected") },
                shouldStop = { stop },
                feed = feed,
            )
            returnedAt = testScheduler.currentTime
        }
        advanceUntilIdle()
        opened.single().closed shouldBe 1
        (returnedAt in 3_000..3_500) shouldBe true
        feed.awaitCalls shouldBe 1
    }

    @Test
    fun `有外部頁來源時被丟回待做的頁照樣重做完`() = runTest {
        val gov = governor()
        val workers = StandardTestDispatcher(testScheduler)
        val feed = FakeFeed().apply { closed = true }
        val attempts = HashMap<Int, Int>()
        val done = ArrayList<Int>()
        launch {
            NightPump<Int, FakeNets>(gov, workers).run(
                pages = listOf(1, 2),
                open = { kind -> FakeNets(kind) },
                estimate = { 10 * MB },
                page = { p, _ ->
                    val n = (attempts[p] ?: 0) + 1
                    attempts[p] = n
                    delay(300)
                    if (p == 1 && n == 1) {
                        NightPageOutcome.DROPPED
                    } else {
                        done += p
                        NightPageOutcome.DONE
                    }
                },
                onError = { _, t -> throw AssertionError(t) },
                onOomFinal = { throw AssertionError("no OOM expected") },
                shouldStop = { false },
                feed = feed,
            )
        }
        advanceUntilIdle()
        attempts shouldBe mapOf(1 to 2, 2 to 1)
        done.sorted() shouldBe listOf(1, 2)
    }

    @Test
    fun `OOM 的頁在等新頁之前補跑一次，再 OOM 才交給 onOomFinal`() = runTest {
        val gov = governor()
        val workers = StandardTestDispatcher(testScheduler)
        val events = ArrayList<String>()
        val feed = FakeFeed(events).apply { closed = true }
        val attempts = HashMap<Int, Int>()
        val oomFinal = ArrayList<Int>()
        launch {
            NightPump<Int, FakeNets>(gov, workers).run(
                pages = listOf(1, 2),
                open = { kind -> FakeNets(kind) },
                estimate = { 10 * MB },
                page = { p, _ ->
                    val n = (attempts[p] ?: 0) + 1
                    attempts[p] = n
                    events += "p$p#$n"
                    delay(1_000)
                    if (p == 2) NightPageOutcome.OOM else NightPageOutcome.DONE
                },
                onError = { _, t -> throw AssertionError(t) },
                onOomFinal = { p ->
                    events += "oomFinal$p"
                    oomFinal += p
                },
                shouldStop = { false },
                feed = feed,
            )
        }
        advanceUntilIdle()
        attempts shouldBe mapOf(1 to 1, 2 to 2)
        oomFinal shouldBe listOf(2)
        (events.indexOf("p2#2") < events.indexOf("await")) shouldBe true
        (events.indexOf("oomFinal2") < events.indexOf("await")) shouldBe true
    }

    @Test
    fun `有外部頁來源時初始頁也照章內順序做`() = runTest {
        val gov = governor(cap = 1)
        val workers = StandardTestDispatcher(testScheduler)
        val feed = FakeFeed().apply { closed = true }
        val started = ArrayList<Int>()
        launch {
            NightPump<Int, FakeNets>(gov, workers).run(
                pages = listOf(5, 1, 3),
                open = { kind -> FakeNets(kind) },
                estimate = { 10 * MB },
                page = { p, _ ->
                    started += p
                    delay(100)
                    NightPageOutcome.DONE
                },
                onError = { _, t -> throw AssertionError(t) },
                onOomFinal = { throw AssertionError("no OOM expected") },
                shouldStop = { false },
                feed = feed,
            )
        }
        runCurrent()
        advanceUntilIdle()
        started shouldBe listOf(1, 3, 5)
    }
}
