package eu.kanade.presentation.more.settings.screen

import android.text.format.Formatter
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.kanade.domain.source.interactor.GetSourcesWithFavoriteCount
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TriStateListDialog
import eu.kanade.tachiyomi.data.nightread.NightConcurrency
import eu.kanade.tachiyomi.data.nightread.NightLevel
import eu.kanade.tachiyomi.data.nightread.NightStorage
import eu.kanade.tachiyomi.data.nightread.NightStorageService
import eu.kanade.tachiyomi.data.translation.ModelDownloadManager
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig.ModelGroup
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.isBackgroundActivityRestricted
import eu.kanade.tachiyomi.util.system.isIgnoringBatteryOptimizations
import eu.kanade.tachiyomi.util.system.openAppDetailsSettings
import eu.kanade.tachiyomi.util.system.requestIgnoreBatteryOptimizations
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.i18n.pluralStringResource as ctxPluralStringResource
import tachiyomi.core.common.i18n.stringResource as ctxStringResource

private typealias NightItem = Preference.PreferenceItem<out Any, out Any>

/**
 * 設定 › 夜讀（2026-09-26 從翻譯設定頁拆出）：夜讀是獨立功能——自己的總開關、自己的佇列 pool、自己的模型組、自己的參數，
 * 與翻譯只共用偵測器。分工：「其他」頁的快捷開關＝同一個 pref 的鏡像；閱讀器的懸浮鈕與設定面板＝顯示開關與檔位；
 * 本頁分兩組：
 *  - **顯示（即時）**：填黑程度（標準／更多，[NightLevel]）。兩檔在產生時一次備齊，這個值只決定顯示哪一檔，與閱讀器
 *    懸浮鈕同步。
 *  - **產生（只影響之後產生的頁）**：自動產生的範圍、略過彩色頁、亮度。
 * 另有「背景處理」（電池最佳化的說明、目前狀態與系統請求，不自動跳提示；[backgroundGroup]）與「儲存空間」（夜讀版佔用、清除
 * 已讀的話／全部；總開關關著也顯示；[storageGroup]）。
 *
 * 分類／來源的 gate 只管**自動**路徑（下載完／翻完後）；手動月亮鈕與 reader 長壓不受限（同翻譯）。
 * 亮度改了只影響之後產生的夜讀版（已產生的章用閱讀器長按選單「以目前設定重新產生這一話」；不自動重做——一章要好幾分鐘）。
 * app 更新改了夜讀規則（`NightPages.RULES_VERSION`）時，「產生」組第一列「重新產生舊版夜讀頁」列出有舊規則頁的話數、
 * 一鍵全部排入（只重做舊的頁）。掃描在頁面真的打開時才跑（[Content]；設定搜尋也會組 [getPreferences]，不能在那裡掃全庫），
 * 結果沿用到夜讀版有變（`TranslationManager.refreshOutdatedNight`），每次進來不必再掃一次全庫；掃完沒有、排入了、失敗時
 * 點那一列＝重新掃描。
 */
object SettingsNightReadScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_nightread

    @Composable
    override fun Content() {
        val prefs = remember { Injekt.get<TranslationPreferences>() }
        val translationManager = remember { Injekt.get<TranslationManager>() }
        val nightEnabled by prefs.nightReadEnabled.collectAsState()
        // 進來（或打開總開關）時要一份舊版夜讀頁的掃描結果：夜讀版沒變就沿用上次的；跑在 manager 的 scope，離開頁面不中斷
        LaunchedEffect(nightEnabled) {
            if (nightEnabled) translationManager.refreshOutdatedNight()
        }
        // 「儲存空間」的用量：同樣只在頁面真的打開時算（總開關關著也算——關了夜讀之後正是清空間的時候）。全庫只在這次開 app
        // 第一次進來時掃；之後只重算佇列跑過、刪掉的那幾話（點用量那一列＝全庫重新計算）
        LaunchedEffect(Unit) { translationManager.nightStorage.refresh() }
        super<SearchableSettings>.Content()
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { Injekt.get<TranslationPreferences>() }
        val readerPreferences = remember { Injekt.get<ReaderPreferences>() }
        val translationManager = remember { Injekt.get<TranslationManager>() }
        val context = LocalContext.current
        val nightEnabled by prefs.nightReadEnabled.collectAsState()
        val advBadge = stringResource(MR.strings.pref_advanced_badge)
        val scope = rememberCoroutineScope()

        // 「重新產生舊版夜讀頁」：掃描狀態由 manager 持有（[Content] 觸發掃描、結果沿用到夜讀版有變）；有舊版章時按下去先確認
        // 再整批排入；掃完沒有／排入了／失敗時按下去＝重新掃描。
        val outdatedScan by translationManager.outdatedNightScan.collectAsState()
        val skipColor by prefs.nightReadSkipColor.collectAsState()
        var confirmRegen by remember { mutableStateOf<TranslationManager.OutdatedNightScan.Done?>(null) }
        confirmRegen?.let { scan ->
            AlertDialog(
                onDismissRequest = { confirmRegen = null },
                title = { Text(text = stringResource(MR.strings.pref_nightread_regen_outdated)) },
                text = {
                    val count = scan.chapterCount
                    val main = pluralStringResource(MR.plurals.pref_nightread_regen_outdated_confirm, count, count)
                    // 「略過彩色頁」開著時，重做會刪掉彩頁的舊夜讀版（照目前設定彩頁不該有夜讀版），先講清楚
                    val colorNote = stringResource(MR.strings.pref_nightread_regen_outdated_confirm_skip_color)
                    Text(text = if (skipColor) "$main\n\n$colorNote" else main)
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmRegen = null
                            scope.launch {
                                // 夜讀只要偵測器＋人物分割；缺了排進去只會整章紅，先提示（同章節多選的月亮鈕）
                                val n = withContext(Dispatchers.IO) {
                                    val modelsOk = TranslationEngineConfig.detectorResolvable(context) &&
                                        TranslationEngineConfig.charSegResolvable(context)
                                    if (modelsOk) translationManager.nightRenderOutdated(scan.targets) else -1
                                }
                                context.toast(
                                    when {
                                        n < 0 -> context.ctxStringResource(MR.strings.nightread_missing_models)
                                        n > 0 -> context.ctxPluralStringResource(
                                            MR.plurals.pref_nightread_regen_outdated_queued,
                                            n,
                                            n,
                                        )
                                        else -> context.ctxStringResource(
                                            MR.strings.pref_nightread_regen_outdated_nothing,
                                        )
                                    },
                                )
                                // 一話都沒排進去＝掃描結果已過時（都在產生中或已刪掉）：重掃一次
                                if (n == 0) translationManager.refreshOutdatedNight(force = true)
                            }
                        },
                    ) { Text(text = stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRegen = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
        val regenItem = Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_nightread_regen_outdated),
            subtitle = when (val s = outdatedScan) {
                TranslationManager.OutdatedNightScan.Idle -> null
                is TranslationManager.OutdatedNightScan.Scanning -> if (s.total > 0) {
                    stringResource(MR.strings.pref_nightread_regen_outdated_scanning, s.done, s.total)
                } else {
                    stringResource(MR.strings.pref_nightread_regen_outdated_scanning_start)
                }
                is TranslationManager.OutdatedNightScan.Done -> if (s.chapterCount > 0) {
                    pluralStringResource(MR.plurals.pref_nightread_regen_outdated_count, s.chapterCount, s.chapterCount)
                } else {
                    stringResource(MR.strings.pref_nightread_regen_outdated_none)
                }
                is TranslationManager.OutdatedNightScan.Queued ->
                    pluralStringResource(MR.plurals.pref_nightread_regen_outdated_queued, s.count, s.count)
                TranslationManager.OutdatedNightScan.Failed -> stringResource(
                    MR.strings.pref_nightread_regen_outdated_failed,
                )
            },
            // 掃描中、還沒掃不能按；其餘都能按（有舊版章＝確認後排入，否則＝重新掃描）
            enabled = outdatedScan !is TranslationManager.OutdatedNightScan.Scanning &&
                outdatedScan != TranslationManager.OutdatedNightScan.Idle,
            onClick = {
                val s = outdatedScan
                if (s is TranslationManager.OutdatedNightScan.Done && s.chapterCount > 0) {
                    confirmRegen = s
                } else {
                    translationManager.refreshOutdatedNight(force = true)
                }
            },
        )

        // 舊版存值（三檔時代的 l1、更早的 protect／aggressive）換成對應的 l2／l3，清單才勾得到（懸浮鈕本來就照
        // NightLevel.fromPref 讀）。
        val fillLevel by prefs.nightReadFillLevel.collectAsState()
        val fillSummary = stringResource(MR.strings.pref_nightread_fill_level_summary)
        LaunchedEffect(fillLevel) {
            val normalized = NightLevel.fromPref(fillLevel).prefValue
            if (normalized != fillLevel) prefs.nightReadFillLevel.set(normalized)
        }

        // 亮度數值（Int pref → SliderPreference 需要 value + onValueChanged）。
        val ink by prefs.nightReadInk.collectAsState()
        val edgeInk by prefs.nightReadEdgeInk.collectAsState()
        val strokeObjV by prefs.nightReadStrokeObjV.collectAsState()
        val bg by prefs.nightReadBg.collectAsState()
        val dimCeil by prefs.nightReadDimCeil.collectAsState()
        val glowCap by prefs.nightReadGlowCap.collectAsState()
        // 預設檔＝三個亮度的組合；不符任一組＝「自訂」。選一檔＝把三個數值填進去，之後仍可逐個微調。
        val presets = remember {
            mapOf(
                "standard" to Triple(240, 240, 220),
                "soft" to Triple(205, 170, 190),
                "softer" to Triple(190, 150, 175),
            )
        }
        val presetValue = presets.entries.firstOrNull { it.value == Triple(ink, edgeInk, strokeObjV) }?.key ?: "custom"
        // 清單只列三檔（「自訂」不是可選項，選了沒東西可填）；數值不符任一檔時副標顯示「自訂」、對話框沒有被勾的項。
        val presetEntries = persistentMapOf(
            "standard" to stringResource(MR.strings.nightread_brightness_standard),
            "soft" to stringResource(MR.strings.nightread_brightness_soft),
            "softer" to stringResource(MR.strings.nightread_brightness_softer),
        )
        val customLabel = stringResource(MR.strings.nightread_brightness_custom)

        // 這台 heap 放得下幾頁（「自動」的頁數，也是清單的上限）：只靠 Kotlin 的 maxMemory，不必載 native，仍在背景算。
        // 備份還原可能帶來這台放不下或認不得的值（例如別台的 3）：清單沒有它、對話框會一項都沒勾 → 在這裡（組合之外）
        // 改存實際生效的值（數字夾到上限；認不得的字串生效值本來就等於「自動」）。
        val pageCap by produceState<Int?>(initialValue = null) {
            val cap = withContext(Dispatchers.Default) { NightConcurrency.heapPageCap() }
            val stored = prefs.nightReadPageConcurrency.get()
            val n = stored.toIntOrNull()
            if (stored != NightConcurrency.AUTO && (n == null || n !in 1..cap)) {
                prefs.nightReadPageConcurrency.set(n?.coerceIn(1, cap)?.toString() ?: NightConcurrency.AUTO)
            }
            value = cap
        }

        // 自動產生的分類過濾（tri-state，同即時翻譯分類的對話框）。
        val getCategories = remember { Injekt.get<GetCategories>() }
        val allCategories by getCategories.subscribe().collectAsState(initial = emptyList())
        val included by prefs.nightReadCategories.collectAsState()
        val excluded by prefs.nightReadCategoriesExclude.collectAsState()
        var showCategoryDialog by rememberSaveable { mutableStateOf(false) }
        if (showCategoryDialog) {
            TriStateListDialog(
                title = stringResource(MR.strings.pref_nightread_categories),
                message = stringResource(MR.strings.pref_nightread_categories_message),
                items = allCategories,
                initialChecked = included.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
                initialInversed = excluded.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
                itemLabel = { it.visualName },
                onDismissRequest = { showCategoryDialog = false },
                onValueChanged = { newIncluded, newExcluded ->
                    prefs.nightReadCategories.set(newIncluded.fastMap { it.id.toString() }.toSet())
                    prefs.nightReadCategoriesExclude.set(newExcluded.fastMap { it.id.toString() }.toSet())
                    showCategoryDialog = false
                },
            )
        }

        // per-source 排除：列書庫用到的來源（同翻譯頁）。
        val getSourcesWithFavoriteCount = remember { Injekt.get<GetSourcesWithFavoriteCount>() }
        val librarySources by produceState<ImmutableMap<String, String>>(initialValue = persistentMapOf()) {
            getSourcesWithFavoriteCount.subscribe().collect { list ->
                value = list.associate { (src, _) -> src.id.toString() to src.name }.toImmutableMap()
            }
        }

        // 夜讀模型組：狀態（偵測器＝與翻譯共用；人物分割兩顆至少一顆）＋下載／刪除（只動這組）。
        val modelDownloadManager = remember { Injekt.get<ModelDownloadManager>() }
        val downloadState by modelDownloadManager.state.collectAsState()
        val filesRevision by modelDownloadManager.filesRevision.collectAsState()
        // 這組下載完 / 刪除後 → 重掃狀態（Done 是 data class、每次新物件；delete 後狀態回 Idle）。
        val presence by produceState<List<TranslationEngineConfig.RolePresence>?>(
            initialValue = null,
            downloadState,
            filesRevision,
        ) {
            value = withContext(Dispatchers.IO) { TranslationEngineConfig.nightModelPresence(context) }
        }
        val statusChecking = stringResource(MR.strings.pref_translation_model_status_checking)
        val modelStatusSubtitle = presence?.let { rows ->
            // 分隔用不分語言的「 · 」（片假名中點「・」在英文介面很突兀；佇列頁也是用「 · 」）。
            val text = rows.joinToString(" · ") { r -> "${r.label} ${if (r.present) "✓" else "✗"}" }
            // 就緒判準＝偵測器有 且 人物分割至少一顆（引擎收到一顆會退化成單顆）
            val ready = rows.first().present && rows.drop(1).any { it.present }
            // 缺檔提示走模板（%1$s＝清單）：字串開頭的空白會被 aapt2 修掉，直接接字串會黏在一起。
            if (ready) text else stringResource(MR.strings.pref_nightread_model_status_missing, text)
        } ?: statusChecking
        val downloadSubtitle = when (val s = downloadState) {
            is ModelDownloadManager.State.Running ->
                if (s.group ==
                    ModelGroup.NIGHT
                ) {
                    stringResource(MR.strings.model_dl_progress_subtitle, s.label, s.percent)
                } else {
                    stringResource(MR.strings.model_dl_other_group_busy)
                }
            is ModelDownloadManager.State.Done ->
                if (s.group == ModelGroup.NIGHT) {
                    stringResource(MR.strings.pref_translation_download_models_done)
                } else {
                    stringResource(MR.strings.pref_nightread_download_models_idle)
                }
            is ModelDownloadManager.State.Error ->
                if (s.group == ModelGroup.NIGHT) {
                    stringResource(MR.strings.pref_translation_download_models_error, s.message)
                } else {
                    stringResource(MR.strings.pref_nightread_download_models_idle)
                }
            ModelDownloadManager.State.Idle -> stringResource(MR.strings.pref_nightread_download_models_idle)
        }

        val masterItem = Preference.PreferenceItem.SwitchPreference(
            preference = prefs.nightReadEnabled,
            title = stringResource(MR.strings.pref_nightread_master),
            subtitle = stringResource(MR.strings.pref_nightread_master_summary),
            onValueChanged = { enabled ->
                // 同「其他」頁快捷開關（MoreTab.applyNightRead）：關＝順手關 reader 夜讀模式（入口全隱藏後沒地方關）＋
                // manager 副作用（停夜讀那條／續跑）。pref 由 widget 寫。
                if (!enabled) readerPreferences.nightReadMode.set(false)
                translationManager.onNightEnabledChanged(enabled)
                true
            },
        )
        val modelsGroup = Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_translation_group_models),
            preferenceItems = listOfNotNull<NightItem>(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_translation_model_status),
                    subtitle = modelStatusSubtitle,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_nightread_download_models),
                    subtitle = downloadSubtitle,
                    onClick = { modelDownloadManager.download(ModelGroup.NIGHT) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_nightread_delete_models),
                    subtitle = stringResource(MR.strings.pref_nightread_delete_models_summary),
                    onClick = { modelDownloadManager.delete(ModelGroup.NIGHT) },
                ),
            ).toImmutableList(),
        )
        val storageGroup = storageGroup(translationManager.nightStorage)

        // 總開關關 → 只剩總開關＋儲存空間＋模型組（模型可以先下好、關了夜讀之後也要能清空間；其餘參數關著時無意義）。
        if (!nightEnabled) return listOf(masterItem, storageGroup, modelsGroup)

        return listOf(
            masterItem,
            // —— 顯示（即時）：填黑程度，與閱讀器懸浮鈕同一個偏好 ——
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_nightread_group_display),
                preferenceItems = listOfNotNull<NightItem>(
                    // 背景填黑兩檔 標準 l2／更多 l3（見 NightLevel、TranslationPreferences.nightReadFillLevel）：產生時兩檔
                    // 都做，這裡只選顯示哪一檔。說明尾巴的「目前值」照 NightLevel.fromPref 對應：舊存值（l1、protect、
                    // aggressive）在上面的 LaunchedEffect 換成 l2／l3 之前那一幀也不會印出 null。
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.nightReadFillLevel,
                        entries = persistentMapOf(
                            NightLevel.STANDARD.prefValue to stringResource(MR.strings.nightread_fill_standard),
                            NightLevel.MORE.prefValue to stringResource(MR.strings.nightread_fill_more),
                        ),
                        title = stringResource(MR.strings.pref_nightread_fill_level),
                        subtitle = fillSummary,
                        subtitleProvider = { v, e -> fillSummary.format(e[NightLevel.fromPref(v).prefValue]) },
                    ),
                ).toImmutableList(),
            ),
            // —— 產生（只影響之後產生的頁）：重新產生舊版頁 + 自動產生 + 範圍 + 略過彩色頁 + 亮度 ——
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_nightread_group_generate),
                preferenceItems = listOfNotNull<NightItem>(
                    regenItem,
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.nightReadGenerate,
                        title = stringResource(MR.strings.pref_translation_nightread),
                        subtitle = stringResource(MR.strings.pref_translation_nightread_summary),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_nightread_categories),
                        subtitle = getCategoriesLabel(
                            allCategories = allCategories,
                            included = included,
                            excluded = excluded,
                        ),
                        onClick = { showCategoryDialog = true },
                    ),
                    Preference.PreferenceItem.MultiSelectListPreference(
                        preference = prefs.nightReadSourcesExclude,
                        entries = librarySources,
                        title = stringResource(MR.strings.pref_nightread_excluded_sources),
                        subtitle = stringResource(MR.strings.pref_nightread_excluded_sources_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.nightReadSkipColor,
                        title = stringResource(MR.strings.pref_nightread_skip_color),
                        subtitle = stringResource(MR.strings.pref_nightread_skip_color_summary),
                    ),
                    // 亮度：預設檔一鍵填值 + 三個滑桿
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(MR.strings.pref_nightread_brightness_info),
                    ),
                    Preference.PreferenceItem.BasicListPreference(
                        value = presetValue,
                        entries = presetEntries,
                        title = stringResource(MR.strings.pref_nightread_brightness),
                        subtitleProvider = { v, e -> e[v] ?: customLabel },
                        onValueChanged = { key ->
                            presets[key]?.let { (i, e, s) ->
                                prefs.nightReadInk.set(i)
                                prefs.nightReadEdgeInk.set(e)
                                prefs.nightReadStrokeObjV.set(s)
                            }
                        },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = ink,
                        valueRange = 100..255,
                        title = stringResource(MR.strings.pref_nightread_ink),
                        subtitle = stringResource(MR.strings.pref_nightread_ink_summary),
                        valueString = ink.toString(),
                        onValueChanged = { prefs.nightReadInk.set(it) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = edgeInk,
                        valueRange = 60..255,
                        title = stringResource(MR.strings.pref_nightread_edge_ink),
                        subtitle = stringResource(MR.strings.pref_nightread_edge_ink_summary),
                        valueString = edgeInk.toString(),
                        onValueChanged = { prefs.nightReadEdgeInk.set(it) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = strokeObjV,
                        valueRange = 60..255,
                        title = stringResource(MR.strings.pref_nightread_stroke_obj),
                        subtitle = stringResource(MR.strings.pref_nightread_stroke_obj_summary),
                        valueString = strokeObjV.toString(),
                        onValueChanged = { prefs.nightReadStrokeObjV.set(it) },
                    ),
                ).toImmutableList(),
            ),
            // —— 進階：整頁暗度的三個位準（動這些等於動整體觀感，標進階）；同「產生」組，只影響之後產生的頁 ——
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_nightread_group_advanced),
                preferenceItems = listOfNotNull<NightItem>(
                    Preference.PreferenceItem.SliderPreference(
                        value = bg,
                        valueRange = 0..64,
                        title = stringResource(MR.strings.pref_nightread_bg),
                        subtitle = stringResource(MR.strings.pref_nightread_bg_summary),
                        valueString = bg.toString(),
                        titleBadge = advBadge,
                        onValueChanged = { prefs.nightReadBg.set(it) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = dimCeil,
                        valueRange = 60..255,
                        title = stringResource(MR.strings.pref_nightread_dim_ceil),
                        subtitle = stringResource(MR.strings.pref_nightread_dim_ceil_summary),
                        valueString = dimCeil.toString(),
                        titleBadge = advBadge,
                        onValueChanged = { v ->
                            prefs.nightReadDimCeil.set(v)
                            // 不變式：墨線發光上限 < 場景亮度上限（線永遠比紙暗、不反相）
                            if (prefs.nightReadGlowCap.get() >= v) prefs.nightReadGlowCap.set(v - 1)
                        },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = glowCap,
                        valueRange = 40..254,
                        title = stringResource(MR.strings.pref_nightread_glow_cap),
                        subtitle = stringResource(MR.strings.pref_nightread_glow_cap_summary),
                        valueString = glowCap.toString(),
                        titleBadge = advBadge,
                        onValueChanged = { v ->
                            prefs.nightReadGlowCap.set(v.coerceAtMost(prefs.nightReadDimCeil.get() - 1))
                        },
                    ),
                    // 同時處理頁數（見 TranslationPreferences.nightReadPageConcurrency）：自動＋1..這台 heap 放得下的頁數；
                    // 推論執行緒全自動、不給設定。上限算好前（背景、一瞬間）先不列。
                    pageCap?.let { cap ->
                        val pageSummary = stringResource(MR.strings.pref_nightread_page_concurrency_summary)
                        Preference.PreferenceItem.ListPreference(
                            preference = prefs.nightReadPageConcurrency,
                            entries = buildMap {
                                put(
                                    NightConcurrency.AUTO,
                                    stringResource(MR.strings.pref_nightread_page_concurrency_auto, cap),
                                )
                                (1..cap).forEach { put(it.toString(), it.toString()) }
                            }.toImmutableMap(),
                            title = stringResource(MR.strings.pref_nightread_page_concurrency),
                            subtitle = pageSummary,
                            // 越界的存值上面會改存成生效值；改寫生效前的那一瞬間清單還沒有它 → 顯示實際生效值（夾到 cap）
                            subtitleProvider = { v, e ->
                                val effective = (v.toIntOrNull()?.coerceIn(1, cap) ?: cap).toString()
                                pageSummary.format(e[v] ?: e[effective] ?: v)
                            },
                            titleBadge = advBadge,
                        )
                    },
                ).toImmutableList(),
            ),
            backgroundGroup(),
            storageGroup,
            modelsGroup,
        )
    }

    /**
     * 「背景處理」：螢幕關閉後系統可能凍結夜讀與翻譯佇列（電池最佳化）。只有說明＋目前狀態＋按鈕（跳系統的「允許在背景執行」
     * 請求，與 設定 › 進階 同一份程式），不會自己跳提示。狀態每次回到這頁（例如從系統畫面回來）重讀。vivo 等品牌另有自己的
     * 「允許後台高耗電」，app 查不到也開不了，只能說明。
     * 系統另外「限制了背景活動」（Android 9–11 是獨立的開關；Android 12 起是「電池用量：受限」）時，就算不受電池最佳化限制，
     * 佇列在背景照樣被停住：這時改說明這件事，點一下開「應用程式資訊」（要從那裡的「電池」解除，電池最佳化的請求解不了它）。
     */
    @Composable
    private fun backgroundGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        var unrestricted by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
        var backgroundRestricted by remember { mutableStateOf(context.isBackgroundActivityRestricted()) }
        LifecycleResumeEffect(Unit) {
            unrestricted = context.isIgnoringBatteryOptimizations()
            backgroundRestricted = context.isBackgroundActivityRestricted()
            onPauseOrDispose { }
        }
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_nightread_group_background),
            preferenceItems = listOf<NightItem>(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_nightread_battery),
                    subtitle = stringResource(
                        when {
                            backgroundRestricted -> MR.strings.pref_nightread_battery_background_restricted
                            unrestricted -> MR.strings.pref_nightread_battery_unrestricted
                            else -> MR.strings.pref_nightread_battery_restricted
                        },
                    ),
                    onClick = {
                        if (backgroundRestricted) {
                            context.openAppDetailsSettings()
                        } else {
                            context.requestIgnoreBatteryOptimizations()
                        }
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    title = stringResource(MR.strings.pref_nightread_battery_oem_note),
                ),
            ).toImmutableList(),
        )
    }

    /**
     * 「儲存空間」：夜讀版佔多少（背景計算、帶進度；點一下重新計算）、清除已讀的話／全部（確認框寫出大小）。只算、只刪夜讀自己的
     * 檔（各格式夜讀版、暫存、規則版本記號），翻譯素材、原圖、manifest 都不碰；正在產生的話跳過。見 [NightStorageService]。
     */
    @Composable
    private fun storageGroup(storage: NightStorageService): Preference.PreferenceGroup {
        val context = LocalContext.current
        val state by storage.state.collectAsState()
        // 清除完的提示（清了幾話、釋出多少；有刪不掉的或跳過的另外一行）
        LaunchedEffect(storage) {
            storage.cleared.collect { r ->
                val lines = buildList {
                    add(
                        context.ctxPluralStringResource(
                            MR.plurals.nightread_storage_cleared,
                            r.chapters,
                            r.chapters,
                            Formatter.formatShortFileSize(context, r.bytes),
                        ),
                    )
                    if (r.failedChapters > 0) {
                        add(
                            context.ctxPluralStringResource(
                                MR.plurals.nightread_storage_clear_failed,
                                r.failedChapters,
                                r.failedChapters,
                            ),
                        )
                    }
                    if (r.skipped > 0) {
                        add(
                            context.ctxPluralStringResource(
                                MR.plurals.nightread_storage_clear_skipped,
                                r.skipped,
                                r.skipped,
                            ),
                        )
                    }
                }
                context.toast(lines.joinToString("\n"))
            }
        }

        // 確認框：true＝只清已讀的話、false＝全部；null＝沒開
        var confirmClear by remember { mutableStateOf<Boolean?>(null) }
        val done = state as? NightStorageService.State.Done
        confirmClear?.let { readOnly ->
            val totals = done?.summary?.let { if (readOnly) it.read else it.all }
            if (totals == null) {
                // 結果不見了（重新計算中）：關掉確認框，免得算完又自己跳出來
                LaunchedEffect(Unit) { confirmClear = null }
            } else {
                AlertDialog(
                    onDismissRequest = { confirmClear = null },
                    title = {
                        Text(
                            text = stringResource(
                                if (readOnly) {
                                    MR.strings.pref_nightread_clear_read
                                } else {
                                    MR.strings.pref_nightread_clear_all
                                },
                            ),
                        )
                    },
                    text = {
                        Text(
                            text = pluralStringResource(
                                if (readOnly) {
                                    MR.plurals.pref_nightread_clear_read_confirm
                                } else {
                                    MR.plurals.pref_nightread_clear_all_confirm
                                },
                                totals.chapters,
                                totals.chapters,
                                Formatter.formatShortFileSize(context, totals.bytes),
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmClear = null
                                storage.clear(readOnly)
                            },
                        ) { Text(text = stringResource(MR.strings.action_delete)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmClear = null }) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }
        }

        val usageSubtitle = when (val s = state) {
            NightStorageService.State.Idle -> null
            is NightStorageService.State.Scanning -> if (s.total > 0) {
                stringResource(MR.strings.pref_nightread_storage_scanning, s.done, s.total)
            } else {
                stringResource(MR.strings.pref_nightread_storage_scanning_start)
            }
            is NightStorageService.State.Clearing ->
                stringResource(MR.strings.pref_nightread_storage_clearing, s.done, s.total)
            is NightStorageService.State.Done -> if (s.summary.all.chapters > 0) {
                pluralStringResource(
                    MR.plurals.pref_nightread_storage_total,
                    s.summary.all.chapters,
                    s.summary.all.chapters,
                    Formatter.formatShortFileSize(context, s.summary.all.bytes),
                )
            } else {
                stringResource(MR.strings.pref_nightread_storage_none)
            }
            NightStorageService.State.Failed -> stringResource(MR.strings.pref_nightread_storage_failed)
        }

        @Composable
        fun amount(totals: NightStorage.Totals?): String? = when {
            totals == null -> null
            totals.chapters == 0 -> stringResource(MR.strings.pref_nightread_clear_nothing)
            else -> pluralStringResource(
                MR.plurals.pref_nightread_clear_amount,
                totals.chapters,
                totals.chapters,
                Formatter.formatShortFileSize(context, totals.bytes),
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_nightread_group_storage),
            preferenceItems = listOf<NightItem>(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_nightread_storage_usage),
                    subtitle = usageSubtitle,
                    // 算好或失敗了才能按（＝重新計算）；計算中、清除中不能按
                    enabled = state is NightStorageService.State.Done || state is NightStorageService.State.Failed,
                    onClick = { storage.refresh(force = true) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_nightread_clear_read),
                    subtitle = amount(done?.summary?.read),
                    enabled = (done?.summary?.read?.chapters ?: 0) > 0,
                    onClick = { confirmClear = true },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_nightread_clear_all),
                    subtitle = amount(done?.summary?.all),
                    enabled = (done?.summary?.all?.chapters ?: 0) > 0,
                    onClick = { confirmClear = false },
                ),
            ).toImmutableList(),
        )
    }
}
