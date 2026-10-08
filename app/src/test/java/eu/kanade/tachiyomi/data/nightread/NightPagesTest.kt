package eu.kanade.tachiyomi.data.nightread

import io.kotest.matchers.shouldBe
import li.joye.yakuyomi.nightread.NightTier
import org.junit.jupiter.api.Test

/**
 * 夜讀檔名慣例與查找：兩檔格式（std／more，現行）、三檔格式（l1／l2／l3，2026-10-01 產生、只讀）、舊版單檔（只讀），
 * 以及重新產生被打斷時新舊並存的情形——已產生的章都要照讀、不必重做。
 */
class NightPagesTest {

    private val page = "012.jpg"
    private val std = "012.jpg.night.std.webp"
    private val more = "012.jpg.night.more.webp"
    private val l1 = "012.jpg.night.l1.webp"
    private val l2 = "012.jpg.night.l2.webp"
    private val l3 = "012.jpg.night.l3.webp"
    private val legacy = "012.jpg.night.webp"

    private fun resolveBoth(names: Set<String>) = NightLevel.entries.map { NightPages.resolveName(names, page, it) }

    @Test
    fun `檔名規則：完整頁檔名加尾綴、暫存＝最終名加 tmp`() {
        NightPages.levelName(page, NightLevel.STANDARD) shouldBe std
        NightPages.levelName(page, NightLevel.MORE) shouldBe more
        NightPages.tierName(page, NightTier.L1) shouldBe l1
        NightPages.tierName(page, NightTier.L2) shouldBe l2
        NightPages.tierName(page, NightTier.L3) shouldBe l3
        NightPages.legacyName(page) shouldBe legacy
        NightPages.tmpName(std) shouldBe "$std.tmp"
    }

    @Test
    fun `刪除順序：標記照查找優先序反過來（舊版單檔、l1、std），附屬檔最後`() {
        NightPages.currentNames(page) shouldBe listOf(std, more)
        NightPages.oldFormatNames(page) shouldBe listOf(l1, l2, l3, legacy)
        NightPages.artifactNames(page) shouldBe listOf(legacy, l1, std, more, l2, l3)
    }

    @Test
    fun `整頁作廢刪到一半：任何格式組合、任何中途狀態都只看到原本那組或原圖`() {
        // 所有 2^6 種殘留組合（含新舊並存），照 artifactNames 依序刪：每一步兩個檔位查到的要嘛跟刪之前一樣、要嘛全是原圖。
        // 舊順序（std 先刪）在「std＋三檔」並存時會先退回舊的 l2／l3——過期重做時那是舊畫面。
        val all = NightPages.artifactNames(page)
        for (mask in 0 until (1 shl all.size)) {
            val start = all.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            val before = resolveBoth(start)
            val left = start.toMutableSet()
            for (n in all) {
                left -= n
                val now = resolveBoth(left)
                (now == before || now.all { it == null }) shouldBe true
            }
            left shouldBe emptySet()
        }
    }

    @Test
    fun `產品檔位：標準＝L2、更多＝L3，交給引擎的順序標準在前`() {
        NightLevel.entries shouldBe listOf(NightLevel.STANDARD, NightLevel.MORE)
        NightLevel.engineTiers shouldBe listOf(NightTier.L2, NightTier.L3)
        NightLevel.ofTier(NightTier.L2) shouldBe NightLevel.STANDARD
        NightLevel.ofTier(NightTier.L3) shouldBe NightLevel.MORE
        NightLevel.ofTier(NightTier.L1) shouldBe null
        NightLevel.entries.map { it.fileKey } shouldBe listOf("std", "more")
    }

    @Test
    fun `isNightArtifact 正例：兩檔、三檔各檔位、舊版單檔、它們的暫存`() {
        listOf(std, more, l1, l2, l3, legacy).forEach {
            NightPages.isNightArtifact(it) shouldBe true
            NightPages.isNightArtifact("$it.tmp") shouldBe true
            NightPages.isNightTmp("$it.tmp") shouldBe true
            NightPages.isNightTmp(it) shouldBe false
            NightPages.isFinalNightFile(it) shouldBe true
            NightPages.isFinalNightFile("$it.tmp") shouldBe false
            NightPages.pageOf(it) shouldBe page
        }
    }

