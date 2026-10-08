package eu.kanade.tachiyomi.data.nightread

import eu.kanade.tachiyomi.data.nightread.NightStorage.ChapterRef
import eu.kanade.tachiyomi.data.nightread.NightStorage.ChapterUsage
import eu.kanade.tachiyomi.data.nightread.NightStorage.NightFile
import eu.kanade.tachiyomi.data.nightread.NightStorage.Totals
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 設定 › 夜讀「儲存空間」的純邏輯：哪些檔算夜讀檔（翻譯素材不算）、用量加總、只清已讀的話、刪除順序、清完剩什麼、
 * 章節夾對到資料庫的話。
 */
class NightStorageTest {

    private val page = "012.jpg"
    private val std = "012.jpg.night.std.webp"
    private val more = "012.jpg.night.more.webp"
    private val l1 = "012.jpg.night.l1.webp"
    private val l2 = "012.jpg.night.l2.webp"
    private val l3 = "012.jpg.night.l3.webp"
    private val legacy = "012.jpg.night.webp"
    private val stamp = "012.jpg.night.rules4"

    private fun usage(key: String, id: Long?, read: Boolean, vararg files: Pair<String, Long>) =
        ChapterUsage(key, id, read, files.map { NightFile(it.first, it.second) })

    @Test
    fun `夜讀檔：各格式、暫存、版本記號、探測檔都算`() {
        listOf(std, more, l1, l2, l3, legacy, "$std.tmp", "$legacy.tmp", stamp, NightPages.STAMP_PROBE)
            .forEach { NightStorage.isNightFile(it) shouldBe true }
    }

    @Test
    fun `翻譯素材、頁圖、manifest 不算夜讀檔`() {
        listOf(
            "012.orig.jpg",
            "012.orig.webp",
            "012.orig.webp.wip",
            "012.mask.png",
            "012.json",
            "012.jpg.nightboxes.json",
            page,
            ".yakuyomi_translated",
            ".yakuyomi_errors.txt",
            // 頁名本身含 night 也不會誤判
            "night.jpg",
            "012.night.jpg",
        ).forEach { NightStorage.isNightFile(it) shouldBe false }
    }

    @Test
    fun `一章的用量：只留夜讀檔、負大小當 0、同名只算一次、沒有夜讀檔回 null`() {
        val listing = listOf(
            NightFile(std, 100),
            NightFile(more, 50),
            NightFile(stamp, -1),
            NightFile("012.orig.jpg", 999),
            NightFile("012.mask.png", 999),
            NightFile(std, 100),
        )
        val u = NightStorage.usageOf("d/s/m/c", 7L, true, listing)!!
        u.files shouldBe listOf(NightFile(std, 100), NightFile(more, 50), NightFile(stamp, 0))
        u.bytes shouldBe 150
        NightStorage.usageOf("d/s/m/c", 7L, true, listOf(NightFile("012.orig.jpg", 1))) shouldBe null
        NightStorage.usageOf("d/s/m/c", 7L, true, emptyList()) shouldBe null
    }

    @Test
    fun `總計：全部與已讀分開算，沒有檔的章不算`() {
        val list = listOf(
            usage("a", 1, true, std to 100, more to 20),
            usage("b", 2, false, std to 300),
            usage("c", null, false, std to 5),
            usage("d", 4, true),
        )
        NightStorage.summarize(list) shouldBe NightStorage.Summary(all = Totals(3, 425), read = Totals(1, 120))
        NightStorage.summarize(emptyList()) shouldBe NightStorage.Summary(Totals.ZERO, Totals.ZERO)
    }

    @Test
    fun `只清已讀：未讀與對不到資料庫的章不動`() {
        val read = usage("a", 1, true, std to 1)
        val unread = usage("b", 2, false, std to 1)
        val unknown = usage("c", null, false, std to 1)
        val list = listOf(read, unread, unknown)
        NightStorage.selectForClear(list, readOnly = true, skip = emptySet()) shouldBe listOf(read)
        NightStorage.selectForClear(list, readOnly = false, skip = emptySet()) shouldBe list
    }

    @Test
    fun `正在產生的話跳過；對不到資料庫的章不受跳過清單影響`() {
        val running = usage("a", 1, true, std to 1)
        val other = usage("b", 2, true, std to 1)
        val unknown = usage("c", null, false, std to 1)
        val list = listOf(running, other, unknown)
        NightStorage.selectForClear(list, readOnly = false, skip = setOf(1L)) shouldBe listOf(other, unknown)
        NightStorage.selectForClear(list, readOnly = true, skip = setOf(1L)) shouldBe listOf(other)
    }

    @Test
    fun `刪除順序：每頁照作廢順序（標記先、附屬檔後）、暫存接在後面，記號與探測檔最後，其他檔不刪`() {
        val names = listOf(
            stamp,
            more,
            "$std.tmp",
            NightPages.STAMP_PROBE,
            std,
            l2,
            l1,
            legacy,
            "012.orig.jpg",
            "011.jpg.night.std.webp",
            "011.jpg.night.rules4",
        )
        NightStorage.deletionOrder(names) shouldBe listOf(
            "011.jpg.night.std.webp",
            legacy,
            l1,
            std,
            more,
            l2,
            "$std.tmp",
            "011.jpg.night.rules4",
            stamp,
            NightPages.STAMP_PROBE,
        )
    }

