package eu.kanade.tachiyomi.data.nightread

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * [NightPendingEncodes]（合併輪夜讀的編碼交給編碼執行緒，軌道 B2 的 S5）：工作在別的執行緒上跑、[NightPendingEncodes.awaitAll]
 * 等到全部結束（含還在跑的）、回第一個失敗（照送出順序，不往外拋，Error 也一樣）、拒收時在呼叫執行緒上跑 onRejected 並記成
 * 失敗、可重複等、等的時候被中斷照樣等完並補回中斷旗標。
 */
class NightPendingEncodesTest {

    private val pool = Executors.newFixedThreadPool(2)

    @Test
    fun `工作在編碼執行緒上跑，awaitAll 等到全部做完`() {
        val q = NightPendingEncodes(pool)
        val caller = Thread.currentThread()
        val threads = Collections.synchronizedList(ArrayList<Thread>())
        val release = CountDownLatch(1)
        val done = Collections.synchronizedList(ArrayList<String>())
        q.submit(onRejected = {}) {
            threads += Thread.currentThread()
            check(release.await(5, TimeUnit.SECONDS))
            done += "std"
        }
        q.submit(onRejected = {}) {
            threads += Thread.currentThread()
            done += "more"
        }
        // 呼叫端不會被卡住：第一件還沒放行
        q.submitted shouldBe 2
        Thread {
            Thread.sleep(50)
            release.countDown()
        }.start()
        q.awaitAll() shouldBe null
        done.toSet() shouldBe setOf("std", "more")
        threads.none { it === caller } shouldBe true
        pool.shutdown()
    }

    @Test
    fun `失敗收起來、回第一個（照送出順序），Error 也一樣，可重複等`() {
        val q = NightPendingEncodes(pool)
        val first = IllegalStateException("夜讀版寫檔失敗")
        q.submit(onRejected = {}) {
            Thread.sleep(30)
            throw first
        }
        q.submit(onRejected = {}) { throw OutOfMemoryError("壓縮") }
        q.awaitAll() shouldBe first
        q.awaitAll() shouldBe first
        pool.shutdown()
    }

    @Test
    fun `拒收：onRejected 在呼叫執行緒上跑，記成失敗，後面照樣能送`() {
        var accept = false
        val rejecting = Executor { r ->
            if (!accept) throw RejectedExecutionException("關閉中")
            r.run()
        }
        val q = NightPendingEncodes(rejecting)
        val caller = Thread.currentThread()
        var rejectedOn: Thread? = null
        var ran = false
        q.submit(onRejected = { rejectedOn = Thread.currentThread() }) { ran = true }
        rejectedOn shouldBe caller
        ran shouldBe false
        (q.awaitAll() is RejectedExecutionException) shouldBe true
        accept = true
        q.submit(onRejected = {}) { ran = true }
        ran shouldBe true
        (q.awaitAll() is RejectedExecutionException) shouldBe true // 第一個失敗還是那件
    }

    @Test
    fun `沒送過任何工作：awaitAll 立刻回 null`() {
        NightPendingEncodes(pool).awaitAll() shouldBe null
        pool.shutdown()
    }

    @Test
    fun `等的時候被中斷：照樣等到工作做完，中斷旗標補回去`() {
        val q = NightPendingEncodes(pool)
        val finished = CountDownLatch(1)
        q.submit(onRejected = {}) {
            Thread.sleep(100)
            finished.countDown()
        }
        Thread.currentThread().interrupt()
        q.awaitAll() shouldBe null
        finished.count shouldBe 0L
        Thread.interrupted() shouldBe true
        (q.workMs >= 90) shouldBe true
        pool.shutdown()
    }
}
