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
     * 即時翻譯（reader 邊讀邊翻）：開啟後，開「已下載但未翻」的章時，逐頁依 [liveInpaintMethod] 去字即時翻（預設關）。
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
     * 即時翻（邊讀邊翻）專用去字方法。**預設與 [inpaintMethod] 同為 AI 去字**——AOT-GAN 取代 LaMa 後
     * 單頁去字快 5–9×，且去字跑在 CPU、與翻譯的網路等待重疊（§11 重貼原文讓兩者解耦），實測延遲可接受，
     * 不值得再用畫質換。仍保留獨立 pref＝弱機或想要極速翻頁的人可改回 boxfill（快速去字）。
     * 註：[PageTranslator] 的跨頁流水線深度會依此值自動切換（boxfill 網路綁定深 4／AI 去字 CPU 綁定深 2）。
     */
    val liveInpaintMethod = preferenceStore.getString("translation_live_inpaint_method", DEFAULT_LIVE_INPAINT_METHOD)

    /** 保留重繪素材：翻完每頁另存遮罩 + 文字區 + 原圖，日後可換去字方法低成本重繪（免重跑 OCR/翻譯）；約多一倍儲存。 */
    val keepMaterials = preferenceStore.getBoolean("pref_translation_keep_materials", false)

    /**
     * 夜讀**表層總開關**（預設關；放「其他」頁翻譯總開關旁、不在設定頁裡）。
     * 關＝**隱藏所有夜讀入口**（章節列夜讀指示器、多選底部選單月亮鈕、reader 長壓切換鈕、reader 設定勾選、
     * 翻譯設定頁的夜讀項）**且夜讀 worker 不跑**：TranslationManager 的夜讀 drain 不再挑夜讀項、正在跑的夜讀項在頁邊界
     * 停下回 QUEUE 留著、翻完也不自動排夜讀。開＝入口全部出現、夜讀 drain 續跑。
     * 與翻譯總開關 [translationMasterEnabled] 獨立（翻譯與夜讀是兩個獨立任務、各自一個消費者）。
     * **不進 configSignature**：只決定夜讀入口與 worker 要不要動，不影響翻譯引擎的建構參數 → 切換不需重建引擎。
     */
    val nightReadEnabled = preferenceStore.getBoolean("translation_nightread_enabled", false)

    /**
     * 翻完一章後自動產生夜讀版（預設**關**；只在 [nightReadEnabled] 開時顯示與生效）：章翻譯成功 → TranslationManager
     * 另排一個「夜讀」佇列工作，把每頁重繪成
     * 「白底／白泡變暗、字反白、人物原樣」的另一張圖，存 `<章節夾>/.yakuyomi/<頁>.night.std.webp`（標準）＋與標準不同時才有的
     * `.night.more.webp`（更多）（原圖不動、翻譯結果不動）。
     * 為何預設關：離線預算每頁 6–25 s（人物分割 + 偵測），且要人物分割模型（yolo/cseg，選配）——不是每個人都要。
     * **與翻譯就緒無關**：isReady 不看它、夜讀模型缺也不影響翻譯；缺模型時只有夜讀那個工作標 ERROR。
     * **不進 configSignature**：它只決定「翻完要不要多排一個工作」，不影響翻譯引擎的建構參數 → 切換不需重建引擎。
     * 舊章補做走章節多選「產生夜讀版」；reader 端要不要顯示夜讀版由 ReaderPreferences.nightReadMode 決定（沒夜讀版就顯示正常版）。
     */
    val nightReadGenerate = preferenceStore.getBoolean("pref_translation_nightread", false)

    // ── 夜讀（設定 › 夜讀 頁）：以下只管**自動**路徑（下載完／翻完後），手動月亮鈕與 reader 長壓不查。 ──

    /** 自動產生夜讀版的分類過濾（tri-state，語義同即時翻譯分類：包含非空→書至少屬其一；命中排除→不做）。 */
    val nightReadCategories = preferenceStore.getStringSet("nightread_categories", emptySet())
    val nightReadCategoriesExclude = preferenceStore.getStringSet("nightread_categories_exclude", emptySet())

    /** 不自動做夜讀的來源（source id 字串集合，同翻譯的 per-source 排除）。 */
    val nightReadSourcesExclude = preferenceStore.getStringSet("nightread_sources_exclude", emptySet())

    /** 略過彩色頁：研究端對彩頁只是去色套同一條曲線、效果明顯較差；開了就量頁面彩度、彩頁不產生（reader 顯示原圖）。 */
    val nightReadSkipColor = preferenceStore.getBoolean("nightread_skip_color", false)

    /**
     * 背景填黑的檔位：產品**兩檔**（2026-10-01 定案）`l2`＝標準（預設）、`l3`＝更多，對應 nightread 的 L2／L3
     * （app 的 `NightLevel`）：
     * `l2` 標準＝分鏡溝、頁邊留白、封閉對話框、「無畫面背景」，加上大而形狀簡單的白（貼紙規則通過、輪廓複雜度 rough ≤ 10、
     * 面積 ≥ 頁面 0.5%）；`l3` 更多＝rough ≤ 20、無面積下限。兩檔都不做偽泡與亮島填黑——桌面消融證明撕裂（黑色侵染）的
     * 真凶是「碰到人物、內有線稿的大白」被核心填色沿人物邊界停下，rough 把它們和正當的大白分得開（1.8–8.3 vs 17.8+）。
     * 三檔時代的 `l1`（最低）從產品拿掉：47 頁裡 L1 與 L2 只有 14 頁不同、守護框違規相同（12/664）。
     * 舊值照對應、不必遷移：`l1`／`protect`→標準、`aggressive`→更多、其餘→標準。詳見 nightread `docs/DECISIONS.md`。
     *
     * **意義＝顯示哪一檔**：產生時兩檔一律備齊（`.night.std.webp`／`.night.more.webp`，更多與標準相同就不存；2026-10-01
     * 產生的三檔 `.night.l1/l2/l3.webp` 照讀），這個值只決定 reader 讀哪一檔；閱讀器懸浮鈕、閱讀設定面板的 chips、
     * 設定 › 夜讀 寫的都是它，三處自然同步。轉成檔位用 app 的 `NightLevel.fromPref`（domain 不依賴 nightread 函式庫）。
     * key 與預設值不變，不必遷移。
     */
    val nightReadFillLevel = preferenceStore.getString("nightread_fill_level", "l2")

    /**
     * 夜讀亮度（0–255，對應 nightread `NightReadParams`）。研究端定案＝240/240/220（字／描亮邊線／人物描邊）；
     * 真機回報「純白到發亮的線條、字特別刺眼」後 fork 預設改**柔和** 205/170/190。只影響之後產生的夜讀版。
     */
    val nightReadInk = preferenceStore.getInt("nightread_ink", 205)
    val nightReadEdgeInk = preferenceStore.getInt("nightread_edge_ink", 170)
    val nightReadStrokeObjV = preferenceStore.getInt("nightread_stroke_obj", 190)

    /** 進階：底色（研究端 16）、場景亮度上限（紙白壓到多亮，140）、墨線發光上限（112，須小於場景上限）。 */
    val nightReadBg = preferenceStore.getInt("nightread_bg", 16)
    val nightReadDimCeil = preferenceStore.getInt("nightread_dim_ceil", 140)
    val nightReadGlowCap = preferenceStore.getInt("nightread_glow_cap", 112)

    /**
     * 夜讀同時處理頁數（`auto`／`1`..`3`，存字串；消費端 NightConcurrency.pagesCap parse + 夾到這台 heap 放得下的頁數）。
     * 只在只有夜讀在跑時生效；翻譯／即時翻在跑時夜讀自動降為一次一頁、單執行緒、低優先權（取代舊的「夜讀用低優先權」開關）。
     * 下一頁放行時生效。
     */
    val nightReadPageConcurrency = preferenceStore.getString("nightread_page_concurrency", "auto")

    /** OCR 逐行並發度（auto=硬體核數 / 2/4/6/8），對應引擎 OcrConfig.concurrency（concurrent 鎖 true）。 */
    val ocrConcurrency = preferenceStore.getString("translation_ocr_concurrency", DEFAULT_OCR_CONCURRENCY)

    /** 文字顏色（auto=依背景亮度黑/白字 / mono=一律黑字白邊），對應 RenderConfig.colorMode。 */
    val colorMode = preferenceStore.getString("translation_color_mode", DEFAULT_COLOR_MODE)

    /** 譯文字描邊，對應 RenderConfig.fontBorder。 */
    val fontBorder = preferenceStore.getBoolean("translation_font_border", true)

    /**
     * 跳過狀聲詞 SFX（預設關）：開啟後 OCR 跳過偵測到的彩色/裝飾性非氣泡狀聲詞、不翻譯它們（保留原味）。
     * 對應引擎 OcrConfig.ignoreBubble——開＝給內建門檻值（buildEngineConfig 填 24）、關＝0。這是「跳過翻譯」非「積極去除」。
     */
    val ignoreSfx = preferenceStore.getBoolean("translation_ignore_sfx", false)

    /** 設定頁是否顯示進階選項（純 UI 開關，不進引擎）。 */
    val showAdvanced = preferenceStore.getBoolean("translation_show_advanced", false)

    /**
     * 進階診斷紀錄（預設關）：開啟後 :app 的 TraceLog 把翻譯引擎各階段寫進 app 私有檔，
     * 用來抓 logcat / 內建 crash log 都抓不到的原生（SIGSEGV/abort）或 OOM（lowmemorykiller SIGKILL）crash。
     * 關閉時完全不執行、零效能負擔（App 不 init、EngineTrace.sink 維持 null）。只在需要診斷時開。
     */
    val diagnosticLog = preferenceStore.getBoolean("translation_diagnostic_log", false)

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

    // —— 進階辨識（OCR 裁切 / 偵測器）：各自對應引擎欄位、buildEngineConfig 讀取並 clamp ——

    /** OCR 裁切前把偵測框外擴 N px（救框太瘦切字→CTC 空讀→漏氣泡）；對應引擎 OcrConfig.stripPad。0–12，實測甜蜜點 4。 */
    val stripPad = preferenceStore.getInt("translation_strip_pad", 4)

    /** OCR 裁切內插法：bicubic（救小假名句尾否定）／bilinear；對應引擎 OcrConfig.useBicubic（==「bicubic」）。 */
    val useBicubic = preferenceStore.getString("translation_use_bicubic", "bicubic")

    /** 偵測輸入銳利化（marginal + OOD，預設關）；對應引擎 DetectorConfig.detectUnsharp。 */
    val detectUnsharp = preferenceStore.getBoolean("translation_detect_unsharp", false)

    /** 偵測辨識尺寸 px（甜蜜點 1024）；對應引擎 DetectorConfig.dbnetInputSize。768–1536（步進 128）。 */
    val dbnetSize = preferenceStore.getInt("translation_dbnet_size", 1024)

    /** OCR 裁切前銳化 strip（抵銷 warp 縮放模糊、救小假名漏讀）；對應引擎 OcrConfig.ocrUnsharp。★預設開（與 detectUnsharp 相反）。 */
    val ocrUnsharp = preferenceStore.getBoolean("translation_ocr_unsharp", true)

    // —— 進階去字（解析度 / 遮罩）——

    /** 整頁 AOT 去字解析度（512/768/1024，甜蜜點 768）；對應引擎 InpainterConfig.tileSize。存 Int，只三檔有意義。 */
    val tileSize = preferenceStore.getInt("translation_tile_size", 768)

    /** 去字遮罩膨脹（值/2＝半徑 px，實測 24）；對應引擎 InpainterConfig.maskDilate（Float）。存 Int，8–40。調小會殘白塊。 */
    val maskDilate = preferenceStore.getInt("translation_mask_dilate", 24)

    /** 縱中橫：直排時把連續短 ASCII 串水平並排一格（預設開）；對應引擎 RenderConfig.tateChuYoko。 */
    val tateChuYoko = preferenceStore.getBoolean("translation_tate_chu_yoko", true)

    /** LLM 取樣溫度（0.0–1.0，預設 0.3）；對應引擎 TranslatorConfig.temperature。存字串、buildEngineConfig parse+clamp（對齊其餘數值參數）。 */
    val temperature = preferenceStore.getString("translation_temperature", "0.3")

    /**
     * 思考模式（reasoning，預設 **關**）；對應引擎 TranslatorConfig.thinking。
     *
     * 各家新世代模型多半預設就會思考，但「逐行照翻」這種結構化任務從中得到的好處很小，卻明顯更慢、token 數倍（更貴）
     * ⇒ 預設關＝復刻舊 deepseek-chat 的非思考行為。欄位形狀 per-provider（thinking / reasoning_effort /
     * enable_thinking / reasoning），由引擎 `LlmProviders.requestParams` 映射；並非每家都能完全關閉。
     */
    val thinking = preferenceStore.getBoolean("translation_thinking", false)

    companion object {
        // ⚠️ 與引擎 TranslatorConfig.toLangName / fromLangName 預設「逐字一致」（引擎＝真理來源）。
        //   鏡像而非共用：本類在 :domain，:domain 不依賴引擎（只 :app 依賴）→ 不能 import 引擎常數。
        //   改一邊請同步改 engine/Config.kt，否則 few-shot 保留/清除判斷會 drift。
        /** 預設目標＝台灣繁中。非此值時 PageTranslator 不放引擎內建的日→繁中 few-shot（避免範例語言衝突）。 */
        const val DEFAULT_TARGET_LANG = "Traditional Chinese (Taiwan, 台灣慣用的繁體中文用語)"
        const val DEFAULT_SOURCE_LANG = "Japanese"
        const val DEFAULT_ORIENTATION = "auto"
        const val DEFAULT_INPAINT_METHOD = "auto_whole" // 下載/手動翻＝AI 去字（引擎把非 boxfill 一律當 aot·整頁 768）

        // 即時翻也用 AI 去字：AOT-GAN 換掉 LaMa 後單頁去字快 5–9×，低延遲已不必用畫質換
        const val DEFAULT_LIVE_INPAINT_METHOD = "auto_whole"
        const val DEFAULT_OCR_CONCURRENCY = "auto" // auto=硬體核數（多數手機8核）；真機 8.9s→4.8s 快46%
        const val DEFAULT_COLOR_MODE = "auto" // auto=依背景亮度判黑/白字
    }
}

/** 不加密的退化實作（未注入 [StringCrypto] 時用，如單元測試）：明文進、明文出。 */
private object IdentityCrypto : StringCrypto {
    override fun encrypt(plain: String): String = plain
    override fun decrypt(encrypted: String): String? = encrypted.ifEmpty { null }
}