    @Test
    fun `刪除順序：只剩暫存的頁也照刪`() {
        NightStorage.deletionOrder(listOf("$more.tmp", "$std.tmp")) shouldBe listOf("$std.tmp", "$more.tmp")
    }

    @Test
    fun `清完：整章刪乾淨的拿掉、刪不掉的只留那些檔、沒清的章不動`() {
        val a = usage("a", 1, true, std to 100, more to 50)
        val b = usage("b", 2, true, std to 10, stamp to 0)
        val c = usage("c", 3, false, std to 7)
        val after = NightStorage.afterClear(listOf(a, b, c), mapOf("a" to emptySet(), "b" to setOf(std)))
        after shouldBe listOf(usage("b", 2, true, std to 10), c)
        NightStorage.freedBytes(a, emptySet()) shouldBe 150
        NightStorage.freedBytes(a, setOf(more)) shouldBe 100
    }

    @Test
    fun `章節夾對到資料庫：依候選名順位登記，舊版沒雜湊的名字撞名時不蓋掉別話的主要名`() {
        val one = ChapterRef(1, read = true)
        val two = ChapterRef(2, read = false)
        val index = NightStorage.indexChapterDirs(
            listOf(
                // 第 1 話：主要名、壓縮檔名、舊版名「Ch 1」
                listOf("d/s/m/Ch 1_aaaaaa", "d/s/m/Ch 1_aaaaaa.cbz", "d/s/m/Ch 1") to one,
                // 第 2 話：主要名剛好是「Ch 1」（同名不同網址的舊下載），舊版名也是「Ch 1」
                listOf("d/s/m/Ch 1", "d/s/m/Ch 1.cbz", "d/s/m/Ch 1") to two,
            ),
        )
        index["d/s/m/Ch 1_aaaaaa"] shouldBe one
        index["d/s/m/Ch 1"] shouldBe two
        index["d/s/m/Ch 1.cbz"] shouldBe two
        index["d/s/m/other"] shouldBe null
        NightStorage.indexChapterDirs(emptyList()) shouldBe emptyMap()
    }

    @Test
    fun `掃描時跳過隱藏檔與一看就是檔案的名字，章節夾名含點照樣找`() {
        listOf(".nomedia", ".yakuyomi", "Ch 1.cbz", "cover.jpg", "details.json", "a.EPUB", "")
            .forEach { NightStorage.canHoldNightFiles(it) shouldBe false }
        listOf("Ch. 10.5", "Vol.1 Ch.2_abcdef", "Some Manga", "Source (EN)")
            .forEach { NightStorage.canHoldNightFiles(it) shouldBe true }
    }

    @Test
    fun `套用變動：重新量過的話換掉舊的那筆（對章 id 或對 key），沒了的話與整本刪掉的書拿掉，其他不動`() {
        val a = usage("d/s/m/Ch 1", 1, true, std to 100)
        val b = usage("d/s/m/Ch 2", 2, false, std to 200)
        val c = usage("d/s/n/Ch 1", 3, false, std to 300)
        val d = usage("d/s/x/Ch 9", null, false, std to 400) // 對不到資料庫
        val e = usage("d/s/m/Ch 5", 5, true, std to 500)
        val result = NightStorage.applyChanges(
            usages = listOf(a, b, c, d, e),
            forgetIds = setOf(5),
            forgetPrefixes = listOf("d/s/n/"),
            remeasured = listOf(
                // 第 1 話多產生了 more
                NightStorage.Remeasured(1, "d/s/m/Ch 1", usage("d/s/m/Ch 1", 1, true, std to 100, more to 50)),
                // 第 2 話的夜讀檔被翻譯作廢光了
                NightStorage.Remeasured(2, "d/s/m/Ch 2", null),
                // 原本對不到資料庫的那章，這次重新量時對到了第 9 話
                NightStorage.Remeasured(9, "d/s/x/Ch 9", usage("d/s/x/Ch 9", 9, false, std to 410)),
                // 新產生夜讀版的章
                NightStorage.Remeasured(7, "d/s/m/Ch 7", usage("d/s/m/Ch 7", 7, false, std to 70)),
            ),
        )
        result.map { it.key }.toSet() shouldBe setOf("d/s/m/Ch 1", "d/s/x/Ch 9", "d/s/m/Ch 7")
        result.first { it.key == "d/s/m/Ch 1" }.bytes shouldBe 150
        result.first { it.key == "d/s/x/Ch 9" }.chapterId shouldBe 9
        // 沒有變動就原封不動
        NightStorage.applyChanges(listOf(a, b), emptySet(), emptyList(), emptyList()) shouldBe listOf(a, b)
    }

    @Test
    fun `重對讀過沒有：照最新的資料庫對照表，表裡沒有的章沿用原本的`() {
        val a = usage("d/s/m/Ch 1", 1, false, std to 100)
        val b = usage("d/s/m/Ch 2", null, false, std to 200)
        val c = usage("d/s/m/Ch 3", 3, true, std to 300)
        val index = mapOf(
            "d/s/m/Ch 1" to ChapterRef(1, read = true), // 之後讀過了
            "d/s/m/Ch 2" to ChapterRef(2, read = true), // 這次才對到
        )
        val remapped = NightStorage.remapRefs(listOf(a, b, c), index)
        remapped[0].read shouldBe true
        remapped[1].chapterId shouldBe 2
        remapped[1].read shouldBe true
        remapped[2] shouldBe c
        NightStorage.summarize(remapped).read shouldBe Totals(3, 600)
    }
}