    @Test
    fun `isNightArtifact 反例：不誤中重繪素材與頁圖`() {
        listOf(
            "012.orig.webp",
            "012.orig.jpg",
            "012.orig.png",
            "012.orig.jpg.tmp",
            "012.orig.webp.tmp",
            "012.mask.png",
            "012.json",
            "012.jpg",
            "012.webp",
            "012.jpg.night.l4.webp",
            "012.jpg.night.standard.webp",
            "012.jpg.night.mores.webp",
            "012.jpg.nightly.webp",
            "012.jpg.night.l1.png",
            "012.jpg.night.std.png",
            "012.jpg.night.std.webp.bak",
            "012.jpg.night.webp.tmp.old",
        ).forEach {
            NightPages.isNightArtifact(it) shouldBe false
        }
    }

    @Test
    fun `頁名本身含 night 也正確`() {
        val p = "a.night.jpg"
        NightPages.isNightArtifact(p) shouldBe false
        val s = NightPages.levelName(p, NightLevel.STANDARD)
        val m = NightPages.levelName(p, NightLevel.MORE)
        s shouldBe "a.night.jpg.night.std.webp"
        NightPages.isNightArtifact(s) shouldBe true
        NightPages.pageOf(s) shouldBe p
        NightPages.pageOf(m) shouldBe p
        NightPages.pageOf(NightPages.tierName(p, NightTier.L2)) shouldBe p
        NightPages.pageOf(NightPages.legacyName(p)) shouldBe p
        NightPages.pageOf("a.night.jpg") shouldBe null
        NightPages.pageOf("$s.tmp") shouldBe null
        NightPages.resolveName(setOf(s, m), p, NightLevel.MORE) shouldBe m
    }

    // —— 兩檔格式（現行） ——

    @Test
    fun `兩檔：只有 std（更多與標準相同沒存）`() {
        resolveBoth(setOf(std)) shouldBe listOf(std, std)
    }

    @Test
    fun `兩檔：std 加 more`() {
        resolveBoth(setOf(std, more)) shouldBe listOf(std, more)
    }

    @Test
    fun `兩檔：孤兒 more（沒有 std）視而不見`() {
        resolveBoth(setOf(more)) shouldBe listOf(null, null)
        // 有三檔格式或舊版單檔就退回它們
        resolveBoth(setOf(more, l1, l3)) shouldBe listOf(l1, l3)
        resolveBoth(setOf(more, legacy)) shouldBe listOf(legacy, legacy)
    }

    // —— 三檔格式（2026-10-01 產生、只讀；語意＝三檔時代的 L2／L3） ——

    @Test
    fun `三檔：只有 l1`() {
        resolveBoth(setOf(l1)) shouldBe listOf(l1, l1)
    }

    @Test
    fun `三檔：l1 加 l3（L2 與 L1 相同沒存）`() {
        resolveBoth(setOf(l1, l3)) shouldBe listOf(l1, l3)
    }

    @Test
    fun `三檔：l1 加 l2（L3 與 L2 相同沒存）`() {
        resolveBoth(setOf(l1, l2)) shouldBe listOf(l2, l2)
    }

    @Test
    fun `三檔：三檔齊`() {
        resolveBoth(setOf(l1, l2, l3)) shouldBe listOf(l2, l3)
    }

    @Test
    fun `三檔：孤兒 l2 l3（沒有 l1）視而不見`() {
        resolveBoth(setOf(l2, l3)) shouldBe listOf(null, null)
        resolveBoth(setOf(l2)) shouldBe listOf(null, null)
        resolveBoth(setOf(l3)) shouldBe listOf(null, null)
        // 有舊版單檔就退回它
        resolveBoth(setOf(l2, l3, legacy)) shouldBe listOf(legacy, legacy)
    }

    // —— 舊版單檔 ——

