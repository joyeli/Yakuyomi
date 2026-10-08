package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.nightread.NightPages
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 保留素材的「原圖」檔名慣例與查找：原檔（位元組原樣，`<base>.orig.<副檔名>`，現行）與舊版有損 WebP
 * （`<base>.orig.webp`，只讀）並存，已存的舊素材要照樣讀得到。
 */
class OriginalMaterialTest {

    @Test
    fun `檔名規則：base 加 orig 加頁檔的副檔名，副檔名照原樣`() {
        OriginalMaterial.exactName("012.jpg") shouldBe "012.orig.jpg"
        OriginalMaterial.exactName("012.jpeg") shouldBe "012.orig.jpeg"
        OriginalMaterial.exactName("012.png") shouldBe "012.orig.png"
        OriginalMaterial.exactName("012.webp") shouldBe "012.orig.webp"
        OriginalMaterial.exactName("012.JPG") shouldBe "012.orig.JPG"
        // 頁檔名裡有別的點：只切最後一個
        OriginalMaterial.exactName("ch.1_012.jpg") shouldBe "ch.1_012.orig.jpg"
        // 沒有副檔名（實際不會發生：列頁只收圖檔副檔名）
        OriginalMaterial.exactName("012") shouldBe "012.orig"

        OriginalMaterial.legacyName("012.jpg") shouldBe "012.orig.webp"
        OriginalMaterial.legacyName("ch.1_012.png") shouldBe "ch.1_012.orig.webp"
        OriginalMaterial.tmpName("012.jpg") shouldBe "012.orig.jpg.tmp"
        OriginalMaterial.inflightName("012.jpg") shouldBe "012.orig.jpg.wip"
        OriginalMaterial.inflightName("012.webp") shouldBe "012.orig.webp.wip"
    }

    @Test
    fun `base 與 json、遮罩用的是同一個`() {
        // PageTranslator 的素材 json／遮罩是 `<頁檔名去副檔名>.json`／`.mask.png`
        listOf("012.jpg", "ch.1_012.png", "012.webp").forEach { page ->
            OriginalMaterial.base(page) shouldBe page.substringBeforeLast('.')
        }
    }

    @Test
    fun `從素材檔名看得出原本的副檔名`() {
        listOf("012.jpg", "012.jpeg", "012.png", "012.webp", "012.JPG", "ch.1_012.jpg").forEach { page ->
            val name = OriginalMaterial.exactName(page)
            OriginalMaterial.pageExtensionOf(name) shouldBe OriginalMaterial.extension(page)
            // base + 副檔名拼回去就是頁檔名
            "${OriginalMaterial.base(page)}.${OriginalMaterial.pageExtensionOf(name)}" shouldBe page
            OriginalMaterial.isOriginalMaterial(name) shouldBe true
        }
        OriginalMaterial.pageExtensionOf("012.orig.webp") shouldBe "webp"
    }

    @Test
    fun `isOriginalMaterial 反例：暫存、遮罩、json、頁圖、夜讀檔都不算`() {
        listOf(
            "012.orig.jpg.tmp",
            "012.orig.webp.tmp",
            "012.orig.jpg.wip",
            "012.orig.webp.wip",
            "012.mask.png",
            "012.json",
            "012.jpg",
            "012.webp",
            "012.jpg.night.std.webp",
            "012.jpg.night.more.webp",
            "012.jpg.night.l1.webp",
            "012.jpg.night.webp",
            "012.jpg.night.std.webp.tmp",
            ".orig.jpg",
            "012.orig",
        ).forEach {
            OriginalMaterial.isOriginalMaterial(it) shouldBe false
            OriginalMaterial.pageExtensionOf(it) shouldBe null
        }
    }

    @Test
    fun `夜讀那邊不會把原圖素材當成夜讀檔`() {
        listOf("012.jpg", "012.png", "012.webp", "012.JPG").forEach { page ->
            listOf(
                OriginalMaterial.exactName(page),
                OriginalMaterial.legacyName(page),
                OriginalMaterial.tmpName(page),
                OriginalMaterial.inflightName(page),
            ).forEach { name ->
                NightPages.isNightArtifact(name) shouldBe false
                NightPages.isFinalNightFile(name) shouldBe false
                NightPages.pageOf(name) shouldBe null
            }
        }
        // 夜讀狀態只看夜讀檔：原圖素材在不在都不影響
        NightPages.chapterState(listOf("012.orig.jpg", "012.orig.webp", "012.mask.png", "012.json")) shouldBe
            NightPages.ChapterState.NONE
    }

