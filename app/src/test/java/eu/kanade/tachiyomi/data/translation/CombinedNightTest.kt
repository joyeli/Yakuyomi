package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.nightread.NightLevel
import eu.kanade.tachiyomi.data.nightread.NightPages
import eu.kanade.tachiyomi.data.translation.CombinedNight.PagePlan
import eu.kanade.tachiyomi.data.translation.model.TranslationItem.Status
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** 合併處理（翻譯＋夜讀一條線）的純函式：意圖、閘門、線位、保留量、hold、涵蓋、重挑、頁計畫、q4、補掃、還原、記號檔名。 */
class CombinedNightTest {

    private val mb = 1024L * 1024

    @Test
    fun `合併意圖依觸發來源與兩個偏好決定（全表）`() {
        val expected = mapOf(
            // (觸發, 手動合併, 即時一併) → 意圖
            Triple(TranslateTrigger.MANUAL, false, false) to NightLink.NONE,
            Triple(TranslateTrigger.MANUAL, false, true) to NightLink.NONE,
            Triple(TranslateTrigger.MANUAL, true, false) to NightLink.EXPLICIT,
            Triple(TranslateTrigger.MANUAL, true, true) to NightLink.EXPLICIT,
            Triple(TranslateTrigger.DOWNLOAD, false, false) to NightLink.NONE,
            Triple(TranslateTrigger.DOWNLOAD, false, true) to NightLink.NONE,
            Triple(TranslateTrigger.DOWNLOAD, true, false) to NightLink.AUTO,
            Triple(TranslateTrigger.DOWNLOAD, true, true) to NightLink.AUTO,
            Triple(TranslateTrigger.LIVE, false, false) to NightLink.NONE,
            Triple(TranslateTrigger.LIVE, false, true) to NightLink.AUTO,
            Triple(TranslateTrigger.LIVE, true, false) to NightLink.NONE,
            Triple(TranslateTrigger.LIVE, true, true) to NightLink.AUTO,
            Triple(TranslateTrigger.PREFETCH, false, false) to NightLink.NONE,
            Triple(TranslateTrigger.PREFETCH, false, true) to NightLink.AUTO,
            Triple(TranslateTrigger.PREFETCH, true, false) to NightLink.NONE,
            Triple(TranslateTrigger.PREFETCH, true, true) to NightLink.AUTO,
        )
        expected.size shouldBe 16
        expected.forEach { (k, v) -> CombinedNight.linkFor(k.first, k.second, k.third) shouldBe v }
    }

    @Test
    fun `意圖只升不降（九種組合）`() {
        for (current in NightLink.entries) {
            for (requested in NightLink.entries) {
                val want = if (requested.code > current.code) requested else current
                CombinedNight.upgrade(current, requested) shouldBe want
            }
        }
        CombinedNight.upgrade(NightLink.EXPLICIT, NightLink.NONE) shouldBe NightLink.EXPLICIT
        CombinedNight.upgrade(NightLink.EXPLICIT, NightLink.AUTO) shouldBe NightLink.EXPLICIT
        CombinedNight.upgrade(NightLink.AUTO, NightLink.EXPLICIT) shouldBe NightLink.EXPLICIT
        CombinedNight.upgrade(NightLink.NONE, NightLink.AUTO) shouldBe NightLink.AUTO
    }

    @Test
    fun `代碼還原：認不得的一律當 NONE`() {
        NightLink.entries.forEach { NightLink.fromCode(it.code) shouldBe it }
        NightLink.fromCode(9) shouldBe NightLink.NONE
        NightLink.fromCode(-1) shouldBe NightLink.NONE
    }

    @Test
    fun `夜讀階段閘門：五道都要過，自動意圖另外要過夜讀範圍`() {
        fun gate(
            link: NightLink = NightLink.AUTO,
            on: Boolean = true,
            models: Boolean = true,
            loose: Boolean = true,
            store: Boolean = true,
            scope: Boolean = true,
        ) = CombinedNight.canRunNightStage(link, on, models, loose, store, scope)

        gate() shouldBe true
        gate(on = false) shouldBe false
        gate(models = false) shouldBe false
        gate(loose = false) shouldBe false
        gate(store = false) shouldBe false
        gate(scope = false) shouldBe false
        gate(link = NightLink.EXPLICIT, scope = false) shouldBe true
        gate(link = NightLink.EXPLICIT, on = false) shouldBe false
        gate(link = NightLink.EXPLICIT, models = false) shouldBe false
        gate(link = NightLink.EXPLICIT, loose = false) shouldBe false
        gate(link = NightLink.EXPLICIT, store = false) shouldBe false
        gate(link = NightLink.NONE) shouldBe false
        gate(link = NightLink.NONE, scope = false) shouldBe false
    }

