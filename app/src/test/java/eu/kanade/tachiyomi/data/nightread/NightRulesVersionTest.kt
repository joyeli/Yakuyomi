package eu.kanade.tachiyomi.data.nightread

import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import io.kotest.matchers.shouldBe
import li.joye.yakuyomi.nightread.NightRead
import li.joye.yakuyomi.nightread.NightTier
import org.junit.jupiter.api.Test

/**
 * 夜讀規則版本（2026-10-03，使用者拍板「標示＋一鍵更新」）：每頁換檔完成後在旁邊寫空的版本記號 `<頁檔名>.night.rules<N>`，
 * 頁的版本＝有 std 時同頁記號的最大值（沒有＝0），只有三檔格式或舊版單檔＝0。版本 < 目前的頁＝舊版：章節列「可更新」、
 * 產生端當它不新鮮。這裡驗記號的命名／解析、頁的版本、整章的「可更新」（含舊版單檔、三檔格式、同章新舊混合）、可清的記號，
 * 以及章節列判斷與產生端判斷一致。
 */
class NightRulesVersionTest {

    private val page = "012.jpg"
    private val std = NightPages.levelName(page, NightLevel.STANDARD)
    private val more = NightPages.levelName(page, NightLevel.MORE)
    private val l1 = NightPages.tierName(page, NightTier.L1)
    private val l2 = NightPages.tierName(page, NightTier.L2)
    private val l3 = NightPages.tierName(page, NightTier.L3)
    private val legacy = NightPages.legacyName(page)
    private val r1 = NightPages.rulesStampName(page, 1)
    private val r2 = NightPages.rulesStampName(page, 2)

    private fun stdOf(p: String) = NightPages.levelName(p, NightLevel.STANDARD)
    private fun stampOf(p: String, v: Int) = NightPages.rulesStampName(p, v)

    @Test
    fun `版本號來自 nightread、從 1 起算`() {
        NightPages.RULES_VERSION shouldBe NightRead.RULES_VERSION
        (NightPages.RULES_VERSION >= 1) shouldBe true
        NightPages.rulesStampName(page) shouldBe NightPages.rulesStampName(page, NightRead.RULES_VERSION)
    }

    @Test
    fun `記號命名與解析：完整頁檔名加 night rules 加版本`() {
        r1 shouldBe "012.jpg.night.rules1"
        NightPages.rulesStampName(page, 12) shouldBe "012.jpg.night.rules12"
        NightPages.parseRulesStamp(r1) shouldBe (page to 1)
        NightPages.parseRulesStamp("012.jpg.night.rules12") shouldBe (page to 12)
        NightPages.isRulesStamp(r1) shouldBe true
        // 頁名本身含 night 也拆得對
        NightPages.parseRulesStamp(NightPages.rulesStampName("a.night.jpg", 3)) shouldBe ("a.night.jpg" to 3)
        // 反例：沒有版本號、非數字、暫存、夜讀檔、素材、空頁名
        listOf(
            "012.jpg.night.rules",
            "012.jpg.night.rulesx",
            "012.jpg.night.rules1.tmp",
            "012.jpg.night.rules1.webp",
            "012.jpg.night.rules-1",
            ".night.rules1",
            std,
            legacy,
            "012.json",
            "012.orig.jpg",
        ).forEach {
            NightPages.isRulesStamp(it) shouldBe false
            NightPages.parseRulesStamp(it) shouldBe null
        }
    }

    @Test
    fun `記號不是夜讀檔、不是重繪素材、reader 查找不認它`() {
        listOf(r1, r2).forEach {
            NightPages.isNightArtifact(it) shouldBe false
            NightPages.isFinalNightFile(it) shouldBe false
            NightPages.isNightTmp(it) shouldBe false
            NightPages.pageOf(it) shouldBe null
            OriginalMaterial.isOriginalMaterial(it) shouldBe false
        }
        // 有沒有記號，兩個檔位查到的檔都一樣
        NightLevel.entries.forEach { level ->
            NightPages.resolveName(setOf(std, more, r1), page, level) shouldBe
                NightPages.resolveName(setOf(std, more), page, level)
        }
        NightPages.markerName(setOf(std, r1), page) shouldBe std
        NightPages.leftovers(setOf(std, more, r1, r2)) shouldBe emptySet()
    }

