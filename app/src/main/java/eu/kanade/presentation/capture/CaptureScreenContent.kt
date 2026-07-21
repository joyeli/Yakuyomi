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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.WebContent
import com.kevinnzou.web.WebView
import com.kevinnzou.web.WebViewNavigator
import com.kevinnzou.web.WebViewState
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.webview.captureWebView
import eu.kanade.presentation.webview.findActivity
import eu.kanade.tachiyomi.ui.capture.CaptureSaveResult
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
    onCapture: suspend (android.graphics.Bitmap) -> CaptureSaveResult,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
        captureWebView(webView, window) { bitmap ->
            if (bitmap == null) {
                context.toast(context.contextStringResource(MR.strings.webview_capture_failed))
                return@captureWebView
            }
            scope.launch {
                when (val result = onCapture(bitmap)) {
                    is CaptureSaveResult.Saved ->
                        context.toast(context.contextStringResource(MR.strings.capture_saved, result.page))
                    CaptureSaveResult.MissingName ->
                        context.toast(context.contextStringResource(MR.strings.capture_missing_name))
                    is CaptureSaveResult.Failed ->
                        context.toast(result.message ?: context.contextStringResource(MR.strings.webview_capture_failed))
                }
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    // WebView 內還能上一頁時，系統返回＝WebView 上一頁（而非直接關畫面）。
    BackHandler(enabled = navigator.canGoBack) { navigator.navigateBack() }

    Scaffold(
        topBar = {
            Column {
                AppBar(
                    title = stringResource(MR.strings.capture_manga),
                    navigateUp = onNavigateUp,
                    navigationIcon = Icons.Outlined.Close,
                )
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
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        modifier = Modifier.weight(1f),
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
                    Button(onClick = { capture() }) {
                        Icon(imageVector = Icons.Outlined.PhotoCamera, contentDescription = null)
                        Text(
                            text = stringResource(MR.strings.action_capture_page),
                            modifier = Modifier.padding(start = 6.dp),
                        )
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
