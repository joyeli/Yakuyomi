package eu.kanade.tachiyomi.data.translation

import li.joye.yakuyomi.engine.DetectorConfig
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.LlmProviders
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TranslatorConfig
import tachiyomi.domain.translation.service.TranslationPreferences

/**
 * 翻譯偏好 → 引擎設定的**純映射**：只讀 [TranslationPreferences]，不碰 Context、Injekt、檔案、模型。
 *
 * 三個地方共用這一份，免得各抄一份又飄掉：
 *  - 翻譯：常駐引擎 [TranslationEngineService] 經 [TranslationEngineConfig.buildEngineConfig] 拿 [buildEngineConfig]；
 *  - 重繪：[PageTranslator.reRenderChapter]／[PageTranslator.reRenderPage]（含 reader 單頁重繪與設定頁觸發的整庫重繪）
 *    不跑偵測／OCR／翻譯，只拿 [inpainterConfig] 與 [renderConfig]——就是翻譯那份 [EngineConfig] 的同名兩欄；
 *  - 常駐引擎的重建簽章：[signatureFields]。
 *
 * 重繪以前各自內聯一份 InpainterConfig／RenderConfig，漏了去字解析度、遮罩膨脹、縱中橫（固定用引擎預設 768／24／開），
 * 改了這三個設定之後重繪照舊畫（設定頁改縱中橫會問要不要重繪已翻章，重繪了卻沒套上）。現在兩邊是同一個函式的結果。
 *
 * 獨立成物件、不放進 [TranslationEngineConfig]，是為了 JVM 單元測試：那個物件一初始化就要從 Injekt 拿 StoragePreferences。
 */
object EngineConfigMapping {

    /**
     * 去字方法字串（[TranslationPreferences.inpaintMethod] / 即時翻的 [TranslationPreferences.liveInpaintMethod] 原始值）
     * → 引擎 method。兩門別：`boxfill`（快速去字·平塗）／其餘＝`aot`（AI 去字·NCNN AOT-GAN 整頁·預設）。
     * 集中於此一處映射、下載/即時/重繪共用。
     */
    fun mapInpaintMethod(methodRaw: String): String = if (methodRaw == "boxfill") "boxfill" else "aot"

    /**
     * 用 [prefs] 組出完整 [EngineConfig]（偵測/OCR/翻譯/去字/排版）。
     *
     * @param methodRaw 去字方法原始字串（[TranslationPreferences.inpaintMethod] 或即時翻的
     *   [TranslationPreferences.liveInpaintMethod]）。此處只決定去字 method，其餘參數一律照 [prefs]。
     *
     * 緒數裝置相依、進階數值 parse + clamp、改目標語言時清掉內建 few-shot。
     * 這裡讀到的每一個 pref 都要在 [signatureFields] 裡（測試 EngineConfigMappingTest 用反射逐一檢查）。
     */
    fun buildEngineConfig(prefs: TranslationPreferences, methodRaw: String): EngineConfig {
        // 供應商：解析預設表 → 聊天端點 + 模型（per-provider，見引擎 LlmProviders / 設定頁）。
        // 全 OpenAI 相容（含 Gemini 的 compat 端點）⇒ LlmTranslator 不變，只是換 apiBase/model。
        val preset = LlmProviders.byId(prefs.provider.get())
        val chatUrl = LlmProviders.chatUrlOf(preset, prefs.apiBase.get())
        // 語言對（預設日→繁中）。改目標語言就清掉引擎內建的日→繁中 few-shot，免得範例語言跟新目標衝突、把輸出帶偏。
        val target = prefs.targetLangName.get()
        // LLM 取樣溫度（存字串、parse + clamp 到 0.0–1.0；預設 0.3）。
        val temperature = prefs.temperature.get().toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.3
        var translatorCfg = TranslatorConfig(
            provider = preset.id,
            model = prefs.model.get().ifBlank { preset.defaultModel },
            // base 空（自架/自訂未填）→ isReady 已擋；萬一漏 → LlmTranslator 拋例外標 Failed（不靜默）。
            apiBase = chatUrl,
            toLangName = target,
            fromLangName = prefs.sourceLangName.get(),
            temperature = temperature,
            // 思考模式（預設關）：欄位形狀 per-provider，由引擎 LlmProviders.requestParams 映射。
            thinking = prefs.thinking.get(),
        )
        if (target != TranslationPreferences.DEFAULT_TARGET_LANG) {
            translatorCfg = translatorCfg.copy(sampleSource = "", sampleTarget = "")
        }

        // OCR 逐行並發度：auto=核數 / 2/4/6/8（concurrent 鎖 true）。真機 8.9s→4.8s。
        val cores = Runtime.getRuntime().availableProcessors()
        val ocrConcurrency = when (val v = prefs.ocrConcurrency.get()) {
            "auto" -> cores
            else -> (v.toIntOrNull() ?: cores).coerceIn(1, 32)
        }

        return EngineConfig(
            detector = DetectorConfig(
                segThreshold = parseFloat(prefs.segThreshold.get(), 0f, 1f, 0.12f),
                // 進階辨識：偵測輸入銳利化（預設關）+ DBNet 辨識尺寸（768–1536，clamp）。
                detectUnsharp = prefs.detectUnsharp.get(),
                dbnetInputSize = prefs.dbnetSize.get().coerceIn(768, 1536),
            ),
            ocr = OcrConfig(
                minProb = parseFloat(prefs.minProb.get(), 0f, 1f, 0.5f),
                // 跳過狀聲詞 SFX：開→給內建門檻 24（1–50 中段，不讓使用者調數字）、關→0。
                ignoreBubble = if (prefs.ignoreSfx.get()) 24 else 0,
                // 進階辨識：OCR 裁切外擴（0–12，clamp）+ 內插法（bicubic/bilinear）+ strip 銳化（預設開）。
                stripPad = prefs.stripPad.get().coerceIn(0, 12),
                useBicubic = prefs.useBicubic.get() == "bicubic",
                ocrUnsharp = prefs.ocrUnsharp.get(),
                concurrent = true,
                concurrency = ocrConcurrency,
            ),
            translator = translatorCfg,
            inpainter = inpainterConfig(prefs, methodRaw),
            render = renderConfig(prefs),
        )
    }

