package eu.kanade.tachiyomi.data.nightread

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * 合併輪夜讀多緒模型組跨輪常駐（S2，[NightNetsHolder]）的保留與釋放規則：同一把鑰匙重用、鑰匙不同先關舊的、還回來閒置
 * 60 s 才關、閒置中被再拿走就不關、主動釋放（閒著當場關、借出中等還回來才關）、第二個同時來拿的另開一組不留、多還一次忽略。
 */
class NightNetsHolderTest {

    private class Nets(val id: Int) : AutoCloseable {
        var closed = 0
        override fun close() {
            closed++
        }
    }

    private class Env(scope: TestScope) {
        val logs = ArrayList<String>()
        var opened = 0
        val holder = NightNetsHolder<String, Nets>(
            scope = scope.backgroundScope,
            now = { scope.testScheduler.currentTime },
            log = { logs += it },
        )

        fun acquire(key: String) = holder.acquire(key) { Nets(++opened) }
    }

    @Test
    fun `一輪用完還回來留著，下一輪同一把鑰匙直接重用`() = runTest {
        val env = Env(this)
        val a = env.acquire("k")
        a.reused shouldBe false
        env.holder.holdsNets shouldBe true
        env.holder.giveBack(a)
        advanceTimeBy(30_000)
        runCurrent()
        val b = env.acquire("k")
        b.reused shouldBe true
        b.nets shouldBe a.nets
        env.opened shouldBe 1
        a.nets.closed shouldBe 0
        env.logs.any { it.startsWith("held open") } shouldBe true
        env.logs.any { it.startsWith("held reuse") } shouldBe true
    }

    @Test
    fun `還回來閒置 60 秒沒人拿就關`() = runTest {
        val env = Env(this)
        val a = env.acquire("k")
        env.holder.giveBack(a)
        advanceTimeBy(59_999)
        runCurrent()
        a.nets.closed shouldBe 0
        advanceTimeBy(2)
        runCurrent()
        a.nets.closed shouldBe 1
        env.holder.holdsNets shouldBe false
        env.logs.last() shouldBe "held release reason=idle"
        // 之後再拿＝開新的
        env.acquire("k").reused shouldBe false
        env.opened shouldBe 2
    }

    @Test
    fun `閒置計時中被拿走：計時作廢，用完再還重新計時`() = runTest {
        val env = Env(this)
        val a = env.acquire("k")
        env.holder.giveBack(a)
        advanceTimeBy(50_000)
        runCurrent()
        val b = env.acquire("k")
        advanceTimeBy(30_000) // 舊的計時本來會在這裡到
        runCurrent()
        b.nets.closed shouldBe 0
        env.holder.giveBack(b)
        advanceTimeBy(59_000)
        runCurrent()
        b.nets.closed shouldBe 0
        advanceTimeBy(2_000)
        runCurrent()
        b.nets.closed shouldBe 1
    }

    @Test
    fun `鑰匙不同（模型換了）：先關留著的再開新的`() = runTest {
        val env = Env(this)
        val a = env.acquire("old")
        env.holder.giveBack(a)
        val b = env.acquire("new")
        b.reused shouldBe false
        a.nets.closed shouldBe 1
        (b.nets === a.nets) shouldBe false
        env.logs.contains("held release reason=key-changed") shouldBe true
    }

    @Test
    fun `主動釋放：閒著當場關`() = runTest {
        val env = Env(this)
        val a = env.acquire("k")
        env.holder.giveBack(a)
        env.holder.release("night-off")
        a.nets.closed shouldBe 1
        env.holder.holdsNets shouldBe false
        env.logs.last() shouldBe "held release reason=night-off"
        // 計時已取消：時間到也不會再關一次
        advanceTimeBy(120_000)
        runCurrent()
        a.nets.closed shouldBe 1
    }

    @Test
    fun `主動釋放：借出中不關，還回來時才關、不留`() = runTest {
        val env = Env(this)
        val a = env.acquire("k")
        env.holder.release("trim")
        a.nets.closed shouldBe 0
        env.logs.last() shouldBe "held release deferred reason=trim"
        env.holder.giveBack(a)
        a.nets.closed shouldBe 1
        env.holder.holdsNets shouldBe false
        env.logs.last() shouldBe "held release reason=trim"
        env.acquire("k").reused shouldBe false
    }

    @Test
    fun `改走單緒組：放掉留著的多緒組`() = runTest {
        val env = Env(this)
        val a = env.acquire("k")
        // pump 換組：先關舊組（還回來），再開 FREE 前呼叫 release("free")
        env.holder.giveBack(a)
        env.holder.release("free")
        a.nets.closed shouldBe 1
        env.holder.holdsNets shouldBe false
    }

    @Test
    fun `什麼都沒留著時釋放不記任何事`() = runTest {
        val env = Env(this)
        env.holder.release("trim")
        env.logs shouldBe emptyList()
    }

    @Test
    fun `同時第二個來拿：另開一組不留，還回來就關；留著那組不受影響`() = runTest {
        val env = Env(this)
        val a = env.acquire("k")
        val extra = env.acquire("k")
        extra.reused shouldBe false
        (extra.nets === a.nets) shouldBe false
        env.holder.giveBack(extra)
        extra.nets.closed shouldBe 1
        env.holder.giveBack(a)
        a.nets.closed shouldBe 0
        env.acquire("k").reused shouldBe true
    }

    @Test
    fun `同一次借用多還一次忽略`() = runTest {
        val env = Env(this)
        val a = env.acquire("k")
        env.holder.giveBack(a)
        val b = env.acquire("k")
        env.holder.giveBack(a) // 舊的那次又還一次：不得把正在用的 b 當成還回來
        env.holder.release("trim")
        b.nets.closed shouldBe 0
        env.holder.giveBack(b)
        b.nets.closed shouldBe 1
    }

    @Test
    fun `開組失敗什麼都不留`() = runTest {
        val env = Env(this)
        val r = runCatching { env.holder.acquire("k") { error("載不起來") } }
        r.isFailure shouldBe true
        env.holder.holdsNets shouldBe false
        env.acquire("k").reused shouldBe false
    }
}