    @Test
    fun `線位數＝夜讀同時頁數（至少 1）`() {
        CombinedNight.slots(0) shouldBe 1
        CombinedNight.slots(1) shouldBe 1
        CombinedNight.slots(2) shouldBe 2
        CombinedNight.slots(3) shouldBe 3
    }

    @Test
    fun `夜讀放行多替翻譯留的 heap：翻譯中第 2 頁起每頁 56 MB`() {
        CombinedNight.extraReserve(0) shouldBe 0L
        CombinedNight.extraReserve(1) shouldBe 0L
        CombinedNight.extraReserve(2) shouldBe 56 * mb
        CombinedNight.extraReserve(3) shouldBe 112 * mb
    }

    @Test
    fun `章層級 hold 的真值表`() {
        for (master in listOf(false, true)) {
            for (nightOn in listOf(false, true)) {
                for (link in NightLink.entries) {
                    for (status in Status.entries) {
                        for (active in listOf(false, true)) {
                            for (may in listOf(false, true)) {
                                val want = master && nightOn && link != NightLink.NONE && when (status) {
                                    Status.TRANSLATING -> active
                                    Status.QUEUE -> may
                                    Status.ERROR -> false
                                }
                                CombinedNight.holdActive(master, nightOn, link, status, active, may) shouldBe want
                            }
                        }
                    }
                }
            }
        }
        // 幾個具名的情況
        CombinedNight.holdActive(true, true, NightLink.AUTO, Status.TRANSLATING, true, false) shouldBe true
        CombinedNight.holdActive(true, true, NightLink.AUTO, Status.TRANSLATING, false, true) shouldBe false
        CombinedNight.holdActive(true, true, NightLink.EXPLICIT, Status.QUEUE, false, true) shouldBe true
        CombinedNight.holdActive(true, true, NightLink.EXPLICIT, Status.ERROR, true, true) shouldBe false
        CombinedNight.holdActive(false, true, NightLink.EXPLICIT, Status.TRANSLATING, true, true) shouldBe false
        CombinedNight.holdActive(true, false, NightLink.EXPLICIT, Status.TRANSLATING, true, true) shouldBe false
    }

    @Test
    fun `涵蓋：排隊中，或翻譯中而且夜讀階段開著`() {
        for (status in Status.entries) {
            for (active in listOf(false, true)) {
                CombinedNight.covers(NightLink.NONE, status, active) shouldBe false
            }
        }
        for (link in listOf(NightLink.AUTO, NightLink.EXPLICIT)) {
            CombinedNight.covers(link, Status.QUEUE, false) shouldBe true
            CombinedNight.covers(link, Status.QUEUE, true) shouldBe true
            CombinedNight.covers(link, Status.TRANSLATING, true) shouldBe true
            CombinedNight.covers(link, Status.TRANSLATING, false) shouldBe false
            CombinedNight.covers(link, Status.ERROR, false) shouldBe false
            CombinedNight.covers(link, Status.ERROR, true) shouldBe false
        }
    }

