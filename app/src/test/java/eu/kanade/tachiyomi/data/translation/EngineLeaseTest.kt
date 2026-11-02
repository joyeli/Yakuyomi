package eu.kanade.tachiyomi.data.translation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 夜讀借翻譯引擎偵測器的租約（S1，[EngineLease]）：借出條件、關引擎的路徑停借並等借出的那次偵測做完、等不到就這次不關、
 * 非 suspend 路徑不等，以及判「同一顆模型」的檔案戳記（[ModelStamp]）。
 */
class EngineLeaseTest {

    private data class Eng(val path: String)

    @Test
    fun `沒有引擎、路徑不同時借不到；借到要還`() {
        val lease = EngineLease<Eng>()
        lease.acquire { true } shouldBe null
        lease.available { true } shouldBe false
        lease.publish(Eng("a.param"))
        lease.acquire { it.path == "b.param" } shouldBe null
        lease.available { it.path == "a.param" } shouldBe true
        lease.acquire { it.path == "a.param" } shouldBe Eng("a.param")
        lease.outstanding shouldBe 1
        lease.release()
        lease.outstanding shouldBe 0
        shouldThrow<IllegalStateException> { lease.release() }
    }

    @Test
    fun `開始關之後一律不借；沒借出就可以直接關`() {
        val lease = EngineLease<Eng>()
        lease.publish(Eng("a"))
        lease.beginClose() shouldBe 0
        lease.acquire { true } shouldBe null
        lease.available { true } shouldBe false
        // 關掉（publish null）之後仍借不到；新引擎 publish 才恢復
        lease.publish(null)
        lease.acquire { true } shouldBe null
        lease.publish(Eng("b"))
        lease.acquire { true } shouldBe Eng("b")
    }

    @Test
    fun `關的路徑等借出的那次偵測還了才關`() = runTest {
        val lease = EngineLease<Eng>()
        lease.publish(Eng("a"))
        lease.acquire { true } shouldBe Eng("a")
        lease.beginClose() shouldBe 1
        val drained = async { lease.awaitDrained(timeoutMs = 15_000) }
        advanceTimeBy(1_000)
        runCurrent()
        drained.isCompleted shouldBe false
        lease.release()
        advanceTimeBy(100)
        runCurrent()
        drained.await() shouldBe true
    }

    @Test
    fun `等不到就這次不關、恢復借出`() = runTest {
        val lease = EngineLease<Eng>()
        lease.publish(Eng("a"))
        lease.acquire { true }
        lease.beginClose()
        lease.awaitDrained(timeoutMs = 500) shouldBe false
        lease.cancelClose()
        lease.acquire { true } shouldBe Eng("a")
        lease.outstanding shouldBe 2
    }

    @Test
    fun `非 suspend 路徑：有借出就不關（beginClose 回非 0 後 cancelClose）`() {
        val lease = EngineLease<Eng>()
        lease.publish(Eng("a"))
        lease.acquire { true }
        val n = lease.beginClose()
        n shouldBe 1
        lease.cancelClose()
        lease.available { true } shouldBe true
    }

    @Test
    fun `多緒同時借還，計數最後歸零；關閉中搶不到`() {
        val lease = EngineLease<Eng>()
        lease.publish(Eng("a"))
        val pool = Executors.newFixedThreadPool(8)
        val got = AtomicInteger()
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                start.await()
                repeat(1_000) {
                    if (lease.acquire { true } != null) {
                        got.incrementAndGet()
                        lease.release()
                    }
                }
                done.countDown()
            }
        }
        start.countDown()
        done.await(10, TimeUnit.SECONDS) shouldBe true
        pool.shutdown()
        got.get() shouldBe 8_000
        lease.outstanding shouldBe 0
        lease.beginClose() shouldBe 0
        lease.acquire { true } shouldBe null
    }

    @Test
    fun `檔案戳記：兩個檔都在才有；任一個變了就不同`(@TempDir dir: File) {
        val param = File(dir, "dbnet.param").apply { writeText("p") }
        ModelStamp.of(param.path) shouldBe null // 沒有 .bin
        val bin = File(dir, "dbnet.bin").apply { writeText("bin") }
        val a = ModelStamp.of(param.path)
        (a != null) shouldBe true
        ModelStamp.of(param.path) shouldBe a
        bin.writeText("bin-longer")
        (ModelStamp.of(param.path) == a) shouldBe false
        ModelStamp.of(null) shouldBe null
    }
}