    /**
     * 去字設定（翻譯與重繪共用）。重繪不跑偵測：遮罩取素材裡存的原始 seg 遮罩（`<base>.mask.png`），bbox 外擴與膨脹
     * 都在引擎 Inpainter 裡照這份設定再做一次，所以 [InpainterConfig.bboxPad]／[InpainterConfig.maskDilate] 對重繪一樣有效。
     */
    fun inpainterConfig(prefs: TranslationPreferences, methodRaw: String): InpainterConfig = InpainterConfig(
        method = mapInpaintMethod(methodRaw),
        bboxPad = parseInt(prefs.bboxPad.get(), 0, 64, 16),
        // 進階去字：整頁去字解析度（三檔 512/768/1024，clamp 保險）+ 遮罩膨脹（8–40，存 Int→Float）。
        tileSize = prefs.tileSize.get().coerceIn(512, 1024),
        maskDilate = prefs.maskDilate.get().coerceIn(8, 40).toFloat(),
    )

    /** 排版設定（翻譯與重繪共用）。 */
    fun renderConfig(prefs: TranslationPreferences): RenderConfig = RenderConfig(
        orientation = when (prefs.orientation.get()) {
            "vertical" -> TextOrientation.VERTICAL
            "horizontal" -> TextOrientation.HORIZONTAL
            else -> TextOrientation.AUTO
        },
        fontBorder = prefs.fontBorder.get(),
        colorMode = if (prefs.colorMode.get() == "mono") "mono" else "auto",
        artStrokeRatio = parseFloat(prefs.artStrokeRatio.get(), 0f, 0.5f, 0.16f),
        fontSizeMax = parseInt(prefs.fontSizeMax.get(), 20, 120, 60),
        fontSizeMin = parseInt(prefs.fontSizeMin.get(), 6, 40, 9),
        colTrim = parseInt(prefs.colTrim.get(), 0, 10, 3),
        rowTrim = parseInt(prefs.rowTrim.get(), 0, 10, 3),
        fontScale = parseFloat(prefs.fontScale.get(), 0.3f, 1.5f, 0.85f),
        // 進階排版：縱中橫（直排短 ASCII 串水平並排，預設開）。
        tateChuYoko = prefs.tateChuYoko.get(),
    )

    /**
     * 常駐引擎重建簽章裡「去字法與 API key 以外」的欄位（[TranslationEngineService] 的 configSignature 再前置去字法與 key）。
     *
     * **維護鐵則：本清單必須涵蓋 [buildEngineConfig] 讀到的每一個 pref**。漏一個＝改了那個設定卻沿用 warm 引擎、
     * 舊值繼續生效（曾漏 provider/model/apiBase/temperature 等 13 個 → 使用者換 model 後請求仍打舊 model、持續 HTTP 400）。
     * 日後在 [buildEngineConfig] 加讀任何 pref，**同時**在此加一行；順序刻意對齊 [buildEngineConfig] 的分區。
     * EngineConfigMappingTest 用反射把 [TranslationPreferences] 每個 pref 改一下：改了會讓 [buildEngineConfig] 變、
     * 簽章卻沒變 → 測試失敗。
     */
    fun signatureFields(prefs: TranslationPreferences): List<String> = listOf(
        // —— LLM（TranslatorConfig）：換 provider/model/apiBase/temperature 都要重建才會套用 ——
        prefs.provider.get(),
        prefs.model.get(),
        prefs.apiBase.get(),
        prefs.temperature.get(),
        prefs.thinking.get().toString(), // 思考模式（per-provider 參數映射）→ 換了要重建才會套用
        prefs.targetLangName.get(), // 也決定要不要清掉引擎內建 few-shot
        prefs.sourceLangName.get(),
        // —— 偵測（DetectorConfig）——
        prefs.segThreshold.get(),
        prefs.detectUnsharp.get().toString(),
        prefs.dbnetSize.get().toString(),
        // —— OCR（OcrConfig）——
        prefs.minProb.get(),
        prefs.ignoreSfx.get().toString(),
        prefs.stripPad.get().toString(),
        prefs.useBicubic.get(),
        prefs.ocrUnsharp.get().toString(),
        prefs.ocrConcurrency.get(),
        // —— 去字（InpainterConfig）——
        prefs.bboxPad.get(),
        prefs.tileSize.get().toString(),
        prefs.maskDilate.get().toString(),
        // —— 排版（RenderConfig）——
        prefs.orientation.get(),
        prefs.fontBorder.get().toString(),
        prefs.colorMode.get(),
        prefs.artStrokeRatio.get(),
        prefs.fontSizeMax.get(),
        prefs.fontSizeMin.get(),
        prefs.colTrim.get(),
        prefs.rowTrim.get(),
        prefs.fontScale.get(),
        prefs.tateChuYoko.get().toString(),
    )

    // 進階數值：存字串、此處 parse + clamp 到值域（超界夾回，不擋存）。
    private fun parseFloat(s: String, lo: Float, hi: Float, d: Float) = s.toFloatOrNull()?.coerceIn(lo, hi) ?: d
    private fun parseInt(s: String, lo: Int, hi: Int, d: Int) = s.toIntOrNull()?.coerceIn(lo, hi) ?: d
}
