package eu.kanade.presentation.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.more.DownloadQueueState
import tachiyomi.core.common.Constants
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun MoreScreen(
    downloadQueueStateProvider: () -> DownloadQueueState,
    einkMode: Boolean,
    onEinkModeChange: (Boolean) -> Unit,
    downloadedOnly: Boolean,
    onDownloadedOnlyChange: (Boolean) -> Unit,
    incognitoMode: Boolean,
    onIncognitoModeChange: (Boolean) -> Unit,
    translationMasterEnabled: Boolean,
    onTranslationMasterChange: (Boolean) -> Unit,
    onClickDownloadQueue: () -> Unit,
    onClickUpdates: () -> Unit,
    onClickCategories: () -> Unit,
    onClickStats: () -> Unit,
    onClickDataAndStorage: () -> Unit,
    onClickSettings: () -> Unit,
    onClickSupport: () -> Unit,
    onClickAbout: () -> Unit,
    onOpenUrlInWebView: (String) -> Unit,
    webViewUrlHistoryProvider: () -> List<String>,
    onAddWebViewUrl: (String) -> Unit,
    onRemoveWebViewUrl: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    // Yakuyomi：以 WebView 開啟任意網址的對話框開關（通用瀏覽入口）。
    var showOpenUrlDialog by remember { mutableStateOf(false) }
    if (showOpenUrlDialog) {
        OpenUrlInWebViewDialog(
            // 開啟對話框當下讀一次歷史當初始清單；之後刪除/新增於對話框內管理。
            initialHistory = webViewUrlHistoryProvider(),
            onRemoveUrl = onRemoveWebViewUrl,
            onDismissRequest = { showOpenUrlDialog = false },
            onConfirm = { url ->
                showOpenUrlDialog = false
                onAddWebViewUrl(url)
                onOpenUrlInWebView(url)
            },
        )
    }

    Scaffold { contentPadding ->
        ScrollbarLazyColumn(contentPadding = contentPadding) {
            item {
                LogoHeader(
                    iconPadding = PaddingValues(vertical = 32.dp),
                )
            }
            item {
                // Yakuyomi：墨水屏一鍵（灰階＋白底＋換頁閃白＋關動畫）。放離線模式上面。
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.pref_eink_mode),
                    subtitle = stringResource(MR.strings.pref_eink_mode_summary),
                    icon = Icons.Outlined.Contrast,
                    checked = einkMode,
                    onCheckedChanged = onEinkModeChange,
                )
            }
            item {
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.label_downloaded_only),
                    subtitle = stringResource(MR.strings.downloaded_only_summary),
                    icon = Icons.Outlined.CloudOff,
                    checked = downloadedOnly,
                    onCheckedChanged = onDownloadedOnlyChange,
                )
            }
            item {
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.pref_incognito_mode),
                    subtitle = stringResource(MR.strings.pref_incognito_mode_summary),
                    icon = ImageVector.vectorResource(R.drawable.ic_glasses_24dp),
                    checked = incognitoMode,
                    onCheckedChanged = onIncognitoModeChange,
                )
            }
            item {
                // Yakuyomi：翻譯總開關快捷（與翻譯設定頁綁同一 pref、連動）。
                SwitchPreferenceWidget(
                    title = stringResource(MR.strings.pref_translation_master),
                    subtitle = stringResource(MR.strings.pref_translation_master_summary),
                    icon = Icons.Outlined.Translate,
                    checked = translationMasterEnabled,
                    onCheckedChanged = onTranslationMasterChange,
                )
            }

            item { HorizontalDivider() }

            item {
                val downloadQueueState = downloadQueueStateProvider()
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_download_queue),
                    subtitle = when (downloadQueueState) {
                        DownloadQueueState.Stopped -> null
                        is DownloadQueueState.Paused -> {
                            val pending = downloadQueueState.pending
                            if (pending == 0) {
                                stringResource(MR.strings.paused)
                            } else {
                                "${stringResource(MR.strings.paused)} • ${
                                    pluralStringResource(
                                        MR.plurals.download_queue_summary,
                                        count = pending,
                                        pending,
                                    )
                                }"
                            }
                        }
                        is DownloadQueueState.Downloading -> {
                            val pending = downloadQueueState.pending
                            pluralStringResource(MR.plurals.download_queue_summary, count = pending, pending)
                        }
                    },
                    icon = Icons.Outlined.GetApp,
                    onPreferenceClick = onClickDownloadQueue,
                )
            }
            item {
                // Yakuyomi：「更新」分頁從導覽列移除後，改由此進入（翻譯佇列已移到導覽列分頁）。
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_recent_updates),
                    icon = Icons.Outlined.NewReleases,
                    onPreferenceClick = onClickUpdates,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.categories),
                    icon = Icons.AutoMirrored.Outlined.Label,
                    onPreferenceClick = onClickCategories,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_stats),
                    icon = Icons.Outlined.QueryStats,
                    onPreferenceClick = onClickStats,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_data_storage),
                    icon = Icons.Outlined.Storage,
                    onPreferenceClick = onClickDataAndStorage,
                )
            }

            item { HorizontalDivider() }

            item {
                // Yakuyomi：通用「以 WebView 開啟網址」入口——不綁 source，用內建 WebView 開任意網站。
                TextPreferenceWidget(
                    title = stringResource(MR.strings.action_open_url_in_webview),
                    icon = Icons.Outlined.Public,
                    onPreferenceClick = { showOpenUrlDialog = true },
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_settings),
                    icon = Icons.Outlined.Settings,
                    onPreferenceClick = onClickSettings,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.pref_category_about),
                    icon = Icons.Outlined.Info,
                    onPreferenceClick = onClickAbout,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_help),
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    onPreferenceClick = { uriHandler.openUri(Constants.URL_HELP) },
                )
            }
        }
    }
}

// Yakuyomi：輸入任意網址 → 補 https:// 後交給呼叫端用 WebView 開啟。
// 輸入框下方帶出輸入歷史（點列＝填回輸入框可再微調；trailing 刪除鈕＝逐筆清掉）。
@Composable
private fun OpenUrlInWebViewDialog(
    initialHistory: List<String>,
    onRemoveUrl: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    // 歷史清單在對話框內管理：初值來自 pref，刪除即時反映 UI 並同步寫回 pref。
    var history by remember { mutableStateOf(initialHistory) }
    val trimmed = input.trim()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty(),
                onClick = {
                    val normalized = if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
                    onConfirm(normalized)
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_open_url_in_webview))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(text = stringResource(MR.strings.open_url_in_webview_label)) },
                    placeholder = { Text(text = stringResource(MR.strings.open_url_in_webview_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                )

                if (history.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                    ) {
                        items(items = history, key = { it }) { url ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 點列＝填回輸入框（可微調後再開），比直接開更靈活、與輸入框語意一致。
                                    .clickable { input = url },
                            ) {
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 8.dp),
                                )
                                IconButton(
                                    onClick = {
                                        history = history.filterNot { it == url }
                                        onRemoveUrl(url)
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = stringResource(MR.strings.action_delete),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
