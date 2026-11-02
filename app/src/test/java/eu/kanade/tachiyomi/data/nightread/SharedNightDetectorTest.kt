package eu.kanade.tachiyomi.data.nightread

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 合併輪夜讀的偵測來源（S1，[SharedNightDetector]）：先借、借不到才用自己的（必要時建、只建一次）、回合邊界在引擎又借得到時
 * 收掉自己那顆（正在用的不收）、close 關自己那顆。
 */
class SharedNightDetectorTest {

    private class Own(val id: Int) : AutoCloseable {
        var closed = 0
        override fun close() {
            closed++
        }
    }

    private class Env(var lendable: Boolean, own: Own? = null) {
        val opened = ArrayList<Own>()
        val logs = ArrayList<String>()
        val det = SharedNightDetector<Int, String, Own>(
            borrow = { p -> if (lendable) "shared:$p" else null },
            openOwn = { Own(opened.size + 1).also { opened += it } },
            runOwn = { o, p -> "own${o.id}:$p" },
            own = own,
            log = { synchronized(logs) { logs += it } },
        )
    }

    @Test
    fun `借得到就用借的，不建自己的`() {
        val env = Env(lendable = true)
        env.det.detectReport(1) shouldBe ("shared:1" to true)
        env.det.detect(2) shouldBe "shared:2"
        env.opened.size shouldBe 0
        env.det.hasOwn shouldBe false
    }

    @Test
    fun `借不到才建自己的，只建一次；之後又借得到就先借`() {
        val env = Env(lendable = false)
        env.det.detectReport(1) shouldBe ("own1:1" to false)
        env.det.detect(2) shouldBe "own1:2"
        env.opened.size shouldBe 1
        env.det.hasOwn shouldBe true
        env.lendable = true
        env.det.detectReport(3) shouldBe ("shared:3" to true)
        env.opened.size shouldBe 1
    }

    @Test
    fun `開組時就給了自己的那顆：借不到直接用它`() {
        val own = Own(9)
        val env = Env(lendable = false, own = own)
        env.det.detect(1) shouldBe "own9:1"
        env.opened.size shouldBe 0
    }

    @Test
    fun `多頁同時借不到：自己那顆只建一次`() {
        val env = Env(lendable = false)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        val ok = AtomicInteger()
        repeat(4) { i ->
            pool.execute {
                start.await()
                if (env.det.detect(i).startsWith("own1:")) ok.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        done.await(5, TimeUnit.SECONDS) shouldBe true
        pool.shutdown()
        ok.get() shouldBe 4
        env.opened.size shouldBe 1
    }

    @Test
    fun `回合邊界：引擎又借得到才收掉自己那顆`() {
        val env = Env(lendable = false)
        env.det.detect(1)
        val own = env.opened.single()
        env.det.dropOwnIfBorrowable { false } shouldBe false
        own.closed shouldBe 0
        env.det.dropOwnIfBorrowable { true } shouldBe true
        own.closed shouldBe 1
        env.det.hasOwn shouldBe false
        // 沒有自己那顆時什麼都不做
        env.det.dropOwnIfBorrowable { true } shouldBe false
    }

    @Test
    fun `正在用自己那顆時不收`() {
        val inRun = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opened = ArrayList<Own>()
        val det = SharedNightDetector<Int, String, Own>(
            borrow = { null },
            openOwn = { Own(1).also { opened += it } },
            runOwn = { _, p ->
                inRun.countDown()
                release.await(5, TimeUnit.SECONDS)
                "own:$p"
            },
        )
        val t = Thread { det.detect(1) }
        t.start()
        inRun.await(5, TimeUnit.SECONDS) shouldBe true
        det.dropOwnIfBorrowable { true } shouldBe false
        opened.single().closed shouldBe 0
        release.countDown()
        t.join(5_000)
        det.dropOwnIfBorrowable { true } shouldBe true
        opened.single().closed shouldBe 1
    }

    @Test
    fun `close 關自己那顆、只關一次`() {
        val env = Env(lendable = false)
        env.det.detect(1)
        env.det.close()
        env.det.close()
        env.opened.single().closed shouldBe 1
    }
}
