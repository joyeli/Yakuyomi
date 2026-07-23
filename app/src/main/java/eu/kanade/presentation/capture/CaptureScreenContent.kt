package eu.kanade.presentation.capture

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.WebContent
import com.kevinnzou.web.WebView
import com.kevinnzou.web.WebViewNavigator
import com.kevinnzou.web.WebViewState
import eu.kanade.presentation.webview.captureWebView
import eu.kanade.presentation.webview.findActivity
import eu.kanade.tachiyomi.ui.capture.CaptureMode
import eu.kanade.tachiyomi.ui.capture.CaptureSaveResult
import eu.kanade.tachiyomi.ui.capture.FrameGrabber
import eu.kanade.tachiyomi.ui.capture.suggestCaptureChapterNames
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.core.common.i18n.stringResource as contextStringResource

/**
 * Yakuyomi 擷取漫畫畫面內容（階段 1：介面骨架重構）。
 *
 * 版面：WebView **鋪滿全螢幕**（底層），但 status bar / navigation bar 讓出（[statusBarsPadding] /
 * [navigationBarsPadding]），系統列不與畫面重疊；WebView 未載真網址時背景透出底層系統色（不露全白 html 畫布）。
 * 工具列改成**浮動 overlay**（半透明底、疊在 WebView 上、不擠壓）。
 *
 * 主工具列（頂部一條浮動 bar）＝**5 鍵**：返回 / 瀏覽 / 新漫畫 / 新話數 / 開始‧停止。
 * - **瀏覽**：toggle「瀏覽 panel」（網址列＋清除＋前往＋歷史＋上一頁/下一頁＋清除 Cookie）。
 * - **新漫畫**：toggle「新漫畫 panel」＝書名輸入 ＋「從網頁標題帶入」（讀 [WebView.getTitle] **原生屬性**，
 *   不注入 JS、不碰 DOM——本工具的護欄是「截像素」）；確定＝設定書名。封面框選 / 記網址留階段 3。
 * - **新話數**：toggle「新話數 panel」＝已截話數總覽（掃該書夾下的話夾）＋話數建議按鈕
 *   （[suggestCaptureChapterNames]）＋可手動編輯的輸入框；確定＝設定章名。
 * - **開始‧停止**：接現有連續截圖 [toggleContinuous]；書名/章名皆非空才可開始，連續中顯示紅色停止。
 * 重截 / 插入（[singleShotMode]）只留單張截圖鍵（底部浮動 bar），隱藏連續/新漫畫/新話數。
 *
 * ★ 漸進式解鎖（S0→S3）：5 鍵依「目前走到哪」逐一解鎖，沒到的鍵 disabled（M3 IconButton 自帶灰階弱化）——
 * - **S0**（WebView 還在 `about:blank`／空網址）：只有「瀏覽」（且沿用既有的自動展開引導）。
 * - **S1**（已有網址）：＋「新漫畫」。
 * - **S2**（書名非空）：＋「新話數」。
 * - **S3**（章名非空）：＋「開始」。
 * 「返回」永遠可用；[CaptureMode.REVIEW] / [CaptureMode.SINGLE_SHOT] 不受此解鎖影響（那兩個模式本就只露單張鍵）。
 *
 * 浮動元件配色一律走 `surfaceContainerHigh`＋`onSurface`（與 app 其他 bar 同色階，非純黑膠帶）；工具列收起後
 * 只留一個貼右緣的 32×40dp 小把手。連續擷取進行中，底部置中顯示「已截 N 頁 · 翻到下一頁繼續」引導。
 *
 * ★ 截圖零 overlay：截圖前把 [hideOverlayForCapture] 設 true → 等兩個 frame（隱藏工具列那次重繪畫上螢幕）
 * 才 [captureWebView]（PixelCopy 抓 WebView 區域的合成像素 → 此時區域內只剩 WebView、無任何浮動工具列）。
 * 存完再設回 false。
 *
 * ★ WebView 常駐（2026-07 重構）：確認 / 重截 / 插入不再是別的 Screen，而是本 composable 的 [mode]
 * （[CaptureMode]）。**WebView 永遠 render**（不被任何 if 分支丟掉），[CaptureMode.REVIEW] 時由
 * [reviewContent] 全屏蓋在它上面（同時把 WebView 那層的觸控在 Initial pass 吃掉，避免蓋著還能捲）。
 *
 * ★ 連續擷取零閃爍：偵測用的「比較幀」不隱藏工具列（靜態工具列前後幀都在、不影響 frame-diff），
 * 只有真的要存那一刻才用 cleanGrabber 隱藏 overlay 抓乾淨幀（見
 * [eu.kanade.tachiyomi.ui.capture.CaptureScreenModel.startContinuous]）。
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
    // 「新話數」panel 用：給書名 → 回該書已截的話夾名稱（總覽 + 話數建議的來源）。
    existingChaptersProvider: suspend (String) -> List<String> = { emptyList() },
    continuousRunning: Boolean,
    capturedCount: Int,
    // 目前模式：擷取 / 確認（面板蓋在 WebView 上）/ 單張重截或插入。
    mode: CaptureMode = CaptureMode.CAPTURING,
    // 剛存下的頁碼（非 null＝顯示「已擷取第 N 頁 · 請翻下一頁」提示，期間 model 暫停偵測）。
    justCapturedPage: Int? = null,
    // (比較幀抓取器, 乾淨幀抓取器, 網址讀取器)。
    onStartContinuous: (FrameGrabber, FrameGrabber, () -> String?) -> Unit,
    onStopContinuous: () -> Unit,
    // 按停止後進確認模式（不 push Screen）。
    onEnterReview: () -> Unit = {},
    // 非 null＝重截模式：隱藏書名/章名輸入與連續擷取，「截這頁」改成覆蓋第 N 頁、成功後 [onReCaptureDone]。
    reCaptureTargetPage: Int? = null,
    // 非 null＝插入模式：同樣隱藏書名/章名與連續，「截這頁」改成「插入為第 X 頁」、成功後 [onReCaptureDone]。
    insertTargetPage: Int? = null,
    // 單張模式要開回的網址；null/空＝該頁沒記網址 → **WebView 保持現狀不動**（不是每個站的網址都帶頁資訊）。
    singleShotUrl: String? = null,
    // 每次進單張模式遞增，讓上面的 loadUrl 重新觸發（同一頁重截兩次也算）。
    singleShotToken: Int = 0,
    onReCaptureDone: () -> Unit = {},
    // 單張模式取消（返回鍵 / 取消鈕）→ 回確認模式。
    onSingleShotCancel: () -> Unit = {},
    // 確認模式按系統返回＝「繼續擷取」（回擷取模式、不刪頁）。
    onReviewContinue: () -> Unit = {},
    // 確認模式的面板內容（疊在常駐 WebView 上）。
    reviewContent: @Composable () -> Unit = {},
    // 網址列輸入歷史（帶出歷史清單 + 逐筆刪除 + 造訪時記錄）。
    urlHistoryProvider: () -> List<String> = { emptyList() },
    onAddUrl: (String) -> Unit = {},
    onRemoveUrl: (String) -> Unit = {},
) {
    val reCaptureMode = reCaptureTargetPage != null
    val insertMode = insertTargetPage != null
    // 單張目標模式（重截 / 插入）：隱藏書名/章名輸入與連續擷取，只留單一擷取鈕、成功後退回。
    val singleShotMode = mode == CaptureMode.SINGLE_SHOT && (reCaptureMode || insertMode)
    val reviewMode = mode == CaptureMode.REVIEW
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val navigator = remember { WebViewNavigator(scope) }
    val state = remember { WebViewState(WebContent.Url(initialUrl.ifBlank { "about:blank" })) }
    // 抓 onCreated 給的原生 WebView：截圖 / 手動載址都要它。
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(initialUrl) }
    // WebView **實際載入**的網址（不是網址列草稿）：漸進式解鎖的 S1 判準，只由 WebViewClient 的導覽回呼更新
    // ——否則使用者在網址列打幾個字（address 一路變動）就會誤判成「已有網址」。
    var loadedUrl by remember { mutableStateOf(initialUrl) }

    // 工具列收起/展開（收起＝只剩 WebView + 展開小鈕）。
    var toolbarExpanded by remember { mutableStateOf(true) }
    // 全新入口（initialUrl 空）＝自動展開「瀏覽」panel 引導輸入網址；否則預設收合。
    var browseExpanded by remember { mutableStateOf(initialUrl.isBlank()) }
    // 「新漫畫」panel（書名輸入 + 從網頁標題帶入）展開與否。
    var mangaPanelExpanded by remember { mutableStateOf(false) }
    // 「新話數」panel（已截話數總覽 + 建議 + 章名輸入）展開與否。
    var chapterPanelExpanded by remember { mutableStateOf(false) }
    // 兩個 panel 的暫存輸入：按「確定」才寫回 model 的 bookName / chapterName（panel 開啟時同步當前值）。
    var bookDraft by remember { mutableStateOf(bookName) }
    var chapterDraft by remember { mutableStateOf(chapterName) }
    // 目前書名底下已截過的話夾名稱（開「新話數」panel 時掃一次）。
    var existingChapters by remember { mutableStateOf(emptyList<String>()) }
    // 歷史清單展開與否。
    var historyExpanded by remember { mutableStateOf(false) }
    // 歷史清單在畫面內管理：初值來自 pref，刪除即時反映 UI 並同步寫回 pref；展開時再重讀（納入剛造訪的網址）。
    var history by remember { mutableStateOf(urlHistoryProvider()) }
    // ★ 截圖當下把所有浮動 overlay 隱藏，避免進到 PixelCopy 的截圖裡。
    var hideOverlayForCapture by remember { mutableStateOf(false) }
    // 清除 Cookie 確認對話框（防誤觸）。
    var showClearCookiesDialog by remember { mutableStateOf(false) }

    // 網址列上的當前網址（隨 WebView 導覽同步）；造訪時記錄進歷史 pref。
    val webClient = remember {
        object : AccompanistWebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    address = it
                    loadedUrl = it
                    onAddUrl(it)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                url?.let {
                    address = it
                    loadedUrl = it
                }
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
        // 載入後收起瀏覽 panel（回到乾淨看漫畫視野）。
        browseExpanded = false
        onAddUrl(normalized)
        webView?.loadUrl(normalized)
    }

    fun capture() {
        scope.launch {
            // ★ 先隱藏所有浮動 overlay，等兩個 frame 讓「隱藏」那次重繪畫上螢幕，PixelCopy 才不會抓到工具列。
            hideOverlayForCapture = true
            withFrameNanos {}
            withFrameNanos {}
            val window = context.findActivity()?.window
            // WebView 網址須在主執行緒讀；captureWebView 回呼在主執行緒，這裡先取好再帶進存檔。
            val url = webView?.url
            captureWebView(webView, window) { bitmap ->
                // 拿到（含失敗的 null）像素後即可還原 overlay，存檔在背景進行。
                hideOverlayForCapture = false
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
                            context.toast(
                                result.message ?: context.contextStringResource(MR.strings.webview_capture_failed),
                            )
                    }
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }
    }

    // 連續截圖 toggle：進行中→停止（停止後切確認模式檢視/剔除/儲存這次的截圖，WebView 原地留著）；
    // 否則檢查書名/章名後把兩個「抓幀器」交給 ScreenModel 驅動迴圈。
    // 只有使用者「按停止」才進確認模式；生命週期 ON_STOP / onDispose 直接呼叫 onStopContinuous、不切模式。
    fun toggleContinuous() {
        if (continuousRunning) {
            onStopContinuous()
            onEnterReview()
            return
        }
        if (bookName.isBlank() || chapterName.isBlank()) {
            context.toast(context.contextStringResource(MR.strings.capture_missing_name))
            return
        }
        val window = context.findActivity()?.window
        // ① 比較幀抓取器：**不隱藏 overlay**。工具列是靜態的、前後幀都有它 → 不影響 frame-diff，
        // 也就不必每 500ms 閃一次工具列（舊做法的閃爍來源）。
        val compareGrabber: FrameGrabber = { onResult ->
            captureWebView(webView, window) { bmp -> onResult(bmp) }
        }
        // ② 乾淨幀抓取器：只有真的要存那一刻才用——隱藏 overlay、等兩 frame（讓隱藏那次重繪上螢幕）、
        // 截圖、還原 → 落地的截圖零工具列（護欄不破）。
        val cleanGrabber: FrameGrabber = { onResult ->
            scope.launch {
                hideOverlayForCapture = true
                withFrameNanos {}
                withFrameNanos {}
                captureWebView(webView, window) { bmp ->
                    hideOverlayForCapture = false
                    onResult(bmp)
                }
            }
        }
        onStartContinuous(compareGrabber, cleanGrabber) { webView?.url }
    }

    // 進單張模式（重截 / 插入）：該頁有記網址才開回去；沒有就**保持 WebView 現狀**（讓使用者自己捲到位）。
    LaunchedEffect(singleShotToken) {
        if (mode == CaptureMode.SINGLE_SHOT && !singleShotUrl.isNullOrBlank()) {
            address = singleShotUrl
            webView?.loadUrl(singleShotUrl)
        }
    }

    // ── 漸進式解鎖 S0→S3（見檔頭 KDoc）─────────────────────────────────────────
    // S1 條件＝WebView 已載入真網址（[loadedUrl]＝initialUrl / WebViewClient 導覽回呼，非網址列草稿）。
    val hasUrl = loadedUrl.isNotBlank() && loadedUrl.trim() != "about:blank"
    val canNewManga = hasUrl // S1 → 解鎖「新漫畫」
    val canNewChapter = canNewManga && bookName.isNotBlank() // S2 → 解鎖「新話數」
    val canStart = canNewChapter && chapterName.isNotBlank() // S3 → 解鎖「開始」

    // 條件退回（例如把書名清空）時收起對應 panel，免得停在一個已按不到的面板上。
    LaunchedEffect(canNewManga, canNewChapter) {
        if (!canNewManga) mangaPanelExpanded = false
        if (!canNewChapter) chapterPanelExpanded = false
    }

    // 開「新漫畫」panel ＝草稿同步當前書名。
    LaunchedEffect(mangaPanelExpanded) {
        if (mangaPanelExpanded) bookDraft = bookName
    }

    // 開「新話數」panel（或書名換了）＝掃該書已截的話夾；章名還空就用第一個建議話數預填草稿。
    LaunchedEffect(chapterPanelExpanded, bookName) {
        if (!chapterPanelExpanded) return@LaunchedEffect
        existingChapters = existingChaptersProvider(bookName)
        chapterDraft = chapterName.ifBlank {
            suggestCaptureChapterNames(existingChapters).firstOrNull().orEmpty()
        }
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

    // 系統返回：確認模式＝回擷取模式（等同「繼續擷取」，不刪任何頁）；單張模式＝取消回確認模式；
    // 擷取模式下 WebView 還能上一頁＝WebView 上一頁（而非直接關畫面）。
    BackHandler(enabled = reviewMode || singleShotMode || navigator.canGoBack) {
        when {
            reviewMode -> onReviewContinue()
            singleShotMode -> onSingleShotCancel()
            else -> navigator.navigateBack()
        }
    }

    // 浮動 bar 半透明底：讓文字可讀又不完全擋住 WebView。
    // 用 surfaceContainerHigh（＝app 內其他 bar／對話框的抬升面色）而非 surface——深色主題下 surface 幾乎純黑、
    // 浮在 WebView 上像一塊黑膠帶；surfaceContainerHigh 與全域介面同一套色階，風格一致又看得出是工具列。
    val barColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
    // 明確指定內容色：Surface 的預設 contentColor 走 contentColorFor(color)，帶 alpha 的顏色比對不到色票
    // → 退回 LocalContentColor（可能是純黑），icon/文字會失色；直接給 onSurface 最穩。
    val barContentColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 底層系統色：WebView 未載真網址（about:blank 全白）時不露白，風格與 app 一致。
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 底層：WebView 佔滿 status/navigation bar 之間（不涵蓋系統列 → 系統文字不與畫面重疊、截圖也不含系統列）。
        // ★ 這個 Box 及其中的 WebView **永遠 render**（不進任何 if 分支）——確認 / 重截 / 插入只是疊模式，
        // WebView 不重建 ⇒ 捲動位置 / 登入 / JS 狀態全保留。確認模式時在 Initial pass 吃掉觸控，
        // 免得被面板蓋住還能捲動底下的網頁。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .pointerInput(reviewMode) {
                    if (!reviewMode) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            WebView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                navigator = navigator,
                onCreated = { wv ->
                    wv.setDefaultSettings()
                    // 透明背景：未載入頁面時讓底層系統色透出（不是全白 html 畫布）。
                    wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webView = wv
                },
                client = webClient,
            )
        }

        // ★ 截圖進行中：不 render 任何浮動 overlay（頂部 bar / 底部 bar / 收起小鈕 / panel / 對話框）。
        // 確認模式時整片被面板蓋住，浮動工具列一併不畫（免壓在面板下方漏出來）。
        if (!hideOverlayForCapture && !reviewMode) {
            if (toolbarExpanded) {
                // 頂部浮動工具列（5 鍵）+ 可展開的瀏覽 / 新話數 panel。
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(8.dp),
                ) {
                    Surface(
                        color = barColor,
                        contentColor = barContentColor,
                        shape = MaterialTheme.shapes.large,
                        shadowElevation = 3.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 1) 返回（單張模式＝取消回確認面板，其餘＝關掉整個擷取畫面）
                            IconButton(onClick = { if (singleShotMode) onSingleShotCancel() else onNavigateUp() }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(MR.strings.action_close),
                                )
                            }
                            // 2) 瀏覽（永遠可用；toggle 瀏覽 panel，開時關掉其他 panel 免過高）
                            IconButton(
                                onClick = {
                                    browseExpanded = !browseExpanded
                                    if (browseExpanded) {
                                        mangaPanelExpanded = false
                                        chapterPanelExpanded = false
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Public,
                                    contentDescription = stringResource(MR.strings.capture_browse),
                                    tint = if (browseExpanded) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        LocalContentColor.current
                                    },
                                )
                            }
                            if (!singleShotMode) {
                                if (continuousRunning) {
                                    // 連續中：紅色停止 + 進度「已截 N 頁」（隱藏新漫畫/新話數，翻頁自動截）。
                                    IconButton(onClick = { toggleContinuous() }) {
                                        Icon(
                                            imageVector = Icons.Filled.Stop,
                                            contentDescription = stringResource(MR.strings.capture_continuous_stop),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    Text(
                                        text = stringResource(MR.strings.capture_continuous_count, capturedCount),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                } else {
                                    // 3) 新漫畫（S1：有網址才解鎖）：toggle 書名 panel
                                    IconButton(
                                        onClick = {
                                            mangaPanelExpanded = !mangaPanelExpanded
                                            if (mangaPanelExpanded) {
                                                browseExpanded = false
                                                chapterPanelExpanded = false
                                            }
                                        },
                                        enabled = canNewManga,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.CollectionsBookmark,
                                            contentDescription = stringResource(MR.strings.capture_new_manga),
                                            // disabled 時 IconButton 已把 LocalContentColor 換成灰階弱化色。
                                            tint = if (mangaPanelExpanded) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                LocalContentColor.current
                                            },
                                        )
                                    }
                                    // 4) 新話數（S2：書名非空才解鎖）：toggle 話數 panel
                                    IconButton(
                                        onClick = {
                                            chapterPanelExpanded = !chapterPanelExpanded
                                            if (chapterPanelExpanded) {
                                                browseExpanded = false
                                                mangaPanelExpanded = false
                                            }
                                        },
                                        enabled = canNewChapter,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Edit,
                                            contentDescription = stringResource(MR.strings.capture_new_chapter),
                                            tint = if (chapterPanelExpanded) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                LocalContentColor.current
                                            },
                                        )
                                    }
                                    // 5) 開始（S3：書名 + 章名皆非空才解鎖）
                                    IconButton(onClick = { toggleContinuous() }, enabled = canStart) {
                                        Icon(
                                            imageVector = Icons.Outlined.PlayArrow,
                                            contentDescription = stringResource(MR.strings.capture_continuous_start),
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            // 收起工具列（清爽看漫畫；截圖本就不含 overlay，收起純為視覺）。
                            IconButton(onClick = { toolbarExpanded = false }) {
                                Icon(
                                    imageVector = Icons.Outlined.UnfoldLess,
                                    contentDescription = stringResource(MR.strings.capture_toolbar_hide),
                                )
                            }
                        }
                    }

                    // 瀏覽 panel：網址列（X 清除 + 前往）+ 上一頁/下一頁 + 歷史 + 清除 Cookie。
                    if (browseExpanded) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = barColor,
                            contentColor = barContentColor,
                            shape = MaterialTheme.shapes.large,
                            shadowElevation = 3.dp,
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
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
                                    // 前往（載入 go() + 收起 panel）
                                    IconButton(onClick = { go() }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                            contentDescription = stringResource(MR.strings.capture_go),
                                        )
                                    }
                                }

                                // 導覽列：上一頁 / 下一頁 / 歷史 toggle / 清除 Cookie。
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(
                                        onClick = { if (navigator.canGoBack) navigator.navigateBack() },
                                        enabled = navigator.canGoBack,
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                            contentDescription = stringResource(MR.strings.action_webview_back),
                                        )
                                    }
                                    IconButton(
                                        onClick = { if (navigator.canGoForward) navigator.navigateForward() },
                                        enabled = navigator.canGoForward,
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                            contentDescription = stringResource(MR.strings.action_webview_forward),
                                        )
                                    }
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
                                    Spacer(modifier = Modifier.weight(1f))
                                    IconButton(onClick = { showClearCookiesDialog = true }) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteSweep,
                                            contentDescription = stringResource(MR.strings.pref_clear_cookies),
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
                            }
                        }
                    }

                    // 新漫畫 panel（階段 2 簡易版＝書名 + 從網頁標題帶入）；重截/插入與連續進行中不顯示。
                    // 封面框選 / 記錄書籍網址 / 書櫃「繼續擷取」入口留階段 3。
                    if (mangaPanelExpanded && !singleShotMode && !continuousRunning) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = barColor,
                            contentColor = barContentColor,
                            shape = MaterialTheme.shapes.large,
                            shadowElevation = 3.dp,
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                                OutlinedTextField(
                                    value = bookDraft,
                                    onValueChange = { bookDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(MR.strings.capture_book_name)) },
                                    singleLine = true,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // ★ 讀 WebView 的原生 title 屬性（不注入 JS、不碰 DOM）→ 填進輸入框，
                                    // 讓使用者自己 trim/改（網頁標題常帶「- 第X話 - 站名」等雜訊）。
                                    TextButton(
                                        onClick = {
                                            val title = webView?.title?.trim().orEmpty()
                                            if (title.isEmpty() || title == "about:blank") {
                                                context.toast(
                                                    context.contextStringResource(
                                                        MR.strings.capture_title_unavailable,
                                                    ),
                                                )
                                            } else {
                                                bookDraft = title
                                            }
                                        },
                                    ) {
                                        Text(text = stringResource(MR.strings.capture_use_page_title))
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    Button(
                                        onClick = {
                                            val newBook = bookDraft.trim()
                                            // 換一本書＝原本的章名不再適用（避免誤截進 <新書>/<舊章名>）→ 清空，
                                            // 使用者接著走「新話數」panel（該書的已截話數/建議也才對得上）。
                                            if (newBook != bookName) onChapterNameChange("")
                                            onBookNameChange(newBook)
                                            mangaPanelExpanded = false
                                        },
                                        enabled = bookDraft.isNotBlank(),
                                    ) {
                                        Text(text = stringResource(MR.strings.action_ok))
                                    }
                                }
                            }
                        }
                    }

                    // 新話數 panel（階段 2 完整版＝已截話數總覽 + 話數建議 + 可手動編輯的章名）；
                    // 重截/插入與連續進行中不顯示。
                    if (chapterPanelExpanded && !singleShotMode && !continuousRunning) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = barColor,
                            contentColor = barContentColor,
                            shape = MaterialTheme.shapes.large,
                            shadowElevation = 3.dp,
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                                // ① 已截話數總覽：掃 <local>/<書名>/ 下的話夾名稱（讓使用者知道這本截過哪些話）。
                                Text(
                                    text = stringResource(MR.strings.capture_existing_chapters),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                if (existingChapters.isEmpty()) {
                                    Text(
                                        text = stringResource(MR.strings.capture_no_chapters),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    FlowRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 96.dp)
                                            .verticalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        existingChapters.forEach { name ->
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                shape = MaterialTheme.shapes.small,
                                            ) {
                                                Text(
                                                    text = name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                )
                                            }
                                        }
                                    }
                                }

                                // ② 話數建議：依「已截的最後一話」推（整數 → n+1 / +0.1 / +0.5；小數 → +0.1 / 下一個整數；
                                // 沒有已截話 → 01）。點一下填進輸入框，仍可手動改。
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(MR.strings.capture_chapter_suggestions),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    suggestCaptureChapterNames(existingChapters).forEach { suggestion ->
                                        OutlinedButton(onClick = { chapterDraft = suggestion }) {
                                            Text(text = suggestion)
                                        }
                                    }
                                }

                                // ③ 章名輸入（可直接手動編輯）+ 確定。
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = chapterDraft,
                                    onValueChange = { chapterDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(MR.strings.capture_chapter_name)) },
                                    singleLine = true,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    Button(
                                        onClick = {
                                            onChapterNameChange(chapterDraft.trim())
                                            chapterPanelExpanded = false
                                        },
                                        enabled = chapterDraft.isNotBlank(),
                                    ) {
                                        Text(text = stringResource(MR.strings.action_ok))
                                    }
                                }
                            }
                        }
                    }
                }

                // 底部浮動 bar：僅重截 / 插入（singleShot）用的單張截圖鍵（保留現有行為）。
                if (singleShotMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(8.dp),
                    ) {
                        Surface(
                            color = barColor,
                            contentColor = barContentColor,
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
                                Button(onClick = { capture() }) {
                                    Icon(imageVector = Icons.Outlined.PhotoCamera, contentDescription = null)
                                    Text(
                                        text = if (reCaptureMode) {
                                            stringResource(
                                                MR.strings.capture_recapture_action,
                                                reCaptureTargetPage ?: 0,
                                            )
                                        } else {
                                            stringResource(MR.strings.capture_insert_action, insertTargetPage ?: 0)
                                        },
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                                TextButton(onClick = onSingleShotCancel) {
                                    Text(text = stringResource(MR.strings.action_cancel))
                                }
                            }
                        }
                    }
                }
            } else {
                // 收起：只剩 WebView + 一個「展開工具列」小把手。
                // 取「貼右緣的小把手」而非圓鈕：48dp 圓鈕壓在漫畫右上角很搶眼，把手只有 32dp 寬、右側切齊螢幕邊
                // （右側直角、左側圓角），視覺上像從邊緣拉出來的抽屜提把，不像可誤觸的主要動作；高度仍留 40dp
                // 讓拇指好按。收起的目的就是「乾淨看漫畫」，這是最低調又找得到的作法。
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 8.dp)
                        .size(width = 32.dp, height = 40.dp),
                    color = barColor,
                    contentColor = barContentColor,
                    shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                    shadowElevation = 3.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { toolbarExpanded = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.UnfoldMore,
                            contentDescription = stringResource(MR.strings.capture_toolbar_show),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // 連續擷取進行中的引導提示（底部置中小條）：光看「已截 N 頁」不知道還要不要動作，
            // 這條明講「翻到下一頁繼續」——工具列收起時也看得到（放在 toolbarExpanded 之外），
            // 且同樣在 hideOverlayForCapture 內 → 截圖時不會入鏡。
            // 剛存完一頁（justCapturedPage != null）時改成醒目的主色提示「已擷取第 N 頁 · 請翻下一頁」，
            // 此期間 model 暫停偵測（見 startContinuous 的 CAPTURE_PAUSE_MS）——閃爍剛好發生在使用者看提示時。
            if (continuousRunning && !singleShotMode) {
                val justCaptured = justCapturedPage
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                    color = if (justCaptured != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        barColor
                    },
                    contentColor = if (justCaptured != null) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        barContentColor
                    },
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 3.dp,
                ) {
                    Text(
                        text = if (justCaptured != null) {
                            stringResource(MR.strings.capture_continuous_saved_hint, justCaptured)
                        } else {
                            stringResource(MR.strings.capture_continuous_hint, capturedCount)
                        },
                        style = if (justCaptured != null) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            // 清除 Cookie 確認對話框（清整個內建瀏覽器的 Cookie，防誤觸）。
            if (showClearCookiesDialog) {
                AlertDialog(
                    onDismissRequest = { showClearCookiesDialog = false },
                    title = { Text(stringResource(MR.strings.pref_clear_cookies)) },
                    text = { Text(stringResource(MR.strings.capture_clear_cookies_confirm)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                CookieManager.getInstance().removeAllCookies(null)
                                CookieManager.getInstance().flush()
                                showClearCookiesDialog = false
                                context.toast(context.contextStringResource(MR.strings.cookies_cleared))
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearCookiesDialog = false }) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }
        }

        // 確認模式：不透明面板全屏蓋在**仍然活著**的 WebView 上（不是 push 新 Screen）。
        // 「繼續擷取」只是把 mode 切回 CAPTURING，網頁還停在按停止時那一頁。
        if (reviewMode) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                reviewContent()
            }
        }
    }
}