    @Test
    fun `舊版單檔：兩檔都是它；有 std 或 l1 時不用`() {
        resolveBoth(setOf(legacy)) shouldBe listOf(legacy, legacy)
        resolveBoth(setOf(l1, legacy)) shouldBe listOf(l1, l1)
        resolveBoth(setOf(l1, l2, legacy)) shouldBe listOf(l2, l2)
        resolveBoth(setOf(std, legacy)) shouldBe listOf(std, std)
    }

    // —— 新舊並存（重新產生被打斷：新 std 已落地、舊格式還沒刪完） ——

    @Test
    fun `並存：有 std 就只看兩檔格式，三檔與舊版單檔不看`() {
        resolveBoth(setOf(std, l1, l2, l3, legacy)) shouldBe listOf(std, std)
        resolveBoth(setOf(std, more, l1, l2, l3)) shouldBe listOf(std, more)
        resolveBoth(setOf(std, l3)) shouldBe listOf(std, std)
    }

    @Test
    fun `並存：std 不在（換檔時已刪舊 std、新的還沒改名）但有 l1 → 照三檔規則`() {
        resolveBoth(setOf(more, l1, l2, l3)) shouldBe listOf(l2, l3)
        resolveBoth(setOf(l1, l3, "$std.tmp", "$more.tmp")) shouldBe listOf(l1, l3)
    }

    @Test
    fun `什麼都沒有、暫存、別頁的檔都不算`() {
        resolveBoth(emptySet()) shouldBe listOf(null, null)
        resolveBoth(setOf("$std.tmp", "$l1.tmp", "$legacy.tmp")) shouldBe listOf(null, null)
        resolveBoth(
            setOf("013.jpg.night.std.webp", "012.png.night.std.webp", "013.jpg.night.l1.webp", "012.png.night.webp"),
        ) shouldBe listOf(null, null)
    }

    @Test
    fun `完成標記（新鮮度比它）：有 std 用 std、否則 l1、都沒有 null`() {
        NightPages.markerName(setOf(std, more, l1, l2), page) shouldBe std
        NightPages.markerName(setOf(std), page) shouldBe std
        NightPages.markerName(setOf(l1, l3), page) shouldBe l1
        NightPages.markerName(setOf(more, l1), page) shouldBe l1
        NightPages.markerName(setOf(more, l2, l3, legacy), page) shouldBe null
        NightPages.markerName(setOf(legacy), page) shouldBe null
        NightPages.markerName(setOf("$std.tmp", "013.jpg.night.std.webp"), page) shouldBe null
    }

    @Test
    fun `可清的殘留：被 std 取代的舊格式、孤兒 more、孤兒 l2 l3`() {
        // 有 std：同頁三檔與舊版單檔都永遠不會被顯示
        NightPages.leftovers(setOf(std, more, l1, l2, l3, legacy)) shouldBe setOf(l1, l2, l3, legacy)
        // 沒有 std：more 是孤兒；有 l1 的三檔照留
        NightPages.leftovers(setOf(more, l1, l3)) shouldBe setOf(more)
        // 沒有 std 也沒有 l1：l2／l3 是孤兒，舊版單檔照留
        NightPages.leftovers(setOf(l2, l3, legacy)) shouldBe setOf(l2, l3)
        // 完整的各格式都不動
        NightPages.leftovers(setOf(std, more)) shouldBe emptySet()
        NightPages.leftovers(setOf(l1, l2, l3)) shouldBe emptySet()
        NightPages.leftovers(setOf(legacy)) shouldBe emptySet()
        // 每頁各自判斷
        val other = "013.jpg"
        NightPages.leftovers(
            setOf(std, l1, NightPages.tierName(other, NightTier.L1), NightPages.levelName(other, NightLevel.MORE)),
        ) shouldBe setOf(l1, NightPages.levelName(other, NightLevel.MORE))
        // 殘留永遠不是任何檔位的查找結果
        val mixed = setOf(std, more, l1, l2, l3, legacy)
        val dead = NightPages.leftovers(mixed)
        NightLevel.entries.forEach { (NightPages.resolveName(mixed, page, it) in dead) shouldBe false }
    }

