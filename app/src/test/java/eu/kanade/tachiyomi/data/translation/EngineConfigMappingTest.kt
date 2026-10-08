package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.TextOrientation
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.service.TranslationPreferences
import java.lang.reflect.Field

/**
 * 偏好 → 引擎設定的純映射（[EngineConfigMapping]）：重繪（[PageTranslator.reRenderChapter]／reRenderPage）拿的去字／排版設定
 * 要和翻譯（常駐引擎 [EngineConfigMapping.buildEngineConfig]）那份一模一樣；常駐引擎的重建簽章要涵蓋 buildEngineConfig 讀到的
 * 每一個 pref。以前重繪自己內聯一份、漏了去字解析度／遮罩膨脹／縱中橫，簽章也曾一次漏 13 個。
 */
class EngineConfigMappingTest {

    private fun prefs(setup: TranslationPreferences.() -> Unit = {}) =
        TranslationPreferences(InMemoryPreferenceStore()).apply(setup)

    /** 佇列／reader 會傳進來的去字法原始字串（含退役的 auto_tile 與空字串，映射一律當 aot）。 */
    private val methods = listOf("auto_whole", "boxfill", "auto_tile", "")

    @Test
    fun `預設值：重繪拿到的就是引擎預設（768、24、16、縱中橫開）`() {
        val p = prefs()
        EngineConfigMapping.inpainterConfig(p, "auto_whole") shouldBe InpainterConfig(method = "aot")
        EngineConfigMapping.inpainterConfig(p, "boxfill") shouldBe InpainterConfig(method = "boxfill")
        EngineConfigMapping.renderConfig(p) shouldBe RenderConfig()
    }

    @Test
    fun `重繪帶上使用者的去字解析度、遮罩膨脹、縱中橫`() {
        val p = prefs {
            tileSize.set(1024)
            maskDilate.set(36)
            tateChuYoko.set(false)
            bboxPad.set("8")
        }
        val inpainter = EngineConfigMapping.inpainterConfig(p, "auto_whole")
        inpainter.method shouldBe "aot"
        inpainter.tileSize shouldBe 1024
        inpainter.maskDilate shouldBe 36f
        inpainter.bboxPad shouldBe 8
        EngineConfigMapping.renderConfig(p).tateChuYoko shouldBe false

        val small = prefs { tileSize.set(512) }
        EngineConfigMapping.inpainterConfig(small, "boxfill").tileSize shouldBe 512
    }

    @Test
    fun `重繪的去字與排版設定和翻譯那份逐欄相同`() {
        val setups = listOf<TranslationPreferences.() -> Unit>(
            {},
            {
                tileSize.set(512)
                maskDilate.set(10)
                bboxPad.set("40")
                tateChuYoko.set(false)
                orientation.set("horizontal")
                fontBorder.set(false)
                colorMode.set("mono")
                artStrokeRatio.set("0.3")
                fontSizeMax.set("80")
                fontSizeMin.set("12")
                colTrim.set("5")
                rowTrim.set("1")
                fontScale.set("1.1")
            },
            {
                tileSize.set(1024)
                maskDilate.set(40)
                orientation.set("vertical")
                provider.set("custom")
                apiBase.set("http://10.0.0.1:8080/v1")
            },
        )
        for (setup in setups) {
            val p = prefs(setup)
            for (m in methods) {
                val engine = EngineConfigMapping.buildEngineConfig(p, m)
                EngineConfigMapping.inpainterConfig(p, m) shouldBe engine.inpainter
                EngineConfigMapping.renderConfig(p) shouldBe engine.render
            }
        }
    }

    /**
     * 常駐引擎簽章裡的去字法用 [EngineConfigMapping.mapInpaintMethod] 映射後的值（TranslationEngineService.signatureMethod）：
     * 前提是 buildEngineConfig 只經由這個映射看去字法，映射後相同的原始字串建出來的設定要一模一樣。
     */
    @Test
    fun `去字法舊值與 auto_whole 建出同一份引擎設定，簽章可用映射後的值`() {
        for (p in listOf(prefs(), prefs { tileSize.set(1024) })) {
            val whole = EngineConfigMapping.buildEngineConfig(p, "auto_whole")
            for (legacy in listOf("auto_tile", "auto_aot", "lama_whole", "")) {
                EngineConfigMapping.mapInpaintMethod(legacy) shouldBe "aot"
                EngineConfigMapping.buildEngineConfig(p, legacy) shouldBe whole
            }
            EngineConfigMapping.mapInpaintMethod("boxfill") shouldBe "boxfill"
            (EngineConfigMapping.buildEngineConfig(p, "boxfill") == whole) shouldBe false
        }
    }