    @Test
    fun `重挑：只在翻譯中、沒開夜讀階段、新意圖非 NONE，而且意圖升了或夜讀剛打開`() {
        val t = Status.TRANSLATING
        // 意圖真的升了
        CombinedNight.restartForNight(t, false, NightLink.AUTO, NightLink.EXPLICIT, false) shouldBe true
        CombinedNight.restartForNight(t, false, NightLink.NONE, NightLink.AUTO, false) shouldBe true
        CombinedNight.restartForNight(t, false, NightLink.NONE, NightLink.EXPLICIT, false) shouldBe true
        // 同意圖不重挑，除非夜讀剛打開
        CombinedNight.restartForNight(t, false, NightLink.AUTO, NightLink.AUTO, false) shouldBe false
        CombinedNight.restartForNight(t, false, NightLink.AUTO, NightLink.AUTO, true) shouldBe true
        CombinedNight.restartForNight(t, false, NightLink.EXPLICIT, NightLink.EXPLICIT, true) shouldBe true
        // 章本身開不了夜讀（壓縮檔章、建不出素材夾）：意圖升了、夜讀剛打開都不重挑
        val wouldRestart = listOf(
            Triple(NightLink.AUTO, NightLink.EXPLICIT, false),
            Triple(NightLink.AUTO, NightLink.AUTO, true),
            Triple(NightLink.NONE, NightLink.EXPLICIT, true),
        )
        for ((old, new, on) in wouldRestart) {
            CombinedNight.restartForNight(t, false, old, new, on, stageBlocked = true) shouldBe false
            CombinedNight.restartForNight(t, false, old, new, on, stageBlocked = false) shouldBe true
        }
        // 新意圖是 NONE 一律不重挑
        CombinedNight.restartForNight(t, false, NightLink.NONE, NightLink.NONE, true) shouldBe false
        CombinedNight.restartForNight(t, false, NightLink.NONE, NightLink.NONE, false) shouldBe false
        // 已經開著夜讀階段一律不重挑
        for (old in NightLink.entries) {
            for (new in NightLink.entries) {
                for (on in listOf(false, true)) {
                    CombinedNight.restartForNight(t, true, old, new, on) shouldBe false
                }
            }
        }
        // 不在翻譯中一律不重挑
        for (status in listOf(Status.QUEUE, Status.ERROR)) {
            CombinedNight.restartForNight(status, false, NightLink.NONE, NightLink.EXPLICIT, true) shouldBe false
        }
    }

    @Test
    fun `頁計畫四種組合`() {
        CombinedNight.planPage(inManifest = false, nightFresh = false) shouldBe PagePlan.TRANSLATE
        // 不在清單的頁一律重翻（落地會作廢舊夜讀版）
        CombinedNight.planPage(inManifest = false, nightFresh = true) shouldBe PagePlan.TRANSLATE
        CombinedNight.planPage(inManifest = true, nightFresh = false) shouldBe PagePlan.NIGHT
        CombinedNight.planPage(inManifest = true, nightFresh = true) shouldBe PagePlan.DONE
    }

    @Test
    fun `q4：清單至少一頁才補，扣掉不可靠頁、保持原順序`() {
        CombinedNight.q4Pages(listOf("3", "1", "2"), emptySet(), manifestPages = 0) shouldBe emptyList()
        CombinedNight.q4Pages(listOf("3", "1", "2"), setOf("1"), manifestPages = 1) shouldBe listOf("3", "2")
        CombinedNight.q4Pages(listOf("3", "1", "2"), emptySet(), manifestPages = 5) shouldBe listOf("3", "1", "2")
        CombinedNight.q4Pages(emptyList<String>(), emptySet(), manifestPages = 5) shouldBe emptyList()
        CombinedNight.q4Pages(listOf("1"), setOf("1"), manifestPages = 5) shouldBe emptyList()
        // 重複的只送一次
        CombinedNight.q4Pages(listOf("2", "2", "1"), emptySet(), manifestPages = 1) shouldBe listOf("2", "1")
    }

    @Test
    fun `補掃：在清單、不新鮮、不在飛、這一輪夜讀沒失敗過，照章內順序`() {
        val pages = listOf("001", "002", "003", "004", "005", "006")
        val picks = CombinedNight.sweepPages(
            pages = pages,
            manifest = setOf("001", "002", "003", "004", "006"),
            nightFresh = setOf("001"),
            inFlight = setOf("003"),
            nightFailedThisRound = setOf("004"),
        )
        picks shouldBe listOf("002", "006")
        CombinedNight.sweepPages(pages, emptySet(), emptySet(), emptySet(), emptySet()) shouldBe emptyList()
        CombinedNight.sweepPages(pages, pages.toSet(), pages.toSet(), emptySet(), emptySet()) shouldBe emptyList()
        CombinedNight.sweepPages(pages, pages.toSet(), emptySet(), emptySet(), emptySet()) shouldBe pages
    }

    @Test
    fun `還原：舊配對夜讀項在有合併工作的章丟掉`() {
        CombinedNight.keepRestoredNightEntry(legacyPaired = true, chapterHasCombined = true) shouldBe false
        CombinedNight.keepRestoredNightEntry(legacyPaired = true, chapterHasCombined = false) shouldBe true
        CombinedNight.keepRestoredNightEntry(legacyPaired = false, chapterHasCombined = true) shouldBe true
        CombinedNight.keepRestoredNightEntry(legacyPaired = false, chapterHasCombined = false) shouldBe true
    }

