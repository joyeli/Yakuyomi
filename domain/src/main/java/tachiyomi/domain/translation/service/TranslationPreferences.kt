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

    companion object {
        /** 預設目標＝台灣繁中。非此值時 PageTranslator 不放引擎內建的日→繁中 few-shot（避免範例語言衝突）。 */
        const val DEFAULT_TARGET_LANG = "Traditional Chinese (Taiwan, 台灣慣用的繁體中文用語)"
        const val DEFAULT_SOURCE_LANG = "Japanese"
    }
}
