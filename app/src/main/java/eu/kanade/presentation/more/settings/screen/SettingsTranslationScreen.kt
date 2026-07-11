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
            mp.joinToString("・") { (n, ok) -> "$n ${if (ok) "✓" else "✗"}" } +
                when {
                    !mp.all { it.second } -> modelStatusMissing
                    modelsOutdated -> modelStatusOutdated
                    else -> ""
                }
        } ?: modelStatusChecking

        // 全域翻譯總開關：關閉時下載翻 / 即時翻變灰停用（自動翻譯一律不做）。
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

        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_translation_quickstart),
                subtitle = stringResource(MR.strings.pref_translation_quickstart_summary),
                onClick = { navigator.push(TranslationQuickstartScreen()) },
            ),
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
                        preference = prefs.translationMasterEnabled,
                        title = stringResource(MR.strings.pref_translation_master),
                        subtitle = stringResource(MR.strings.pref_translation_master_summary),
                        onValueChanged = { enabled ->
                            // 副作用抽進 manager（與 More 頁快捷開關共用、連動）。
                            translationManager.onMasterEnabledChanged(enabled)
                            true
                        },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.translationEnabled,
                        title = stringResource(MR.strings.pref_translation_on_download),
                        subtitle = stringResource(MR.strings.pref_translation_on_download_summary),
                        enabled = masterEnabled,
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
                        enabled = masterEnabled,
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
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_translation_live_categories),
                        subtitle = getCategoriesLabel(
                            allCategories = allCategories,
                            included = liveIncluded,
                            excluded = liveExcluded,
                        ),
                        enabled = masterEnabled,
                        onClick = { showLiveCategoryDialog = true },
                    ),
                    Preference.PreferenceItem.MultiSelectListPreference(
                        preference = prefs.translationSourcesExclude,
                        entries = librarySources,
                        title = stringResource(MR.strings.pref_translation_excluded_sources),
                        subtitle = stringResource(MR.strings.pref_translation_excluded_sources_summary),
                        enabled = masterEnabled,
                    ),
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
            // —— 隱私（點開看完整宣告）——
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_privacy),
                preferenceItems = listOfNotNull<Item>(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_translation_privacy_summary),
                        onClick = { showPrivacyDialog = true },
                    ),
                ).toImmutableList(),
            ),
            // —— 去字 ——（翻譯總開關關時整組收起：純渲染參數、關閉時無意義）
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_group_inpaint),
                enabled = masterEnabled,
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
                        prefs.autoStdThreshold,
                        stringResource(MR.strings.pref_translation_auto_std),
                        stringResource(MR.strings.pref_translation_auto_std_desc) + curSuffix,
                    ),
                    adv(
                        showAdvanced,
                        prefs.autoWhiteThreshold,
                        stringResource(MR.strings.pref_translation_auto_white),
                        stringResource(MR.strings.pref_translation_auto_white_desc) + curSuffix,
                    ),
                    adv(
                        showAdvanced,
                        prefs.bboxPad,
                        stringResource(MR.strings.pref_translation_bbox_pad),
                        stringResource(MR.strings.pref_translation_bbox_pad_desc) + curSuffix,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.keepMaterials,
                        title = stringResource(MR.strings.pref_translation_keep_materials),
                        subtitle = stringResource(MR.strings.pref_translation_keep_materials_summary),
                    ),
                ).toImmutableList(),
            ),
            // —— 排版 ——（翻譯總開關關時整組收起）
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_group_typeset),
                enabled = masterEnabled,
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
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.colorMode,
                        entries = persistentMapOf(
                            "auto" to stringResource(MR.strings.pref_translation_color_auto),
                            "mono" to stringResource(MR.strings.pref_translation_color_mono),
                        ),
                        title = stringResource(MR.strings.pref_translation_color_mode),
                        onValueChanged = { _ ->
                            if (prefs.translationMasterEnabled.get() &&
                                (prefs.translationEnabled.get() || prefs.liveTranslate.get())
                            ) {
                                pendingRenderUpdate = RenderUpdateKind.LAYOUT
                            }
                            true
                        },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.fontBorder,
                        title = stringResource(MR.strings.pref_translation_font_border),
                        subtitle = stringResource(MR.strings.pref_translation_font_border_summary),
                        onValueChanged = { _ ->
                            if (prefs.translationMasterEnabled.get() &&
                                (prefs.translationEnabled.get() || prefs.liveTranslate.get())
                            ) {
                                pendingRenderUpdate = RenderUpdateKind.LAYOUT
                            }
                            true
                        },
                    ),
                    adv(
                        showAdvanced,
                        prefs.fontSizeMax,
                        stringResource(MR.strings.pref_translation_font_size_max),
                        stringResource(MR.strings.pref_translation_font_size_max_desc) + curSuffix,
                    ),
                    adv(
                        showAdvanced,
                        prefs.fontSizeMin,
                        stringResource(MR.strings.pref_translation_font_size_min),
                        stringResource(MR.strings.pref_translation_font_size_min_desc) + curSuffix,
                    ),
                    adv(
                        showAdvanced,
                        prefs.artStrokeRatio,
                        stringResource(MR.strings.pref_translation_art_stroke),
                        stringResource(MR.strings.pref_translation_art_stroke_desc) + curSuffix,
                    ),
                    adv(
                        showAdvanced,
                        prefs.colTrim,
                        stringResource(MR.strings.pref_translation_col_trim),
                        stringResource(MR.strings.pref_translation_col_trim_desc) + curSuffix,
                    ),
                    adv(
                        showAdvanced,
                        prefs.rowTrim,
                        stringResource(MR.strings.pref_translation_row_trim),
                        stringResource(MR.strings.pref_translation_row_trim_desc) + curSuffix,
                    ),
                    adv(
                        showAdvanced,
                        prefs.fontScale,
                        stringResource(MR.strings.pref_translation_font_scale),
                        stringResource(MR.strings.pref_translation_font_scale_desc) + curSuffix,
                    ),
                ).toImmutableList(),
            ),
            // —— 效能 ——（翻譯總開關關時整組收起）
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_group_performance),
                enabled = masterEnabled,
                preferenceItems = listOfNotNull<Item>(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.ocrConcurrency,
                        entries = threadEntries,
                        title = stringResource(MR.strings.pref_translation_ocr_concurrency),
                        subtitle = stringResource(MR.strings.pref_translation_ocr_concurrency_summary),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.intraThreads,
                        entries = threadEntries,
                        title = stringResource(MR.strings.pref_translation_intra_threads),
                        subtitle = stringResource(MR.strings.pref_translation_intra_threads_summary),
                    ),
                ).toImmutableList(),
            ),
            // —— 辨識（進階）——
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_group_recognition),
                enabled = showAdvanced && masterEnabled,
                preferenceItems = listOfNotNull<Item>(
                    adv(
                        showAdvanced,
                        prefs.segThreshold,
                        stringResource(MR.strings.pref_translation_seg_threshold),
                        stringResource(MR.strings.pref_translation_seg_threshold_desc) + curSuffix,
                    ),
                    adv(
                        showAdvanced,
                        prefs.minProb,
                        stringResource(MR.strings.pref_translation_min_prob),
                        stringResource(MR.strings.pref_translation_min_prob_desc) + curSuffix,
                    ),
                ).toImmutableList(),
            ),
        ).filter { it !is Preference.PreferenceGroup || it.preferenceItems.isNotEmpty() }
    }

    /** 進階數值輸入：showAdvanced 關時回 null（不顯示）。subtitle 已含說明 + 「。現值：%s」尾綴。 */
    private fun adv(show: Boolean, pref: PreferenceData<String>, title: String, subtitle: String): Item? =
        if (!show) {
            null
        } else {
            Preference.PreferenceItem.EditTextPreference(preference = pref, title = title, subtitle = subtitle)
        }
}