    @Test
    fun `hold 記號檔名來回`() {
        PairHoldFiles.markerName("012.jpg", tempOriginal = false) shouldBe "012.jpg.pairhold"
        PairHoldFiles.markerName("012.jpg", tempOriginal = true) shouldBe "012.jpg.pairhold-temp"
        // 頁名本身含 .pairhold 字樣也解得對（只錨定結尾）
        val pages = listOf(
            "012.jpg",
            "a.pairhold.jpg",
            "b.pairhold-temp.png",
            "c.orig.webp",
            "d.night.std.webp.jpg",
            "e",
        )
        for (page in pages) {
            for (temp in listOf(false, true)) {
                val name = PairHoldFiles.markerName(page, temp)
                PairHoldFiles.parse(name) shouldBe (page to temp)
                PairHoldFiles.isMarker(name) shouldBe true
            }
        }
        PairHoldFiles.hasMarker(setOf("012.jpg.pairhold"), "012.jpg") shouldBe true
        PairHoldFiles.hasMarker(setOf("012.jpg.pairhold-temp"), "012.jpg") shouldBe true
        PairHoldFiles.hasMarker(setOf("012.jpg.pairhold", "013.jpg.night.std.webp"), "013.jpg") shouldBe false
        PairHoldFiles.hasMarker(emptySet(), "012.jpg") shouldBe false
    }

    @Test
    fun `不是記號的檔名一律解成 null`() {
        val others = listOf(
            NightPages.levelName("012.jpg", NightLevel.STANDARD),
            NightPages.levelName("012.jpg", NightLevel.MORE),
            NightPages.tmpName(NightPages.levelName("012.jpg", NightLevel.STANDARD)),
            NightPages.legacyName("012.jpg"),
            NightPages.rulesStampName("012.jpg"),
            NightPages.STAMP_PROBE,
            OriginalMaterial.exactName("012.jpg"),
            OriginalMaterial.legacyName("012.jpg"),
            OriginalMaterial.tmpName("012.jpg"),
            OriginalMaterial.inflightName("012.jpg"),
            "012.mask.png",
            "012.json",
            NightTextBoxes.fileName("012.jpg"),
            "012.jpg.pairhold.tmp",
            ".pairhold",
            ".pairhold-temp",
            "random.txt",
            "",
        )
        others.forEach {
            PairHoldFiles.parse(it) shouldBe null
            PairHoldFiles.isMarker(it) shouldBe false
        }
    }

    @Test
    fun `記號不會被當成夜讀檔、版本記號、原圖素材或素材 json，清理計畫也不碰它`() {
        val markers = listOf("012.jpg", "a.orig.jpg", "b.night.std.webp.png", "c.json.jpg").flatMap { page ->
            listOf(PairHoldFiles.markerName(page, false), PairHoldFiles.markerName(page, true))
        }
        markers.forEach { m ->
            NightPages.isNightArtifact(m) shouldBe false
            NightPages.isRulesStamp(m) shouldBe false
            OriginalMaterial.isOriginalMaterial(m) shouldBe false
            NightTextBoxes.isMaterialsJson(m) shouldBe false
        }
        val names = markers.toSet() + setOf(
            NightPages.levelName("012.jpg", NightLevel.STANDARD),
            NightPages.rulesStampName("012.jpg"),
            NightPages.tmpName(NightPages.levelName("013.jpg", NightLevel.STANDARD)),
            NightPages.levelName("gone.jpg", NightLevel.STANDARD),
        )
        val plan = NightPages.cleanupPlan(names, pageNames = setOf("012.jpg", "013.jpg"))
        plan.none { PairHoldFiles.isMarker(it) } shouldBe true
        // 該清的照清（暫存、不在本章的頁）
        (NightPages.tmpName(NightPages.levelName("013.jpg", NightLevel.STANDARD)) in plan) shouldBe true
        (NightPages.levelName("gone.jpg", NightLevel.STANDARD) in plan) shouldBe true
        NightPages.cleanupPlan(names, pageNames = null).none { PairHoldFiles.isMarker(it) } shouldBe true
    }
}
