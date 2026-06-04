package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentMapOf
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { Injekt.get<TranslationPreferences>() }
        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = prefs.translationEnabled,
                title = "Translate chapters on download",
                subtitle = "Detection / OCR / text-removal run on-device; translation uses a cloud LLM",
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = prefs.apiKey,
                title = "API key (BYOK)",
                // static subtitle so the key value is never rendered in the list
                subtitle = "Translation LLM key — OpenAI-compatible, DeepSeek by default",
            ),
            Preference.PreferenceItem.ListPreference(
                preference = prefs.targetLangName,
                entries = persistentMapOf(
                    TranslationPreferences.DEFAULT_TARGET_LANG to stringResource(MR.strings.pref_translation_lang_trad_chinese),
                    "Japanese" to stringResource(MR.strings.pref_translation_lang_japanese),
                    "Simplified Chinese" to stringResource(MR.strings.pref_translation_lang_simp_chinese),
                    "English" to stringResource(MR.strings.pref_translation_lang_english),
                    "Korean" to stringResource(MR.strings.pref_translation_lang_korean),
                    "Spanish" to stringResource(MR.strings.pref_translation_lang_spanish),
                    "French" to stringResource(MR.strings.pref_translation_lang_french),
                    "German" to stringResource(MR.strings.pref_translation_lang_german),
                    "Portuguese" to stringResource(MR.strings.pref_translation_lang_portuguese),
                    "Russian" to stringResource(MR.strings.pref_translation_lang_russian),
                ),
                title = stringResource(MR.strings.pref_translation_target_lang),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = prefs.sourceLangName,
                entries = persistentMapOf(
                    "" to stringResource(MR.strings.pref_translation_lang_auto_detect),
                    TranslationPreferences.DEFAULT_SOURCE_LANG to stringResource(MR.strings.pref_translation_lang_japanese),
                    TranslationPreferences.DEFAULT_TARGET_LANG to stringResource(MR.strings.pref_translation_lang_trad_chinese),
                    "Simplified Chinese" to stringResource(MR.strings.pref_translation_lang_simp_chinese),
                    "English" to stringResource(MR.strings.pref_translation_lang_english),
                    "Korean" to stringResource(MR.strings.pref_translation_lang_korean),
                    "Spanish" to stringResource(MR.strings.pref_translation_lang_spanish),
                    "French" to stringResource(MR.strings.pref_translation_lang_french),
                    "German" to stringResource(MR.strings.pref_translation_lang_german),
                    "Portuguese" to stringResource(MR.strings.pref_translation_lang_portuguese),
                    "Russian" to stringResource(MR.strings.pref_translation_lang_russian),
                ),
                title = stringResource(MR.strings.pref_translation_source_lang),
                subtitle = stringResource(MR.strings.pref_translation_source_lang_subtitle),
                subtitleProvider = { _, _ -> stringResource(MR.strings.pref_translation_source_lang_subtitle) },
            ),
            Preference.PreferenceItem.ListPreference(
                preference = prefs.orientation,
                entries = persistentMapOf(
                    "auto" to stringResource(MR.strings.pref_translation_orientation_auto),
                    "vertical" to stringResource(MR.strings.pref_translation_orientation_vertical),
                    "horizontal" to stringResource(MR.strings.pref_translation_orientation_horizontal),
                ),
                title = stringResource(MR.strings.pref_translation_orientation),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = prefs.inpaintMethod,
                entries = persistentMapOf(
                    "boxfill" to stringResource(MR.strings.pref_translation_inpaint_boxfill),
                    "auto_whole" to stringResource(MR.strings.pref_translation_inpaint_auto_whole),
                    "auto_tile" to stringResource(MR.strings.pref_translation_inpaint_auto_tile),
                ),
                title = stringResource(MR.strings.pref_translation_inpaint_method),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = prefs.ocrConcurrency,
                entries = persistentMapOf(
                    "auto" to stringResource(MR.strings.pref_translation_ocr_concurrency_auto),
                    "1" to stringResource(MR.strings.pref_translation_ocr_concurrency_serial),
                    "2" to "2",
                    "4" to "4",
                    "6" to "6",
                    "8" to stringResource(MR.strings.pref_translation_ocr_concurrency_max),
                ),
                title = stringResource(MR.strings.pref_translation_ocr_concurrency),
            ),
        )
    }
}
