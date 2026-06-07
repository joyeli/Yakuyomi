package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.Yakuyomi
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 即時翻譯（reader 邊讀邊翻）用的**常駐引擎服務**（process singleton，於 [AppModule] 註冊）。
 *
 * 與離線整章翻（[PageTranslator.translateChapter]，每章 `Yakuyomi.create(...).use { }` 用完即釋放）的差別：
 *  - 這裡引擎**跨頁、跨章復用**——一次建好（~450MB native）就留著，避免每頁/每章重載 ~450MB + 編譯延遲（M4 ⑦ 引擎生命週期）。
 *  - 去字法**一律 boxfill**（低延遲）：即時讀的當下不能等整頁 lama 的 ~7s；使用者選的去字法是未來「閒置自動升級」的目標，不在即時路徑用。
 *
 * **並發**：單一引擎實例**非並發安全**（同實例同時翻多頁會壞）→ 用 [mutex] 序列化 [translate]；
 * 多個 [TranslatingPageLoader.loadPage] 併發呼叫會在此排隊（一次只有一頁進引擎）。
 * **生命週期**：[translate] 首次呼叫才 lazy 建引擎（不拖 app 冷啟）；設定改了（簽章變）會在下次 [translate] 重建；
 * [shutdown] 釋放原生資源（reader 全關時可叫，但 process singleton 通常留著）。
 */
class TranslationEngineService(private val context: Context) {

    private val translationPreferences: TranslationPreferences = Injekt.get()

    /** 引擎非並發安全 → 所有 [translate]/[shutdown]/重建都在此鎖下序列化（一次一頁進引擎）。 */
    private val mutex = Mutex()

    /** 常駐引擎（lazy 建、跨頁/章復用）。null＝還沒建或已 [shutdown]。只在 [mutex] 下讀寫。 */
    private var engine: TranslationEngine? = null

    /** 上次建引擎時的設定簽章；與當前 prefs 簽章不同 → 設定改過 → 重建（讓設定即時生效）。只在 [mutex] 下讀寫。 */
    private var builtSignature: String? = null

    /** [translate] 的回傳：去字+排版後的新 bitmap + 重繪素材（日後 boxfill→lama 升級用，本里程碑先留著不落地）。 */
    data class EnginePageOutput(val bitmap: Bitmap, val analysis: PageAnalysis?)

    /** key：優先設定頁（BYOK）；空白時 fallback build-time key（與 [PageTranslator] 同規則）。 */
    private fun apiKey(): String =
        translationPreferences.apiKey.get().ifBlank { BuildConfig.DEEPSEEK_API_KEY }

    /**
     * 即時翻譯是否就緒：key 有設 + 3 顆模型齊。給 [ChapterLoader] 決定要不要包 [TranslatingPageLoader]。
     *
     * **刻意不檢查 [TranslationPreferences.translationEnabled]**——那是「下載時翻譯章節」（離線整章翻）的開關，
     * 手動翻的使用者常關著它；即時翻譯由 [TranslationPreferences.liveTranslate] 獨立控制（在 [ChapterLoader.shouldTranslateLive] 檢查），
     * 故此處只看 key + 模型，否則即時翻會被 translationEnabled 靜默擋掉。
     * 注意：[PageTranslator] 的下載 hook 仍各自檢查 translationEnabled，不受此影響。
     */
    fun isReady(): Boolean {
        if (apiKey().isBlank()) return false
        return TranslationEngineConfig.hasAllModels(context)
    }