    @Test
    fun `超出值域的數值夾回、壞字串退回預設`() {
        fun inp(setup: TranslationPreferences.() -> Unit) =
            EngineConfigMapping.inpainterConfig(prefs(setup), "auto_whole")
        fun ren(setup: TranslationPreferences.() -> Unit) = EngineConfigMapping.renderConfig(prefs(setup))

        inp { tileSize.set(4096) }.tileSize shouldBe 1024
        inp { tileSize.set(100) }.tileSize shouldBe 512
        inp { maskDilate.set(2) }.maskDilate shouldBe 8f
        inp { maskDilate.set(99) }.maskDilate shouldBe 40f
        inp { bboxPad.set("abc") }.bboxPad shouldBe 16
        inp { bboxPad.set("500") }.bboxPad shouldBe 64
        inp { bboxPad.set("-3") }.bboxPad shouldBe 0

        ren { orientation.set("vertical") }.orientation shouldBe TextOrientation.VERTICAL
        ren { orientation.set("horizontal") }.orientation shouldBe TextOrientation.HORIZONTAL
        ren { orientation.set("weird") }.orientation shouldBe TextOrientation.AUTO
        ren { colorMode.set("mono") }.colorMode shouldBe "mono"
        ren { colorMode.set("other") }.colorMode shouldBe "auto"
        ren { fontScale.set("9") }.fontScale shouldBe 1.5f
        ren { colTrim.set("x") }.colTrim shouldBe 3
        ren { fontSizeMax.set("5") }.fontSizeMax shouldBe 20
        ren { fontSizeMin.set("100") }.fontSizeMin shouldBe 40
    }

    /**
     * 把 [TranslationPreferences] 每個 pref 改成幾個候選值，各在兩種起點（預設；自訂供應商＋自填端點，讓 apiBase 也會影響設定）
     * 上比：只要 [EngineConfigMapping.buildEngineConfig] 因此變了，[EngineConfigMapping.signatureFields] 就一定要跟著變
     * （否則常駐引擎不重建、新設定不生效）；同時重繪那兩份設定要一直等於翻譯那份的同名欄位。
     * 以後在 buildEngineConfig 加讀新的 pref、忘了加進簽章，這個測試會點名。
     */
    @Test
    fun `每個會改到引擎設定的 pref 都在簽章裡，重繪設定一直跟翻譯那份相同`() {
        val baselines = listOf<TranslationPreferences.() -> Unit>(
            {},
            {
                provider.set("custom")
                apiBase.set("http://10.0.0.1:8080/v1")
            },
        )
        val affecting = mutableSetOf<String>()
        val missing = mutableListOf<String>()
        val mismatch = mutableListOf<String>()
        for (baseline in baselines) {
            val base = prefs(baseline)
            val baseSig = EngineConfigMapping.signatureFields(base)
            val baseCfg = methods.associateWith { EngineConfigMapping.buildEngineConfig(base, it) }
            for (field in prefFields) {
                for (value in candidates(prefOf(base, field).defaultValue())) {
                    val changed = prefs(baseline).also { prefOf(it, field).set(value) }
                    var configChanged = false
                    for (m in methods) {
                        val cfg = EngineConfigMapping.buildEngineConfig(changed, m)
                        if (cfg != baseCfg.getValue(m)) configChanged = true
                        if (EngineConfigMapping.inpainterConfig(changed, m) != cfg.inpainter ||
                            EngineConfigMapping.renderConfig(changed) != cfg.render
                        ) {
                            mismatch += "${field.name}=$value ($m)"
                        }
                    }
                    if (configChanged) {
                        affecting += field.name
                        if (EngineConfigMapping.signatureFields(changed) == baseSig) missing += "${field.name}=$value"
                    }
                }
            }
        }
        missing.shouldBeEmpty()
        mismatch.shouldBeEmpty()
        // 防止測試空轉：已知 buildEngineConfig 會讀的 pref 都要真的被改到、被檢查到。
        affecting shouldContainAll listOf(
            "provider", "model", "apiBase", "temperature", "thinking", "targetLangName", "sourceLangName",
            "segThreshold", "detectUnsharp", "dbnetSize",
            "minProb", "ignoreSfx", "stripPad", "useBicubic", "ocrUnsharp", "ocrConcurrency",
            "bboxPad", "tileSize", "maskDilate",
            "orientation", "fontBorder", "colorMode", "artStrokeRatio", "fontSizeMax", "fontSizeMin",
            "colTrim", "rowTrim", "fontScale", "tateChuYoko",
        )
    }

    private val prefFields: List<Field> = TranslationPreferences::class.java.declaredFields
        .filter { Preference::class.java.isAssignableFrom(it.type) }
        .onEach { it.isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun prefOf(p: TranslationPreferences, field: Field): Preference<Any> = field.get(p) as Preference<Any>

    private fun candidates(default: Any): List<Any> = when (default) {
        is Boolean -> listOf(!default)
        is Int -> listOf(default + 1, default - 1, 0, 512, 1024, 4096)
        is Long -> listOf(default + 1)
        is Float -> listOf(default + 1f)
        is String -> STRING_CANDIDATES
        is Set<*> -> listOf(setOf("1"))
        else -> emptyList()
    }.filter { it != default }

    private companion object {
        /** 字串 pref 的候選值：數字（含會被夾回的）、各列舉值、供應商 id、網址、亂字串。 */
        val STRING_CANDIDATES = listOf(
            "0", "1", "2", "7", "0.05", "0.7", "vertical", "horizontal", "mono", "bilinear",
            "openai", "custom", "boxfill", "http://127.0.0.1:8080/v1", "zz-test",
        )
    }
}