    @Test
    fun `查找：有原檔就用原檔、可位元組原樣寫回`() {
        val names = setOf("012.orig.jpg", "012.mask.png", "012.json")
        OriginalMaterial.resolve("012.jpg", names) shouldBe OriginalMaterial.Found("012.orig.jpg", byteCopy = true)
    }

    @Test
    fun `查找：只有舊版 WebP 就讀舊版、還原要重新編碼`() {
        val names = setOf("012.orig.webp", "012.mask.png", "012.json")
        OriginalMaterial.resolve("012.jpg", names) shouldBe OriginalMaterial.Found("012.orig.webp", byteCopy = false)
        OriginalMaterial.resolve("012.png", names) shouldBe OriginalMaterial.Found("012.orig.webp", byteCopy = false)
        OriginalMaterial.resolve("012.jpeg", names) shouldBe OriginalMaterial.Found("012.orig.webp", byteCopy = false)
    }

    @Test
    fun `查找：新舊並存時只看原檔`() {
        val names = setOf("012.orig.jpg", "012.orig.webp", "012.json")
        OriginalMaterial.resolve("012.jpg", names) shouldBe OriginalMaterial.Found("012.orig.jpg", byteCopy = true)
    }

    @Test
    fun `查找：頁檔是 webp 時兩種檔名相同，一律原樣寫回`() {
        OriginalMaterial.exactName("012.webp") shouldBe OriginalMaterial.legacyName("012.webp")
        val names = setOf("012.orig.webp", "012.json")
        OriginalMaterial.resolve("012.webp", names) shouldBe OriginalMaterial.Found("012.orig.webp", byteCopy = true)
        // 沒有可以刪的舊版檔名（刪了就是刪掉原檔自己）
        OriginalMaterial.supersededLegacyName("012.webp") shouldBe null
    }

    @Test
    fun `頁檔副檔名是大寫 WEBP：跟舊版檔名只差大小寫，當成同一個檔`() {
        // 裝置的共用儲存與 SAF 的 findFile 不分大小寫：拿 012.orig.webp 去找會找到剛存好的 012.orig.WEBP。
        // 所以「存好原檔後要刪的舊版」不能回 012.orig.webp，不然刪掉的是原檔自己。
        listOf("012.WEBP", "012.WebP", "012.webP").forEach { page ->
            OriginalMaterial.supersededLegacyName(page) shouldBe null
        }
        // 不分大小寫的儲存：兩個檔名都查得到同一個檔 → 回原檔名、可原樣寫回
        val caseInsensitive = { n: String -> n.equals("012.orig.WEBP", ignoreCase = true) }
        OriginalMaterial.resolve("012.WEBP", caseInsensitive) shouldBe
            OriginalMaterial.Found("012.orig.WEBP", byteCopy = true)
        // 分大小寫的儲存上只有舊版小寫檔：照樣讀得到，內容是 WebP、可原樣寫回 .WEBP 頁檔
        OriginalMaterial.resolve("012.WEBP", setOf("012.orig.webp")) shouldBe
            OriginalMaterial.Found("012.orig.webp", byteCopy = true)
        // 其他格式大小寫不影響：JPG 頁的舊版一樣是要重新編碼的 WebP
        OriginalMaterial.supersededLegacyName("012.JPG") shouldBe "012.orig.webp"
        OriginalMaterial.resolve("012.JPG", setOf("012.orig.webp")) shouldBe
            OriginalMaterial.Found("012.orig.webp", byteCopy = false)
    }

    @Test
    fun `查找：沒有素材、只有暫存、只有別頁的素材都回 null`() {
        OriginalMaterial.resolve("012.jpg", emptySet()) shouldBe null
        OriginalMaterial.resolve("012.jpg", setOf("012.orig.jpg.tmp", "012.json", "012.mask.png")) shouldBe null
        OriginalMaterial.resolve("012.jpg", setOf("013.orig.jpg", "013.orig.webp")) shouldBe null
        // 同 base 不同副檔名的另一頁的原檔，不會被當成這頁的原檔
        OriginalMaterial.resolve("012.jpg", setOf("012.orig.png")) shouldBe null
    }

