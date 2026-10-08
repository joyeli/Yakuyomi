package eu.kanade.presentation.reader.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.tachiyomi.data.nightread.NightLevel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private val themes = listOf(
    MR.strings.black_background to 1,
    MR.strings.gray_background to 2,
    MR.strings.white_background to 0,
    MR.strings.automatic_background to 3,
)

/** 夜讀填黑程度（標準／更多；與懸浮鈕同一個偏好，這裡給有文字的版本）。 */
private val nightLevels = listOf(
    MR.strings.nightread_level_chip_standard to NightLevel.STANDARD,
    MR.strings.nightread_level_chip_more to NightLevel.MORE,
)

private val flashColors = listOf(
    MR.strings.pref_flash_style_black to ReaderPreferences.FlashColor.BLACK,
    MR.strings.pref_flash_style_white to ReaderPreferences.FlashColor.WHITE,
    MR.strings.pref_flash_style_white_black to ReaderPreferences.FlashColor.WHITE_BLACK,
)

@Composable
internal fun ColumnScope.GeneralPage(viewModel: ReaderSettingsViewModel) {
    val readerTheme by viewModel.preferences.readerTheme.collectAsState()

    val flashPageState by viewModel.preferences.flashOnPageChange.collectAsState()

    val flashMillisPref = viewModel.preferences.flashDurationMillis
    val flashMillis by flashMillisPref.collectAsState()

    val flashIntervalPref = viewModel.preferences.flashPageInterval
    val flashInterval by flashIntervalPref.collectAsState()

    val flashColorPref = viewModel.preferences.flashColor
    val flashColor by flashColorPref.collectAsState()

    // 即時翻譯開關已移到 App 設定 →「翻譯」（與「下載時翻譯章節」放一起、加分類過濾）；reader 面板不再放。
    SettingsChipRow(MR.strings.pref_reader_theme) {
        themes.map { (labelRes, value) ->
            FilterChip(
                selected = readerTheme == value,
                onClick = { viewModel.preferences.readerTheme.set(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    // Yakuyomi 夜讀模式：有夜讀版（章節夾 `.yakuyomi/` 的夜讀檔）的頁改顯示夜讀版、沒有的照常顯示原圖。
    // 這裡只翻偏好；切換後當前/相鄰章的重畫由 ReaderViewModel 觀察此偏好統一處理（與懸浮鈕同一條路）。
    // 只在夜讀表層總開關（「其他」頁 TranslationPreferences.nightReadEnabled）開時顯示；ReaderSettingsViewModel 只帶
    // ReaderPreferences → 這裡直接從 Injekt 取（同翻譯設定頁的做法）。
    val translationPreferences = remember { Injekt.get<TranslationPreferences>() }
    val nightReadEnabled by translationPreferences.nightReadEnabled.collectAsState()
    if (nightReadEnabled) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_reader_nightread_mode),
            pref = viewModel.preferences.nightReadMode,
        )
        // 填黑程度（有文字的替代入口，與懸浮鈕的訊號格圖示同一個偏好）：寫入後 ReaderViewModel debounce 再只重畫會變的頁。
        val fillLevel by translationPreferences.nightReadFillLevel.collectAsState()
        val currentLevel = NightLevel.fromPref(fillLevel)
        SettingsChipRow(MR.strings.pref_reader_nightread_fill_level) {
            nightLevels.map { (labelRes, level) ->
                FilterChip(
                    selected = currentLevel == level,
                    onClick = { translationPreferences.nightReadFillLevel.set(level.prefValue) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_show_page_number),
        pref = viewModel.preferences.showPageNumber,
    )

    val verticalNavigatorModes by viewModel.preferences.verticalNavigator.collectAsState()

    SettingsChipRow(MR.strings.pref_vertical_navigator) {
        ReadingMode.entries.filter { it != ReadingMode.DEFAULT }.forEach { mode ->
            FilterChip(
                selected = verticalNavigatorModes.contains(mode),
                onClick = {
                    val newModes = if (verticalNavigatorModes.contains(mode)) {
                        verticalNavigatorModes - mode
                    } else {
                        verticalNavigatorModes + mode
                    }
                    viewModel.preferences.verticalNavigator.set(newModes)
                },
                label = { Text(stringResource(mode.stringRes)) },
            )
        }
    }

    if (verticalNavigatorModes.isNotEmpty()) {
        val verticalNavigatorHeightPref = viewModel.preferences.verticalNavigatorHeight
        val verticalNavigatorHeight by verticalNavigatorHeightPref.collectAsState()

        CheckboxItem(
            label = stringResource(MR.strings.pref_webtoon_vertical_navigator_on_left),
            pref = viewModel.preferences.verticalNavigatorOnLeft,
        )

        SliderItem(
            label = stringResource(MR.strings.pref_vertical_navigator_height),
            value = verticalNavigatorHeight,
            valueRange = 65..100,
            steps = 6,
            onChange = { verticalNavigatorHeightPref.set(it) },
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_fullscreen),
        pref = viewModel.preferences.fullscreen,
    )

    val isFullscreen by viewModel.preferences.fullscreen.collectAsState()
    if (LocalActivity.current?.hasDisplayCutout() == true && isFullscreen) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_cutout_short),
            pref = viewModel.preferences.drawUnderCutout,
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_keep_screen_on),
        pref = viewModel.preferences.keepScreenOn,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_read_with_long_tap),
        pref = viewModel.preferences.readWithLongTap,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_always_show_chapter_transition),
        pref = viewModel.preferences.alwaysShowChapterTransition,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_page_transitions),
        pref = viewModel.preferences.pageTransitions,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_flash_page),
        pref = viewModel.preferences.flashOnPageChange,
    )
    if (flashPageState) {
        SliderItem(
            value = flashMillis / ReaderPreferences.MILLI_CONVERSION,
            valueRange = 1..15,
            label = stringResource(MR.strings.pref_flash_duration),
            valueString = stringResource(MR.strings.pref_flash_duration_summary, flashMillis),
            onChange = { flashMillisPref.set(it * ReaderPreferences.MILLI_CONVERSION) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = flashInterval,
            valueRange = 1..10,
            label = stringResource(MR.strings.pref_flash_page_interval),
            valueString = pluralStringResource(MR.plurals.pref_pages, flashInterval, flashInterval),
            onChange = {
                flashIntervalPref.set(it)
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SettingsChipRow(MR.strings.pref_flash_with) {
            flashColors.map { (labelRes, value) ->
                FilterChip(
                    selected = flashColor == value,
                    onClick = { flashColorPref.set(value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
    }
}
