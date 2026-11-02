package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [NightHandoff]（合併輪譯圖的交接，軌道 B2 的 S3／S4）：落地回報只認第一次、等落地的四種結果（成功、失敗、被要求停、逾時）、
 * 遮罩借用中被關要等最後一個還回來才回收、關掉之後不再借得到也不再有位元組、關掉＝把沒回報的落地當失敗、可重複關。
 */
class NightHandoffTest {

    private class Mask(val id: Int) {
        var recycled = 0
    }

    private fun handoff(mask: Mask? = Mask(1)) = NightHandoff(
        bytes = ByteArray(16) { it.toByte() },
        length = 10,
        width = 4,
        height = 3,
        quads = listOf(listOf(listOf(1f, 2f), listOf(3f, 4f), listOf(5f, 6f), listOf(7f, 8f))),
        mask = mask,
        recycle = { m: Mask -> m.recycled++ },
    )

    @Test
    fun `落地成功：等得到成功與頁檔快照，之後的失敗回報不算`() {
        val h = handoff()
        h.landingNow() shouldBe null
        h.land(mtime = 1234L, size = 99L, materialsExact = true)
        h.landFailed()
        val w = h.awaitLanding(stop = { false })
        w shouldBe NightHandoff.Wait.Landed(NightHandoff.Landing(1234L, 99L, true))
        h.landingNow() shouldBe w
    }

    @Test
    fun `落地失敗：之後的成功回報不算`() {
        val h = handoff()
        h.landFailed()
        h.land(1L, 2L, true)
        h.awaitLanding(stop = { false }) shouldBe NightHandoff.Wait.Failed
    }

    @Test
    fun `等落地時另一條執行緒回報：等的人醒來拿到結果`() {
        val h = handoff()
        val started = CountDownLatch(1)
        var got: NightHandoff.Wait? = null
        val t = thread {
            started.countDown()
            got = h.awaitLanding(stop = { false }, pollMs = 1_000, timeoutMs = 10_000)
        }
        started.await(5, TimeUnit.SECONDS) shouldBe true
        Thread.sleep(50)
        h.land(7L, 8L, materialsExact = false)
        t.join(5_000)
        got shouldBe NightHandoff.Wait.Landed(NightHandoff.Landing(7L, 8L, false))
    }

    @Test
    fun `等落地時被要求停或等太久`() {
        val h = handoff()
        var asks = 0
        h.awaitLanding(stop = { ++asks >= 3 }, pollMs = 5, timeoutMs = 10_000) shouldBe NightHandoff.Wait.Stopped
        asks shouldBe 3
        h.awaitLanding(stop = { false }, pollMs = 5, timeoutMs = 30) shouldBe NightHandoff.Wait.TimedOut
        // 等的時候被中斷：照樣等到結果，中斷旗標補回去
        Thread.currentThread().interrupt()
        h.awaitLanding(stop = { false }, pollMs = 5, timeoutMs = 30) shouldBe NightHandoff.Wait.TimedOut
        Thread.interrupted() shouldBe true
    }

    @Test
    fun `遮罩借用中被關：最後一個還回來才回收，之後借不到`() {
        val m = Mask(1)
        val h = handoff(m)
        h.hasMask shouldBe true
        h.acquireMask() shouldBe m
        h.acquireMask() shouldBe m
        h.close()
        m.recycled shouldBe 0
        h.acquireMask() shouldBe null
        h.releaseMask()
        m.recycled shouldBe 0
        h.releaseMask()
        m.recycled shouldBe 1
        h.close()
        m.recycled shouldBe 1
    }

    @Test
    fun `沒人借時關掉：遮罩立刻回收、位元組放掉、沒回報的落地算失敗、可重複關`() {
        val m = Mask(2)
        val h = handoff(m)
        h.bytes?.size shouldBe 16
        h.length shouldBe 10
        h.close()
        m.recycled shouldBe 1
        h.bytes shouldBe null
        h.isClosed shouldBe true
        h.landingNow() shouldBe NightHandoff.Wait.Failed
        h.land(1L, 1L, true) // 關掉之後的回報不算
        h.awaitLanding(stop = { false }) shouldBe NightHandoff.Wait.Failed
        h.close()
        m.recycled shouldBe 1
    }

    @Test
    fun `沒有遮罩：借不到、關掉不回收任何東西`() {
        val h = handoff(mask = null)
        h.hasMask shouldBe false
        h.acquireMask() shouldBe null
        h.close()
        h.bytes shouldBe null
    }
}
