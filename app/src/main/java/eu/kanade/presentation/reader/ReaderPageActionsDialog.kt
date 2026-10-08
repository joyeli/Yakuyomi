package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ActionButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderPageActionsDialog(
    onDismissRequest: () -> Unit,
    onSetAsCover: () -> Unit,
    onShare: (Boolean) -> Unit,
    onSave: () -> Unit,
    // 重繪當頁（換去字法重做去字+排版）；只在已下載章可用，呼叫端傳 null 時不顯示此鈕。
    onReRender: (() -> Unit)? = null,
    // 翻譯當頁；只在已下載章可用（線上章先不提供），呼叫端傳 null 時不顯示此鈕。
    onTranslatePage: (() -> Unit)? = null,
    // 開始翻譯這話（已下載＝排佇列／線上＝觸發下載+標記待翻）。當前章「未在翻譯」時顯示（與停止互斥）。
    onStartChapterTranslate: (() -> Unit)? = null,
    // 中止這話翻譯（取消佇列 + 中止進行中）。當前章「正在翻譯」時顯示（與開始互斥）。
    onStopChapterTranslate: (() -> Unit)? = null,
    // 當前章是否正在翻譯佇列（QUEUE/TRANSLATING）：true → 顯示「中止」、false → 顯示「開始」（XOR）。
    isChapterTranslating: Boolean = false,
    // 日常／夜讀切換與檔位已移到閱讀器的常駐懸浮鈕（ReaderNightFloatingControl），這裡只剩「產生」類動作。
    /** 「為這一話產生夜讀版」（非翻譯本的夜讀入口）；null＝不顯示（已有夜讀版／已在佇列／夜讀關／不支援）。 */
    onStartChapterNightRender: (() -> Unit)? = null,
    /** 這章只有舊版單檔：產生鈕改叫「重新產生夜讀版」（換成可切檔位的兩檔）。 */
    nightLegacy: Boolean = false,
    /** 「以目前設定重新產生這一話」（已有可切檔位的夜讀版；改了亮度後用，新鮮的頁也重做）；null＝不顯示。 */
    onRegenerateChapterNight: (() -> Unit)? = null,
    /** 「更新夜讀版」（這章有舊規則產生的頁；只重做那些頁）；null＝不顯示。 */
    onUpdateChapterNight: (() -> Unit)? = null,
) {
    var showSetCoverDialog by remember { mutableStateOf(false) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        // 兩列：第一列＝原有頁動作（封面/複製/分享/儲存，固定 4 顆），第二列＝翻譯相關（翻這頁/開始或中止這話/換去字法）。
        // 「換去字法」（原「重繪」）放第二列＝與翻譯成組、語意更清楚；上 4 下 3 也較平衡。
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.set_as_cover),
                    icon = Icons.Outlined.Photo,
                    onClick = { showSetCoverDialog = true },
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.action_copy_to_clipboard),
                    icon = Icons.Outlined.ContentCopy,
                    onClick = {
                        onShare(true)
                        onDismissRequest()
                    },
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.action_share),
                    icon = Icons.Outlined.Share,
                    onClick = {
                        onShare(false)
                        onDismissRequest()
                    },
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = stringResource(MR.strings.action_save),
                    icon = Icons.Outlined.Save,
                    onClick = {
                        onSave()
                        onDismissRequest()
                    },
                )
            }
            // 第二列：翻譯控制。每顆鈕的回呼自己關對話框（VM 內 closeDialog），故這裡不再呼叫 onDismissRequest。
            Row(
                modifier = Modifier.padding(top = MaterialTheme.padding.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                if (onTranslatePage != null) {
                    // 翻譯這頁（已下載章）：單頁進引擎翻、就地覆蓋。線上章不提供（呼叫端傳 null）。
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        title = stringResource(MR.strings.reader_translate_this_page),
                        icon = Icons.Outlined.Translate,
                        onClick = onTranslatePage,
                    )
                }
                // 開始 XOR 中止：依當前章是否在翻譯佇列二選一顯示。
                if (isChapterTranslating) {
                    if (onStopChapterTranslate != null) {
                        ActionButton(
                            modifier = Modifier.weight(1f),
                            title = stringResource(MR.strings.reader_stop_chapter_translate),
                            icon = Icons.Outlined.Close,
                            onClick = onStopChapterTranslate,
                        )
                    }
                } else {
                    if (onStartChapterTranslate != null) {
                        ActionButton(
                            modifier = Modifier.weight(1f),
                            title = stringResource(MR.strings.reader_translate_this_chapter),
                            icon = Icons.Filled.PlayArrow,
                            onClick = onStartChapterTranslate,
                        )
                    }
                }
                if (onReRender != null) {
                    // 換去字法（原「重繪」）：把對話框換成去字法選擇器（共用同一個 dialog state slot）。
                    // 不呼叫 onDismissRequest()——那會把剛開的選擇器一起關掉（兩者都寫 dialog 欄）。
                    ActionButton(
                        modifier = Modifier.weight(1f),
                        title = stringResource(MR.strings.reader_change_removal),
                        icon = Icons.Outlined.AutoFixHigh,
                        onClick = onReRender,
                    )
                }
            }
            // 第三列：夜讀（產生／重新產生）。VM 自己關對話框 + toast。
            val anyNight = onStartChapterNightRender != null || onRegenerateChapterNight != null ||
                onUpdateChapterNight != null
            if (anyNight) {
                Row(
                    modifier = Modifier.padding(top = MaterialTheme.padding.small),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    if (onStartChapterNightRender != null) {
                        // 為這一話產生夜讀版：不經翻譯直接排夜讀佇列（非翻譯本也能用夜讀）。舊版單檔章＝重新產生成兩檔。
                        ActionButton(
                            modifier = Modifier.weight(1f),
                            title = stringResource(
                                if (nightLegacy) {
                                    MR.strings.action_nightread_chapter_regen_tiers
                                } else {
                                    MR.strings.action_nightread_chapter
                                },
                            ),
                            icon = Icons.Outlined.DarkMode,
                            onClick = onStartChapterNightRender,
                        )
                    }
                    if (onUpdateChapterNight != null) {
                        // 更新夜讀版：app 更新改了夜讀規則，只重做這章舊規則產生的頁
                        ActionButton(
                            modifier = Modifier.weight(1f),
                            title = stringResource(MR.strings.action_nightread_chapter_update),
                            icon = Icons.Outlined.Update,
                            onClick = onUpdateChapterNight,
                        )
                    }
                    if (onRegenerateChapterNight != null) {
                        // 以目前設定重新產生這一話：亮度等設定改了之後用（夜讀版已在、只是想套新設定）
                        ActionButton(
                            modifier = Modifier.weight(1f),
                            title = stringResource(MR.strings.action_nightread_chapter_regen_current),
                            icon = Icons.Outlined.Refresh,
                            onClick = onRegenerateChapterNight,
                        )
                    }
                }
            }
        }
    }

    if (showSetCoverDialog) {
        SetCoverDialog(
            onConfirm = {
                onSetAsCover()
                showSetCoverDialog = false
            },
            onDismiss = { showSetCoverDialog = false },
        )
    }
}

@Composable
private fun SetCoverDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        text = {
            Text(stringResource(MR.strings.confirm_set_image_as_cover))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
        onDismissRequest = onDismiss,
    )
}

/**
 * 重繪去字法選擇對話框（reader 內版）：2 門別（快速去字 / AI 去字）。
 * 與 MangaScreen 的同名對話框一致；選項對映 [eu.kanade.tachiyomi.data.translation.PageTranslator.reRenderPage] 吃的去字法原始字串。
 */
@Composable
fun ReRenderMethodDialog(
    onDismissRequest: () -> Unit,
    onSelect: (String) -> Unit,
) {
    // 顯示名 → 去字法字串（與設定頁/引擎 when 映射一致）；「原圖」＝用素材還原未翻原圖（不去字、不載 lama）。
    val options = listOf(
        stringResource(MR.strings.rerender_boxfill) to "boxfill",
        stringResource(MR.strings.rerender_auto_whole) to "auto_whole",
        stringResource(MR.strings.rerender_original) to "original",
    )
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.rerender_method_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEach { (label, method) ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(method) },
                        headlineContent = { Text(text = label) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