    @Test
    fun `頁的版本：有 std 看記號最大值，沒有記號＝0`() {
        NightPages.pageRulesVersion(setOf(std, r1), page) shouldBe 1
        NightPages.pageRulesVersion(setOf(std, more, r1), page) shouldBe 1
        NightPages.pageRulesVersion(setOf(std), page) shouldBe 0
        NightPages.pageRulesVersion(setOf(std, more), page) shouldBe 0
        NightPages.pageRulesVersion(setOf(std, r1, r2), page) shouldBe 2
        // 別頁的記號不算
        NightPages.pageRulesVersion(setOf(std, stampOf("013.jpg", 1)), page) shouldBe 0
        // std 和殘留的舊格式並存：std 說了算
        NightPages.pageRulesVersion(setOf(std, l1, legacy, r1), page) shouldBe 1
    }

    @Test
    fun `頁的版本：三檔格式、舊版單檔一律 0，殘留的記號不替它們背書`() {
        NightPages.pageRulesVersion(setOf(l1), page) shouldBe 0
        NightPages.pageRulesVersion(setOf(l1, l2, l3), page) shouldBe 0
        NightPages.pageRulesVersion(setOf(l1, r1), page) shouldBe 0
        NightPages.pageRulesVersion(setOf(legacy), page) shouldBe 0
        NightPages.pageRulesVersion(setOf(legacy, r1), page) shouldBe 0
    }

    @Test
    fun `頁的版本：沒有可顯示的夜讀版＝null（只剩記號、孤兒 more l2 l3 都不算）`() {
        NightPages.pageRulesVersion(emptySet(), page) shouldBe null
        NightPages.pageRulesVersion(setOf(r1), page) shouldBe null
        NightPages.pageRulesVersion(setOf(more, l2, l3, r1), page) shouldBe null
        NightPages.pageRulesVersion(setOf("$std.tmp", r1), page) shouldBe null
    }

    @Test
    fun `舊版判斷：版本小於目前才算，沒有夜讀版不算`() {
        NightPages.isPageOutdated(setOf(std, r1), page, current = 1) shouldBe false
        NightPages.isPageOutdated(setOf(std, r1), page, current = 2) shouldBe true
        NightPages.isPageOutdated(setOf(std, r2), page, current = 2) shouldBe false
        // 比目前還新（降版裝回舊 app）不算舊版
        NightPages.isPageOutdated(setOf(std, r2), page, current = 1) shouldBe false
        NightPages.isPageOutdated(setOf(std), page, current = 1) shouldBe true
        NightPages.isPageOutdated(setOf(l1, l2), page, current = 1) shouldBe true
        NightPages.isPageOutdated(setOf(legacy), page, current = 1) shouldBe true
        NightPages.isPageOutdated(emptySet(), page, current = 1) shouldBe false
        NightPages.isPageOutdated(setOf(r1), page, current = 2) shouldBe false
    }

    @Test
    fun `整章：全是目前版本不可更新`() {
        val names = listOf(
            stdOf("001.jpg"),
            stampOf("001.jpg", 1),
            stdOf("002.jpg"),
            NightPages.levelName("002.jpg", NightLevel.MORE),
            stampOf("002.jpg", 1),
        )
        NightPages.summarize(names, current = 1) shouldBe
            NightPages.ChapterSummary(NightPages.ChapterState.READY, outdated = false)
    }

    @Test
    fun `整章：記版本之前的格式一律可更新（沒記號的兩檔、三檔、舊版單檔）`() {
        NightPages.summarize(listOf(stdOf("001.jpg"), stdOf("002.jpg")), current = 1) shouldBe
            NightPages.ChapterSummary(NightPages.ChapterState.READY, outdated = true)
        NightPages.summarize(
            listOf(NightPages.tierName("001.jpg", NightTier.L1), NightPages.tierName("001.jpg", NightTier.L3)),
            current = 1,
        ) shouldBe NightPages.ChapterSummary(NightPages.ChapterState.READY, outdated = true)
        NightPages.summarize(listOf(NightPages.legacyName("001.jpg"), "001.orig.jpg"), current = 1) shouldBe
            NightPages.ChapterSummary(NightPages.ChapterState.LEGACY, outdated = true)
    }

