package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** A selectable language: [name] is the English name sent to the LLM; [native] is for display/search only. */
data class LanguageOption(val name: String, val native: String = "")

/** Common languages. [LanguageOption.name] is stored verbatim as the pref and injected into the LLM prompt. */
val COMMON_LANGUAGES: List<LanguageOption> = listOf(
    LanguageOption("Japanese", "日本語"),
    LanguageOption("Simplified Chinese", "简体中文"),
    LanguageOption("English", "English"),
    LanguageOption("Korean", "한국어"),
    LanguageOption("Arabic", "العربية"),
    LanguageOption("Modern Standard Arabic (فصحى)", "العربية الفصحى"),
    LanguageOption("Egyptian Arabic", "مصري"),
    LanguageOption("Gulf Arabic", "خليجي"),
    LanguageOption("Levantine Arabic", "شامي"),
    LanguageOption("Moroccan Arabic", "دارجة"),
    LanguageOption("Iraqi Arabic", "عراقي"),
    LanguageOption("Spanish", "Español"),
    LanguageOption("French", "Français"),
    LanguageOption("German", "Deutsch"),
    LanguageOption("Portuguese", "Português"),
    LanguageOption("Russian", "Русский"),
    LanguageOption("Afrikaans"),
    LanguageOption("Albanian", "Shqip"),
    LanguageOption("Amharic", "አማርኛ"),
    LanguageOption("Armenian", "Հայերեն"),
    LanguageOption("Azerbaijani", "Azərbaycanca"),
    LanguageOption("Basque", "Euskara"),
    LanguageOption("Belarusian", "Беларуская"),
    LanguageOption("Bengali", "বাংলা"),
    LanguageOption("Bosnian", "Bosanski"),
    LanguageOption("Bulgarian", "Български"),
    LanguageOption("Burmese", "မြန်မာ"),
    LanguageOption("Cantonese", "廣東話"),
    LanguageOption("Catalan", "Català"),
    LanguageOption("Croatian", "Hrvatski"),
    LanguageOption("Czech", "Čeština"),
    LanguageOption("Danish", "Dansk"),
    LanguageOption("Dutch", "Nederlands"),
    LanguageOption("Esperanto"),
    LanguageOption("Estonian", "Eesti"),
    LanguageOption("Filipino", "Tagalog"),
    LanguageOption("Finnish", "Suomi"),
    LanguageOption("Galician", "Galego"),
    LanguageOption("Georgian", "ქართული"),
    LanguageOption("Greek", "Ελληνικά"),
    LanguageOption("Gujarati", "ગુજરાતી"),
    LanguageOption("Haitian Creole", "Kreyòl ayisyen"),
    LanguageOption("Hausa"),
    LanguageOption("Hebrew", "עברית"),
    LanguageOption("Hindi", "हिन्दी"),
    LanguageOption("Hungarian", "Magyar"),
    LanguageOption("Icelandic", "Íslenska"),
    LanguageOption("Igbo"),
    LanguageOption("Indonesian", "Bahasa Indonesia"),
    LanguageOption("Irish", "Gaeilge"),
    LanguageOption("Italian", "Italiano"),
    LanguageOption("Javanese", "Basa Jawa"),
    LanguageOption("Kannada", "ಕನ್ನಡ"),
    LanguageOption("Kazakh", "Қазақша"),
    LanguageOption("Khmer", "ភាសាខ្មែរ"),
    LanguageOption("Kurdish", "Kurdî"),
    LanguageOption("Kyrgyz", "Кыргызча"),
    LanguageOption("Lao", "ລາວ"),
    LanguageOption("Latin", "Latina"),
    LanguageOption("Latvian", "Latviešu"),
    LanguageOption("Lithuanian", "Lietuvių"),
    LanguageOption("Macedonian", "Македонски"),
    LanguageOption("Malay", "Bahasa Melayu"),
    LanguageOption("Malayalam", "മലയാളം"),
    LanguageOption("Maltese", "Malti"),
    LanguageOption("Marathi", "मराठी"),
    LanguageOption("Mongolian", "Монгол"),
    LanguageOption("Nepali", "नेपाली"),
    LanguageOption("Norwegian", "Norsk"),
    LanguageOption("Pashto", "پښتو"),
    LanguageOption("Persian", "فارسی"),
    LanguageOption("Polish", "Polski"),
    LanguageOption("Punjabi", "ਪੰਜਾਬੀ"),
    LanguageOption("Romanian", "Română"),
    LanguageOption("Serbian", "Српски"),
    LanguageOption("Sinhala", "සිංහල"),
    LanguageOption("Slovak", "Slovenčina"),
    LanguageOption("Slovenian", "Slovenščina"),
    LanguageOption("Somali", "Soomaali"),
    LanguageOption("Swahili", "Kiswahili"),
    LanguageOption("Swedish", "Svenska"),
    LanguageOption("Tamil", "தமிழ்"),
    LanguageOption("Telugu", "తెలుగు"),
    LanguageOption("Thai", "ไทย"),
    LanguageOption("Turkish", "Türkçe"),
    LanguageOption("Ukrainian", "Українська"),
    LanguageOption("Urdu", "اردو"),
    LanguageOption("Uzbek", "Oʻzbek"),
    LanguageOption("Vietnamese", "Tiếng Việt"),
    LanguageOption("Welsh", "Cymraeg"),
    LanguageOption("Yoruba", "Yorùbá"),
    LanguageOption("Zulu", "isiZulu"),
)

/**
 * Searchable language picker with a free-text "custom" entry.
 * [pinned] entries (value to label) are shown first (e.g. the default language, "Auto-detect").
 * Whatever is chosen / typed is returned verbatim via [onSelect] — the engine accepts arbitrary language names.
 */
@Composable
fun LanguagePickerDialog(
    title: String,
    pinned: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val pinnedShown = pinned.filter { q.isEmpty() || it.second.contains(q, true) || it.first.contains(q, true) }
    val pinnedValues = pinned.map { it.first }.toSet()
    val filtered = COMMON_LANGUAGES.filter {
        it.name !in pinnedValues &&
            (q.isEmpty() || it.name.contains(q, true) || it.native.contains(q, true))
    }
    val hasExact = q.isNotEmpty() &&
        (COMMON_LANGUAGES.any { it.name.equals(q, true) } || pinned.any { it.first.equals(q, true) })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(text = stringResource(MR.strings.pref_translation_lang_search_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    if (q.isNotEmpty() && !hasExact) {
                        item {
                            Text(
                                text = stringResource(MR.strings.pref_translation_lang_use_custom, q),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(q) }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                    items(pinnedShown) { (value, label) ->
                        Text(
                            text = label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(value) }
                                .padding(vertical = 12.dp),
                        )
                    }
                    items(filtered) { lang ->
                        Text(
                            text = if (lang.native.isEmpty() || lang.native == lang.name) {
                                lang.name
                            } else {
                                "${lang.name} — ${lang.native}"
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(lang.name) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(MR.strings.action_cancel)) }
        },
    )
}
