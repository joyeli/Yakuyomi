package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.nightread.NightPages
import eu.kanade.tachiyomi.data.translation.NightTextBoxes.Source
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 夜讀文字框檔（只存夜讀素材的譯後頁，2026-10-06 起）：檔名不會被任何人當成完整素材、夜讀檔或原圖素材；
 * 夜讀先看完整素材 json、沒有才看它；頁換過就不用；遮罩大小對不上就不帶遮罩；夜讀開跑後才寫好的素材要補做。
 */
class NightTextBoxesTest {

    private val page = "012.jpg"
    private val boxesName = "012.jpg.nightboxes.json"

    private fun quad(x: Float, y: Float) = listOf(
        listOf(x, y),
        listOf(x + 10f, y),
        listOf(x + 10f, y + 40f),
        listOf(x, y + 40f),
    )

    private fun region(vararg quads: List<List<Float>>) = RegionMaterial(
        quads = quads.toList(),
        angle = 0f,
        onArt = false,
        source = "こんにちは",
        target = "你好",
        bbox = listOf(0f, 0f, 1f, 1f),
        direction = "v",
        cx = 0f,
        cy = 0f,
        boxW = 1f,
        boxH = 1f,
    )

    private fun boxes(w: Int, h: Int, bytes: Long, mask: Long = 0L, quads: List<List<List<Float>>> = emptyList()) =
        NightTextBoxes.decode(NightTextBoxes.encode(w, h, bytes, mask, quads))!!

    @Test
    fun `檔名：完整頁檔名加尾綴，同 base 不同副檔名的兩頁不撞`() {
        NightTextBoxes.fileName(page) shouldBe boxesName
        NightTextBoxes.fileName("012.png") shouldBe "012.png.nightboxes.json"
        NightTextBoxes.fileName("ch.1_012.webp") shouldBe "ch.1_012.webp.nightboxes.json"
        NightTextBoxes.pageOf(boxesName) shouldBe page
        NightTextBoxes.pageOf("ch.1_012.webp.nightboxes.json") shouldBe "ch.1_012.webp"
        NightTextBoxes.pageOf("012.json").shouldBeNull()
        NightTextBoxes.pageOf(NightTextBoxes.SUFFIX).shouldBeNull() // 頁名是空的
        NightTextBoxes.isBoxesFile(boxesName) shouldBe true
        NightTextBoxes.isBoxesFile("012.json") shouldBe false
    }

    @Test
    fun `完整素材的分界：只有 base json 算，文字框檔、遮罩、原圖、夜讀檔都不算`() {
        NightTextBoxes.materialsJsonName(page) shouldBe "012.json"
        NightTextBoxes.materialsJsonName("ch.1_012.png") shouldBe "ch.1_012.json"
        NightTextBoxes.materialsJsonName(page) shouldBe "${OriginalMaterial.base(page)}.json"

        NightTextBoxes.isMaterialsJson("012.json") shouldBe true
        NightTextBoxes.isMaterialsJson("ch.1_012.json") shouldBe true
        NightTextBoxes.isMaterialsJson(boxesName) shouldBe false
        // 儲存位置同名衝突時改出來的名字也不算（不會被升級全庫當成有素材）
        NightTextBoxes.isMaterialsJson("012.jpg.nightboxes (1).json") shouldBe false
        NightTextBoxes.isMaterialsJson("012.mask.png") shouldBe false
        NightTextBoxes.isMaterialsJson("012.orig.jpg") shouldBe false
        NightTextBoxes.isMaterialsJson("012.json.tmp") shouldBe false
        NightTextBoxes.isMaterialsJson("012.jpg.night.std.webp") shouldBe false
        NightTextBoxes.isMaterialsJson(".json") shouldBe false

        // 只存夜讀素材的頁：素材夾裡有遮罩與文字框，沒有完整 json → 重繪／還原／升級／判成品都當它沒有素材
        val nightOnly = setOf("012.mask.png", boxesName, "012.jpg.night.std.webp", "012.jpg.night.rules4")
        (NightTextBoxes.materialsJsonName(page) in nightOnly) shouldBe false
        nightOnly.none(NightTextBoxes::isMaterialsJson) shouldBe true
        OriginalMaterial.resolve(page, nightOnly).shouldBeNull()

        // storedInpaintMethod 的掃描：文字框檔排在前面也挑到完整 json
        listOf(boxesName, "013.jpg.nightboxes (1).json", "013.jpg.nightboxes.json", "013.json", "013.mask.png")
            .firstOrNull(NightTextBoxes::isMaterialsJson) shouldBe "013.json"
    }

