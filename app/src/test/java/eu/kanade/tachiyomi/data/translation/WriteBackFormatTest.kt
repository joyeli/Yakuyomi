package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** 譯圖寫回頁檔的格式跟著副檔名走（使用者 2026-10-07 拍板）：png → PNG、webp → 有損 WebP、其他 → JPEG。 */
class WriteBackFormatTest {

    @Test
    fun `png 頁寫 PNG`() {
        WriteBackFormat.forFileName("001.png") shouldBe WriteBackFormat.PNG
        WriteBackFormat.forFileName("001.PNG") shouldBe WriteBackFormat.PNG
        WriteBackFormat.forFileName("ch.1_001.Png") shouldBe WriteBackFormat.PNG
    }

    @Test
    fun `webp 頁寫 WebP（以前寫成 JPEG）`() {
        WriteBackFormat.forFileName("001.webp") shouldBe WriteBackFormat.WEBP
        WriteBackFormat.forFileName("001.WEBP") shouldBe WriteBackFormat.WEBP
        WriteBackFormat.forFileName("vol.2.001.WebP") shouldBe WriteBackFormat.WEBP
    }

    @Test
    fun `jpg、jpeg 與其他副檔名照舊寫 JPEG`() {
        WriteBackFormat.forFileName("001.jpg") shouldBe WriteBackFormat.JPEG
        WriteBackFormat.forFileName("001.JPEG") shouldBe WriteBackFormat.JPEG
        WriteBackFormat.forFileName("001.gif") shouldBe WriteBackFormat.JPEG
        WriteBackFormat.forFileName("001.avif") shouldBe WriteBackFormat.JPEG
    }

    @Test
    fun `看的是最後一個副檔名，不是檔名裡的字`() {
        WriteBackFormat.forFileName("png.jpg") shouldBe WriteBackFormat.JPEG
        WriteBackFormat.forFileName("webp_cover.png") shouldBe WriteBackFormat.PNG
        WriteBackFormat.forFileName("001.png.jpg") shouldBe WriteBackFormat.JPEG
        WriteBackFormat.forFileName("001.jpg.webp") shouldBe WriteBackFormat.WEBP
    }

    @Test
    fun `沒有副檔名或檔名讀不到時寫 JPEG`() {
        WriteBackFormat.forFileName("001") shouldBe WriteBackFormat.JPEG
        WriteBackFormat.forFileName("") shouldBe WriteBackFormat.JPEG
        WriteBackFormat.forFileName("001.") shouldBe WriteBackFormat.JPEG
        WriteBackFormat.forFileName(null) shouldBe WriteBackFormat.JPEG
    }

    @Test
    fun `WebP 與 JPEG 用同一個品質`() {
        WriteBackFormat.QUALITY shouldBe 92
    }

    @Test
    fun `各格式的尺寸上限：WebP 16383、JPEG 65500、PNG 不設限`() {
        WriteBackFormat.WEBP.fits(16_383, 16_383) shouldBe true
        WriteBackFormat.WEBP.fits(16_384, 1000) shouldBe false
        WriteBackFormat.WEBP.fits(1000, 16_384) shouldBe false
        WriteBackFormat.JPEG.fits(800, 65_500) shouldBe true
        WriteBackFormat.JPEG.fits(800, 65_501) shouldBe false
        WriteBackFormat.PNG.fits(600, 70_000) shouldBe true
        // 一般頁三種都寫得下
        WriteBackFormat.entries.forEach { it.fits(1351, 1920) shouldBe true }
        // 空圖寫不出來
        WriteBackFormat.entries.forEach { it.fits(0, 1920) shouldBe false }
    }
}
