package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * 開 app 時要不要預暖翻譯引擎（[decidePrewarmAtProcessStart]、[decidePrewarmInForeground]）。
 * 重要度數值照 android.jar 的 RunningAppProcessInfo：FOREGROUND＝100、FOREGROUND_SERVICE＝125、VISIBLE＝200、
 * SERVICE＝300、CACHED＝400。
 */
class StartupPrewarmPolicyTest {

    private val bools = listOf(false, true)
    private val importances = listOf(-1, 100, 125, 200, 300, 400)

    @Test
    fun `行程剛起來：不管什麼條件都不在這裡預暖`() {
        for (master in bools) {
            for (live in bools) {
                for (importance in importances) {
                    decidePrewarmAtProcessStart(master, live, importance).action shouldNotBe PrewarmAction.START
                }
            }
        }
    }

    @Test
    fun `行程剛起來：使用者點開的等第一個畫面，背景叫起來的記 background-start`() {
        decidePrewarmAtProcessStart(masterEnabled = true, liveTranslate = true, importance = 100) shouldBe
            PrewarmDecision(PrewarmAction.DEFER, "wait-first-screen")
        for (importance in listOf(-1, 125, 200, 300, 400)) {
            decidePrewarmAtProcessStart(masterEnabled = true, liveTranslate = true, importance = importance) shouldBe
                PrewarmDecision(PrewarmAction.DEFER, "background-start")
        }
    }

    @Test
    fun `行程剛起來：總開關或即時翻譯關著，這個行程不預暖`() {
        decidePrewarmAtProcessStart(masterEnabled = false, liveTranslate = true, importance = 100) shouldBe
            PrewarmDecision(PrewarmAction.SKIP, "translation-off")
        decidePrewarmAtProcessStart(masterEnabled = false, liveTranslate = false, importance = 300) shouldBe
            PrewarmDecision(PrewarmAction.SKIP, "translation-off")
        decidePrewarmAtProcessStart(masterEnabled = true, liveTranslate = false, importance = 100) shouldBe
            PrewarmDecision(PrewarmAction.SKIP, "live-translate-off")
    }

    private fun foreground(
        trigger: PrewarmTrigger = PrewarmTrigger.FIRST_SCREEN,
        stillForeground: Boolean = true,
        master: Boolean = true,
        live: Boolean = true,
        ready: Boolean = true,
        warm: Boolean = false,
        loading: Boolean = false,
    ) = decidePrewarmInForeground(trigger, stillForeground, master, live, ready, warm, loading)

    @Test
    fun `前景：條件都齊才預暖，原因記是被什麼叫起來的`() {
        foreground() shouldBe PrewarmDecision(PrewarmAction.START, "first-screen-ready")
        foreground(trigger = PrewarmTrigger.NO_SCREEN_SIGNAL) shouldBe
            PrewarmDecision(PrewarmAction.START, "no-first-screen-signal")
    }

    @Test
    fun `前景：判斷當下已經回到背景，延後到下次回前景`() {
        foreground(stillForeground = false) shouldBe PrewarmDecision(PrewarmAction.DEFER, "went-background")
        // 就算其他條件也不符，回到背景仍是延後（下次回前景再看）
        foreground(stillForeground = false, master = false, warm = true) shouldBe
            PrewarmDecision(PrewarmAction.DEFER, "went-background")
    }

    @Test
    fun `前景：各種不預暖的原因`() {
        foreground(master = false) shouldBe PrewarmDecision(PrewarmAction.SKIP, "translation-off")
        foreground(live = false) shouldBe PrewarmDecision(PrewarmAction.SKIP, "live-translate-off")
        foreground(warm = true) shouldBe PrewarmDecision(PrewarmAction.SKIP, "already-warm")
        foreground(loading = true) shouldBe PrewarmDecision(PrewarmAction.SKIP, "already-loading")
        foreground(ready = false) shouldBe PrewarmDecision(PrewarmAction.SKIP, "engine-not-ready")
        // 引擎已經在了就不管金鑰／模型檢查（呼叫端那時根本不查，傳 false）
        foreground(warm = true, ready = false) shouldBe PrewarmDecision(PrewarmAction.SKIP, "already-warm")
        foreground(loading = true, ready = false) shouldBe PrewarmDecision(PrewarmAction.SKIP, "already-loading")
    }

    @Test
    fun `前景：預暖若且唯若在前景、兩個開關開、引擎就緒且還沒載`() {
        for (trigger in PrewarmTrigger.entries) {
            // 六個布林的 64 種組合：第 i 位元對應一個條件
            for (bits in 0 until 64) {
                val fg = bits and 1 != 0
                val master = bits and 2 != 0
                val live = bits and 4 != 0
                val ready = bits and 8 != 0
                val warm = bits and 16 != 0
                val loading = bits and 32 != 0
                val start = decidePrewarmInForeground(trigger, fg, master, live, ready, warm, loading).action ==
                    PrewarmAction.START
                start shouldBe (fg && master && live && ready && !warm && !loading)
            }
        }
    }

    @Test
    fun `紀錄那一行`() {
        PrewarmDecision(PrewarmAction.START, "first-screen-ready").logLine() shouldBe
            "prewarm started reason=first-screen-ready"
        PrewarmDecision(PrewarmAction.DEFER, "background-start").logLine() shouldBe
            "prewarm deferred reason=background-start"
        PrewarmDecision(PrewarmAction.SKIP, "already-warm").logLine() shouldBe
            "prewarm skipped reason=already-warm"
    }
}