    @Test
    fun `文字框檔不會被夜讀檔或原圖素材的規則認走`() {
        NightPages.isNightArtifact(boxesName) shouldBe false
        NightPages.isFinalNightFile(boxesName) shouldBe false
        NightPages.isRulesStamp(boxesName) shouldBe false
        NightPages.pageOf(boxesName).shouldBeNull()
        OriginalMaterial.isOriginalMaterial(boxesName) shouldBe false
        OriginalMaterial.pageExtensionOf(boxesName).shouldBeNull()
        // 頁名本身帶 .orig.／.night. 也一樣
        OriginalMaterial.isOriginalMaterial(NightTextBoxes.fileName("a.orig.jpg")) shouldBe false
        NightPages.isNightArtifact(NightTextBoxes.fileName("a.night.webp")) shouldBe false
        // 夜讀產生端開跑的清理不碰它（不是夜讀檔，照樣傳進去會被略過）
        val withNight = setOf(boxesName, "012.jpg.night.std.webp")
        (boxesName in NightPages.cleanupPlan(withNight, setOf(page))) shouldBe false
        (boxesName in NightPages.cleanupPlan(setOf(boxesName), emptySet())) shouldBe false
    }

    @Test
    fun `內容：寫了讀得回來，解不開、版本看不懂、寬高不合理都當作沒有`() {
        val quads = listOf(quad(1.5f, 2.25f), quad(100f, 200f))
        val text = NightTextBoxes.encode(1200, 1800, 345_678L, 23_456L, quads)
        val back = NightTextBoxes.decode(text).shouldNotBeNull()
        back.version shouldBe NightTextBoxes.VERSION
        back.width shouldBe 1200
        back.height shouldBe 1800
        back.pageBytes shouldBe 345_678L
        back.maskBytes shouldBe 23_456L
        back.quads shouldBe quads

        NightTextBoxes.encode(10, 10, -1L, -5L, emptyList()).let { NightTextBoxes.decode(it)!! }.let {
            it.pageBytes shouldBe 0L
            it.maskBytes shouldBe 0L
        }

        NightTextBoxes.decode("").shouldBeNull()
        NightTextBoxes.decode("{\"v\":1,\"w\":12").shouldBeNull() // 寫到一半被殺
        NightTextBoxes.decode("not json").shouldBeNull()
        NightTextBoxes.decode("""{"v":2,"w":10,"h":10,"bytes":1,"quads":[]}""").shouldBeNull()
        NightTextBoxes.decode("""{"v":0,"w":10,"h":10,"bytes":1,"quads":[]}""").shouldBeNull()
        NightTextBoxes.decode("""{"v":1,"w":0,"h":10,"bytes":1,"quads":[]}""").shouldBeNull()
        NightTextBoxes.decode("""{"v":1,"w":10,"h":10,"quads":[]}""").shouldBeNull() // 缺欄位
        // 沒有遮罩大小的寫法讀得回來、當作遮罩沒寫成；多出來的欄位不影響
        NightTextBoxes.decode("""{"v":1,"w":10,"h":10,"bytes":1,"quads":[]}""")!!.maskBytes shouldBe 0L
        NightTextBoxes.decode("""{"v":1,"w":10,"h":10,"bytes":1,"mask":3,"quads":[],"extra":true}""")
            .shouldNotBeNull().maskBytes shouldBe 3L
    }

