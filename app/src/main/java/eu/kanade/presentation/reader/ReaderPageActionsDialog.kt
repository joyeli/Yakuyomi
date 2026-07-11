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
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
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
