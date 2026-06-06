package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
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
) {
    var showSetCoverDialog by remember { mutableStateOf(false) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Row(
            modifier = Modifier.padding(vertical = 16.dp),
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
            if (onReRender != null) {
                // 重繪：交給 VM 把對話框換成去字法選擇器（共用同一個 dialog state slot）。
                // 不呼叫 onDismissRequest()——那會把剛開的選擇器一起關掉（兩者都寫 dialog 欄）。
                ActionButton(
                    modifier = Modifier.weight(1f),
                    title = "重繪",
                    icon = Icons.Outlined.AutoFixHigh,
                    onClick = onReRender,
                )
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
 * 重繪去字法選擇對話框（reader 內版）：3 階梯（BoxFill / Auto-整頁 / Auto-逐格）。
 * 與 MangaScreen 的同名對話框一致；選項對映 [eu.kanade.tachiyomi.data.translation.PageTranslator.reRenderPage] 吃的去字法原始字串。
 */
@Composable
fun ReRenderMethodDialog(
    onDismissRequest: () -> Unit,
    onSelect: (String) -> Unit,
) {
    // 顯示名 → 去字法字串（與設定頁/引擎 when 映射一致）
    val options = listOf(
        "BoxFill" to "boxfill",
        "Auto-整頁" to "auto_whole",
        "Auto-逐格" to "auto_tile",
    )
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = "重繪去字方法") },
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
