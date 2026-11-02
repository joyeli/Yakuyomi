package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [NightHandoffCheck.verdict]（debug 交接比對的判定，軌道 B2）：頁像素、文字框、遮罩、兩檔位元組（重做那邊壓的對編碼執行緒
 * 寫出的）全部相同才算 same；任一項不同、檔位集合不同、沒有任何檔位都算 diff；紀錄只有雜湊與 same/diff。
 */
class NightHandoffCheckTest {

    private val quads = listOf(listOf(listOf(1f, 2f), listOf(3f, 4f), listOf(5f, 6f), listOf(7f, 8f)))
    private val rec = NightHandoffCheck.Record(pageHash = "p1", quads = quads, maskHash = "m1")

    private fun file(
        page: String = "p1",
        quadsSame: Boolean = true,
        mask: String = "m1",
        tiers: Map<String, String> = mapOf("std" to "s1", "more" to "none"),
    ) = NightHandoffCheck.FileSide(page, quadsSame, "MATERIALS", mask, tiers)

    private val written = mapOf("std" to "s1", "more" to "none")

    @Test
    fun `全部相同：same`() {
        val v = NightHandoffCheck.verdict(rec, file(), written)
        v.same shouldBe true
        v.summary shouldContain "page=same"
        v.summary shouldContain "tiers=same[more=none/none std=s1/s1]"
    }

    @Test
    fun `任一項不同：diff`() {
        NightHandoffCheck.verdict(rec, file(page = "p2"), written).same shouldBe false
        NightHandoffCheck.verdict(rec, file(quadsSame = false), written).same shouldBe false
        NightHandoffCheck.verdict(rec, file(mask = "none"), written).same shouldBe false
        NightHandoffCheck.verdict(rec, file(tiers = mapOf("std" to "s2", "more" to "none")), written).same shouldBe
            false
        // 重做那邊更多與標準不同、編碼執行緒卻沒寫更多（或反過來）
        NightHandoffCheck.verdict(rec, file(tiers = mapOf("std" to "s1", "more" to "x")), written).same shouldBe false
    }

    @Test
    fun `檔位集合不同或沒有檔位：diff`() {
        NightHandoffCheck.verdict(rec, file(tiers = mapOf("std" to "s1")), written).same shouldBe false
        NightHandoffCheck.verdict(rec, file(tiers = emptyMap()), emptyMap()).same shouldBe false
    }

    @Test
    fun `位元組雜湊：同內容同雜湊、16 個 hex 字元`() {
        val a = NightHandoffCheck.bytesHash(byteArrayOf(1, 2, 3))
        a shouldBe NightHandoffCheck.bytesHash(byteArrayOf(1, 2, 3))
        (a == NightHandoffCheck.bytesHash(byteArrayOf(1, 2, 4))) shouldBe false
        a.length shouldBe 16
    }
}
