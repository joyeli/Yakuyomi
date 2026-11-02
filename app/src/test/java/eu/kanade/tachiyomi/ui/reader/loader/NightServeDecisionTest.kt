package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.ui.reader.loader.NightPageStreams.Served
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * 閱讀器每頁「送什麼、壓不壓暗」：合併處理的 hold（白天與夜讀都送原圖素材）、夜讀檔、頁檔，以及夜讀模式下沒有夜讀版的頁
 * 依目前檔位的壓暗值壓暗（只影響顯示）。
 */
class NightServeDecisionTest {

    /** 預設＝一頁「該 hold」的狀態（章層級 hold 生效、有記號、有原圖、沒夜讀版、沒放行過），白天、壓暗開 50。 */
    private fun decide(
        effectiveNight: Boolean = false,
        holdActive: Boolean = true,
        hasHoldMarker: Boolean = true,
        hasOriginal: Boolean = true,
        nightDone: Boolean = false,
        nightForTier: Boolean = false,
        stickyReleased: Boolean = false,
        dimEnabled: Boolean = true,
        dimPercent: Int = 50,
    ) = NightServeDecision.decide(
        effectiveNight = effectiveNight,
        holdActive = holdActive,
        hasHoldMarker = hasHoldMarker,
        hasOriginal = hasOriginal,
        nightDone = nightDone,
        nightForTier = nightForTier,
        stickyReleased = stickyReleased,
        dimEnabled = dimEnabled,
        dimPercent = dimPercent,
    )

    private fun approx(actual: Float, expected: Float) {
        (abs(actual - expected) < 1e-5f) shouldBe true
    }

    @Test
    fun `hold 的五個條件全成立才送原圖素材，任一翻轉就不 hold`() {
        decide().kind shouldBe Served.HELD_ORIGINAL
        decide(holdActive = false).kind shouldBe Served.PAGE
        decide(hasHoldMarker = false).kind shouldBe Served.PAGE
        decide(hasOriginal = false).kind shouldBe Served.PAGE
        decide(nightDone = true).kind shouldBe Served.PAGE
        decide(stickyReleased = true).kind shouldBe Served.PAGE
        // 夜讀模式下一樣：五個條件任一翻轉就照一般規則（有夜讀檔送夜讀檔、否則頁檔）
        decide(effectiveNight = true).kind shouldBe Served.HELD_ORIGINAL
        decide(effectiveNight = true, holdActive = false).kind shouldBe Served.PAGE
        decide(effectiveNight = true, hasHoldMarker = false).kind shouldBe Served.PAGE
        decide(effectiveNight = true, hasOriginal = false).kind shouldBe Served.PAGE
        decide(effectiveNight = true, stickyReleased = true).kind shouldBe Served.PAGE
        decide(effectiveNight = true, nightDone = true, nightForTier = true).kind shouldBe Served.NIGHT
    }

    @Test
    fun `白天 hold 送原圖素材、不壓暗；夜讀模式 hold 送原圖素材並壓暗`() {
        decide().let {
            it.kind shouldBe Served.HELD_ORIGINAL
            it.dim shouldBe 1f
        }
        decide(effectiveNight = true).let {
            it.kind shouldBe Served.HELD_ORIGINAL
            approx(it.dim, 0.5f)
        }
    }

    @Test
    fun `夜讀模式有目前檔位的夜讀檔：送夜讀檔、不壓暗`() {
        decide(effectiveNight = true, holdActive = false, nightDone = true, nightForTier = true).let {
            it.kind shouldBe Served.NIGHT
            it.dim shouldBe 1f
        }
        // 沒有合併工作的一般章
        decide(
            effectiveNight = true,
            holdActive = false,
            hasHoldMarker = false,
            hasOriginal = false,
            nightDone = true,
            nightForTier = true,
        ).kind shouldBe Served.NIGHT
        // 白天就算有夜讀檔也送頁檔
        decide(holdActive = false, hasHoldMarker = false, nightDone = true, nightForTier = true).let {
            it.kind shouldBe Served.PAGE
            it.dim shouldBe 1f
        }
    }

    @Test
    fun `夜讀模式沒有夜讀檔：送頁檔並依壓暗值壓暗（夾到 0 到 90）`() {
        fun plain(pct: Int) = decide(
            effectiveNight = true,
            holdActive = false,
            hasHoldMarker = false,
            hasOriginal = false,
            dimPercent = pct,
        )
        plain(50).kind shouldBe Served.PAGE
        plain(0).dim shouldBe 1f
        approx(plain(50).dim, 0.5f)
        approx(plain(65).dim, 0.35f)
        approx(plain(95).dim, 0.1f)
        approx(plain(-10).dim, 1f)
        // 只有標準檔、選「更多」時 resolveName 退回標準＝有檔，這裡由呼叫端給 nightForTier；沒有就壓暗
        decide(effectiveNight = true, holdActive = false, nightDone = true, nightForTier = false).let {
            it.kind shouldBe Served.PAGE
            approx(it.dim, 0.5f)
        }
    }

    @Test
    fun `壓暗關掉或白天：一律不壓暗`() {
        decide(effectiveNight = true, holdActive = false, hasHoldMarker = false, dimEnabled = false).dim shouldBe 1f
        decide(effectiveNight = true, dimEnabled = false).dim shouldBe 1f // hold 中也一樣
        decide(effectiveNight = false, holdActive = false, hasHoldMarker = false, dimPercent = 90).dim shouldBe 1f
        // dimMultiplier 單獨用（沒有 nightStreams 的 loader）：同一套條件
        approx(NightServeDecision.dimMultiplier(true, true, 65, Served.PAGE), 0.35f)
        NightServeDecision.dimMultiplier(true, true, 65, Served.NIGHT) shouldBe 1f
        NightServeDecision.dimMultiplier(false, true, 65, Served.PAGE) shouldBe 1f
        NightServeDecision.dimMultiplier(true, false, 65, Served.PAGE) shouldBe 1f
        approx(NightServeDecision.dimMultiplier(true, true, 65, Served.HELD_ORIGINAL), 0.35f)
    }

    @Test
    fun `黏著放行只在章層級 hold 失效但有記號、有原圖、沒夜讀版時`() {
        decide(holdActive = false).markReleased shouldBe true
        decide(holdActive = false, effectiveNight = true).markReleased shouldBe true
        decide(holdActive = false, stickyReleased = true).markReleased shouldBe true
        decide().markReleased shouldBe false
        decide(holdActive = false, hasHoldMarker = false).markReleased shouldBe false
        decide(holdActive = false, hasOriginal = false).markReleased shouldBe false
        decide(holdActive = false, nightDone = true).markReleased shouldBe false
        // 放行時送的是頁檔（譯圖）
        decide(holdActive = false).kind shouldBe Served.PAGE
    }
}
