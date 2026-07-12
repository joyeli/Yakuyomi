package tachiyomi.domain.translation.service

import tachiyomi.core.common.preference.EncryptedStringPreference
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.StringCrypto

/**
 * Yakuyomi 翻譯偏好（BYOK + 語言對）。模型(BYOM)沿用 StoragePreferences 的儲存位置底下 models/。
 *
 * API key 落地加密：[apiKey] 是個 [EncryptedStringPreference]——對外仍是明文 [tachiyomi.core.common.preference.Preference]<String>
 * （UI/讀取端無感），但磁碟上存的是 [crypto]（app 層的 Android Keystore 實作）加密後的密文（pref key `translation_api_key_enc`）。
 * 並把舊的明文 key（`translation_api_key`）一次性遷移過去後清掉。[crypto] 為 null（如測試）時退化成不加密。
 */
class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
    crypto: StringCrypto? = null,
) {

    /** 給 [apiKeyFor] 為非預設 provider 建金鑰偏好用（與 [apiKey] 同一把 keystore cipher）。 */
    private val cipher: StringCrypto = crypto ?: IdentityCrypto

    /**
     * 全域翻譯總開關（預設開）。關閉＝**自動翻譯一律不做**：下載時翻譯（[translationEnabled]）與即時翻譯
     * （[liveTranslate]）都被 gate 掉、引擎不預暖；設定頁其餘翻譯選項變灰。手動翻譯不受此限。
     */
    val translationMasterEnabled = preferenceStore.getBoolean("translation_master_enabled", true)

    /** 下載完成後自動翻譯該章（連同 isReady 的模型/key 檢查）。 */
    val translationEnabled = preferenceStore.getBoolean("translation_enabled", false)

    /**
     * 即時翻譯（reader 邊讀邊翻）：開啟後，開「已下載但未翻」的章時，逐頁用快速 boxfill 去字即時翻（預設關）。
     * 與 [translationEnabled]（下載後整章離線翻）獨立：即時翻是讀到才翻、不落地（本里程碑），整章翻是預先翻好覆蓋原檔。
     * reader 設定面板可切；切換後現有章會重新透過 ChapterLoader 套用/取消這層包裝。
     */
    val liveTranslate = preferenceStore.getBoolean("translation_live", false)

    /**
     * 即時翻譯的「包含」分類（書庫分類 id 字串集合）：非空＝只對屬於這些分類的書即時翻；空＝不限包含。
     * 與 [liveTranslateCategoriesExclude] 一起構成 tri-state 過濾（鏡射 DownloadPreferences.downloadNewChapterCategories）。
     */
    val liveTranslateCategories = preferenceStore.getStringSet("translation_live_categories", emptySet())

    /** 即時翻譯的「排除」分類：屬於這些分類的書一律不即時翻（優先於包含）。 */
    val liveTranslateCategoriesExclude = preferenceStore.getStringSet("translation_live_categories_exclude", emptySet())

    /**
     * 不自動翻譯的來源（source id 字串集合）：屬於這些來源的書，下載時翻譯與即時翻譯一律跳過（全域硬排除）。
     * 手動翻譯（書籍頁翻譯鈕 / 多選 / 重繪）**不受此限**——被排除來源的書若仍想翻就手動觸發。
     * 與分類過濾獨立：分類只作用於即時翻；來源排除作用於所有「自動」翻譯（下載時 + 即時）。
     */
    val translationSourcesExclude = preferenceStore.getStringSet("translation_sources_exclude", emptySet())

    /**
     * 翻譯 LLM 的 API key（BYOK，OpenAI 相容，預設 DeepSeek）。
     *
     * 落地加密：對外是明文 Preference<String>，但磁碟上存密文（`translation_api_key_enc`，由 [crypto] 加密）。
     * 舊版明文 key（`translation_api_key`）在首次讀寫時一次性遷移＋清除。[crypto] 為 null 時退化成不加密（identity）。
     */
    val apiKey = EncryptedStringPreference(
        backing = preferenceStore.getString("translation_api_key_enc", ""),
        crypto = crypto ?: IdentityCrypto,
        defaultValue = "",
        legacyPlaintext = preferenceStore.getString("translation_api_key", ""),
    )

    /**
     * 目前選用的 LLM 供應商 id（對應引擎 `LlmProviders` 預設表：deepseek/openai/gemini/groq/qwen/openrouter/sakura/custom）。
     * 切換 provider 不改聊天協定（全 OpenAI 相容），只換 apiBase/model/key（每家一格，見 [apiKeyFor]）。
     */
    val provider = preferenceStore.getString("translation_provider", "deepseek")

    /** 選用的 model id（空＝用該 provider 的預設模型）。可手填或從「抓取模型」清單選（引擎 `LlmModels.list`）。 */
    val model = preferenceStore.getString("translation_model", "")

    /** 自架 / 自訂 provider（sakura/custom）的 API base，例 `http://192.168.1.5:8080/v1`；其餘 provider 用內建端點時忽略。 */
    val apiBase = preferenceStore.getString("translation_api_base", "")

    /** 每個 provider 一格的金鑰快取（§2.1：切換 provider 保留各家 key）。 */
    private val apiKeyCache = HashMap<String, Preference<String>>()

    /**
     * 取某 provider 的金鑰偏好（每家一格，加密落地）。
     * `deepseek` 沿用既有 slot（`translation_api_key_enc`，含舊明文遷移、保留現有 key）；其餘為 `translation_api_key_enc__<id>`。
     */
    @Synchronized
    fun apiKeyFor(providerId: String): Preference<String> = apiKeyCache.getOrPut(providerId) {
        if (providerId == "deepseek") {
            apiKey
        } else {
            EncryptedStringPreference(
                backing = preferenceStore.getString("translation_api_key_enc__$providerId", ""),
                crypto = cipher,
                defaultValue = "",
            )
        }
    }

    /** 目前 provider 的金鑰（明文；引擎建構用）。 */
    fun activeApiKey(): String = apiKeyFor(provider.get()).get()

    /** 是否已看過翻譯隱私揭露（一次性同意對話框只跳一次）。 */
    val privacyAcknowledged = preferenceStore.getBoolean("translation_privacy_ack", false)

    /** 是否已看過翻譯快速上手導覽（首次開啟翻譯設定時自動跳一次；之後可從設定列重開）。 */
    val quickstartShown = preferenceStore.getBoolean("translation_quickstart_shown", false)

    /** 目標語言（LLM 直接照這個翻）；預設台灣繁中，對齊引擎 TranslatorConfig.toLangName。 */
    val targetLangName = preferenceStore.getString("translation_target_lang", DEFAULT_TARGET_LANG)

    /** 來源語言標註（進 prompt；留空＝讓 LLM 自己判。實際來源由 OCR 模型決定＝BYOM）。 */
    val sourceLangName = preferenceStore.getString("translation_source_lang", DEFAULT_SOURCE_LANG)

    /** 排版方向（auto/vertical/horizontal），對應引擎 RenderConfig.orientation。 */
    val orientation = preferenceStore.getString("translation_orientation", DEFAULT_ORIENTATION)

    /** 去字方法（boxfill=快速去字／其餘=AI 去字 aot），對應引擎 InpainterConfig。用於下載時翻譯/手動翻譯/重繪。 */
    val inpaintMethod = preferenceStore.getString("translation_inpaint_method", DEFAULT_INPAINT_METHOD)

    /**
     * 即時翻（邊讀邊翻）專用去字方法。與 [inpaintMethod] 分開＝即時翻求低延遲、預設 boxfill（快速去字）；
     * 想要即時就看到 AI 去字的可自訂成 aot（較慢、被翻譯網路等待部分蓋住）。
     */
    val liveInpaintMethod = preferenceStore.getString("translation_live_inpaint_method", DEFAULT_LIVE_INPAINT_METHOD)

    /** 保留重繪素材：翻完每頁另存遮罩 + 文字區 + 原圖，日後可換去字方法低成本重繪（免重跑 OCR/翻譯）；約多一倍儲存。 */
    val keepMaterials = preferenceStore.getBoolean("pref_translation_keep_materials", false)

    /** OCR 逐行並發度（auto=硬體核數 / 2/4/6/8），對應引擎 OcrConfig.concurrency（concurrent 鎖 true）。 */
    val ocrConcurrency = preferenceStore.getString("translation_ocr_concurrency", DEFAULT_OCR_CONCURRENCY)

    /** 文字顏色（auto=依背景亮度黑/白字 / mono=一律黑字白邊），對應 RenderConfig.colorMode。 */
    val colorMode = preferenceStore.getString("translation_color_mode", DEFAULT_COLOR_MODE)

    /** 譯文字描邊，對應 RenderConfig.fontBorder。 */
    val fontBorder = preferenceStore.getBoolean("translation_font_border", true)

    /** 設定頁是否顯示進階選項（純 UI 開關，不進引擎）。 */
    val showAdvanced = preferenceStore.getBoolean("translation_show_advanced", false)

    // —— 進階數值（存字串、消費端 PageTranslator parse + clamp 到值域）——
    val segThreshold = preferenceStore.getString("translation_seg_threshold", "0.12") // 0.0–1.0
    val minProb = preferenceStore.getString("translation_min_prob", "0.5") // 0.0–1.0
    val bboxPad = preferenceStore.getString("translation_bbox_pad", "16") // 0–64
    val artStrokeRatio = preferenceStore.getString("translation_art_stroke", "0.16") // 0.0–0.5
    val fontSizeMax = preferenceStore.getString("translation_font_size_max", "60") // 20–120
    val fontSizeMin = preferenceStore.getString("translation_font_size_min", "9") // 6–40
    val colTrim = preferenceStore.getString("translation_col_trim", "3") // 0–10
    val rowTrim = preferenceStore.getString("translation_row_trim", "3") // 0–10
    val fontScale = preferenceStore.getString("translation_font_scale", "0.85") // 0.3–1.5

    companion object {
        // ⚠️ 與引擎 TranslatorConfig.toLangName / fromLangName 預設「逐字一致」（引擎＝真理來源）。
        //   鏡像而非共用：本類在 :domain，:domain 不依賴引擎（只 :app 依賴）→ 不能 import 引擎常數。
        //   改一邊請同步改 engine/Config.kt，否則 few-shot 保留/清除判斷會 drift。
        /** 預設目標＝台灣繁中。非此值時 PageTranslator 不放引擎內建的日→繁中 few-shot（避免範例語言衝突）。 */
        const val DEFAULT_TARGET_LANG = "Traditional Chinese (Taiwan, 台灣慣用的繁體中文用語)"
        const val DEFAULT_SOURCE_LANG = "Japanese"
        const val DEFAULT_ORIENTATION = "auto"
        const val DEFAULT_INPAINT_METHOD = "auto_whole" // 下載/手動翻＝AI 去字（引擎把非 boxfill 一律當 aot·整頁 768）
        const val DEFAULT_LIVE_INPAINT_METHOD = "boxfill" // 即時翻＝快速去字，求低延遲；可自訂成 aot
        const val DEFAULT_OCR_CONCURRENCY = "auto" // auto=硬體核數（多數手機8核）；真機 8.9s→4.8s 快46%
        const val DEFAULT_COLOR_MODE = "auto" // auto=依背景亮度判黑/白字
    }
}

/** 不加密的退化實作（未注入 [StringCrypto] 時用，如單元測試）：明文進、明文出。 */
private object IdentityCrypto : StringCrypto {
    override fun encrypt(plain: String): String = plain
    override fun decrypt(encrypted: String): String? = encrypted.ifEmpty { null }
}
