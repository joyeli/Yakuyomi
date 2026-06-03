package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
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
            Preference.PreferenceItem.EditTextPreference(
                preference = prefs.targetLangName,
                title = "Target language",
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = prefs.sourceLangName,
                title = "Source language",
                subtitle = "Prompt label; blank = let the LLM infer. Actual source = whatever the OCR model reads (BYOM).",
            ),
        )
    }
}
