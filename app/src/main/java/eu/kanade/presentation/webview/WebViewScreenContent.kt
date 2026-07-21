package eu.kanade.presentation.webview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.PixelCopy
import android.view.Window
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.stack.mutableStateStackOf
import com.kevinnzou.web.AccompanistWebChromeClient
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.LoadingState
import com.kevinnzou.web.WebContent
import com.kevinnzou.web.WebView
import com.kevinnzou.web.WebViewNavigator
import com.kevinnzou.web.WebViewState
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.getHtml
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.i18n.stringResource as contextStringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WebViewWindow(webContent: WebContent, val navigator: WebViewNavigator) {
    var state by mutableStateOf(WebViewState(webContent))
    var popupMessage: Message? = null
        private set
    var webView: WebView? = null

    constructor(popupMessage: Message, navigator: WebViewNavigator) : this(WebContent.NavigatorOnly, navigator) {
        this.popupMessage = popupMessage
    }
}

@Composable
fun WebViewScreenContent(
    onNavigateUp: () -> Unit,
    initialTitle: String?,
    url: String,
    onShare: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onClearCookies: (String) -> Unit,
    headers: Map<String, String> = emptyMap(),
    onUrlChange: (String) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()

    val windowStack = remember {
        mutableStateStackOf(
            WebViewWindow(
                WebContent.Url(url = url, additionalHttpHeaders = headers),
                WebViewNavigator(coroutineScope),
            ),
        )
    }

    val currentWindow = windowStack.lastItemOrNull!!
    val navigator = currentWindow.navigator

    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentUrl by remember { mutableStateOf(url) }
    var showCloudflareHelp by remember { mutableStateOf(false) }
    var isActive by remember { mutableStateOf(true) }
    // Yakuyomi B0 spike：截圖預覽結果（bitmap + 已存檔）；非 null 時彈預覽對話框。
    var captureResult by remember { mutableStateOf<CaptureResult?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            isActive = false
            // Yakuyomi：離開 WebView 時把 session cookie 寫盤（全 repo 唯一漏掉 flush → 行程被殺前
            // 未持久化的登入 cookie 遺失＝「每次都要重登入」的根因）。只讓 cookie 更可靠保存、不改行為。
            CookieManager.getInstance().flush()
        }
    }

    val webClient = remember {
        object : AccompanistWebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    currentUrl = it
                    onUrlChange(it)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                scope.launch {
                    val html = view.getHtml()
                    showCloudflareHelp = "window._cf_chl_opt" in html || "Ray ID is" in html
                }
            }

            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String?,
                isReload: Boolean,
            ) {
                super.doUpdateVisitedHistory(view, url, isReload)
                url?.let {
                    currentUrl = it
                    onUrlChange(it)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // Ignore intents urls
                if (url.startsWith("intent://")) return true

                // Only open valid web urls
                if (url.startsWith("http") || url.startsWith("https")) {
                    if (url != view?.url) {
                        view?.loadUrl(url, headers)
                        return true
                    }
                }

                return false
            }
        }
    }

    val webChromeClient = remember {
        object : AccompanistWebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                // if it wasn't initiated by a user gesture, we should ignore it like a normal browser would
                if (isUserGesture) {
                    windowStack.push(WebViewWindow(resultMsg, WebViewNavigator(coroutineScope)))
                    return true
                }
                return false
            }

            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                if (!isActive) {
                    result.confirm()
                    return true
                }
                return super.onJsAlert(view, url, message, result)
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                if (!isActive) {
                    result.cancel()
                    return true
                }
                return super.onJsConfirm(view, url, message, result)
            }

            override fun onJsPrompt(
                view: WebView,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult,
            ): Boolean {
                if (!isActive) {
                    result.cancel()
                    return true
                }
                return super.onJsPrompt(view, url, message, defaultValue, result)
            }
        }
    }

    fun initializePopup(webView: WebView, message: Message): WebView {
        val transport = message.obj as WebView.WebViewTransport
        transport.webView = webView
        message.sendToTarget()
        return webView
    }

    // Yakuyomi B0 spike：截當前 WebView → 存檔 → 彈預覽對話框。首選 PixelCopy（抓合成後像素、含硬體加速
    // 層），失敗退回 webView.draw(Canvas)。截到全黑/空白＝該來源（canvas/DRM）截不到——這正是要驗的。
    fun captureCurrentPage() {
        val webView = currentWindow.webView
        val window = context.findActivity()?.window
        captureWebView(webView, window) { bitmap ->
            if (bitmap == null) {
                context.toast(context.contextStringResource(MR.strings.webview_capture_failed))
                return@captureWebView
            }
            scope.launch {
                val file = withContext(Dispatchers.IO) {
                    runCatching { saveCapture(context, bitmap) }.getOrNull()
                }
                file?.let { context.toast(it.absolutePath) }
                captureResult = CaptureResult(bitmap, file)
            }
        }
    }

    val popState = remember<() -> Unit> {
        {
            if (windowStack.size == 1) {
                onNavigateUp()
            } else {
                windowStack.pop()
            }
        }
    }

    BackHandler(windowStack.size > 1, popState)

    Scaffold(
        topBar = {
            Box {
                Column {
                    AppBar(
                        title = currentWindow.state.pageTitle ?: initialTitle,
                        subtitle = currentUrl,
                        navigateUp = onNavigateUp,
                        navigationIcon = Icons.Outlined.Close,
                        actions = {
                            AppBarActions(
                                listOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_webview_back),
                                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                        onClick = {
                                            if (navigator.canGoBack) {
                                                navigator.navigateBack()
                                            }
                                        },
                                        enabled = navigator.canGoBack,
                                    ),
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_webview_forward),
                                        icon = Icons.AutoMirrored.Outlined.ArrowForward,
                                        onClick = {
                                            if (navigator.canGoForward) {
                                                navigator.navigateForward()
                                            }
                                        },
                                        enabled = navigator.canGoForward,
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_webview_refresh),
                                        onClick = { navigator.reload() },
                                    ),
                                    // Yakuyomi B0 spike：截這頁（驗 WebView 截得到圖還是空白）
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_capture_page),
                                        onClick = { captureCurrentPage() },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_share),
                                        onClick = { onShare(currentUrl) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_open_in_browser),
                                        onClick = { onOpenInBrowser(currentUrl) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.pref_clear_cookies),
                                        onClick = { onClearCookies(currentUrl) },
                                    ),
                                ).toMutableList().apply {
                                    if (windowStack.size > 1) {
                                        add(
                                            0,
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_webview_close_tab),
                                                icon = ImageVector.vectorResource(R.drawable.ic_tab_close_24px),
                                                onClick = popState,
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                    )

                    if (showCloudflareHelp) {
                        Surface(
                            modifier = Modifier.padding(8.dp),
                        ) {
                            WarningBanner(
                                textRes = MR.strings.information_cloudflare_help,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        uriHandler.openUri(
                                            "https://mihon.app/docs/guides/troubleshooting/#cloudflare",
                                        )
                                    },
                            )
                        }
                    }
                }
                when (val loadingState = currentWindow.state.loadingState) {
                    is LoadingState.Initializing -> LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                    is LoadingState.Loading -> LinearProgressIndicator(
                        progress = { loadingState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                    else -> {}
                }
            }
        },
    ) { contentPadding ->
        // We need to key the WebView composable to the window object since simply updating the WebView composable will
        // not cause it to re-invoke the WebView factory and render the new current window's WebView. This lets us
        // completely reset the WebView composable when the current window switches.
        key(currentWindow) {
            WebView(
                state = currentWindow.state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                navigator = navigator,
                onCreated = { webView ->
                    webView.setDefaultSettings()

                    // Debug mode (chrome://inspect/#devices)
                    if (BuildConfig.DEBUG &&
                        0 != webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE
                    ) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }

                    headers["user-agent"]?.let {
                        webView.settings.userAgentString = it
                    }
                },
                onDispose = { webView ->
                    val window = windowStack.items.find { it.webView == webView }
                    if (window == null) {
                        // If we couldn't find any window on the stack that owns this WebView, it means that we can
                        // safely dispose of it because the window containing it has been closed.
                        webView.destroy()
                    } else {
                        // The composable is being disposed but the WebView object is not.
                        // When the WebView element is recomposed, we will want the WebView to resume from its state
                        // before it was unmounted, we won't want it to reset back to its original target.
                        window.state.content = WebContent.NavigatorOnly
                    }
                },
                client = webClient,
                chromeClient = webChromeClient,
                factory = { context ->
                    currentWindow.webView
                        ?: WebView(context).also { webView ->
                            currentWindow.webView = webView
                            currentWindow.popupMessage?.let {
                                initializePopup(webView, it)
                            }
                        }
                },
            )
        }
    }

    // Yakuyomi B0 spike：截圖預覽對話框——顯示截到的 bitmap（限高可捲）+ 分享 / 關閉。
    captureResult?.let { result ->
        val dismiss: () -> Unit = {
            captureResult = null
            if (!result.bitmap.isRecycled) result.bitmap.recycle()
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(MR.strings.webview_capture_dialog_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Image(
                        bitmap = result.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        result.file?.let { file ->
                            try {
                                context.startActivity(
                                    file.getUriCompat(context).toShareIntent(context, type = "image/png"),
                                )
                            } catch (e: Exception) {
                                context.toast(e.message)
                            }
                        }
                    },
                    enabled = result.file != null,
                ) {
                    Text(stringResource(MR.strings.action_share))
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss) {
                    Text(stringResource(MR.strings.action_close))
                }
            },
        )
    }
}

