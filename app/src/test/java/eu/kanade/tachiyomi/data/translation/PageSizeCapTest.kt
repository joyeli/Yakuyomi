package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** 翻譯路徑的頁圖像素上限（40 MPx，使用者 2026-10-07 拍板）：寬 × 高大於上限才擋，等於照翻；讀不出尺寸另成一類。 */
class PageSizeCapTest {

    @Test
    fun `上限是 4000 萬像素`() {
        PageSizeCap.MAX_PIXELS shouldBe 40_000_000L
    }

    @Test
    fun `一般漫畫頁照翻`() {
        PageSizeCap.check(1200, 1700) shouldBe PageSizeCap.Verdict.Ok
        PageSizeCap.check(2400, 3500) shouldBe PageSizeCap.Verdict.Ok
        // 條漫長條：800 寬、4 萬高＝3200 萬像素，還在上限內
        PageSizeCap.check(800, 40_000) shouldBe PageSizeCap.Verdict.Ok
    }

    @Test
    fun `剛好等於上限照翻，多一個像素就擋`() {
        PageSizeCap.check(5000, 8000) shouldBe PageSizeCap.Verdict.Ok // 40,000,000
        PageSizeCap.check(8000, 5000) shouldBe PageSizeCap.Verdict.Ok
        PageSizeCap.check(40_000_000, 1) shouldBe PageSizeCap.Verdict.Ok
        PageSizeCap.check(5000, 8001) shouldBe PageSizeCap.Verdict.TooLarge(5000, 8001) // 40,005,000
        PageSizeCap.check(40_000_001, 1) shouldBe PageSizeCap.Verdict.TooLarge(40_000_001, 1)
    }

    @Test
    fun `大掃圖與超長條漫擋下`() {
        PageSizeCap.check(7000, 9000) shouldBe PageSizeCap.Verdict.TooLarge(7000, 9000)
        PageSizeCap.check(1080, 60_000) shouldBe PageSizeCap.Verdict.TooLarge(1080, 60_000)
    }

    @Test
    fun `寬高相乘不溢位`() {
        // Int 相乘會溢位成負數而被誤放行；要用 Long 算
        PageSizeCap.check(70_000, 70_000) shouldBe PageSizeCap.Verdict.TooLarge(70_000, 70_000)
        PageSizeCap.check(Int.MAX_VALUE, Int.MAX_VALUE) shouldBe
            PageSizeCap.Verdict.TooLarge(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    @Test
    fun `檔頭讀不出尺寸算讀不出來，不算太大也不算照翻`() {
        // BitmapFactory 讀不出檔頭時 outWidth／outHeight 是 -1；0 也不是能翻的圖
        PageSizeCap.check(-1, -1) shouldBe PageSizeCap.Verdict.Unreadable
        PageSizeCap.check(0, 0) shouldBe PageSizeCap.Verdict.Unreadable
        PageSizeCap.check(1200, 0) shouldBe PageSizeCap.Verdict.Unreadable
        PageSizeCap.check(-1, 50_000_000) shouldBe PageSizeCap.Verdict.Unreadable
    }

    @Test
    fun `錯誤檔原因寫出尺寸、百萬像素與上限，不含分隔用的 tab 與換行`() {
        val reason = PageSizeCap.tooLargeReason(7000, 9000)
        reason shouldContain "7000×9000"
        reason shouldContain "63.0 百萬像素"
        reason shouldContain "上限 40 百萬像素"
        reason shouldContain "保留原圖"
        reason shouldNotContain "\t"
        reason shouldNotContain "\n"
        // 小數點固定用「.」，不跟系統語系變成「,」
        PageSizeCap.tooLargeReason(5000, 8001) shouldContain "40.0 百萬像素"
        PageSizeCap.tooLargeReason(6001, 7001) shouldContain "42.0 百萬像素"
    }

    @Test
    fun `認得出錯誤檔裡「頁圖太大」的原因行（整章重跑時要留著），別的原因不算`() {
        PageSizeCap.isTooLargeReason(PageSizeCap.tooLargeReason(7000, 9000)) shouldBe true
        PageSizeCap.isTooLargeReason("例外 OutOfMemoryError: null") shouldBe false
        PageSizeCap.isTooLargeReason("讀不出頁圖尺寸（檔案壞掉或不是支援的圖檔）") shouldBe false
        PageSizeCap.isTooLargeReason("") shouldBe false
    }
}
