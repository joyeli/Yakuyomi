package eu.kanade.presentation.capture

import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import eu.kanade.presentation.components.AppBar
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
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Yakuyomi 擷取漫畫（B1a-1 骨架）畫面內容。
 *
 * 重用 A 的通用 WebView 能力（底層 [com.kevinnzou.web.WebView] + [setDefaultSettings]）：頂部書名 / 章名輸入、
 * 底部網址列 + 「截這頁」。截圖走 B0 抽出的 [captureWebView]，存檔交給 [onCapture]（ScreenModel）。
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

    // 網址列上的當前網址（隨 WebView 導覽同步）。
    val webClient = remember {
        object : AccompanistWebViewClient() {
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

    fun go() {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return
        val normalized = if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
        address = normalized
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

    Scaffold(
        topBar = {
            Column {
                AppBar(
                    title = stringResource(
                        when {
                            reCaptureMode -> MR.strings.capture_recapture_title
                            insertMode -> MR.strings.capture_insert_title
                            else -> MR.strings.capture_manga
                        },
                    ),
                    navigateUp = onNavigateUp,
                    navigationIcon = Icons.Outlined.Close,
                )
                // 重截 / 插入模式不需要書名/章名輸入（目標頁已鎖定），隱藏之。
                if (!singleShotMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
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
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(MR.strings.open_url_in_webview_label)) },
                        placeholder = { Text(stringResource(MR.strings.open_url_in_webview_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { go() }),
                        trailingIcon = {
                            IconButton(onClick = { go() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = stringResource(MR.strings.capture_go),
                                )
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
        },
    ) { contentPadding ->
        WebView(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            navigator = navigator,
            onCreated = { wv ->
                wv.setDefaultSettings()
                webView = wv
            },
            client = webClient,
        )
    }
}