    @Test
    fun `夜讀取框的先後：完整素材 json 優先，沒有才用文字框檔`() {
        val fromJson = quad(1f, 1f)
        val fromBoxes = quad(500f, 500f)
        val materials = PageMaterials(method = "auto_whole", regions = listOf(region(fromJson)))
        val b = boxes(800, 1200, 1000L, quads = listOf(fromBoxes))

        NightTextBoxes.pick(materials, b, true, 800, 1200, 1000L) shouldBe
            NightTextBoxes.Pick(Source.MATERIALS, listOf(fromJson))
        NightTextBoxes.pick(null, b, true, 800, 1200, 1000L) shouldBe
            NightTextBoxes.Pick(Source.BOXES, listOf(fromBoxes))
        NightTextBoxes.pick(null, null, false, 800, 1200, 1000L) shouldBe
            NightTextBoxes.Pick(Source.NONE, emptyList())
        // 文字框檔在、卻讀不出來（寫到一半、壞掉）→ 拿不準，框與遮罩都不帶
        NightTextBoxes.pick(null, null, true, 800, 1200, 1000L) shouldBe
            NightTextBoxes.Pick(Source.STALE_BOXES, emptyList())
        // 完整素材的框不檢查尺寸（同以前）；頁已還原成原圖照樣用（那是原文本來的位置）
        NightTextBoxes.pick(materials, null, false, 1, 1, 0L).source shouldBe Source.MATERIALS
        NightTextBoxes.pick(materials.copy(method = PageTranslator.ORIGINAL_METHOD), null, false, 800, 1200, 1000L)
            .quads shouldBe listOf(fromJson)
    }

    @Test
    fun `頁換過就不用：寬高或位元組數對不上，位元組數讀不到也不用`() {
        val b = boxes(800, 1200, 1000L, quads = listOf(quad(5f, 5f)))
        listOf(
            Triple(801, 1200, 1000L),
            Triple(800, 1199, 1000L),
            Triple(800, 1200, 999L), // 同尺寸的另一張頁（擷取重截／插頁改名）
            Triple(800, 1200, 0L), // 現在讀不到位元組數：寧可不用
            Triple(800, 1200, -1L),
        ).forEach { (w, h, bytes) ->
            val pick = NightTextBoxes.pick(null, b, true, w, h, bytes)
            pick.source shouldBe Source.STALE_BOXES
            pick.quads.shouldBeEmpty()
        }
        // 檔裡沒記位元組數（0）：只比寬高擋不住同尺寸的別張頁 → 一律不用
        val noBytes = boxes(800, 1200, 0L, quads = listOf(quad(5f, 5f)))
        NightTextBoxes.fits(noBytes, 800, 1200, 12345L) shouldBe false
        NightTextBoxes.pick(null, noBytes, true, 800, 1200, 12345L).source shouldBe Source.STALE_BOXES
        NightTextBoxes.fits(b, 800, 1200, 1000L) shouldBe true
    }

    @Test
    fun `遮罩：文字框檔記了大小才帶、要比大小；對不上或讀不出來的文字框檔不帶遮罩`() {
        val withMask = boxes(800, 1200, 1000L, mask = 4321L, quads = listOf(quad(5f, 5f)))
        val noMask = boxes(800, 1200, 1000L, mask = 0L, quads = listOf(quad(5f, 5f)))
        val materials = PageMaterials(method = "boxfill", regions = listOf(region(quad(1f, 1f))))
        val use = { m: PageMaterials?, b: NightTextBoxes.Boxes?, present: Boolean, hasMask: Boolean ->
            NightTextBoxes.maskUse(NightTextBoxes.pick(m, b, present, 800, 1200, 1000L), b, hasMask)
        }
        use(null, withMask, true, true) shouldBe NightTextBoxes.MaskUse.Exact(4321L)
        use(null, withMask, true, false) shouldBe NightTextBoxes.MaskUse.Exact(4321L) // 開跑後才找到的頁照樣帶
        use(null, noMask, true, true) shouldBe NightTextBoxes.MaskUse.Skip // 遮罩沒寫成：殘留的舊遮罩不能用
        use(null, null, true, true) shouldBe NightTextBoxes.MaskUse.Skip // 文字框檔讀不出來
        NightTextBoxes.maskUse(NightTextBoxes.pick(null, withMask, true, 800, 1200, 999L), withMask, true) shouldBe
            NightTextBoxes.MaskUse.Skip // 頁換過
        // 完整素材、只存遮罩的舊頁：跟以前一樣
        use(materials, null, false, false) shouldBe NightTextBoxes.MaskUse.Unchecked
        use(null, null, false, true) shouldBe NightTextBoxes.MaskUse.Unchecked
        use(null, null, false, false) shouldBe NightTextBoxes.MaskUse.Skip // 日文頁
    }