/**
 * Yakuyomi B0 spike：截圖結果快照（供預覽對話框）。
 */
private class CaptureResult(val bitmap: Bitmap, val file: File?)

/**
 * Yakuyomi B0 spike：從 Compose 的 [Context] 往上找 [Activity]（PixelCopy 需要 Activity 的 window）。
 */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Yakuyomi B0 spike：截 WebView 內容。首選 [PixelCopy]（抓合成後的實際像素、含硬體加速層），
 * 失敗或拿不到 window 時退回 [WebView.draw]。回呼一律在主執行緒；截不到回傳 null。
 */
private fun captureWebView(
    webView: WebView?,
    window: Window?,
    onResult: (Bitmap?) -> Unit,
) {
    if (webView == null || webView.width <= 0 || webView.height <= 0) {
        onResult(null)
        return
    }
    val width = webView.width
    val height = webView.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    fun drawFallback(): Bitmap? = try {
        webView.draw(Canvas(bitmap))
        bitmap
    } catch (e: Throwable) {
        if (!bitmap.isRecycled) bitmap.recycle()
        null
    }

    if (window != null) {
        val location = IntArray(2)
        webView.getLocationInWindow(location)
        val srcRect = Rect(
            location[0],
            location[1],
            location[0] + width,
            location[1] + height,
        )
        try {
            PixelCopy.request(
                window,
                srcRect,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        onResult(bitmap)
                    } else {
                        onResult(drawFallback())
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            return
        } catch (e: Throwable) {
            // PixelCopy 對沒有 backing surface 的 window 會丟 IllegalArgumentException
            onResult(drawFallback())
            return
        }
    }
    onResult(drawFallback())
}

/**
 * Yakuyomi B0 spike：把截圖存到 getExternalFilesDir("captures")（免權限、檔案管理看得到）下
 * capture_<yyyyMMdd_HHmmss>.png。外部儲存不可用時退到內部 filesDir/captures（兩者皆在 FileProvider 白名單）。
 */
private fun saveCapture(context: Context, bitmap: Bitmap): File {
    val dir = context.getExternalFilesDir("captures")
        ?: File(context.filesDir, "captures").also { it.mkdirs() }
    val name = "capture_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
    val file = File(dir, name)
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}