    @Test
    fun `chapterState：有 std 或 l1 就 READY、只有舊版 LEGACY、其餘 NONE`() {
        NightPages.chapterState(listOf("001.jpg.night.std.webp")) shouldBe NightPages.ChapterState.READY
        NightPages.chapterState(listOf("001.jpg.night.l1.webp", "002.jpg.night.webp")) shouldBe
            NightPages.ChapterState.READY
        // 新舊格式混在同一章（部分頁已重新產生）
        val mixedChapter = listOf("001.jpg.night.std.webp", "002.jpg.night.l1.webp", "003.jpg.night.webp")
        NightPages.chapterState(mixedChapter) shouldBe NightPages.ChapterState.READY
        NightPages.chapterState(listOf("002.jpg.night.webp", "002.orig.webp")) shouldBe
            NightPages.ChapterState.LEGACY
        NightPages.chapterState(emptyList()) shouldBe NightPages.ChapterState.NONE
        // 孤兒 more／l2／l3、暫存、重繪素材都不算有
        NightPages.chapterState(
            listOf(
                "001.jpg.night.more.webp",
                "001.jpg.night.l2.webp",
                "001.jpg.night.l3.webp",
                "001.jpg.night.std.webp.tmp",
                "001.jpg.night.l1.webp.tmp",
                "001.json",
            ),
        ) shouldBe NightPages.ChapterState.NONE
        NightPages.chapterState(listOf("001.jpg.night.webp.tmp")) shouldBe NightPages.ChapterState.NONE
        // 沒有頁名的怪檔不算
        NightPages.chapterState(listOf(".night.std.webp", ".night.l1.webp")) shouldBe NightPages.ChapterState.NONE
    }

    @Test
    fun `availability：不支援優先，其餘照 chapterState`() {
        NightPages.availability(false, listOf(std)) shouldBe NightAvailability.UNSUPPORTED
        NightPages.availability(true, listOf(std, legacy)) shouldBe NightAvailability.READY
        NightPages.availability(true, listOf(l1, legacy)) shouldBe NightAvailability.READY
        NightPages.availability(true, listOf(legacy)) shouldBe NightAvailability.LEGACY
        NightPages.availability(true, listOf(more)) shouldBe NightAvailability.NONE
        NightPages.availability(true, listOf(l2)) shouldBe NightAvailability.NONE
        NightPages.availability(true, emptyList()) shouldBe NightAvailability.NONE
    }

    @Test
    fun `hasAnyArtifact：含孤兒與舊版，不含暫存`() {
        NightPages.hasAnyArtifact(setOf(more), page) shouldBe true
        NightPages.hasAnyArtifact(setOf(l3), page) shouldBe true
        NightPages.hasAnyArtifact(setOf(legacy), page) shouldBe true
        NightPages.hasAnyArtifact(setOf("$std.tmp"), page) shouldBe false
        NightPages.hasAnyArtifact(setOf("013.jpg.night.std.webp"), page) shouldBe false
    }

    @Test
    fun `偏好對應：l2＝標準、l3＝更多；舊值 l1 protect＝標準、aggressive＝更多；其餘＝標準`() {
        NightLevel.fromPref("l2") shouldBe NightLevel.STANDARD
        NightLevel.fromPref("l3") shouldBe NightLevel.MORE
        NightLevel.fromPref("l1") shouldBe NightLevel.STANDARD
        NightLevel.fromPref("protect") shouldBe NightLevel.STANDARD
        NightLevel.fromPref("aggressive") shouldBe NightLevel.MORE
        NightLevel.fromPref("") shouldBe NightLevel.STANDARD
        NightLevel.fromPref(null) shouldBe NightLevel.STANDARD
        NightLevel.fromPref("standard") shouldBe NightLevel.STANDARD
        NightLevel.fromPref("L3") shouldBe NightLevel.STANDARD
        NightLevel.fromPref("garbage") shouldBe NightLevel.STANDARD
        // 產品只寫 l2／l3，往返不變
        NightLevel.entries.map { it.prefValue } shouldBe listOf("l2", "l3")
        NightLevel.entries.forEach { NightLevel.fromPref(it.prefValue) shouldBe it }
    }
}