    @Test
    fun `查找只問它需要的檔名：先原檔、沒有才問舊版`() {
        val asked = mutableListOf<String>()
        OriginalMaterial.resolve("012.jpg") { n ->
            asked += n
            n == "012.orig.jpg"
        } shouldBe OriginalMaterial.Found("012.orig.jpg", byteCopy = true)
        asked shouldBe listOf("012.orig.jpg")

        asked.clear()
        OriginalMaterial.resolve("012.jpg") { n ->
            asked += n
            false
        } shouldBe null
        asked shouldBe listOf("012.orig.jpg", "012.orig.webp")

        // 頁檔是 webp：兩個檔名相同，只問一次
        asked.clear()
        OriginalMaterial.resolve("012.webp") { n ->
            asked += n
            false
        } shouldBe null
        asked shouldBe listOf("012.orig.webp")
    }

    @Test
    fun `存好原檔後要刪的舊版檔名`() {
        OriginalMaterial.supersededLegacyName("012.jpg") shouldBe "012.orig.webp"
        OriginalMaterial.supersededLegacyName("012.png") shouldBe "012.orig.webp"
    }

    @Test
    fun `進行中記號不算原圖素材，查找不會拿到它`() {
        OriginalMaterial.resolve("012.jpg", setOf("012.orig.jpg.wip", "012.json")) shouldBe null
        OriginalMaterial.resolve("012.jpg", setOf("012.orig.jpg.wip", "012.orig.jpg")) shouldBe
            OriginalMaterial.Found("012.orig.jpg", byteCopy = true)
    }

    @Test
    fun `中斷復原：還沒進清單、記號在、原檔在才把原檔寫回頁檔`() {
        // 上次存好原檔、覆蓋頁檔到一半被打斷
        OriginalMaterial.shouldRecover(inManifest = false, hasMarker = true, hasExactOriginal = true) shouldBe true
        // 沒有記號：頁檔沒被我們動過（或使用者自己換了頁檔）→ 不能拿舊原檔蓋回去
        OriginalMaterial.shouldRecover(inManifest = false, hasMarker = false, hasExactOriginal = true) shouldBe false
        // 記號在但原檔不在：沒東西可寫回
        OriginalMaterial.shouldRecover(inManifest = false, hasMarker = true, hasExactOriginal = false) shouldBe false
        // 已進清單：記號是「清單寫完、記號還沒刪」留下的，頁檔是完整的譯圖 → 不寫回
        OriginalMaterial.shouldRecover(inManifest = true, hasMarker = true, hasExactOriginal = true) shouldBe false
    }

    @Test
    fun `頁檔是不是我們自己的成品：在清單裡、有原圖素材、頁檔不比 json 新`() {
        // 翻譯落地：先寫頁檔、再寫 json → 頁檔時間 ≤ json 時間
        OriginalMaterial.pageIsOwnOutput(true, true, pageMtime = 1_000L, jsonMtime = 1_000L) shouldBe true
        OriginalMaterial.pageIsOwnOutput(true, true, pageMtime = 1_000L, jsonMtime = 1_200L) shouldBe true
        // 之後頁檔被換過（擷取重截、使用者換檔）→ 頁檔比 json 新 → 當成新的原圖
        OriginalMaterial.pageIsOwnOutput(true, true, pageMtime = 5_000L, jsonMtime = 1_200L) shouldBe false
        // 不在清單裡、沒有原圖素材 → 不是
        OriginalMaterial.pageIsOwnOutput(false, true, pageMtime = 1_000L, jsonMtime = 1_200L) shouldBe false
        OriginalMaterial.pageIsOwnOutput(true, false, pageMtime = 1_000L, jsonMtime = 1_200L) shouldBe false
        // 時間讀不到（0）→ 不敢當成自己的成品，照以前的做法
        OriginalMaterial.pageIsOwnOutput(true, true, pageMtime = 0L, jsonMtime = 1_200L) shouldBe false
        OriginalMaterial.pageIsOwnOutput(true, true, pageMtime = 1_000L, jsonMtime = 0L) shouldBe false
    }
}