    @Test
    fun `整章：新舊混合（升級後續做的章）只要有一頁舊就可更新`() {
        val mixed = listOf(
            stdOf("001.jpg"),
            stampOf("001.jpg", 1), // 新版 app 續做的
            stdOf("002.jpg"), // 舊版 app 做的（沒記號）
            NightPages.tierName("003.jpg", NightTier.L1), // 三檔時代做的
        )
        NightPages.summarize(mixed, current = 1) shouldBe
            NightPages.ChapterSummary(NightPages.ChapterState.READY, outdated = true)
        // 下一次規則改版（版本 2）：做到一半的章，1 頁新、1 頁舊
        val half = listOf(stdOf("001.jpg"), stampOf("001.jpg", 2), stdOf("002.jpg"), stampOf("002.jpg", 1))
        NightPages.summarize(half, current = 2).outdated shouldBe true
        NightPages.summarize(half, current = 1).outdated shouldBe false
        // 舊頁補完之後
        val done = listOf(stdOf("001.jpg"), stampOf("001.jpg", 2), stdOf("002.jpg"), stampOf("002.jpg", 2))
        NightPages.summarize(done, current = 2).outdated shouldBe false
    }

    @Test
    fun `整章：沒有可顯示的夜讀版就不算可更新（只剩記號、孤兒、暫存、素材）`() {
        NightPages.summarize(emptyList(), current = 1) shouldBe
            NightPages.ChapterSummary(NightPages.ChapterState.NONE, outdated = false)
        NightPages.summarize(
            listOf(
                stampOf("001.jpg", 1),
                NightPages.levelName("001.jpg", NightLevel.MORE),
                NightPages.tierName("002.jpg", NightTier.L2),
                "${stdOf("003.jpg")}.tmp",
                "003.json",
                "003.mask.png",
            ),
            current = 1,
        ) shouldBe NightPages.ChapterSummary(NightPages.ChapterState.NONE, outdated = false)
    }

    @Test
    fun `chapterState 不受記號影響`() {
        NightPages.chapterState(listOf(stampOf("001.jpg", 1))) shouldBe NightPages.ChapterState.NONE
        NightPages.chapterState(listOf(stdOf("001.jpg"), stampOf("001.jpg", 1))) shouldBe NightPages.ChapterState.READY
        NightPages.availability(true, listOf(stampOf("001.jpg", 1))) shouldBe NightAvailability.NONE
    }

    @Test
    fun `可清的記號：孤兒（同頁沒有 std）與被更大版本取代的`() {
        val names = setOf(
            stdOf("001.jpg"),
            stampOf("001.jpg", 1), // 正常：留
            stampOf("002.jpg", 1), // 孤兒：作廢後 std 沒了
            NightPages.tierName("003.jpg", NightTier.L1),
            stampOf("003.jpg", 1), // 只有三檔格式：記號不是替它寫的
            stdOf("004.jpg"),
            stampOf("004.jpg", 1),
            stampOf("004.jpg", 2), // 被取代：留最大
        )
        NightPages.staleStamps(names) shouldBe
            setOf(stampOf("002.jpg", 1), stampOf("003.jpg", 1), stampOf("004.jpg", 1))
    }

    @Test
    fun `已經不在這章的頁留下的夜讀檔與記號`() {
        val names = listOf(
            stdOf("001.jpg"), stampOf("001.jpg", 1),
            stdOf("002.jpg"), NightPages.levelName("002.jpg", NightLevel.MORE), stampOf("002.jpg", 1),
            NightPages.legacyName("003.png"),
            "${stdOf("002.jpg")}.tmp", // 暫存另外清，不在這裡
            "002.json",
            "002.orig.jpg",
        )
        NightPages.filesOfMissingPages(names, setOf("001.jpg", "003.jpg")) shouldBe setOf(
            stdOf("002.jpg"),
            NightPages.levelName("002.jpg", NightLevel.MORE),
            stampOf("002.jpg", 1),
            NightPages.legacyName("003.png"), // 頁是 003.jpg、不是 003.png
        )
        NightPages.filesOfMissingPages(names, setOf("001.jpg", "002.jpg", "003.png")) shouldBe emptySet()
    }

