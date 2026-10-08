package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 啟動預暖的流程（[StartupPrewarmController]）：背景叫起來的行程不預暖、前景等第一個畫面＋settle、回到背景取消、主畫面還在就
 * 不再等訊號、每個行程最多一次、寫紀錄與查重要度丟到 io。決策本身另有 StartupPrewarmPolicyTest。
 */
class StartupPrewarmControllerTest {

    private class FakeEnv : PrewarmEnv {
        var master = true
        var live = true
        var warm = false
        var loading = false
        var ready = true
        var foreground = true
        var importanceValue: Int? = 100
        var starts = 0
        var readyCalls = 0
        var importanceCalls = 0
        val lines = ArrayList<String>()

        override fun masterEnabled() = master
        override fun liveTranslate() = live
        override fun engineWarm() = warm
        override fun engineLoading() = loading
        override fun engineReady(): Boolean {
            readyCalls++
            return ready
        }
        override fun stillForeground() = foreground
        override fun startWarmUp() {
            starts++
        }
        override fun importance(): Int? {
            importanceCalls++
            return importanceValue
        }
        override fun sinceProcessStartMs() = 42L
        override fun log(line: String) {
            lines += line
        }
        override fun logError(e: Throwable) = Unit
    }

    /** 數派工次數的 io（delay 委派給內層的測試排程器）。 */
    @OptIn(InternalCoroutinesApi::class)
    private class CountingDispatcher(private val inner: CoroutineDispatcher) :
        CoroutineDispatcher(),
        Delay by (inner as Delay) {
        var dispatches = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            inner.dispatch(context, block)
        }
    }

    private class Rig(scope: TestScope) {
        val env = FakeEnv()
        val io = CountingDispatcher(StandardTestDispatcher(scope.testScheduler))
        val c = StartupPrewarmController(scope.backgroundScope, io, env)
    }

    private val settle = StartupPrewarmController.SETTLE
    private val timeout = StartupPrewarmController.NO_SIGNAL_TIMEOUT

    @Test
    fun `background-started process never prewarms`() = runTest {
        val r = Rig(this)
        r.env.importanceValue = 300
        r.c.onProcessStart(masterEnabled = true, liveTranslate = true)
        r.env.lines.shouldBeEmpty() // 紀錄丟到 io，不在呼叫端寫
        runCurrent()
        r.env.lines shouldBe
            listOf("prewarm deferred reason=background-start importance=300(service) sinceProcessStart=42ms")
        advanceTimeBy(60.seconds)
        runCurrent()
        r.env.starts shouldBe 0
        r.env.readyCalls shouldBe 0
        r.c.everForeground shouldBe false
    }

    @Test
    fun `user-opened process prewarms once the first screen is up and settled`() = runTest {
        val r = Rig(this)
        r.c.onProcessStart(masterEnabled = true, liveTranslate = true)
        r.c.onForeground()
        r.c.everForeground shouldBe true
        advanceTimeBy(1.seconds)
        r.c.onFirstScreenReady()
        advanceTimeBy(settle - 1.milliseconds)
        runCurrent()
        r.env.starts shouldBe 0
        advanceTimeBy(2.milliseconds)
        runCurrent()
        r.env.starts shouldBe 1
        r.env.lines.first() shouldStartWith "prewarm deferred reason=wait-first-screen importance=100(foreground)"
        r.env.lines.last() shouldStartWith "prewarm started reason=first-screen-ready"
    }

    @Test
    fun `no first-screen signal decides after the timeout`() = runTest {
        val r = Rig(this)
        r.c.onProcessStart(masterEnabled = true, liveTranslate = true)
        r.c.onForeground()
        advanceTimeBy(timeout + settle - 1.milliseconds)
        runCurrent()
        r.env.starts shouldBe 0
        advanceTimeBy(2.milliseconds)
        runCurrent()
        r.env.starts shouldBe 1
        r.env.lines.last() shouldStartWith "prewarm started reason=no-first-screen-signal"
    }

    @Test
    fun `going to the background cancels, and coming back does not wait for the signal again`() = runTest {
        val r = Rig(this)
        r.c.onProcessStart(masterEnabled = true, liveTranslate = true)
        r.c.onForeground()
        r.c.onFirstScreenReady()
        advanceTimeBy(1.seconds)
        r.c.onBackground()
        runCurrent()
        r.env.lines.last() shouldStartWith "prewarm deferred reason=went-background"
        advanceTimeBy(10.seconds)
        runCurrent()
        r.env.starts shouldBe 0
        // 主畫面還在（Activity 沒被銷毀）：回來只等 settle，不用等滿逾時（以前回到背景就把訊號重設、每次都等 6 + 2 秒）
        r.c.onForeground()
        advanceTimeBy(settle + 1.milliseconds)
        runCurrent()
        r.env.starts shouldBe 1
        r.env.lines.last() shouldStartWith "prewarm started reason=first-screen-ready"
    }

    @Test
    fun `a destroyed main screen has to show again before the prewarm`() = runTest {
        val r = Rig(this)
        r.c.onProcessStart(masterEnabled = true, liveTranslate = true)
        r.c.onFirstScreenReady()
        r.c.onScreenGone()
        r.c.onForeground()
        advanceTimeBy(3.seconds)
        runCurrent()
        r.env.starts shouldBe 0
        r.c.onFirstScreenReady()
        advanceTimeBy(settle + 1.milliseconds)
        runCurrent()
        r.env.starts shouldBe 1
    }

    @Test
    fun `at most one prewarm per process`() = runTest {
        val r = Rig(this)
        r.c.onProcessStart(masterEnabled = true, liveTranslate = true)
        r.c.onFirstScreenReady()
        r.c.onForeground()
        advanceTimeBy(settle + 1.milliseconds)
        runCurrent()
        r.env.starts shouldBe 1
        r.c.onBackground()
        r.env.warm = false // 例如記憶體壓力放掉了
        r.c.onForeground()
        advanceTimeBy(20.seconds)
        runCurrent()
        r.env.starts shouldBe 1
    }

    @Test
    fun `switches off at process start means no prewarm and no importance query`() = runTest {
        val r = Rig(this)
        r.c.onProcessStart(masterEnabled = false, liveTranslate = true)
        runCurrent()
        r.env.lines shouldBe listOf("prewarm skipped reason=translation-off sinceProcessStart=42ms")
        r.env.importanceCalls shouldBe 0
        r.c.onForeground()
        r.c.onFirstScreenReady()
        advanceTimeBy(20.seconds)
        runCurrent()
        r.env.starts shouldBe 0
        r.c.everForeground shouldBe true
    }

    @Test
    fun `already warm or missing models skip without starting, and the file check runs on io`() = runTest {
        val r = Rig(this)
        r.env.warm = true
        r.c.onProcessStart(masterEnabled = true, liveTranslate = true)
        r.c.onFirstScreenReady()
        r.c.onForeground()
        advanceTimeBy(settle + 1.milliseconds)
        runCurrent()
        r.env.starts shouldBe 0
        r.env.readyCalls shouldBe 0 // 已經暖了就不查檔案
        r.env.lines.last() shouldStartWith "prewarm skipped reason=already-warm"

        val r2 = Rig(this)
        r2.env.ready = false
        r2.c.onProcessStart(masterEnabled = true, liveTranslate = true)
        r2.c.onFirstScreenReady()
        r2.c.onForeground()
        runCurrent()
        val before = r2.io.dispatches
        advanceTimeBy(settle + 1.milliseconds)
        runCurrent()
        r2.env.starts shouldBe 0
        r2.env.readyCalls shouldBe 1
        (r2.io.dispatches > before) shouldBe true // 檔案檢查與紀錄都經過 io
        r2.env.lines.last() shouldStartWith "prewarm skipped reason=engine-not-ready"
    }

    @Test
    fun `back in the background by decision time defers to the next foreground`() = runTest {
        val r = Rig(this)
        r.c.onProcessStart(masterEnabled = true, liveTranslate = true)
        r.c.onFirstScreenReady()
        r.c.onForeground()
        r.env.foreground = false // ON_STOP 還沒到，但判斷當下已經不在前景
        advanceTimeBy(settle + 1.milliseconds)
        runCurrent()
        r.env.starts shouldBe 0
        r.env.lines.last() shouldStartWith "prewarm deferred reason=went-background"
        r.env.foreground = true
        r.c.onForeground()
        advanceTimeBy(settle + 1.milliseconds)
        runCurrent()
        r.env.starts shouldBe 1
    }
}
