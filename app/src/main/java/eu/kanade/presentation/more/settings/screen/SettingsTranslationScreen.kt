package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.util.fastMap
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TriStateListDialog
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// 進階數值用字面繁中標題/說明（與既有 apiKey 標題同風格，免動 strings.xml）；說明含值域 + 極值效果，尾 %s＝現值。
private typealias Item = Preference.PreferenceItem<out Any, out Any>

object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { Injekt.get<TranslationPreferences>() }
        // 「閱讀後刪除」綁下載偏好同一個 pref → 與下載設定頁連動（任一邊改都同步）。
        val downloadPrefs = remember { Injekt.get<DownloadPreferences>() }
        val showAdvanced by prefs.showAdvanced.collectAsState()
        val cores = remember { Runtime.getRuntime().availableProcessors() }

        // 即時翻譯分類過濾（包含/排除，鏡射下載「新章分類」）：取所有書庫分類 + tri-state 對話框狀態。
        val getCategories = remember { Injekt.get<GetCategories>() }
        val allCategories by getCategories.subscribe().collectAsState(initial = emptyList())
        val liveIncluded by prefs.liveTranslateCategories.collectAsState()
        val liveExcluded by prefs.liveTranslateCategoriesExclude.collectAsState()
        var showLiveCategoryDialog by rememberSaveable { mutableStateOf(false) }
        if (showLiveCategoryDialog) {
            TriStateListDialog(
                title = "即時翻譯分類",
                message = "選了「包含」分類＝只翻這些分類的書；選「排除」＝這些分類的書不翻；都不選＝全部",
                items = allCategories,
                initialChecked = liveIncluded.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
                initialInversed = liveExcluded.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
                itemLabel = { it.visualName },
                onDismissRequest = { showLiveCategoryDialog = false },
                onValueChanged = { newIncluded, newExcluded ->
                    prefs.liveTranslateCategories.set(newIncluded.fastMap { it.id.toString() }.toSet())
                    prefs.liveTranslateCategoriesExclude.set(newExcluded.fastMap { it.id.toString() }.toSet())
                    showLiveCategoryDialog = false
                },
            )
        }
        // 裝置感知緒數選項：自動 + {2,4,6,8 ≤ 核數}（超過核數的隱藏）
        val threadEntries = remember(cores) {
            buildMap {
                put("auto", "自動（$cores 核）")
                listOf(2, 4, 6, 8).filter { it <= cores }.forEach { put(it.toString(), "$it 緒") }
            }.toImmutableMap()
        }
        val targetLangs = persistentMapOf(
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
        )
        val sourceLangs = persistentMapOf(
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
        )

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = prefs.showAdvanced,
                title = "顯示進階選項",
                subtitle = "展開所有微調參數（一般使用者免動）",
            ),
            Preference.PreferenceGroup(
                title = "翻譯",
                preferenceItems = listOfNotNull<Item>(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.translationEnabled,
                        title = "下載時翻譯章節",
                        subtitle = "偵測 / OCR / 去字在裝置上跑；翻譯走雲端 LLM",
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.liveTranslate,
                        title = "即時翻譯（邊讀邊翻）",
                        subtitle = "開啟後，讀未翻章節時逐頁用快速 boxfill 即時翻並置換頁面；需 API key + 模型，與「下載時翻譯章節」獨立",
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = "即時翻譯分類",
                        subtitle = getCategoriesLabel(
                            allCategories = allCategories,
                            included = liveIncluded,
                            excluded = liveExcluded,
                        ),
                        onClick = { showLiveCategoryDialog = true },
                    ),
                    // 閱讀後刪除：綁下載偏好同一 pref（removeAfterReadSlots）→ 與下載設定頁完全連動。
                    // 即時翻譯讓「讀＝下載＋翻」會累積章節，這裡可就地設定讀完自動清。
                    Preference.PreferenceItem.ListPreference(
                        preference = downloadPrefs.removeAfterReadSlots,
                        entries = persistentMapOf(
                            -1 to stringResource(MR.strings.disabled),
                            0 to stringResource(MR.strings.last_read_chapter),
                            1 to stringResource(MR.strings.second_to_last),
                            2 to stringResource(MR.strings.third_to_last),
                            3 to stringResource(MR.strings.fourth_to_last),
                            4 to stringResource(MR.strings.fifth_to_last),
                        ),
                        title = stringResource(MR.strings.pref_remove_after_read),
                        subtitle = "%s（與下載設定連動）",
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.apiKey,
                        title = "API key (BYOK)",
                        subtitle = "翻譯 LLM 金鑰（OpenAI 相容，預設 DeepSeek）",
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.targetLangName,
                        entries = targetLangs,
                        title = "目標語言",
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.sourceLangName,
                        entries = sourceLangs,
                        title = "來源語言",
                        subtitle = stringResource(MR.strings.pref_translation_source_lang_subtitle),
                        subtitleProvider = { _, _ -> stringResource(MR.strings.pref_translation_source_lang_subtitle) },
                    ),
                ).toImmutableList(),
            ),
            Preference.PreferenceGroup(
                title = "去字",
                preferenceItems = listOfNotNull<Item>(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.inpaintMethod,
                        entries = persistentMapOf(
                            "boxfill" to stringResource(MR.strings.pref_translation_inpaint_boxfill),
                            "auto_whole" to stringResource(MR.strings.pref_translation_inpaint_auto_whole),
                            "auto_tile" to stringResource(MR.strings.pref_translation_inpaint_auto_tile),
                        ),
                        title = stringResource(MR.strings.pref_translation_inpaint_method),
                    ),
                    advFloat(
                        showAdvanced,
                        prefs.autoStdThreshold,
                        "auto 泡泡判定門檻 std",
                        "背景 std<此值=平塗、否則 lama；0–30，越低越多走 lama(精/慢)、越高壓畫面字塗成色塊",
                    ),
                    advFloat(
                        showAdvanced,
                        prefs.autoWhiteThreshold,
                        "auto 白底門檻",
                        "背景亮度≥此值才算對話框；0–255，越低暗背景誤平塗、越高灰白泡也走 lama",
                    ),
                    advInt(showAdvanced, prefs.bboxPad, "去字外擴 (px)", "去字範圍外擴、涵蓋貼邊假名；0–64，太小漏邊假名、太大挖到鄰近畫面"),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.keepMaterials,
                        title = "保留重繪素材",
                        subtitle = "翻完每頁另存原圖 + 遮罩 + 文字區，日後可換去字方法重繪（免重跑 OCR/翻譯）；約多一倍儲存",
                    ),
                ).toImmutableList(),
            ),
            Preference.PreferenceGroup(
                title = "排版",
                preferenceItems = listOfNotNull<Item>(
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
                        preference = prefs.colorMode,
                        entries = persistentMapOf(
                            "auto" to "自動（依背景亮度黑/白字）",
                            "mono" to "一律黑字白邊",
                        ),
                        title = "文字顏色",
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.fontBorder,
                        title = "文字描邊",
                        subtitle = "雜背景上的字加描邊更好讀",
                    ),
                    advInt(showAdvanced, prefs.fontSizeMax, "字級上限 (px)", "20–120，太小大泡撐不滿、太大短句爆大"),
                    advInt(showAdvanced, prefs.fontSizeMin, "字級下限 (px)", "6–40，再小寧可溢出也不縮"),
                    advFloat(showAdvanced, prefs.artStrokeRatio, "壓畫面描邊寬比例", "字級×此；0–0.5，太小難讀、太大白邊吃字"),
                    advInt(showAdvanced, prefs.colTrim, "直排每欄少放字數", "0–10，越大字越小欄越多"),
                    advInt(showAdvanced, prefs.rowTrim, "橫排每行少放字數", "0–10，colTrim 的橫排版"),
                    advFloat(showAdvanced, prefs.fontScale, "字級整體縮放", "0.3–1.5，<1 更 fit 格子留邊距"),
                ).toImmutableList(),
            ),
            Preference.PreferenceGroup(
                title = "效能（裝置相依）",
                preferenceItems = listOfNotNull<Item>(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.ocrConcurrency,
                        entries = threadEntries,
                        title = "OCR 並發度",
                        subtitle = "同時跑幾行 OCR（自動=核數）；越高越快到核數為止。%s",
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.intraThreads,
                        entries = threadEntries,
                        title = "推論執行緒（偵測/去字）",
                        subtitle = "一次推論用幾緒（自動=大核數）；含小核反拖累。%s",
                    ),
                ).toImmutableList(),
            ),
            Preference.PreferenceGroup(
                title = "辨識（偵測 / OCR）",
                enabled = showAdvanced,
                preferenceItems = listOfNotNull<Item>(
                    advFloat(showAdvanced, prefs.segThreshold, "去字遮罩門檻 seg", "0–1，越低抓越多筆畫(救漢字旁假名殘留)、越高漏細筆畫"),
                    advFloat(showAdvanced, prefs.minProb, "OCR 信心門檻", "0–1，低於此丟該行；太低收雜訊亂碼、太高漏字"),
                ).toImmutableList(),
            ),
        ).filter { it !is Preference.PreferenceGroup || it.preferenceItems.isNotEmpty() }
    }

    /** 進階浮點輸入：showAdvanced 關時回 null（不顯示）。subtitle 帶說明 + 現值(%s)。 */
    private fun advFloat(
        show: Boolean,
        pref: tachiyomi.core.common.preference.Preference<String>,
        title: String,
        desc: String,
    ): Item? =
        if (!show) {
            null
        } else {
            Preference.PreferenceItem.EditTextPreference(
                preference = pref,
                title = title,
                subtitle = "$desc。現值：%s",
            )
        }

    /** 進階整數輸入：同 [advFloat]。 */
    private fun advInt(
        show: Boolean,
        pref: tachiyomi.core.common.preference.Preference<String>,
        title: String,
        desc: String,
    ): Item? =
        if (!show) {
            null
        } else {
            Preference.PreferenceItem.EditTextPreference(
                preference = pref,
                title = title,
                subtitle = "$desc。現值：%s",
            )
        }
}
