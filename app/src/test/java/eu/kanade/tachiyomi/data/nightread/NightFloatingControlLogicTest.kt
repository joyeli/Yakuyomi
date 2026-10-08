package eu.kanade.tachiyomi.data.nightread

import eu.kanade.presentation.reader.components.NightFabLayout
import eu.kanade.presentation.reader.components.NightFabPosition
import eu.kanade.presentation.reader.components.NightFabPosition.Side
import eu.kanade.presentation.reader.components.NightFabWake
import eu.kanade.presentation.reader.components.NightFabWake.ToggleAction
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale

/** 夜讀懸浮鈕的純邏輯（位置存取與換算、閒置變淡與防誤觸）。放在 nightread 套件下，跟著夜讀的測試一起跑。 */
class NightFloatingControlLogicTest {

    @Test
    fun `位置：解析與編碼`() {
        NightFabPosition.parse(null) shouldBe NightFabPosition.DEFAULT
        NightFabPosition.parse("") shouldBe NightFabPosition.DEFAULT
        NightFabPosition.parse("R") shouldBe NightFabPosition(Side.RIGHT, null)
        NightFabPosition.parse("L") shouldBe NightFabPosition(Side.LEFT, null)
        NightFabPosition.parse("l:0.25") shouldBe NightFabPosition(Side.LEFT, 0.25f)
        NightFabPosition.parse("R:abc") shouldBe NightFabPosition(Side.RIGHT, null)
        NightFabPosition.parse("R:NaN") shouldBe NightFabPosition(Side.RIGHT, null)
        // 不認得的邊＝右；比例夾到 0..1
        NightFabPosition.parse("X:2") shouldBe NightFabPosition(Side.RIGHT, 1f)
        NightFabPosition.parse("L:-1") shouldBe NightFabPosition(Side.LEFT, 0f)

        NightFabPosition.DEFAULT.encode() shouldBe "R"
        NightFabPosition(Side.LEFT, null).encode() shouldBe "L"
        NightFabPosition(Side.LEFT, 0.5f).encode() shouldBe "L:0.5000"
        // 小數點不隨系統語系變逗號
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            NightFabPosition(Side.RIGHT, 0.125f).encode() shouldBe "R:0.1250"
        } finally {
            Locale.setDefault(saved)
        }
        val p = NightFabPosition(Side.LEFT, 0.3333f)
        NightFabPosition.parse(p.encode()) shouldBe p
    }

    @Test
    fun `版面：夜讀膠囊的檔位槽數＝產品檔位數（標準／更多）`() {
        NightFabLayout.TIER_SLOTS shouldBe NightLevel.entries.size
        NightFabLayout.TIER_SLOTS shouldBe 2
        // 左右各 2 ＋ 開關槽 44 ＋ 分隔線 1 ＋ 兩個檔位槽各 36
        NightFabLayout.NIGHT_WIDTH_DP shouldBe 121f
    }

    @Test
    fun `版面：離邊 8dp 量視覺邊、日常圓鈕觸控框多 6dp`() {
        val l = NightFabLayout(400f, 800f, 0f, 0f, 0f, 0f, density = 1f)
        l.boxHeight shouldBe 48f
        l.boxWidth(false) shouldBe 48f
        l.boxWidth(true) shouldBe 121f
        // 日常：觸控框 48、圓 36 → 觸控框離邊 2、圓離邊 8
        l.left(Side.LEFT, night = false) shouldBe 2f
        l.left(Side.RIGHT, night = false) shouldBe 350f
        // 夜讀膠囊左右沒有多出來的觸控區
        l.left(Side.LEFT, night = true) shouldBe 8f
        l.left(Side.RIGHT, night = true) shouldBe 271f
        // 上下：觸控框上下各多 6dp
        l.minTop shouldBe 2f
        l.maxTop shouldBe 750f
        // 預設：視覺底邊離底 96dp → 視覺上緣 668、觸控框上緣 662
        l.defaultTop shouldBe 662f
        l.top(NightFabPosition.DEFAULT) shouldBe 662f
    }

    @Test
    fun `版面：比例往返、夾在範圍內、inset 與密度`() {
        val l = NightFabLayout(1080f, 2400f, 0f, 96f, 0f, 144f, density = 3f)
        val top = l.top(NightFabPosition(Side.RIGHT, 0.4f))
        l.fracOf(top) shouldBe (0.4f plusOrMinus 1e-5f)
        l.top(NightFabPosition(Side.RIGHT, 0f)) shouldBe l.minTop
        l.top(NightFabPosition(Side.RIGHT, 1f)) shouldBe l.maxTop
        l.minTop shouldBe 96f + 24f - 18f
        l.maxTop shouldBe 2400f - 144f - 24f + 18f - 144f
        l.fracOf(-1000f) shouldBe 0f
        l.fracOf(99999f) shouldBe 1f
        l.clampTop(-5f) shouldBe l.minTop
        l.clampTop(1e9f) shouldBe l.maxTop
        l.clampLeft(-10f, night = true) shouldBe 0f
        l.clampLeft(5000f, night = true) shouldBe 1080f - 121f * 3f
        // 預設位置在範圍內
        (l.defaultTop in l.minTop..l.maxTop) shouldBe true
    }

    @Test
    fun `版面：容器太矮時範圍重合、比例一律 0`() {
        val l = NightFabLayout(300f, 40f, 0f, 0f, 0f, 0f, density = 1f)
        l.maxTop shouldBe l.minTop
        l.fracOf(10f) shouldBe 0f
        l.defaultTop shouldBe l.minTop
    }

    @Test
    fun `吸附：觸控框中心在左半就貼左`() {
        val l = NightFabLayout(400f, 800f, 0f, 0f, 0f, 0f, density = 1f)
        l.snapSide(100f, night = false) shouldBe Side.LEFT
        l.snapSide(200f, night = false) shouldBe Side.RIGHT
        l.snapSide(100f, night = true) shouldBe Side.LEFT // 中心 160.5
        l.snapSide(150f, night = true) shouldBe Side.RIGHT // 中心 210.5
    }

    @Test
    fun `防誤觸：閒置變淡時夜讀轉日常要兩下`() {
        val w = NightFabWake(awakeMs = 2_500)
        // 一開始是閒置變淡：第一下只喚醒
        w.isFaded(0, alwaysOpaque = false) shouldBe true
        w.onToggleTap(1_000, nightOn = true, alwaysOpaque = false) shouldBe ToggleAction.WAKE_ONLY
        // 2.5 s 內第二下才切換
        w.isFaded(2_000, alwaysOpaque = false) shouldBe false
        w.onToggleTap(2_000, nightOn = true, alwaysOpaque = false) shouldBe ToggleAction.TOGGLE
    }

    @Test
    fun `防誤觸：日常轉夜讀、切檔位不受限；過了 2_5 s 又要兩下`() {
        val w = NightFabWake(awakeMs = 2_500)
        // 日常 → 夜讀：變淡時一下就切
        w.onToggleTap(10_000, nightOn = false, alwaysOpaque = false) shouldBe ToggleAction.TOGGLE
        // 切換後 2.5 s 全亮：夜讀 → 日常一下就切
        w.onToggleTap(12_000, nightOn = true, alwaysOpaque = false) shouldBe ToggleAction.TOGGLE
        // 剛好到期（12_000 + 2_500）＝已變淡
        w.isAwake(14_500) shouldBe false
        w.onToggleTap(14_500, nightOn = true, alwaysOpaque = false) shouldBe ToggleAction.WAKE_ONLY
        // 點檔位也會喚醒，之後夜讀 → 日常一下就切
        w.onTierTap(30_000)
        w.onToggleTap(31_000, nightOn = true, alwaysOpaque = false) shouldBe ToggleAction.TOGGLE
    }

    @Test
    fun `防誤觸：e-ink 或無障礙導覽固定不透明、沒有兩下規則`() {
        val w = NightFabWake(awakeMs = 2_500)
        w.isFaded(0, alwaysOpaque = true) shouldBe false
        w.onToggleTap(100_000, nightOn = true, alwaysOpaque = true) shouldBe ToggleAction.TOGGLE
        w.onToggleTap(900_000, nightOn = true, alwaysOpaque = true) shouldBe ToggleAction.TOGGLE
    }

    @Test
    fun `閒置透明度：日常 0_35、夜讀 0_45`() {
        NightFabWake.idleAlpha(nightOn = false) shouldBe 0.35f
        NightFabWake.idleAlpha(nightOn = true) shouldBe 0.45f
    }
}