    /**
     * 翻譯單頁（bitmap → bitmap）。**不 recycle 輸入 [src]（所有權屬呼叫端）**；成功時回的 bitmap 是引擎產出的新物件。
     *
     * §11：[PageResult.Skipped]（沒字可翻）/[PageResult.Failed]（網路等錯誤）/未就緒/例外 → 回 **null**，
     * 由呼叫端顯示原圖（絕不用比原圖更糟的東西蓋掉）。
     *
     * 在 [mutex] 下序列化：一次只有一頁進引擎；模型 SAF→filesDir 複製（[TranslationEngineConfig.resolveModelSet]）
     * 也在此（suspend、背景 dispatcher）發生、不卡 UI。
     */
    suspend fun translate(src: Bitmap): EnginePageOutput? {
        return mutex.withLock {
            if (!isReady()) return@withLock null
            val engine = ensureEngine() ?: return@withLock null
            try {
                when (val r = engine.translatePage(src)) {
                    is PageResult.Translated -> EnginePageOutput(r.page, r.analysis)
                    is PageResult.Skipped -> null // 沒字可翻 → 顯示原圖
                    is PageResult.Failed -> null // 網路/例外 → 顯示原圖（§11）
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "即時翻譯單頁失敗（顯示原圖）" }
                null
            }
        }
    }

    /**
     * 取常駐引擎；設定簽章變了先 [closeEngine] 再重建，沒建過就建。建不起來（模型缺/例外）回 null。
     * **僅在 [mutex] 下呼叫**（[translate] 內）。
     */
    private fun ensureEngine(): TranslationEngine? {
        val signature = configSignature()
        if (engine != null && signature == builtSignature) return engine

        // 設定改過 → 丟舊引擎重建（釋放舊 native session 才不疊加 ~450MB）。
        closeEngine()

        return try {
            // 模型解析 + 字元表（SAF→filesDir 複製在此；缺模型回 null）。與離線翻共用同一份解析。
            val bundle = TranslationEngineConfig.resolveModelSet(context) ?: return null
            // 即時翻譯**固定 boxfill**（低延遲）；其餘參數（語言/緒數/排版…）照使用者設定。
            // TODO(live): boxfill→lama 升級——閒置時用使用者選的去字法重翻當頁、無縫換上更乾淨的圖。
            val cfg = TranslationEngineConfig.buildEngineConfig(translationPreferences, LIVE_INPAINT_METHOD)
            engine = Yakuyomi.create(bundle.models, bundle.alphabet, apiKey(), cfg)
            builtSignature = signature
            engine
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "建即時翻譯引擎失敗" }
            closeEngine()
            null
        }
    }

    /**
     * 影響引擎建構的設定的簽章。值變了＝要重建引擎（設定即時生效）。
     * 去字法固定 boxfill 故不入簽章；但語言/緒數/OCR/排版等改了要反映 → 全納入。
     */
    private fun configSignature(): String {
        val p = translationPreferences
        return listOf(
            apiKey(),
            p.targetLangName.get(),
            p.sourceLangName.get(),
            p.orientation.get(),
            p.ocrConcurrency.get(),
            p.intraThreads.get(),
            p.colorMode.get(),
            p.fontBorder.get().toString(),
            p.segThreshold.get(),
            p.minProb.get(),
            p.autoStdThreshold.get(),
            p.autoWhiteThreshold.get(),
            p.bboxPad.get(),
            p.artStrokeRatio.get(),
            p.fontSizeMax.get(),
            p.fontSizeMin.get(),
            p.colTrim.get(),
            p.rowTrim.get(),
            p.fontScale.get(),
        ).joinToString("")
    }

    /** 釋放引擎的原生資源（3 顆 ONNX session）。僅在 [mutex] 下呼叫。 */
    private fun closeEngine() {
        try {
            engine?.close()
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "關閉即時翻譯引擎時例外" }
        }
        engine = null
        builtSignature = null
    }

    /** 對外關閉（reader 全部關閉時可叫）：在 [mutex] 下釋放引擎。process singleton 多半留著、不必每次叫。 */
    suspend fun shutdown() = mutex.withLock {
        closeEngine()
    }

    companion object {
        /** 即時翻譯固定用 boxfill（低延遲）；使用者的 inpaintMethod 是未來升級目標、不用於即時。 */
        private const val LIVE_INPAINT_METHOD = "boxfill"
    }
}
