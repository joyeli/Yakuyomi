package eu.kanade.tachiyomi.data.nightread

import eu.kanade.tachiyomi.data.nightread.NightConcurrency.MB
import io.kotest.matchers.shouldBe
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
import org.junit.jupiter.api.Test

/** [ChannelNightFeed]：合併處理的夜讀段用程式送頁（翻譯落地一頁就送一頁）。 */
class ChannelNightFeedTest {

    @Test
    fun `poll 取走全部、不等待`() {
        val feed = ChannelNightFeed<Int> { it }
        feed.poll() shouldBe emptyList()
        feed.offer(3) shouldBe true
        feed.offer(1) shouldBe true
        feed.poll() shouldBe listOf(3, 1)
        feed.poll() shouldBe emptyList()
        feed.order(7) shouldBe 7
    }

    @Test
    fun `awaitMore 等到有頁才回`() = runTest {
        val feed = ChannelNightFeed<Int> { it }
        val got = async { feed.awaitMore { false } }
        advanceTimeBy(1_000)
        runCurrent()
        got.isCompleted shouldBe false
        feed.offer(5)
        runCurrent()
        got.isCompleted shouldBe true
        got.await() shouldBe listOf(5)
    }

    @Test
    fun `close 之後先交完剩下的頁再回 null，之後 offer 回 false`() = runTest {
        val feed = ChannelNightFeed<Int> { it }
        feed.offer(1)
        feed.offer(2)
        feed.close()
        feed.offer(3) shouldBe false
        feed.awaitMore { false } shouldBe listOf(1, 2)
        feed.awaitMore { false } shouldBe null
        feed.poll() shouldBe emptyList()
    }

    @Test
    fun `等待中 close 會叫醒並回 null`() = runTest {
        val feed = ChannelNightFeed<Int> { it }
        val got = async { feed.awaitMore { false } }
        advanceTimeBy(600)
        runCurrent()
        got.isCompleted shouldBe false
        feed.close()
        runCurrent()
        got.isCompleted shouldBe true
        got.await() shouldBe null
    }

    @Test
    fun `shouldStop 變 true 後 300 ms 內回 null`() = runTest {
        val feed = ChannelNightFeed<Int> { it }
        var stop = false
        var returnedAt = -1L
        val got = async {
            feed.awaitMore { stop }.also { returnedAt = testScheduler.currentTime }
        }
        advanceTimeBy(1_000)
        runCurrent()
        got.isCompleted shouldBe false
        stop = true
        val stoppedAt = testScheduler.currentTime
        advanceTimeBy(300)
        runCurrent()
        got.isCompleted shouldBe true
        got.await() shouldBe null
        (returnedAt - stoppedAt in 0..300) shouldBe true
    }

    private class FakeNets : AutoCloseable {
        var closed = 0

        override fun close() {
            closed++
        }
    }

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

    @Test
    fun `配 NightPump：頁依 order 做完，最後只關組一次`() = runTest {
        val gov = governor(cap = 1)
        val workers = StandardTestDispatcher(testScheduler)
        val feed = ChannelNightFeed<Int> { it }
        val opened = ArrayList<FakeNets>()
        val done = ArrayList<Int>()
        launch {
            delay(200)
            feed.offer(30)
            feed.offer(10)
            feed.offer(20)
            delay(5_000)
            feed.offer(40)
            delay(100)
            feed.close()
        }
        launch {
            NightPump<Int, FakeNets>(gov, workers).run(
                pages = emptyList(),
                open = { _ -> FakeNets().also { n -> opened += n } },
                estimate = { 10 * MB },
                page = { p, ticket ->
                    ticket.nets.closed shouldBe 0
                    delay(500)
                    done += p
                    NightPageOutcome.DONE
                },
                onError = { _, t -> throw AssertionError(t) },
                onOomFinal = { throw AssertionError("no OOM expected") },
                shouldStop = { false },
                feed = feed,
            )
        }
        advanceUntilIdle()
        done shouldBe listOf(10, 20, 30, 40)
        opened.size shouldBe 1
        opened.single().closed shouldBe 1
    }
}
