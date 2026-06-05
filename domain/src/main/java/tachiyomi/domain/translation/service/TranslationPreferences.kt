package tachiyomi.domain.translation.service

import tachiyomi.core.common.preference.PreferenceStore

/**
 * Yakuyomi 翻譯偏好（BYOK + 語言對）。模型(BYOM)沿用 StoragePreferences 的儲存位置底下 models/。
 * 注意：API key 目前存一般 SharedPreferences（無加密）；正式版應改 Keystore。
 */
class TranslationPreferences(preferenceStore: PreferenceStore) {

    /** 下載完成後自動翻譯該章（連同 isReady 的模型/key 檢查）。 */
    val translationEnabled = preferenceStore.getBoolean("translation_enabled", false)

    /** 翻譯 LLM 的 API key（BYOK，OpenAI 相容，預設 DeepSeek）。 */
    val apiKey = preferenceStore.getString("translation_api_key", "")

    /** 目標語言（LLM 直接照這個翻）；預設台灣繁中，對齊引擎 TranslatorConfig.toLangName。 */
    val targetLangName = preferenceStore.getString("translation_target_lang", DEFAULT_TARGET_LANG)

    /** 來源語言標註（進 prompt；留空＝讓 LLM 自己判。實際來源由 OCR 模型決定＝BYOM）。 */
    val sourceLangName = preferenceStore.getString("translation_source_lang", DEFAULT_SOURCE_LANG)

    /** 排版方向（auto/vertical/horizontal），對應引擎 RenderConfig.orientation。 */
    val orientation = preferenceStore.getString("translation_orientation", DEFAULT_ORIENTATION)

    /** 去字方法（boxfill/auto_whole/lama_whole/auto_tile/lama_tile），對應引擎 InpainterConfig。 */
    val inpaintMethod = preferenceStore.getString("translation_inpaint_method", DEFAULT_INPAINT_METHOD)

    /** 保留重繪素材：翻完每頁另存遮罩 + 文字區 + 原圖，日後可換去字方法低成本重繪（免重跑 OCR/翻譯）；約多一倍儲存。 */
    val keepMaterials = preferenceStore.getBoolean("pref_translation_keep_materials", false)

    /** OCR 逐行並發度（auto=硬體核數 / 2/4/6/8），對應引擎 OcrConfig.concurrency（concurrent 鎖 true）。 */
    val ocrConcurrency = preferenceStore.getString("translation_ocr_concurrency", DEFAULT_OCR_CONCURRENCY)

    /** 推論執行緒（偵測+去字 lama；auto=硬體大核數估算 / 2/4/6/8），對應 Detector/InpainterConfig.intraThreads。 */
    val intraThreads = preferenceStore.getString("translation_intra_threads", DEFAULT_INTRA_THREADS)

    /** 文字顏色（auto=依背景亮度黑/白字 / mono=一律黑字白邊），對應 RenderConfig.colorMode。 */
    val colorMode = preferenceStore.getString("translation_color_mode", DEFAULT_COLOR_MODE)

    /** 譯文字描邊，對應 RenderConfig.fontBorder。 */
    val fontBorder = preferenceStore.getBoolean("translation_font_border", true)

    /** 設定頁是否顯示進階選項（純 UI 開關，不進引擎）。 */
    val showAdvanced = preferenceStore.getBoolean("translation_show_advanced", false)

    // —— 進階數值（存字串、消費端 PageTranslator parse + clamp 到值域）——
    val segThreshold = preferenceStore.getString("translation_seg_threshold", "0.12")       // 0.0–1.0
    val minProb = preferenceStore.getString("translation_min_prob", "0.5")                  // 0.0–1.0
    val autoStdThreshold = preferenceStore.getString("translation_auto_std", "6")           // 0–30
    val autoWhiteThreshold = preferenceStore.getString("translation_auto_white", "190")     // 0–255
    val bboxPad = preferenceStore.getString("translation_bbox_pad", "16")                   // 0–64
    val artStrokeRatio = preferenceStore.getString("translation_art_stroke", "0.16")        // 0.0–0.5
    val fontSizeMax = preferenceStore.getString("translation_font_size_max", "60")          // 20–120
    val fontSizeMin = preferenceStore.getString("translation_font_size_min", "9")           // 6–40
    val colTrim = preferenceStore.getString("translation_col_trim", "3")                    // 0–10
    val rowTrim = preferenceStore.getString("translation_row_trim", "3")                    // 0–10
    val fontScale = preferenceStore.getString("translation_font_scale", "0.85")             // 0.3–1.5

    companion object {
        /** 預設目標＝台灣繁中。非此值時 PageTranslator 不放引擎內建的日→繁中 few-shot（避免範例語言衝突）。 */
        const val DEFAULT_TARGET_LANG = "Traditional Chinese (Taiwan, 台灣慣用的繁體中文用語)"
        const val DEFAULT_SOURCE_LANG = "Japanese"
        const val DEFAULT_ORIENTATION = "auto"
        const val DEFAULT_INPAINT_METHOD = "auto_whole" // 平衡：泡泡平塗(乾淨無黃暈)+整頁lama(~7s)；逐格(質佳但~64s)留選項
        const val DEFAULT_OCR_CONCURRENCY = "auto" // auto=硬體核數（多數手機8核）；真機 8.9s→4.8s 快46%
        const val DEFAULT_INTRA_THREADS = "auto"   // auto=硬體大核數估算；真機 6 緒最快（big.LITTLE）
        const val DEFAULT_COLOR_MODE = "auto"      // auto=依背景亮度判黑/白字
    }
}
