package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * 佇列項的持久化格式：合併意圖來回一致；合併處理 v1 的 debug 版寫過的舊欄位 `pairRole` 解得開、認得出舊配對夜讀項；
 * v0.23.0 的舊資料與壞資料都解得回來。
 */
class TranslationStoreObjectTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** 嚴格的 Json（不忽略未知欄位）：舊 JSON 的 `pairRole` 有對應欄位，照樣解得開。 */
    private val strictJson = Json { explicitNulls = false }

    private fun saved(
        nightLink: NightLink = NightLink.NONE,
        nightRender: Boolean = false,
    ) = TranslationStore.Saved(
        mangaId = 11L,
        chapterId = 22L,
        method = "auto_whole",
        reRenderMethod = null,
        errored = true,
        mangaPaused = true,
        nightRender = nightRender,
        paused = true,
        nightForce = 1234L,
        nightLink = nightLink,
    )

    @Test
    fun `合併意圖存了再讀回來一樣`() {
        for (link in NightLink.entries) {
            val raw = TranslationStore.encodeObject(json, saved(nightLink = link), 7)
            val obj = TranslationStore.decodeObject(json, raw)!!
            NightLink.fromCode(obj.nightLink) shouldBe link
            obj.order shouldBe 7
        }
    }

    @Test
    fun `舊欄位 pairRole 一律寫 0（不輸出），解回來是 0、不是舊配對`() {
        for (night in listOf(false, true)) {
            val raw = TranslationStore.encodeObject(json, saved(NightLink.AUTO, nightRender = night), 2)
            raw shouldNotContain "pairRole"
            val obj = TranslationStore.decodeObject(json, raw)!!
            obj.pairRole shouldBe 0
            TranslationStore.isLegacyPaired(obj.pairRole) shouldBe false
        }
    }

    @Test
    fun `其他欄位照舊來回`() {
        val obj = TranslationStore.decodeObject(
            json,
            TranslationStore.encodeObject(json, saved(NightLink.AUTO), 5),
        )!!
        obj.mangaId shouldBe 11L
        obj.chapterId shouldBe 22L
        obj.order shouldBe 5
        obj.method shouldBe "auto_whole"
        obj.reRenderMethod shouldBe null
        obj.errored shouldBe true
        obj.mangaPaused shouldBe true
        obj.nightRender shouldBe false
        obj.paused shouldBe true
        obj.nightForce shouldBe 1234L
        obj.nightLink shouldBe NightLink.AUTO.code
    }

    @Test
    fun `v1 debug 版的 JSON（pairRole 1 或 2）解得開、認得出舊配對夜讀項`() {
        for (code in listOf(1, 2)) {
            val raw = """{"mangaId":1,"chapterId":2,"order":3,"nightRender":true,"pairRole":$code}"""
            for (j in listOf(json, strictJson)) {
                val obj = TranslationStore.decodeObject(j, raw)!!
                obj.nightRender shouldBe true
                obj.pairRole shouldBe code
                TranslationStore.isLegacyPaired(obj.pairRole) shouldBe true
                NightLink.fromCode(obj.nightLink) shouldBe NightLink.NONE
            }
        }
        // 同一份 debug 資料裡的合併翻譯項
        val t = TranslationStore.decodeObject(json, """{"mangaId":1,"chapterId":2,"order":0,"nightLink":2}""")!!
        NightLink.fromCode(t.nightLink) shouldBe NightLink.EXPLICIT
        TranslationStore.isLegacyPaired(t.pairRole) shouldBe false
    }

    @Test
    fun `舊版（v0_23_0）存的 JSON 沒有新欄位：解成只翻譯、不是舊配對，其他欄位不變`() {
        val old = """{"mangaId":1,"chapterId":2,"order":3,"method":"boxfill","reRenderMethod":"auto_tile",""" +
            """"errored":true,"mangaPaused":true,"nightRender":true,"paused":true,"nightForce":99}"""
        val obj = TranslationStore.decodeObject(json, old)!!
        NightLink.fromCode(obj.nightLink) shouldBe NightLink.NONE
        TranslationStore.isLegacyPaired(obj.pairRole) shouldBe false
        obj.mangaId shouldBe 1L
        obj.chapterId shouldBe 2L
        obj.order shouldBe 3
        obj.method shouldBe "boxfill"
        obj.reRenderMethod shouldBe "auto_tile"
        obj.errored shouldBe true
        obj.mangaPaused shouldBe true
        obj.nightRender shouldBe true
        obj.paused shouldBe true
        obj.nightForce shouldBe 99L
        // 更舊的（只有必要欄位）也解得開
        val oldest = TranslationStore.decodeObject(json, """{"mangaId":1,"chapterId":2,"order":0}""")!!
        oldest.nightLink shouldBe 0
        oldest.pairRole shouldBe 0
        oldest.nightForce shouldBe -1L
    }

    @Test
    fun `認不得的代碼當成 NONE、不是舊配對，壞資料解不開回 null`() {
        val obj = TranslationStore.decodeObject(
            json,
            """{"mangaId":1,"chapterId":2,"order":0,"nightLink":9,"pairRole":9}""",
        )!!
        NightLink.fromCode(obj.nightLink) shouldBe NightLink.NONE
        TranslationStore.isLegacyPaired(obj.pairRole) shouldBe false
        TranslationStore.isLegacyPaired(0) shouldBe false
        TranslationStore.isLegacyPaired(-1) shouldBe false
        TranslationStore.decodeObject(json, "not json") shouldBe null
        TranslationStore.decodeObject(json, """{"chapterId":2}""") shouldBe null
    }
}