    /**
     * 模擬產生端一輪（`PageTranslator.renderNightChapter` 的開跑清理 [NightPages.cleanupPlan]、待做判斷
     * [NightPages.needsRender]、每頁換檔＋寫記號），跑完之後章節列（[NightPages.summarize]）不該再是「可更新」，
     * 再跑一輪也不該有事做。[colour]＝被「略過彩色頁」跳過的頁（刪掉它的夜讀檔、不寫記號）。標記一律當新鮮（mtime 的判斷
     * 在 UniFile 上，這裡只驗檔名的部分）。
     */
    private fun simulateRun(
        start: Set<String>,
        pages: List<String>,
        current: Int,
        colour: Set<String> = emptySet(),
        topLevel: Set<String>? = pages.toSet() + NightPages.DIR,
    ): Pair<Set<String>, List<String>> {
        val names = start.toMutableSet()
        names -= NightPages.cleanupPlan(start, topLevel)
        val kept = names.filterTo(HashSet()) { NightPages.isFinalNightFile(it) || NightPages.isRulesStamp(it) }
        val stamps = NightPages.rulesStamps(kept)
        val stampsAtStart = kept.mapNotNull { n -> NightPages.parseRulesStamp(n)?.let { it.first to n } }
            .groupBy({ it.first }, { it.second })
        val pending = pages.filter { p -> NightPages.needsRender(kept, p, stamps, current) { true } }
        for (p in pending) {
            if (p in colour) {
                names -= NightPages.artifactNames(p).toSet()
                continue
            }
            // 換檔：舊 std／more 換成新的（這裡假設「更多」與標準不同，兩檔都在），刪同頁舊格式
            names += NightPages.levelName(p, NightLevel.STANDARD)
            names += NightPages.levelName(p, NightLevel.MORE)
            names -= NightPages.oldFormatNames(p).toSet()
            // 寫目前版本的記號、刪開跑時同頁的其他記號
            val stamp = NightPages.rulesStampName(p, current)
            names += stamp
            names -= stampsAtStart[p].orEmpty().filter { it != stamp }.toSet()
        }
        return names to pending
    }

    @Test
    fun `產生一輪之後不再可更新、再跑一輪沒事做（混合格式、不在本章的頁、孤兒記號、彩頁）`() {
        val pages = listOf("001.jpg", "002.jpg", "003.jpg", "004.jpg", "005.jpg", "006.jpg")
        val start = setOf(
            stdOf("001.jpg"), stampOf("001.jpg", 1), // 已是版本 1
            stdOf("002.jpg"), NightPages.levelName("002.jpg", NightLevel.MORE), // 沒記號的兩檔（舊）
            NightPages.tierName("003.jpg", NightTier.L1), NightPages.tierName("003.jpg", NightTier.L3), // 三檔（舊）
            NightPages.legacyName("004.jpg"), // 舊版單檔
            stampOf("005.jpg", 1), // 孤兒記號（沒有 std）
            NightPages.legacyName("006.jpg"), // 彩頁、舊版單檔
            stdOf("099.jpg"), stampOf("099.jpg", 1), NightPages.legacyName("098.png"), // 已經不在這章的頁
            "${stdOf("002.jpg")}.tmp", // 暫存
            "001.json", "001.mask.png", "001.orig.jpg", // 重繪素材（不碰）
        )
        NightPages.summarize(start, current = 1).outdated shouldBe true

        val (after, pending) = simulateRun(start, pages, current = 1, colour = setOf("006.jpg"))
        // 001 已是新版不做；002、003、004 舊版要做；005 沒有夜讀版（只有孤兒記號）要做；006 彩頁也排進來（舊版單檔）
        pending shouldBe listOf("002.jpg", "003.jpg", "004.jpg", "005.jpg", "006.jpg")
        NightPages.summarize(after, current = 1) shouldBe
            NightPages.ChapterSummary(NightPages.ChapterState.READY, outdated = false)
        // 重繪素材沒動、不在本章的頁清掉了、彩頁沒有夜讀版
        after.containsAll(listOf("001.json", "001.mask.png", "001.orig.jpg")) shouldBe true
        after.any { it.startsWith("099.jpg") || it.startsWith("098.png") } shouldBe false
        NightPages.hasAnyArtifact(after, "006.jpg") shouldBe false

        val (again, pending2) = simulateRun(after, pages, current = 1, colour = setOf("006.jpg"))
        // 彩頁沒有夜讀版＝每輪都會解碼看一次（不落標記，見 renderNightPage），其餘不再做
        pending2 shouldBe listOf("006.jpg")
        again shouldBe after
    }