    @Test
    fun `夜讀開跑之後才寫好的素材：什麼時候再找、什麼時候作廢剛做好的夜讀版`() {
        val start = 1_000_000L
        // 再找一次：翻譯在跑、頁是開跑前後才寫的、讀不到修改時間
        NightTextBoxes.mayHaveLateBoxes(true, 1L, start) shouldBe true
        NightTextBoxes.mayHaveLateBoxes(false, start + 5, start) shouldBe true
        NightTextBoxes.mayHaveLateBoxes(false, start - NightTextBoxes.LATE_SLACK_MS, start) shouldBe true
        NightTextBoxes.mayHaveLateBoxes(false, 0L, start) shouldBe true
        // 很久以前的頁不找（快照時就該看得到）
        NightTextBoxes.mayHaveLateBoxes(false, start - NightTextBoxes.LATE_SLACK_MS - 1, start) shouldBe false

        val fit = boxes(800, 1200, 1000L, quads = listOf(quad(5f, 5f)))
        val other = boxes(800, 1200, 999L, quads = listOf(quad(5f, 5f)))
        fun late(source: Source, translating: Boolean, json: Boolean, now: NightTextBoxes.Boxes?) =
            NightTextBoxes.appearedLate(source, translating, json, now, 800, 1200, 1000L)
        late(Source.NONE, true, false, fit) shouldBe true // 讀的時候還沒寫好
        late(Source.NONE, true, true, null) shouldBe true // 保留素材開著：完整 json 後寫好
        late(Source.STALE_BOXES, true, false, fit) shouldBe true // 寫到一半讀到、現在寫好了
        late(Source.STALE_BOXES, true, false, other) shouldBe false // 還是那份對不上的
        late(Source.NONE, true, false, null) shouldBe false // 日文頁
        late(Source.NONE, false, false, fit) shouldBe false // 讀的時候翻譯沒在跑：那一刻沒有頁正在落地
        late(Source.BOXES, true, false, fit) shouldBe false // 讀的時候就有框
        late(Source.MATERIALS, true, true, null) shouldBe false
    }

    @Test
    fun `同一頁拿完整 json 或文字框檔，夜讀拿到的框逐點相同`() {
        val r1 = region(quad(1f, 2f), quad(30f, 2f))
        val r2 = region(quad(300.125f, 400.5f))
        val materials = PageMaterials(method = "boxfill", regions = listOf(r1, r2))
        // 寫入端：各文字區的行四邊形依序攤平（PageTranslator.saveNightMaterials 與 saveMaterials 同一份資料）
        val flat = listOf(r1, r2).flatMap { it.quads }
        val b = boxes(640, 960, 77L, mask = 5L, quads = flat)

        val viaJson = NightTextBoxes.pick(materials, null, false, 640, 960, 77L)
        val viaBoxes = NightTextBoxes.pick(null, b, true, 640, 960, 77L)
        viaBoxes.quads shouldBe viaJson.quads
        viaBoxes.quads.size shouldBe 3
    }

    @Test
    fun `不合格的四邊形照以前一樣丟掉`() {
        val bad3 = quad(0f, 0f).take(3)
        val badPoint = quad(0f, 0f).toMutableList().also { it[2] = listOf(1f) }
        val good = quad(9f, 9f)
        val materials = PageMaterials(method = "boxfill", regions = listOf(region(bad3, good), region(badPoint)))
        NightTextBoxes.pick(materials, null, false, 1, 1, 1L).quads shouldBe listOf(good)
        val b = boxes(1, 1, 1L, quads = listOf(bad3, good, badPoint))
        NightTextBoxes.pick(null, b, true, 1, 1, 1L).quads shouldBe listOf(good)
    }

    @Test
    fun `清理：只刪已經不在這章的頁的文字框檔`() {
        val names = listOf(
            boxesName,
            "013.jpg.nightboxes.json",
            "013.json",
            "013.mask.png",
            "013.jpg.night.std.webp",
        )
        NightTextBoxes.orphans(names, setOf(page, ".yakuyomi")) shouldBe setOf("013.jpg.nightboxes.json")
        NightTextBoxes.orphans(names, setOf(page, "013.jpg")).shouldBeEmpty()
        // 列目錄不可信（null）→ 一個都不刪
        NightTextBoxes.orphans(names, null).shouldBeEmpty()
        // 換了副檔名（012.jpg 不在、012.png 在）＝不同頁
        NightTextBoxes.orphans(listOf(boxesName), setOf("012.png")) shouldBe setOf(boxesName)
    }
}
