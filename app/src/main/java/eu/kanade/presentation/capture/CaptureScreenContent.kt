package eu.kanade.presentation.capture

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.WebContent
import com.kevinnzou.web.WebView
import com.kevinnzou.web.WebViewNavigator
import com.kevinnzou.web.WebViewState
import eu.kanade.presentation.webview.captureWebView
import eu.kanade.presentation.webview.findActivity
import eu.kanade.tachiyomi.ui.capture.CaptureReviewScreen
import eu.kanade.tachiyomi.ui.capture.CaptureSaveResult
import eu.kanade.tachiyomi.ui.capture.FrameGrabber
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource as contextStringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Yakuyomi 擷取漫畫畫面內容（批 B：截圖界面重構）。
 *
 * 版面：WebView **鋪滿全螢幕**（底層），工具列改成**浮動 overlay**（半透明底、疊在 WebView 上、不擠壓）。
 * 因截圖走 [captureWebView] 的 PixelCopy（只抓 WebView 的渲染 surface），浮動的 Compose 工具列**不會進截圖**
 * → 截得到完整一頁（不再被 topBar/bottomBar 切掉視野）。
 *
 * 頂部浮動 bar：返回 + 網址列（X 清除 + 可展開歷史）+ 書名/章名（正常模式；[singleShotMode] 隱藏）。
 * 底部浮動 bar：正常模式「截這頁 / 連續截圖 / 停止」；重截/插入模式「取代/插入第 N 頁」+ 取消。
 * 「收起工具列」小鈕：收起後只剩 WebView + 該鈕（看漫畫/翻頁乾淨；截圖本就不含 overlay，收起純為視覺清爽）。
 */