    @Test
    fun `下一次改版（版本 2）：一輪之後全是 2，舊記號清掉`() {
        val pages = listOf("001.jpg", "002.jpg")
        val start = setOf(stdOf("001.jpg"), stampOf("001.jpg", 1), stdOf("002.jpg"), stampOf("002.jpg", 2))
        NightPages.summarize(start, current = 2).outdated shouldBe true
        val (after, pending) = simulateRun(start, pages, current = 2)
        pending shouldBe listOf("001.jpg")
        NightPages.summarize(after, current = 2).outdated shouldBe false
        after.filter { NightPages.isRulesStamp(it) }.toSet() shouldBe
            setOf(stampOf("001.jpg", 2), stampOf("002.jpg", 2))
    }

    @Test
    fun `列目錄不可信時不清不在本章的頁（列出來是空的不能把整章夜讀版刪光）`() {
        val start = setOf(stdOf("001.jpg"), stampOf("001.jpg", 1), stdOf("002.jpg"), stampOf("002.jpg", 1))
        NightPages.cleanupPlan(start, pageNames = null) shouldBe emptySet()
        NightPages.cleanupPlan(start, pageNames = emptySet()) shouldBe start
        // 沒有頁圖、但列得到素材夾＝真的空了：清掉，章節列就不會一直「可更新」
        NightPages.cleanupPlan(start + NightPages.legacyName("003.jpg"), pageNames = setOf(NightPages.DIR)) shouldBe
            start + NightPages.legacyName("003.jpg")
    }

    @Test
    fun `cleanupPlan 只動夜讀檔與記號，探測檔與素材不碰`() {
        val names = setOf(
            NightPages.STAMP_PROBE,
            "001.json",
            "001.mask.png",
            "001.orig.jpg",
            "${stdOf("001.jpg")}.tmp",
            stdOf("001.jpg"),
            stampOf("001.jpg", 1),
        )
        NightPages.cleanupPlan(names, setOf("001.jpg")) shouldBe setOf("${stdOf("001.jpg")}.tmp")
        NightPages.isRulesStamp(NightPages.STAMP_PROBE) shouldBe false
        NightPages.isNightArtifact(NightPages.STAMP_PROBE) shouldBe false
        OriginalMaterial.isOriginalMaterial(NightPages.STAMP_PROBE) shouldBe false
    }

    @Test
    fun `needsRender：標記不算數（不新鮮或早於強制重做）也要做`() {
        val names = setOf(stdOf(page), r1)
        NightPages.needsRender(names, page, current = 1) { true } shouldBe false
        NightPages.needsRender(names, page, current = 1) { false } shouldBe true
        NightPages.needsRender(names, page, current = 2) { true } shouldBe true
        NightPages.needsRender(setOf(r1), page, current = 1) { true } shouldBe true
        // 三檔格式的 l1 是標記，但沒有版本＝舊版
        NightPages.needsRender(setOf(l1, l2), page, current = 1) { true } shouldBe true
    }

    /**
     * 一頁所有檔案組合（std、more、l1、l2、l3、舊版單檔、記號 1、記號 2；2^8 種）× 目前版本 1、2：
     *  - 有可顯示的夜讀版（[NightPages.resolveName] 查得到）⇔ 頁的版本不是 null。
     *  - 章節列（[NightPages.summarize]）的「可更新」＝產生端判舊版（[NightPages.isPageOutdated]）：章節列標了，按下去一定會重做；
     *    沒標的，產生端也不會因為版本重做。
     *  - 清掉可清的記號（[NightPages.staleStamps]）不改變頁的版本。
     *  - 寫上目前版本的記號之後：有 std 的頁不再是舊版（產生端換檔完成後的狀態）。
     */
    @Test
    fun `一頁所有組合：章節列與產生端一致、清記號不改版本、寫記號後不再舊`() {
        val all = listOf(std, more, l1, l2, l3, legacy, r1, r2)
        for (mask in 0 until (1 shl all.size)) {
            val names = all.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            for (current in 1..2) {
                val version = NightPages.pageRulesVersion(names, page)
                (version != null) shouldBe (NightPages.resolveName(names, page, NightLevel.STANDARD) != null)
                NightPages.summarize(names, current).outdated shouldBe
                    NightPages.isPageOutdated(names, page, current = current)
                NightPages.pageRulesVersion(names - NightPages.staleStamps(names), page) shouldBe version
                if (std in names) {
                    val stamped = names + NightPages.rulesStampName(page, current)
                    NightPages.isPageOutdated(stamped, page, current = current) shouldBe false
                }
            }
        }
    }
}
