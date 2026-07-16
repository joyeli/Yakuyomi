package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.interactor.GetSourcesWithFavoriteCount
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TriStateListDialog
import eu.kanade.tachiyomi.crash.TraceLog
import eu.kanade.tachiyomi.data.translation.ModelDownloadManager
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.ui.translation.TranslationQuickstartScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.LlmModels
import li.joye.yakuyomi.engine.LlmProviders
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.i18n.stringResource as ctxStringResource
import tachiyomi.core.common.preference.Preference as PreferenceData

private typealias Item = Preference.PreferenceItem<out Any, out Any>

/** 「改設定後更新已翻章」對話框種類：UPGRADE＝改去字方法(升級重繪)、LAYOUT＝改排版(各章用原去字法重繪、只套新排版)。 */
private enum class RenderUpdateKind { UPGRADE, LAYOUT }

object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_translation

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember { Injekt.get<TranslationPreferences>() }
        // 首次開啟翻譯設定 → 自動帶出快速上手導覽一次（之後可從頂端「快速上手」列重開）。
        LaunchedEffect(Unit) {
            if (!prefs.quickstartShown.get()) {
                prefs.quickstartShown.set(true)
                navigator.push(TranslationQuickstartScreen())
            }
        }
        super<SearchableSettings>.Content()
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { Injekt.get<TranslationPreferences>() }
        val navigator = LocalNavigator.currentOrThrow
        // 即時翻譯開關 → 控制 warm 引擎生命週期（開＝預暖、關＝釋放 ~100MB）。
        val engineService = remember { Injekt.get<TranslationEngineService>() }
        // 「閱讀後刪除」綁下載偏好同一個 pref → 與下載設定頁連動（任一邊改都同步）。
        val downloadPrefs = remember { Injekt.get<DownloadPreferences>() }
        val translationManager = remember { Injekt.get<TranslationManager>() }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val showAdvanced by prefs.showAdvanced.collectAsState()
        val privacyAck by prefs.privacyAcknowledged.collectAsState()
        val cores = remember { Runtime.getRuntime().availableProcessors() }
        // 進階參數 subtitle 的「。現值：%s」尾綴（%s 由 EditTextPreference 框架填現值）。
        val curSuffix = stringResource(MR.strings.pref_translation_adv_current)
        // 進階 badge 文字（每個進階選項標題旁顯示、與一般選項區分）。
        val advBadge = stringResource(MR.strings.pref_advanced_badge)
        // 進階滑桿現值（Int pref → SliderPreference 需要 value + onValueChanged）。
        val stripPadVal by prefs.stripPad.collectAsState()
        val dbnetSizeVal by prefs.dbnetSize.collectAsState()
        val maskDilateVal by prefs.maskDilate.collectAsState()

        // —— 多 LLM 供應商（m-i-t 全部 + OpenRouter）+ 自動撈模型清單 ——
        val providerId by prefs.provider.collectAsState()
        val providerPreset = remember(providerId) { LlmProviders.byId(providerId) }
        val modelVal by prefs.model.collectAsState()
        val providerEntries = remember {
            LlmProviders.ALL.associate { it.id to it.displayName }.toImmutableMap()
        }
        var modelPicker by remember { mutableStateOf<List<String>?>(null) }
        var fetchingModels by remember { mutableStateOf(false) }

        // 模型自動下載 + 狀態（下載完才重算 modelPresence）。
        val modelDownloadManager = remember { Injekt.get<ModelDownloadManager>() }
        val modelDownloadState by modelDownloadManager.state.collectAsState()
        val modelsJustDownloaded = modelDownloadState is ModelDownloadManager.State.Done
        val modelPresence by produceState<List<Pair<String, Boolean>>?>(initialValue = null, modelsJustDownloaded) {
            value = withContext(Dispatchers.IO) { TranslationEngineConfig.modelPresence(context) }
        }
        // 舊版（v1 ONNX/LaMa）模型偵測：齊全但缺 v2 NCNN → 提示更新（否則去字會壞卻無警示）。
        val modelsOutdated by produceState(initialValue = false, modelsJustDownloaded) {
            value = withContext(Dispatchers.IO) { TranslationEngineConfig.modelsOutdated(context) }
        }
        val modelStatusMissing = stringResource(MR.strings.pref_translation_model_status_missing)
        val modelStatusOutdated = stringResource(MR.strings.pref_translation_model_status_outdated)
        val modelStatusChecking = stringResource(MR.strings.pref_translation_model_status_checking)
        val modelStatusSubtitle = modelPresence?.let { mp ->
            val roles = mp.joinToString("・") { (n, ok) -> "$n ${if (ok) "✓" else "✗"}" }
            when {
                // 過時優先：有舊檔但 v2 引擎載不動（見 modelsResolvable）→ 只顯示 ⚠️ 更新提示，不列「✓✓✓」免誤導「都好了」。
                modelsOutdated -> modelStatusOutdated
                !mp.all { it.second } -> "$roles$modelStatusMissing"
                else -> roles
            }
        } ?: modelStatusChecking

        // 全域翻譯總開關：關閉時整頁只剩「快速上手 + 啟用翻譯」兩項、其餘（顯示進階 + 所有組）全部隱藏（自動翻譯一律不做）。
        val masterEnabled by prefs.translationMasterEnabled.collectAsState()

        // 即時翻譯分類過濾。
        val getCategories = remember { Injekt.get<GetCategories>() }
        val allCategories by getCategories.subscribe().collectAsState(initial = emptyList())
        val liveIncluded by prefs.liveTranslateCategories.collectAsState()
        val liveExcluded by prefs.liveTranslateCategoriesExclude.collectAsState()
        var showLiveCategoryDialog by rememberSaveable { mutableStateOf(false) }

        // per-source 排除：列書庫用到的線上來源。
        val getSourcesWithFavoriteCount = remember { Injekt.get<GetSourcesWithFavoriteCount>() }
        val librarySources by produceState<ImmutableMap<String, String>>(initialValue = persistentMapOf()) {
            getSourcesWithFavoriteCount.subscribe().collect { list ->
                value = list.associate { (src, _) -> src.id.toString() to src.name }.toImmutableMap()
            }
        }
        if (showLiveCategoryDialog) {
            TriStateListDialog(
                title = stringResource(MR.strings.pref_translation_live_categories),
                message = stringResource(MR.strings.pref_translation_live_categories_message),
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

        // 一次性隱私同意 + 隱私完整宣告 + 改設定更新已翻章 + 抓模型清單，四個對話框。
        var pendingEnableSwitch by remember { mutableStateOf<PreferenceData<Boolean>?>(null) }
        var pendingRenderUpdate by remember { mutableStateOf<RenderUpdateKind?>(null) }
        var showPrivacyDialog by remember { mutableStateOf(false) }
        if (pendingEnableSwitch != null) {
            val pending = pendingEnableSwitch!!
            AlertDialog(
                onDismissRequest = { pendingEnableSwitch = null },
                title = { Text(text = stringResource(MR.strings.pref_translation_privacy)) },
                text = { Text(text = stringResource(MR.strings.pref_translation_privacy_full)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            prefs.privacyAcknowledged.set(true)
                            pending.set(true)
                            if (pending === prefs.liveTranslate) engineService.warmUpAsync()
                            pendingEnableSwitch = null
                        },
                    ) { Text(text = stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingEnableSwitch = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
        if (showPrivacyDialog) {
            AlertDialog(
                onDismissRequest = { showPrivacyDialog = false },
                title = { Text(text = stringResource(MR.strings.pref_translation_privacy)) },
                text = { Text(text = stringResource(MR.strings.pref_translation_privacy_full)) },
                confirmButton = {
                    TextButton(onClick = { showPrivacyDialog = false }) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
            )
        }
        pendingRenderUpdate?.let { kind ->
            AlertDialog(
                onDismissRequest = { pendingRenderUpdate = null },
                title = { Text(text = stringResource(MR.strings.pref_translation_render_update_title)) },
                text = {
                    Text(
                        text = when (kind) {
                            RenderUpdateKind.UPGRADE -> stringResource(
                                MR.strings.pref_translation_render_update_upgrade,
                            )
                            RenderUpdateKind.LAYOUT -> stringResource(MR.strings.pref_translation_render_update_layout)
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingRenderUpdate = null
                            scope.launch {
                                val n = when (kind) {
                                    RenderUpdateKind.UPGRADE -> translationManager.reRenderAllUpgradable()
                                    RenderUpdateKind.LAYOUT -> translationManager.reRenderAllWithStoredMethod()
                                }
                                context.toast(
                                    if (n > 0) {
                                        context.ctxStringResource(MR.strings.pref_translation_render_update_queued, n)
                                    } else {
                                        context.ctxStringResource(MR.strings.pref_translation_render_update_none)
                                    },
                                )
                            }
                        },
                    ) { Text(text = stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRenderUpdate = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
        modelPicker?.let { models ->
            AlertDialog(
                onDismissRequest = { modelPicker = null },
                title = { Text(text = stringResource(MR.strings.pref_translation_pick_model, models.size)) },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(models) { id ->
                            Text(
                                text = id,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        prefs.model.set(id)
                                        modelPicker = null
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { modelPicker = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        // 裝置感知緒數選項：自動 + {2,4,6,8 ≤ 核數}。
        val threadEntries = buildMap {
            put("auto", stringResource(MR.strings.pref_translation_thread_auto, cores))
            listOf(2, 4, 6, 8).filter { it <= cores }.forEach {
                put(it.toString(), stringResource(MR.strings.pref_translation_thread_count, it))
            }
        }.toImmutableMap()

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

        // 常駐頂層（不受總開關隱藏）：快速上手導覽 + 啟用翻譯總開關（從「翻譯」組移到頂層）。
        val quickstartItem = Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_translation_quickstart),
            subtitle = stringResource(MR.strings.pref_translation_quickstart_summary),
            onClick = { navigator.push(TranslationQuickstartScreen()) },
        )
        val masterItem = Preference.PreferenceItem.SwitchPreference(
            preference = prefs.translationMasterEnabled,
            title = stringResource(MR.strings.pref_translation_master),
            subtitle = stringResource(MR.strings.pref_translation_master_summary),
            onValueChanged = { enabled ->
                // 副作用抽進 manager（與 More 頁快捷開關共用、連動）。
                translationManager.onMasterEnabledChanged(enabled)
                true
            },
        )
        // 總開關關閉 → 只顯示上面兩項，其餘（顯示進階 + 所有組）全部不 render。
        if (!masterEnabled) return listOf(quickstartItem, masterItem)

        return listOf(
            quickstartItem,
            masterItem,
            Preference.PreferenceItem.SwitchPreference(
                preference = prefs.showAdvanced,
                title = stringResource(MR.strings.pref_translation_show_advanced),
                subtitle = stringResource(MR.strings.pref_translation_show_advanced_summary),
            ),
            // —— 翻譯（開關 + 範圍）——
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_translation),
                preferenceItems = listOfNotNull<Item>(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.translationEnabled,
                        title = stringResource(MR.strings.pref_translation_on_download),
                        subtitle = stringResource(MR.strings.pref_translation_on_download_summary),
                        onValueChanged = { enabled ->
                            if (enabled && !privacyAck) {
                                pendingEnableSwitch = prefs.translationEnabled
                                false
                            } else {
                                true
                            }
                        },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.liveTranslate,
                        title = stringResource(MR.strings.pref_translation_live),
                        subtitle = stringResource(MR.strings.pref_translation_live_summary),
                        onValueChanged = { enabled ->
                            when {
                                enabled && !privacyAck -> {
                                    pendingEnableSwitch = prefs.liveTranslate
                                    false
                                }
                                enabled -> {
                                    engineService.warmUpAsync()
                                    true
                                }
                                else -> {
                                    engineService.shutdownAsync()
                                    true
                                }
                            }
                        },
                    ),
                    Preference.PreferenceItem.MultiSelectListPreference(
                        preference = prefs.translationSourcesExclude,
                        entries = librarySources,
                        title = stringResource(MR.strings.pref_translation_excluded_sources),
                        subtitle = stringResource(MR.strings.pref_translation_excluded_sources_summary),
                    ),
                    // 即時翻譯分類（原獨立「即時翻譯」組移入本組）：tri-state include/exclude 過濾即時翻的書。
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_translation_live_categories),
                        subtitle = getCategoriesLabel(
                            allCategories = allCategories,
                            included = liveIncluded,
                            excluded = liveExcluded,
                        ),
                        onClick = { showLiveCategoryDialog = true },
                    ),
                    // 「閱讀後刪除」（原「即時翻譯」組移入本組）：與下載偏好同一個 pref
                    // （downloadPrefs.removeAfterReadSlots）→ 改這也改下載行為（刻意的）。
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
                        subtitle = stringResource(MR.strings.pref_translation_remove_after_read_summary),
                    ),
                ).toImmutableList(),
            ),
            // —— 去字 ——（緊接翻譯組上移；翻譯總開關關時整組收起：純渲染參數、關閉時無意義）
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_group_inpaint),
                preferenceItems = listOfNotNull<Item>(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.inpaintMethod,
                        entries = persistentMapOf(
                            // 2 門別（快速去字 / AI 去字）；auto_tile（逐格）已退役，stored auto_whole 仍選中
                            "boxfill" to stringResource(MR.strings.pref_translation_inpaint_boxfill),
                            "auto_whole" to stringResource(MR.strings.pref_translation_inpaint_auto_whole),
                        ),
                        title = stringResource(MR.strings.pref_translation_inpaint_method),
                        onValueChanged = { _ ->
                            if (prefs.translationMasterEnabled.get() &&
                                (prefs.translationEnabled.get() || prefs.liveTranslate.get())
                            ) {
                                pendingRenderUpdate = RenderUpdateKind.UPGRADE
                            }
                            true
                        },
                    ),
                    adv(
                        showAdvanced,
                        prefs.bboxPad,
                        stringResource(MR.strings.pref_translation_bbox_pad),
                        stringResource(MR.strings.pref_translation_bbox_pad_desc) + curSuffix,
                        advBadge,
                    ),
                    // 整頁 AI 去字解析度：三檔 512/768/1024（存 Int，只三值有意義）。subtitle 顯示現值、說明進對話框。
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.tileSize,
                        entries = persistentMapOf(
                            512 to "512",
                            768 to "768",
                            1024 to "1024",
                        ),
                        title = stringResource(MR.strings.pref_translation_tile_size),
                        description = stringResource(MR.strings.pref_translation_tile_size_desc),
                        titleBadge = advBadge,
                    ).takeIf { showAdvanced },
                    // 遮罩膨脹半徑（值/2）：吞掉文字白邊、否則去字後殘白塊；8–40，實測 24。
                    Preference.PreferenceItem.SliderPreference(
                        value = maskDilateVal,
                        valueRange = 8..40,
                        title = stringResource(MR.strings.pref_translation_mask_dilate),
                        subtitle = stringResource(MR.strings.pref_translation_mask_dilate_desc),
                        titleBadge = advBadge,
                        onValueChanged = { prefs.maskDilate.set(it) },
                    ).takeIf { showAdvanced },
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.keepMaterials,
                        title = stringResource(MR.strings.pref_translation_keep_materials),
                        subtitle = stringResource(MR.strings.pref_translation_keep_materials_summary),
                    ),
                    // 即時翻去字方法（原獨立「即時翻譯」組移入本組）：與下載/手動翻的去字法分開，預設 boxfill 求低延遲、
                    // 想即時看 AI 去字可選 auto_whole(aot)。值沿用去字設定字串、引擎 mapInpaintMethod 把非 boxfill 一律當 aot。收進進階。
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.liveInpaintMethod,
                        entries = persistentMapOf(
                            "boxfill" to stringResource(MR.strings.pref_translation_inpaint_boxfill),
                            "auto_whole" to stringResource(MR.strings.pref_translation_inpaint_auto_whole),
                        ),
                        title = stringResource(MR.strings.pref_translation_live_inpaint_method),
                        subtitle = stringResource(MR.strings.pref_translation_live_inpaint_summary),
                        titleBadge = advBadge,
                    ).takeIf { showAdvanced },
                ).toImmutableList(),
            ),
            // —— 供應商（LLM）——
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_group_provider),
                preferenceItems = listOfNotNull<Item>(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.provider,
                        entries = providerEntries,
                        title = stringResource(MR.strings.pref_translation_provider),
                        subtitle = stringResource(MR.strings.pref_translation_provider_summary),
                        onValueChanged = { _ ->
                            prefs.model.set("")
                            true
                        },
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.apiBase,
                        title = stringResource(MR.strings.pref_translation_api_base),
                        subtitle = stringResource(MR.strings.pref_translation_api_base_summary),
                    ).takeIf { providerPreset.baseEditable },
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.apiKeyFor(providerId),
                        title = stringResource(MR.strings.pref_translation_api_key),
                        subtitle = stringResource(
                            MR.strings.pref_translation_api_key_summary,
                            providerPreset.displayName,
                        ),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.model,
                        title = stringResource(MR.strings.pref_translation_model),
                        subtitle = modelVal.ifBlank {
                            stringResource(
                                MR.strings.pref_translation_model_default_summary,
                                providerPreset.defaultModel,
                            )
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_translation_fetch_models),
                        subtitle = if (fetchingModels) {
                            stringResource(MR.strings.pref_translation_fetching)
                        } else {
                            stringResource(MR.strings.pref_translation_fetch_models_summary)
                        },
                        onClick = {
                            if (!fetchingModels) {
                                scope.launch {
                                    fetchingModels = true
                                    val url = LlmProviders.modelsUrlOf(providerPreset, prefs.apiBase.get())
                                    val list = LlmModels.list(
                                        url,
                                        providerPreset.modelSource,
                                        prefs.apiKeyFor(providerId).get(),
                                    )
                                    fetchingModels = false
                                    if (list.isEmpty()) {
                                        context.toast(
                                            context.ctxStringResource(MR.strings.pref_translation_fetch_models_empty),
                                        )
                                    } else {
                                        modelPicker = list.map { it.id }
                                    }
                                }
                            }
                        },
                    ),
                    // LLM 取樣溫度：低＝更一致貼字直譯、高＝更靈活但可能偏離；多數人不用動（進階、存字串 parse+clamp 0–1）。
                    adv(
                        showAdvanced,
                        prefs.temperature,
                        stringResource(MR.strings.pref_translation_temperature),
                        stringResource(MR.strings.pref_translation_temperature_desc) + curSuffix,
                        advBadge,
                    ),
                ).toImmutableList(),
            ),
            // —— 語言 ——
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_group_language),
                preferenceItems = listOfNotNull<Item>(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.targetLangName,
                        entries = targetLangs,
                        title = stringResource(MR.strings.pref_translation_target_lang),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.sourceLangName,
                        entries = sourceLangs,
                        title = stringResource(MR.strings.pref_translation_source_lang),
                        subtitle = stringResource(MR.strings.pref_translation_source_lang_subtitle),
                        subtitleProvider = { _, _ -> stringResource(MR.strings.pref_translation_source_lang_subtitle) },
                    ),
                ).toImmutableList(),
            ),
            // —— 模型 ——
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_group_models),
                preferenceItems = listOfNotNull<Item>(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_translation_model_status),
                        subtitle = modelStatusSubtitle,
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(
                            if (modelsOutdated) {
                                MR.strings.pref_translation_update_models
                            } else {
                                MR.strings.pref_translation_download_models
                            },
                        ),
                        subtitle = when (val s = modelDownloadState) {
                            is ModelDownloadManager.State.Running -> "${s.label}　${s.percent}%"
                            ModelDownloadManager.State.Done ->
                                stringResource(MR.strings.pref_translation_download_models_done)
                            is ModelDownloadManager.State.Error ->
                                stringResource(MR.strings.pref_translation_download_models_error, s.message)
                            ModelDownloadManager.State.Idle ->
                                stringResource(MR.strings.pref_translation_download_models_idle)
                        },
                        onClick = { modelDownloadManager.download() },
                    ),
                ).toImmutableList(),
            ),
            // —— 排版 ——（翻譯總開關關時整組收起）
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_group_typeset),
                preferenceItems = listOfNotNull<Item>(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.orientation,
                        entries = persistentMapOf(
                            "auto" to stringResource(MR.strings.pref_translation_orientation_auto),
                            "vertical" to stringResource(MR.strings.pref_translation_orientation_vertical),
                            "horizontal" to stringResource(MR.strings.pref_translation_orientation_horizontal),
                        ),
                        title = stringResource(MR.strings.pref_translation_orientation),
                        onValueChanged = { _ ->
                            if (prefs.translationMasterEnabled.get() &&
                                (prefs.translationEnabled.get() || prefs.liveTranslate.get())
                            ) {
                                pendingRenderUpdate = RenderUpdateKind.LAYOUT
                            }
                            true
                        },
                    ),
                    // 文字顏色 / 描邊收進進階：一般使用者用預設 auto + 描邊即可。
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.colorMode,
                        entries = persistentMapOf(
                            "auto" to stringResource(MR.strings.pref_translation_color_auto),
                            "mono" to stringResource(MR.strings.pref_translation_color_mono),
                        ),
                        title = stringResource(MR.strings.pref_translation_color_mode),
                        titleBadge = advBadge,
                        onValueChanged = { _ ->
                            if (prefs.translationMasterEnabled.get() &&
                                (prefs.translationEnabled.get() || prefs.liveTranslate.get())
                            ) {
                                pendingRenderUpdate = RenderUpdateKind.LAYOUT
                            }
                            true
                        },
                    ).takeIf { showAdvanced },
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.fontBorder,
                        title = stringResource(MR.strings.pref_translation_font_border),
                        subtitle = stringResource(MR.strings.pref_translation_font_border_summary),
                        titleBadge = advBadge,
                        onValueChanged = { _ ->
                            if (prefs.translationMasterEnabled.get() &&
                                (prefs.translationEnabled.get() || prefs.liveTranslate.get())
                            ) {
                                pendingRenderUpdate = RenderUpdateKind.LAYOUT
                            }
                            true
                        },
                    ).takeIf { showAdvanced },
                    // 縱中橫（直排短 ASCII 串水平並排）：純排版 → 改動觸發 LAYOUT 重繪既有已翻章。
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.tateChuYoko,
                        title = stringResource(MR.strings.pref_translation_tate_chu_yoko),
                        subtitle = stringResource(MR.strings.pref_translation_tate_chu_yoko_summary),
                        titleBadge = advBadge,
                        onValueChanged = { _ ->
                            if (prefs.translationMasterEnabled.get() &&
                                (prefs.translationEnabled.get() || prefs.liveTranslate.get())
                            ) {
                                pendingRenderUpdate = RenderUpdateKind.LAYOUT
                            }
                            true
                        },
                    ).takeIf { showAdvanced },
                    adv(
                        showAdvanced,
                        prefs.fontSizeMax,
                        stringResource(MR.strings.pref_translation_font_size_max),
                        stringResource(MR.strings.pref_translation_font_size_max_desc) + curSuffix,
                        advBadge,
                    ),
                    adv(
                        showAdvanced,
                        prefs.fontSizeMin,
                        stringResource(MR.strings.pref_translation_font_size_min),
                        stringResource(MR.strings.pref_translation_font_size_min_desc) + curSuffix,
                        advBadge,
                    ),
                    adv(
                        showAdvanced,
                        prefs.artStrokeRatio,
                        stringResource(MR.strings.pref_translation_art_stroke),
                        stringResource(MR.strings.pref_translation_art_stroke_desc) + curSuffix,
                        advBadge,
                    ),
                    adv(
                        showAdvanced,
                        prefs.colTrim,
                        stringResource(MR.strings.pref_translation_col_trim),
                        stringResource(MR.strings.pref_translation_col_trim_desc) + curSuffix,
                        advBadge,
                    ),
                    adv(
                        showAdvanced,
                        prefs.rowTrim,
                        stringResource(MR.strings.pref_translation_row_trim),
                        stringResource(MR.strings.pref_translation_row_trim_desc) + curSuffix,
                        advBadge,
                    ),
                    adv(
                        showAdvanced,
                        prefs.fontScale,
                        stringResource(MR.strings.pref_translation_font_scale),
                        stringResource(MR.strings.pref_translation_font_scale_desc) + curSuffix,
                        advBadge,
                    ),
                ).toImmutableList(),
            ),
            // —— 辨識（進階）——（排版之後、效能之前）
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_group_recognition),
                enabled = showAdvanced,
                preferenceItems = listOfNotNull<Item>(
                    // —— 偵測 ——
                    adv(
                        showAdvanced,
                        prefs.segThreshold,
                        stringResource(MR.strings.pref_translation_seg_threshold),
                        stringResource(MR.strings.pref_translation_seg_threshold_desc) + curSuffix,
                        advBadge,
                    ),
                    // 偵測辨識尺寸（DBNet input）：1024 甜蜜點；步進 128（768–1536，7 檔＝steps 5）。
                    Preference.PreferenceItem.SliderPreference(
                        value = dbnetSizeVal,
                        valueRange = 768..1536,
                        steps = 5,
                        title = stringResource(MR.strings.pref_translation_dbnet_size),
                        subtitle = stringResource(MR.strings.pref_translation_dbnet_size_desc),
                        titleBadge = advBadge,
                        onValueChanged = { prefs.dbnetSize.set(it) },
                    ).takeIf { showAdvanced },
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.detectUnsharp,
                        title = stringResource(MR.strings.pref_translation_detect_unsharp),
                        subtitle = stringResource(MR.strings.pref_translation_detect_unsharp_summary),
                        titleBadge = advBadge,
                    ).takeIf { showAdvanced },
                    // —— OCR ——
                    adv(
                        showAdvanced,
                        prefs.minProb,
                        stringResource(MR.strings.pref_translation_min_prob),
                        stringResource(MR.strings.pref_translation_min_prob_desc) + curSuffix,
                        advBadge,
                    ),
                    // OCR 裁切外擴：救「框太瘦切字→CTC 空讀→漏氣泡」；0–12（每格 1，steps 預設）。
                    Preference.PreferenceItem.SliderPreference(
                        value = stripPadVal,
                        valueRange = 0..12,
                        title = stringResource(MR.strings.pref_translation_strip_pad),
                        subtitle = stringResource(MR.strings.pref_translation_strip_pad_desc),
                        titleBadge = advBadge,
                        onValueChanged = { prefs.stripPad.set(it) },
                    ).takeIf { showAdvanced },
                    // OCR 裁切內插法：bicubic 救小假名（句尾否定不翻反）／bilinear。
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.useBicubic,
                        entries = persistentMapOf(
                            "bicubic" to stringResource(MR.strings.pref_translation_interp_bicubic),
                            "bilinear" to stringResource(MR.strings.pref_translation_interp_bilinear),
                        ),
                        title = stringResource(MR.strings.pref_translation_use_bicubic),
                        subtitle = stringResource(MR.strings.pref_translation_use_bicubic_desc),
                        titleBadge = advBadge,
                    ).takeIf { showAdvanced },
                    // OCR 裁切銳化：抵銷 warp 縮放模糊、救小假名漏讀；預設開（與偵測輸入銳化 detectUnsharp 相反）。
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.ocrUnsharp,
                        title = stringResource(MR.strings.pref_translation_ocr_unsharp),
                        subtitle = stringResource(MR.strings.pref_translation_ocr_unsharp_summary),
                        titleBadge = advBadge,
                    ).takeIf { showAdvanced },
                    // 跳過狀聲詞 SFX：開→OcrConfig.ignoreBubble 給內建門檻（buildEngineConfig 填 24）跳過彩色/裝飾性
                    // 非氣泡狀聲詞、不翻它們（保留原味）。這是「跳過翻譯」非「積極去除」——SFX 仍留在原圖上。
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.ignoreSfx,
                        title = stringResource(MR.strings.pref_translation_ignore_sfx),
                        subtitle = stringResource(MR.strings.pref_translation_ignore_sfx_summary),
                        titleBadge = advBadge,
                    ).takeIf { showAdvanced },
                ).toImmutableList(),
            ),
            // —— 效能（進階）——（翻譯總開關關時整組收起；非進階時空 group 隱藏，比照辨識/診斷組）
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_group_performance),
                enabled = showAdvanced,
                preferenceItems = listOfNotNull<Item>(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.ocrConcurrency,
                        entries = threadEntries,
                        title = stringResource(MR.strings.pref_translation_ocr_concurrency),
                        subtitle = stringResource(MR.strings.pref_translation_ocr_concurrency_summary),
                        titleBadge = advBadge,
                    ).takeIf { showAdvanced },
                    // 推論緒數選單已移除：NCNN 偵測/去字的緒數改由引擎原生設定（big.LITTLE 大核甜蜜點），非使用者可調。
                ).toImmutableList(),
            ),
            // —— 隱私（點開看完整宣告；移到診斷上面）——
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_privacy),
                preferenceItems = listOfNotNull<Item>(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_translation_privacy_summary),
                        onClick = { showPrivacyDialog = true },
                    ),
                ).toImmutableList(),
            ),
            // —— 診斷（進階）——（抓 logcat / 內建 crash log 都抓不到的原生/OOM crash；預設關、影響效能，只需要時開）
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_group_diagnostics),
                preferenceItems = listOfNotNull<Item>(
                    // 執行時切換：開→TraceLog.init（接引擎 hook + 寫檔）、關→TraceLog.stop（斷 hook + 清 buffer），不必重啟 app。
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.diagnosticLog,
                        title = stringResource(MR.strings.pref_translation_diagnostic_log),
                        subtitle = stringResource(MR.strings.pref_translation_diagnostic_log_summary),
                        titleBadge = advBadge,
                        onValueChanged = { enabled ->
                            if (enabled) TraceLog.init(context) else TraceLog.stop()
                            true
                        },
                    ).takeIf { showAdvanced },
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_translation_share_diagnostic_log),
                        subtitle = stringResource(MR.strings.pref_translation_share_diagnostic_log_summary),
                        titleBadge = advBadge,
                        onClick = {
                            if (!TraceLog.shareLog(context)) {
                                context.toast(
                                    context.ctxStringResource(MR.strings.pref_translation_diagnostic_log_empty),
                                )
                            }
                        },
                    ).takeIf { showAdvanced },
                ).toImmutableList(),
            ),
        ).filter { it !is Preference.PreferenceGroup || it.preferenceItems.isNotEmpty() }
    }

    /**
     * 進階數值輸入：showAdvanced 關時回 null（不顯示）。subtitle 已含說明 + 「。現值：%s」尾綴。
     * [badge]＝進階標記文字（標題旁小 badge，與一般選項區分；所有 adv() 皆進階故一律傳入）。
     */
    private fun adv(
        show: Boolean,
        pref: PreferenceData<String>,
        title: String,
        subtitle: String,
        badge: String?,
    ): Item? =
        if (!show) {
            null
        } else {
            Preference.PreferenceItem.EditTextPreference(
                preference = pref,
                title = title,
                subtitle = subtitle,
                titleBadge = badge,
            )
        }
}