@Composable
fun CaptureScreenContent(
    onNavigateUp: () -> Unit,
    initialUrl: String,
    bookName: String,
    onBookNameChange: (String) -> Unit,
    chapterName: String,
    onChapterNameChange: (String) -> Unit,
    onCapture: suspend (android.graphics.Bitmap, String?) -> CaptureSaveResult,
    continuousRunning: Boolean,
    capturedCount: Int,
    onStartContinuous: (FrameGrabber, () -> String?) -> Unit,
    onStopContinuous: () -> Unit,
    // 停止時讀本次 session 截下的頁碼，帶進確認頁供「放棄這次截圖」只刪這批。
    sessionPages: () -> List<Int> = { emptyList() },
    // 非 null＝重截模式：隱藏書名/章名輸入與連續擷取，「截這頁」改成覆蓋第 N 頁、成功後 [onReCaptureDone]。
    reCaptureTargetPage: Int? = null,
    // 非 null＝插入模式：同樣隱藏書名/章名與連續，「截這頁」改成「插入為第 X 頁」、成功後 [onReCaptureDone]。
    insertTargetPage: Int? = null,
    onReCaptureDone: () -> Unit = {},
    // 網址列輸入歷史（帶出歷史清單 + 逐筆刪除 + 造訪時記錄）。
    urlHistoryProvider: () -> List<String> = { emptyList() },
    onAddUrl: (String) -> Unit = {},
    onRemoveUrl: (String) -> Unit = {},
) {
    val reCaptureMode = reCaptureTargetPage != null
    val insertMode = insertTargetPage != null
    // 單張目標模式（重截 / 插入）：隱藏書名/章名輸入與連續擷取，只留單一擷取鈕、成功後退回。
    val singleShotMode = reCaptureMode || insertMode
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screenNavigator = LocalNavigator.currentOrThrow

    val navigator = remember { WebViewNavigator(scope) }
    val state = remember { WebViewState(WebContent.Url(initialUrl.ifBlank { "about:blank" })) }
    // 抓 onCreated 給的原生 WebView：截圖 / 手動載址都要它。
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(initialUrl) }

    // 工具列收起/展開（收起＝只剩 WebView + 展開小鈕）；歷史清單展開與否。
    var toolbarExpanded by remember { mutableStateOf(true) }
    var historyExpanded by remember { mutableStateOf(false) }
    // 歷史清單在畫面內管理：初值來自 pref，刪除即時反映 UI 並同步寫回 pref；展開時再重讀（納入剛造訪的網址）。
    var history by remember { mutableStateOf(urlHistoryProvider()) }

    // 網址列上的當前網址（隨 WebView 導覽同步）；造訪時記錄進歷史 pref。
    val webClient = remember {
        object : AccompanistWebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    address = it
                    onAddUrl(it)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                url?.let { address = it }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return false
                if (target.startsWith("intent://")) return true
                if ((target.startsWith("http") || target.startsWith("https")) && target != view?.url) {
                    view?.loadUrl(target)
                    return true
                }
                return false
            }
        }
    }

    fun go(urlOverride: String? = null) {
        val trimmed = (urlOverride ?: address).trim()
        if (trimmed.isEmpty()) return
        val normalized = if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
        address = normalized
        historyExpanded = false
        onAddUrl(normalized)
        webView?.loadUrl(normalized)
    }

    fun capture() {
        val window = context.findActivity()?.window
        // WebView 網址須在主執行緒讀；captureWebView 回呼在主執行緒，這裡先取好再帶進存檔。
        val url = webView?.url
        captureWebView(webView, window) { bitmap ->
            if (bitmap == null) {
                context.toast(context.contextStringResource(MR.strings.webview_capture_failed))
                return@captureWebView
            }
            scope.launch {
                when (val result = onCapture(bitmap, url)) {
                    is CaptureSaveResult.Saved -> {
                        when {
                            reCaptureMode -> {
                                context.toast(
                                    context.contextStringResource(MR.strings.capture_recapture_saved, result.page),
                                )
                                onReCaptureDone()
                            }
                            insertMode -> {
                                context.toast(
                                    context.contextStringResource(MR.strings.capture_insert_saved, result.page),
                                )
                                onReCaptureDone()
                            }
                            else ->
                                context.toast(context.contextStringResource(MR.strings.capture_saved, result.page))
                        }
                    }
                    CaptureSaveResult.MissingName ->
                        context.toast(context.contextStringResource(MR.strings.capture_missing_name))
                    is CaptureSaveResult.Failed ->
                        context.toast(result.message ?: context.contextStringResource(MR.strings.webview_capture_failed))
                }
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    // 連續截圖 toggle：進行中→停止（停止後跳確認頁檢視/剔除/儲存這次的截圖）；
    // 否則檢查書名/章名後把「抓幀器」交給 ScreenModel 驅動迴圈。
    // 只有使用者「按停止」才跳確認頁；生命週期 ON_STOP / onDispose 直接呼叫 onStopContinuous、不跳。
    fun toggleContinuous() {
        if (continuousRunning) {
            onStopContinuous()
            screenNavigator.push(
                CaptureReviewScreen(bookName.trim(), chapterName.trim(), sessionPages = sessionPages()),
            )
            return
        }
        if (bookName.isBlank() || chapterName.isBlank()) {
            context.toast(context.contextStringResource(MR.strings.capture_missing_name))
            return
        }
        val window = context.findActivity()?.window
        val grabber: FrameGrabber = { onResult -> captureWebView(webView, window, onResult) }
        onStartContinuous(grabber) { webView?.url }
    }

    // 生命週期：畫面離開（onDispose）或 app 進背景（ON_STOP）都停止連續截圖，避免背景空轉抓幀。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onStopContinuous()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onStopContinuous()
        }
    }

    // WebView 內還能上一頁時，系統返回＝WebView 上一頁（而非直接關畫面）。
    BackHandler(enabled = navigator.canGoBack) { navigator.navigateBack() }

    // 浮動 bar 半透明底：讓文字可讀又不完全擋住 WebView。
    val barColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)

    Box(modifier = Modifier.fillMaxSize()) {
        // 底層：WebView 鋪滿全螢幕（不被工具列擠壓 → 截到完整一頁）。
        WebView(
            state = state,
            modifier = Modifier.fillMaxSize(),
            navigator = navigator,
            onCreated = { wv ->
                wv.setDefaultSettings()
                webView = wv
            },
            client = webClient,
        )

        if (toolbarExpanded) {
            // 頂部浮動 bar：返回 + 網址列（X 清除 + 歷史）+ 書名/章名（正常模式）+ 收起鈕。
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Surface(
                    color = barColor,
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 3.dp,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onNavigateUp) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(MR.strings.action_close),
                                )
                            }
                            OutlinedTextField(
                                value = address,
                                onValueChange = { address = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text(stringResource(MR.strings.open_url_in_webview_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go,
                                ),
                                keyboardActions = KeyboardActions(onGo = { go() }),
                                trailingIcon = {
                                    if (address.isNotEmpty()) {
                                        IconButton(onClick = { address = "" }) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription = stringResource(MR.strings.action_clear),
                                            )
                                        }
                                    }
                                },
                            )
                            // 歷史下拉 toggle（有歷史才顯示）；展開時重讀 pref 納入剛造訪的網址。
                            if (history.isNotEmpty() || historyExpanded) {
                                IconButton(
                                    onClick = {
                                        if (!historyExpanded) history = urlHistoryProvider()
                                        historyExpanded = !historyExpanded
                                    },
                                ) {
                                    Icon(
                                        imageVector = if (historyExpanded) {
                                            Icons.Outlined.ExpandLess
                                        } else {
                                            Icons.Outlined.History
                                        },
                                        contentDescription = stringResource(MR.strings.capture_url_history),
                                    )
                                }
                            }
                            IconButton(onClick = { toolbarExpanded = false }) {
                                Icon(
                                    imageVector = Icons.Outlined.UnfoldLess,
                                    contentDescription = stringResource(MR.strings.capture_toolbar_hide),
                                )
                            }
                        }

                        // 網址輸入歷史清單：點列＝填入並載入、每筆叉叉＝刪除。
                        if (historyExpanded && history.isNotEmpty()) {
                            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                                items(items = history, key = { it }) { url ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { go(urlOverride = url) },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = url,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
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

                        // 重截 / 插入模式不需要書名/章名輸入（目標頁已鎖定），隱藏之。
                        if (!singleShotMode) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = bookName,
                                    onValueChange = onBookNameChange,
                                    modifier = Modifier.weight(1f),
                                    label = { Text(stringResource(MR.strings.capture_book_name)) },
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = chapterName,
                                    onValueChange = onChapterNameChange,
                                    modifier = Modifier.weight(1f),
                                    label = { Text(stringResource(MR.strings.capture_chapter_name)) },
                                    singleLine = true,
                                )
                            }
                        }
                    }
                }
            }

            // 底部浮動 bar：擷取動作。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(8.dp),
            ) {
                Surface(
                    color = barColor,
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (reCaptureMode) {
                            // 重截模式：只有「截這頁（取代第 N 頁）」，捲到對的地方按 → 覆蓋該頁 + 回確認頁。
                            Button(onClick = { capture() }) {
                                Icon(imageVector = Icons.Outlined.PhotoCamera, contentDescription = null)
                                Text(
                                    text = stringResource(
                                        MR.strings.capture_recapture_action,
                                        reCaptureTargetPage ?: 0,
                                    ),
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                            TextButton(onClick = onNavigateUp) {
                                Text(text = stringResource(MR.strings.action_cancel))
                            }
                        } else if (insertMode) {
                            // 插入模式：只有「截這頁（插入為第 X 頁）」，捲到要補的頁按 → 騰位插入 + 回確認頁。
                            Button(onClick = { capture() }) {
                                Icon(imageVector = Icons.Outlined.PhotoCamera, contentDescription = null)
                                Text(
                                    text = stringResource(
                                        MR.strings.capture_insert_action,
                                        insertTargetPage ?: 0,
                                    ),
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                            TextButton(onClick = onNavigateUp) {
                                Text(text = stringResource(MR.strings.action_cancel))
                            }
                        } else if (continuousRunning) {
                            // 連續進行中：紅色「停止」+ 進度「已截 N 頁」。
                            Button(
                                onClick = { toggleContinuous() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) {
                                Icon(imageVector = Icons.Filled.Stop, contentDescription = null)
                                Text(
                                    text = stringResource(MR.strings.capture_continuous_stop),
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                            Text(
                                text = stringResource(MR.strings.capture_continuous_count, capturedCount),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            // 閒置：主要「截這頁」+ 次要「連續擷取」並列。
                            Button(onClick = { capture() }) {
                                Icon(imageVector = Icons.Outlined.PhotoCamera, contentDescription = null)
                                Text(
                                    text = stringResource(MR.strings.action_capture_page),
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                            FilledTonalButton(onClick = { toggleContinuous() }) {
                                Icon(imageVector = Icons.Outlined.Autorenew, contentDescription = null)
                                Text(
                                    text = stringResource(MR.strings.capture_continuous_start),
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // 收起：只剩 WebView + 一顆「展開工具列」浮動小鈕。
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
                color = barColor,
                shape = CircleShape,
                shadowElevation = 3.dp,
            ) {
                IconButton(onClick = { toolbarExpanded = true }) {
                    Icon(
                        imageVector = Icons.Outlined.UnfoldMore,
                        contentDescription = stringResource(MR.strings.capture_toolbar_show),
                    )
                }
            }
        }
    }
}
