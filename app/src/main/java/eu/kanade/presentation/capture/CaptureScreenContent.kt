package eu.kanade.presentation.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlin.math.roundToInt
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
import eu.kanade.tachiyomi.ui.capture.CAPTURE_SCALE_MAX
import eu.kanade.tachiyomi.ui.capture.CAPTURE_SCALE_MIN
import eu.kanade.tachiyomi.ui.capture.CAPTURE_SCALE_STEP
import eu.kanade.tachiyomi.ui.capture.CAPTURE_TAP_DELAY_DEFAULT
import eu.kanade.tachiyomi.ui.capture.CAPTURE_TAP_DELAY_MAX
import eu.kanade.tachiyomi.ui.capture.CAPTURE_TAP_DELAY_MIN
import eu.kanade.tachiyomi.ui.capture.CAPTURE_TAP_DELAY_STEP
import eu.kanade.tachiyomi.ui.capture.CaptureBookmark
import eu.kanade.tachiyomi.ui.capture.CaptureMode
import eu.kanade.tachiyomi.ui.capture.CaptureSaveResult
import eu.kanade.tachiyomi.ui.capture.CaptureSiteSetting
import eu.kanade.tachiyomi.ui.capture.CaptureUrlEntry
import eu.kanade.tachiyomi.ui.capture.FrameGrabber
import eu.kanade.tachiyomi.ui.capture.PageTapper
import eu.kanade.tachiyomi.ui.capture.StartContinuous
import eu.kanade.tachiyomi.ui.capture.captureHostOf
import eu.kanade.tachiyomi.ui.capture.detectCropBounds
import eu.kanade.tachiyomi.ui.capture.suggestCaptureChapterNames
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.core.common.i18n.stringResource as contextStringResource

// 封面框選最小邊長（px）：太小的框（多半是誤點的單擊）不截，提示重框。
private const val MIN_COVER_CROP_PX = 24f

// 全屏清單類型（件 4）：歷史 / 我的最愛。塞在瀏覽 panel 容量小、易爆版面 → 各做成全屏可捲清單。
private enum class CaptureListSheet { HISTORY, BOOKMARKS }

// 瀏覽 panel 內「我的最愛」快選最多顯示幾筆（其餘走全屏清單）；再多會把 panel 撐爆。
private const val BROWSE_BOOKMARK_PREVIEW = 3

// 去頭去尾裁切：兩條線之間至少要留多少比例的畫面（防止把保留區拖到 0）。
private const val CROP_MIN_KEEP_FRACTION = 0.15f

// 自動翻頁模擬點擊的 ACTION_DOWN → ACTION_UP 間隔（ms）：太短某些網站的手勢判定會當成無效觸控，
// 太長會被判成長按（跳出選單）。50–80ms 是「輕點」的自然區間。
private const val TAP_DOWN_UP_GAP_MS = 60L

// 點擊位置標記的半徑（dp）：設定模式＝大圓（好拖）、擷取模式常駐＝小圓（只是提示點在哪、不擋畫面）。
private val TAP_MARKER_SETUP_RADIUS = 22.dp
private val TAP_MARKER_IDLE_RADIUS = 10.dp

// 位置沒設定過時，設定模式的預設落點（畫面正中偏下＝多數站「下一頁」鈕的位置）。
private const val TAP_DEFAULT_X = 0.5f
private const val TAP_DEFAULT_Y = 0.9f

// 頁面設定 panel 最高高度（加了自動翻頁一整段後可能超過一屏 → 內部可捲）。
private val PAGE_PANEL_MAX_HEIGHT = 420.dp

// 「加入最愛」對話框的別名預設草稿：取網址 host（去掉 www.）當好記名字；取不到就退回整串網址。
private fun defaultBookmarkAlias(url: String): String =
    Uri.parse(url).host?.removePrefix("www.")?.takeIf { it.isNotEmpty() } ?: url

/**
 * 畫面層級的 WebView 持有者（`remember` 在 CaptureScreenContent 裡，與整個 composition 同生命週期）。
 * compose 的 interop 節點被丟棄/重建時（例：摺疊展開工具列），`factory` 拿這顆既有實例回填 → 不新建 WebView，
 * 頁面 / 捲動 / 登入 / JS 狀態全保留。仿 [eu.kanade.presentation.webview.WebViewScreenContent] 的既有寫法。
 */
private class CaptureWebViewHolder {
    var webView: WebView? = null
}

/**
 * Yakuyomi 擷取漫畫畫面內容（階段 1：介面骨架重構）。
 *
 * 版面：WebView **鋪滿全螢幕**（底層），但 status bar / navigation bar 讓出（[statusBarsPadding] /
 * [navigationBarsPadding]），系統列不與畫面重疊；WebView 未載真網址時背景透出底層系統色（不露全白 html 畫布）。
 * 逐站的「畫布寬度%」＜100 時 WebView **本身變窄並水平置中**（左右對稱留白透出畫面底色，見 `canvasFraction`）；
 * 浮動工具列 / 面板 / 兩個 overlay 的提示列仍以全螢幕寬為準（操作 UI 不跟著縮）。
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
    // 本話頁數（使用者選填、per-session）：非 null＝進度顯示「已截 5/16 頁」，截滿 model 自動停止。
    capturedTarget: Int? = null,
    // 目前模式：擷取 / 確認（面板蓋在 WebView 上）/ 單張重截或插入。
    mode: CaptureMode = CaptureMode.CAPTURING,
    // 剛存下的頁碼（非 null＝顯示「已擷取第 N 頁 · 請翻下一頁」提示，期間 model 暫停偵測）。
    justCapturedPage: Int? = null,
    // (比較幀抓取器, 乾淨幀抓取器, 網址讀取器, 本話頁數, 點擊延遲, 自動翻頁點擊器)。
    onStartContinuous: StartContinuous,
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
    // 網址列輸入歷史（帶出歷史清單 + 逐筆刪除 + 造訪時記錄；每筆帶頁面標題）。
    urlHistoryProvider: () -> List<CaptureUrlEntry> = { emptyList() },
    onAddUrl: (String, String) -> Unit = { _, _ -> },
    onRemoveUrl: (String) -> Unit = {},
    // 我的最愛（手動存常用站 + 命名別名）：置頂快選、與自動記錄的歷史分開。
    bookmarksProvider: () -> List<CaptureBookmark> = { emptyList() },
    onAddBookmark: (String, String) -> Unit = { _, _ -> },
    onRemoveBookmark: (String) -> Unit = {},
    // 封面框選：裁好的整頁 bitmap + **bitmap 座標系**的裁切框 + 當前書名 → 存 cover.jpg，回封面 uri（失敗 null）。
    onSaveCover: suspend (Bitmap, Rect, String) -> String? = { _, _, _ -> null },
    // 開「新漫畫」panel 時撈該書已存的封面 uri（重進顯示縮圖）。
    coverProvider: suspend (String) -> String? = { null },
    // 「新漫畫」確定時記漫畫來源網址（供日後「繼續擷取」；這批只寫）。
    onWriteMangaMeta: (String, String?) -> Unit = { _, _ -> },
    // 逐站設定（畫布寬度% + 去頭去尾裁切）：以當前網址的 host 為 key 讀 / 寫。
    siteSettingProvider: (String?) -> CaptureSiteSetting = { CaptureSiteSetting() },
    onSaveSiteSetting: (String?, CaptureSiteSetting) -> Unit = { _, _ -> },
) {
    val reCaptureMode = reCaptureTargetPage != null
    val insertMode = insertTargetPage != null
    // 單張目標模式（重截 / 插入）：隱藏書名/章名輸入與連續擷取，只留單一擷取鈕、成功後退回。
    val singleShotMode = mode == CaptureMode.SINGLE_SHOT && (reCaptureMode || insertMode)
    val reviewMode = mode == CaptureMode.REVIEW
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val navigator = remember { WebViewNavigator(scope) }
    // ★ 種子不能是 about:blank（2026-07 修）：compose-webview 內部有
    // `LaunchedEffect(wv, state){ snapshotFlow{state.content}.collect{ wv.loadUrl(it.url) } }`——interop 節點一被重建
    // （例：摺疊/展開工具列）就把 `state.content` 回灌進 WebView。舊碼把 content 永遠停在 about:blank（導覽全繞過它
    // 直接 webView.loadUrl）⇒ 重建＝白頁 ⇒ loadedUrl 變 about:blank ⇒ hasUrl=false（鈕鎖回 S0）⇒ meta 永不寫。
    // 沒有初始網址時用 [WebContent.NavigatorOnly]（內部 collect 是 NO-OP、也不會在歷史留一筆 about:blank）。
    val state = remember {
        val seed = initialUrl.trim()
        WebViewState(if (seed.isEmpty() || seed == "about:blank") WebContent.NavigatorOnly else WebContent.Url(seed))
    }
    // WebView 實例 screen-scoped 持有：interop 節點被重建時**復用同一顆**（頁面 / 捲動 / 登入 / JS 狀態全保留），
    // 而不是新建一顆再被 state.content 回灌初始網址（＝上面那條白頁鏈的另一半）。仿 WebViewScreenContent 的既有寫法。
    val webViewHolder = remember { CaptureWebViewHolder() }
    // 抓 onCreated 給的原生 WebView：截圖 / 手動載址都要它。
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(initialUrl) }
    // WebView **實際載入**的網址（不是網址列草稿）：漸進式解鎖的 S1 判準，只由 WebViewClient 的導覽回呼更新
    // ——否則使用者在網址列打幾個字（address 一路變動）就會誤判成「已有網址」。
    var loadedUrl by remember { mutableStateOf(initialUrl) }

    // 工具列收起/展開（收起＝只剩 WebView + 展開小鈕）。
    var toolbarExpanded by remember { mutableStateOf(true) }
    // 全新入口（initialUrl 空 / about:blank，含「繼續擷取」卻沒記到來源網址）＝自動展開「瀏覽」panel 引導使用者
    // 自己瀏覽到漫畫（loadedUrl 有值後 hasUrl=true、解鎖後續）；否則預設收合（件 1b fallback）。
    var browseExpanded by remember {
        mutableStateOf(initialUrl.isBlank() || initialUrl.trim() == "about:blank")
    }
    // 「新漫畫」panel（書名輸入 + 從網頁標題帶入）展開與否。
    var mangaPanelExpanded by remember { mutableStateOf(false) }
    // 「新話數」panel（已截話數總覽 + 建議 + 章名輸入）展開與否。
    var chapterPanelExpanded by remember { mutableStateOf(false) }
    // 兩個 panel 的暫存輸入：按「確定」才寫回 model 的 bookName / chapterName（panel 開啟時同步當前值）。
    var bookDraft by remember { mutableStateOf(bookName) }
    var chapterDraft by remember { mutableStateOf(chapterName) }
    // 目前書名底下已截過的話夾名稱（開「新話數」panel 時掃一次）。
    var existingChapters by remember { mutableStateOf(emptyList<String>()) }
    // 全屏清單（歷史 / 我的最愛）：null＝不顯示。原本塞在瀏覽 panel 容量太小 → 改成點入口開全屏可捲清單（件 4）。
    var listSheet by remember { mutableStateOf<CaptureListSheet?>(null) }
    // 歷史清單在畫面內管理：初值來自 pref；造訪 / 刪除即時重讀刷新（件 2/3，帶標題、相容舊純 url）。
    var history by remember { mutableStateOf(urlHistoryProvider()) }
    // 我的最愛清單（畫面內管理：初值來自 pref，加入/刪除即時反映 UI 並同步寫回 pref）。
    var bookmarks by remember { mutableStateOf(bookmarksProvider()) }
    // 「加入最愛」對話框：非 null＝正為此網址輸入別名；aliasDraft＝別名草稿（預設帶該 url 的 host）。
    var bookmarkDialogUrl by remember { mutableStateOf<String?>(null) }
    var bookmarkAliasDraft by remember { mutableStateOf("") }
    // ★ 截圖當下把所有浮動 overlay 隱藏，避免進到 PixelCopy 的截圖裡。
    var hideOverlayForCapture by remember { mutableStateOf(false) }
    // 清除 Cookie 確認對話框（防誤觸）。
    var showClearCookiesDialog by remember { mutableStateOf(false) }
    // ── 封面框選（件 1）─────────────────────────────────────────────────────
    // 進封面框選模式（拖框選封面）：期間隱藏整個工具列（!coverCropMode gate）、只留 dim + 選取框 + 底部動作。
    var coverCropMode by remember { mutableStateOf(false) }
    // 拖框的起點/當前點（單位＝px，座標系＝框選 overlay 的左上；overlay 為 fillMaxSize 貼齊外層 Box）。
    var cropStart by remember { mutableStateOf<Offset?>(null) }
    var cropEnd by remember { mutableStateOf<Offset?>(null) }
    // 框選 overlay 自身在 window 的左上（onGloballyPositioned 量）：把 overlay 局部座標 → window 座標，
    // 再扣 WebView 在 window 的左上 → bitmap 座標。overlay 通常貼齊 window 原點（此值≈0），量出來更穩健。
    var cropOverlayOrigin by remember { mutableStateOf(Offset.Zero) }
    // 已存封面的 uri（縮圖預覽）；coverReloadKey 每次存封面 +1，破 coil 快取（同 uri 重存要換 cache key）。
    var coverPreviewUri by remember { mutableStateOf<String?>(null) }
    var coverReloadKey by remember { mutableIntStateOf(0) }

    // ── 逐站設定：畫布寬度% + 去頭去尾裁切（階段 4）─────────────────────────────
    // 目前站台（host）的設定；換站時重讀 pref。
    var siteSetting by remember { mutableStateOf(CaptureSiteSetting()) }
    // 「頁面設定」panel（畫布寬度滑桿 + 裁切設定入口）展開與否。
    var pagePanelExpanded by remember { mutableStateOf(false) }
    // 畫布寬度%（滑桿當前值；50–100）。
    // ★ 2026-07 改法（舊 setInitialScale 作廢，見 [canvasFraction]）：這個值**直接決定 WebView view 的佈局寬度**
    // ⇒ 拖曳即時生效、不 reload。放開滑桿（或 −/＋）才 commit 進 pref 做逐站記憶。
    // 換站時由下方 LaunchedEffect(siteSetting.scale) 同步成該站存的值。
    var scaleDraft by remember { mutableIntStateOf(CAPTURE_SCALE_MAX) }

    /**
     * WebView 佔畫面寬度的比例（0.5f–1.0f）＝畫布寬度%。
     *
     * ★ 為何不再用 [android.webkit.WebView.setInitialScale]（2026-07 改）：那條路真機實測完全沒效果——
     * ① `setDefaultSettings()` 開了 `useWideViewPort` + `loadWithOverviewMode`，此時 WebView **以頁面自己的
     *    `<meta name="viewport">` 為準**（手機版漫畫站幾乎都有，常見 `width=device-width, initial-scale=1`，
     *    甚至 `user-scalable=no` / `minimum-scale=1`）→ 頁面定義的縮放約束把 initialScale 蓋掉 / 夾回 fit 寬度；
     * ② 就算縮放真的套上去，**佈局寬度（CSS px）不變** ⇒ 網頁不會 responsive 重排，只是整頁被視覺縮小 + 右側留白，
     *    不是我們要的「重排到較窄寬度、整頁高度變小」；
     * ③ 本實作還要靠 `onScaleChanged` 量「自然縮放」才換算得出絕對值，而該回呼在「載入後縮放沒變」時根本不會觸發
     *    （naturalScale 停在 0）→ 套用條件永遠不成立 ⇒ 滑桿一動也不動。
     *
     * 新作法＝**純 Compose 佈局**：把 WebView view 本身變窄（置中，左右留白透出畫面底色）。網頁 responsive 依較窄
     * 寬度重排 → 圖等比縮小 → 整頁高度變小、一屏塞得下。立即生效、不 reload（不掉捲動位置）、不受 viewport meta
     * 影響、不注入 JS。
     */
    val canvasFraction = scaleDraft.coerceIn(CAPTURE_SCALE_MIN, CAPTURE_SCALE_MAX) / 100f
    // ── 自動翻頁：點擊位置設定 + 本話頁數 ──────────────────────────────────────
    // 位置設定模式（畫面上出現一個可拖曳的圓形標記，拖到該站「下一頁」鈕的位置）；期間整個工具列藏起。
    var tapSetupMode by remember { mutableStateOf(false) }
    // 位置草稿＝**比例座標** 0f–1f（佔 WebView 寬 / 高）；按「儲存」才寫回逐站 pref。
    var tapXDraft by remember { mutableFloatStateOf(TAP_DEFAULT_X) }
    var tapYDraft by remember { mutableFloatStateOf(TAP_DEFAULT_Y) }
    // 點擊延遲滑桿的當前值（ms）；放開滑桿 / 按 −＋ 才 commit 進 pref（同畫布寬度的作法）。
    var tapDelayDraft by remember { mutableIntStateOf(CAPTURE_TAP_DELAY_DEFAULT) }
    // 本話頁數（選填）：**per-session 不記憶**（每話頁數不同 → 不寫 pref，換話即清空）；空＝不設上限。
    var targetPagesDraft by remember { mutableStateOf("") }

    // ── 去頭去尾裁切設定模式（件 3-A）：兩條可拖曳的水平線 ────────────────────
    var cropSetupMode by remember { mutableStateOf(false) }
    // 草稿＝上/下各裁掉的畫面高度比例（0f–1f）；按「儲存」才寫回 pref。
    var cropTopDraft by remember { mutableFloatStateOf(0f) }
    var cropBottomDraft by remember { mutableFloatStateOf(0f) }
    // 自動偵測進行中（截圖 + 分析期間鎖住按鈕）。
    var cropAutoBusy by remember { mutableStateOf(false) }

    // 網址列上的當前網址（隨 WebView 導覽同步）；造訪時記錄進歷史 pref。
    val webClient = remember {
        object : AccompanistWebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    address = it
                    loadedUrl = it
                    // 造訪即記 url；此時 WebView 原生 title 多半還是前一頁的 → 先不帶標題，onPageFinished 補正確的。
                    onAddUrl(it, "")
                    if (listSheet == CaptureListSheet.HISTORY) history = urlHistoryProvider()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                url?.let {
                    address = it
                    loadedUrl = it
                    // 頁面載完＝WebView 原生 title 可讀（非 JS/DOM）→ 補標題（件 3）；歷史清單開著就即時刷新（件 2）。
                    onAddUrl(it, view.title.orEmpty())
                    if (listSheet == CaptureListSheet.HISTORY) history = urlHistoryProvider()
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

    /**
     * 取「當前真實網址」：`webView.url` 偶爾讀到空（剛建 / 導覽中）→ 退回 [loadedUrl]（WebViewClient 追蹤的實際載入
     * 網址）；兩者都無效（空 / about:blank）回 null。截圖記網址、連續擷取 urlProvider、記漫畫來源網址三處共用，
     * 避免其中一處漏了 fallback 就寫不出 meta。
     */
    fun currentUrl(): String? {
        fun String?.valid(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "about:blank" }
        return webView?.url.valid() ?: loadedUrl.valid()
    }

    /**
     * 導覽單一入口（★ 2026-07 修）：一律透過 [WebViewState] 下指令，**不要裸 `webView.loadUrl`**——
     * 讓 `state.content` 恆為「真實網址」，interop 節點重建時內部 collect 回灌的也就是正確的那一頁。
     * 同一個網址再載一次時 `snapshotFlow` 會去重（不會再發），改叫 [WebViewNavigator] 直接載（重截同一頁要能重載）。
     */
    fun navigate(url: String) {
        val target = WebContent.Url(url)
        if (state.content == target) navigator.loadUrl(url) else state.content = target
    }

    fun go(urlOverride: String? = null) {
        val trimmed = (urlOverride ?: address).trim()
        if (trimmed.isEmpty()) return
        val normalized = if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
        address = normalized
        // 載入後收起瀏覽 panel + 關掉全屏清單（回到乾淨看漫畫視野）。
        browseExpanded = false
        listSheet = null
        // 標題此刻未知（頁面還沒載），onPageFinished 會補上。
        onAddUrl(normalized, "")
        navigate(normalized)
    }

    // 目前站台（以 WebView **實際載入**的網址取 host）＝逐站設定（畫布寬度 / 裁切）的 key。
    val siteHost = captureHostOf(loadedUrl)

    /** 寫回逐站設定：即時更新畫面狀態 + 落地 pref（key＝當前網址的 host）。 */
    fun commitSiteSetting(updated: CaptureSiteSetting) {
        siteSetting = updated
        onSaveSiteSetting(currentUrl() ?: loadedUrl, updated)
    }

    fun capture() {
        scope.launch {
            // ★ 先隱藏所有浮動 overlay，等兩個 frame 讓「隱藏」那次重繪畫上螢幕，PixelCopy 才不會抓到工具列。
            hideOverlayForCapture = true
            withFrameNanos {}
            withFrameNanos {}
            val window = context.findActivity()?.window
            // WebView 網址須在主執行緒讀；captureWebView 回呼在主執行緒，這裡先取好再帶進存檔。
            val url = currentUrl()
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

    // 封面框選截圖：先隱藏所有 overlay（含框選框本身）→ 截乾淨全頁 → **把框選座標換算到 bitmap 座標系**
    // （框選點 overlay 局部 + overlay 在 window 的原點 = window 座標；再扣 WebView 在 window 的左上 = bitmap 座標，
    // clamp 在 bitmap 範圍內）→ 依框裁切存 cover.jpg → 顯示縮圖預覽。
    fun captureCover() {
        val start = cropStart
        val end = cropEnd
        if (start == null || end == null) return
        // 框選在 overlay 局部座標；先算出（左,上,右,下）。
        val selLeft = minOf(start.x, end.x)
        val selTop = minOf(start.y, end.y)
        val selRight = maxOf(start.x, end.x)
        val selBottom = maxOf(start.y, end.y)
        if (selRight - selLeft < MIN_COVER_CROP_PX || selBottom - selTop < MIN_COVER_CROP_PX) {
            context.toast(context.contextStringResource(MR.strings.capture_cover_too_small))
            return
        }
        val book = bookDraft.trim()
        scope.launch {
            // ★ 先隱藏所有浮動 overlay（含框選框），等兩 frame 讓「隱藏」重繪上螢幕，PixelCopy 才乾淨。
            hideOverlayForCapture = true
            withFrameNanos {}
            withFrameNanos {}
            val window = context.findActivity()?.window
            val wv = webView
            captureWebView(wv, window) { bitmap ->
                hideOverlayForCapture = false
                if (bitmap == null || wv == null) {
                    coverCropMode = false
                    cropStart = null
                    cropEnd = null
                    if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                    context.toast(context.contextStringResource(MR.strings.capture_cover_failed))
                    return@captureWebView
                }
                // ★ 座標換算：overlay 局部 → window（+ overlay 在 window 的原點）→ bitmap（- WebView 在 window 左上）。
                val loc = IntArray(2)
                wv.getLocationInWindow(loc)
                fun toBitmapX(v: Float) = (v + cropOverlayOrigin.x).roundToInt().minus(loc[0]).coerceIn(0, bitmap.width)
                fun toBitmapY(v: Float) = (v + cropOverlayOrigin.y).roundToInt().minus(loc[1]).coerceIn(0, bitmap.height)
                val rect = Rect(toBitmapX(selLeft), toBitmapY(selTop), toBitmapX(selRight), toBitmapY(selBottom))
                scope.launch {
                    val uri = onSaveCover(bitmap, rect, book)
                    if (!bitmap.isRecycled) bitmap.recycle()
                    coverCropMode = false
                    cropStart = null
                    cropEnd = null
                    if (uri != null) {
                        coverPreviewUri = uri
                        coverReloadKey++
                        mangaPanelExpanded = true // 回新漫畫 panel 看縮圖預覽
                        context.toast(context.contextStringResource(MR.strings.capture_cover_saved))
                    } else {
                        context.toast(context.contextStringResource(MR.strings.capture_cover_failed))
                    }
                }
            }
        }
    }

    // 去頭去尾「自動偵測」（件 3-B）：截一張**乾淨**當前畫面 → [detectCropBounds] 猜上下分界 → 只把兩條線
    // **預先擺好**（寫進草稿），不直接套用；使用者確認/微調後才按儲存。偵測不到就維持 0（不裁）。
    fun autoDetectCrop() {
        if (cropAutoBusy) return
        scope.launch {
            cropAutoBusy = true
            // 與截圖同一套護欄：先藏 overlay（含兩條線）、等兩 frame 讓隱藏重繪上螢幕，抓到的才是純網頁畫面。
            hideOverlayForCapture = true
            withFrameNanos {}
            withFrameNanos {}
            val window = context.findActivity()?.window
            captureWebView(webView, window) { bitmap ->
                hideOverlayForCapture = false
                if (bitmap == null) {
                    cropAutoBusy = false
                    context.toast(context.contextStringResource(MR.strings.webview_capture_failed))
                    return@captureWebView
                }
                scope.launch {
                    val (top, bottom) = withContext(Dispatchers.Default) { detectCropBounds(bitmap) }
                    if (!bitmap.isRecycled) bitmap.recycle()
                    if (top <= 0f && bottom <= 0f) {
                        context.toast(context.contextStringResource(MR.strings.capture_crop_auto_none))
                    } else {
                        cropTopDraft = top
                        cropBottomDraft = bottom
                    }
                    cropAutoBusy = false
                }
            }
        }
    }

    /**
     * 自動翻頁：對 WebView 派送一次模擬點擊，位置＝[fx]/[fy]（**比例座標** 0–1，相對 WebView 自己的寬高）。
     *
     * ★ 護欄：只用 [android.webkit.WebView.dispatchTouchEvent] 送 ACTION_DOWN + ACTION_UP——等同「使用者自己
     * 點了那裡」，**不注入 JS、不讀 DOM**（本工具一律截像素、不 scrape）。
     * - 座標是 **WebView 本地座標**（不是螢幕座標）：直接把比例乘上 `wv.width/height`。畫布縮窄時 WebView view
     *   本身就變窄了，比例對的仍是同一塊畫布（與標記 overlay 的座標系一致）。
     * - MotionEvent 必須在**主執行緒**派送（[withUIContext]），兩顆事件共用同一個 downTime、間隔
     *   [TAP_DOWN_UP_GAP_MS]（太短算無效觸控、太長變長按），用完一律 `recycle()`。
     * 回 false＝WebView 還沒量到寬高（沒派送），model 端據此更快累進「無效點擊」計數。
     */
    suspend fun dispatchTapAt(fx: Float, fy: Float): Boolean = withUIContext {
        val wv = webView ?: return@withUIContext false
        val w = wv.width
        val h = wv.height
        if (w <= 0 || h <= 0) return@withUIContext false
        val x = fx.coerceIn(0f, 1f) * w
        val y = fy.coerceIn(0f, 1f) * h
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        try {
            wv.dispatchTouchEvent(down)
        } finally {
            down.recycle()
        }
        delay(TAP_DOWN_UP_GAP_MS)
        val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        try {
            wv.dispatchTouchEvent(up)
        } finally {
            up.recycle()
        }
        true
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
        // ③ 自動翻頁點擊器：開關開著且該站設過位置才給（否則 null＝維持原本的「手動翻頁」行為，零回歸）。
        // 這個 lambda 每次被呼叫時才讀 siteSetting（Compose 的 state 委派 → 讀到的永遠是當下的值）。
        val autoTapper: PageTapper? = if (siteSetting.autoTapReady) {
            {
                val fx = siteSetting.tapX
                val fy = siteSetting.tapY
                if (fx != null && fy != null) dispatchTapAt(fx, fy) else false
            }
        } else {
            // 開了自動翻頁卻沒設位置＝點不下去，明講一聲再照手動模式跑（不擋開始）。
            if (siteSetting.autoTap) {
                context.toast(context.contextStringResource(MR.strings.capture_autotap_no_point))
            }
            null
        }
        onStartContinuous(
            compareGrabber,
            cleanGrabber,
            { currentUrl() },
            targetPagesDraft.trim().toIntOrNull()?.takeIf { it > 0 },
            siteSetting.tapDelayMs,
            autoTapper,
        )
    }

    // 進單張模式（重截 / 插入）：該頁有記網址才開回去；沒有就**保持 WebView 現狀**（讓使用者自己捲到位）。
    LaunchedEffect(singleShotToken) {
        if (mode == CaptureMode.SINGLE_SHOT && !singleShotUrl.isNullOrBlank()) {
            address = singleShotUrl
            navigate(singleShotUrl)
        }
    }

    // ── 漸進式解鎖 S0→S3（見檔頭 KDoc）─────────────────────────────────────────
    // S1 條件＝WebView 已載入真網址（[loadedUrl]＝initialUrl / WebViewClient 導覽回呼，非網址列草稿）。
    val hasUrl = loadedUrl.isNotBlank() && loadedUrl.trim() != "about:blank"
    val canNewManga = hasUrl // S1 → 解鎖「新漫畫」（要有頁面才能從標題命名 / 框封面）
    // S2 → 解鎖「新話數」：**只需書名非空、不強制 hasUrl**（件 1b）。「繼續擷取」帶著書名進來（initialUrl 空 /
    // about:blank）可先設話數、再自己瀏覽到頁面；正常流程走過 S1 時 hasUrl 本就成立、行為不變。
    val canNewChapter = bookName.isNotBlank()
    // S3 → 解鎖「開始」：真的要截需有實際頁面（about:blank 會被空白判斷丟棄）→ 仍要求 hasUrl，避免對著空白頁空跑。
    val canStart = bookName.isNotBlank() && chapterName.isNotBlank() && hasUrl

    // 條件退回（例如把書名清空）時收起對應 panel，免得停在一個已按不到的面板上。
    LaunchedEffect(canNewManga, canNewChapter, hasUrl) {
        if (!canNewManga) mangaPanelExpanded = false
        if (!canNewChapter) chapterPanelExpanded = false
        if (!hasUrl) {
            pagePanelExpanded = false
            cropSetupMode = false
            tapSetupMode = false
        }
    }

    // 該站存的點擊延遲變了（換站重讀 / 其他地方寫入）＝同步滑桿值。
    LaunchedEffect(siteSetting.tapDelayMs) {
        tapDelayDraft = siteSetting.tapDelayMs.coerceIn(CAPTURE_TAP_DELAY_MIN, CAPTURE_TAP_DELAY_MAX)
    }

    // 換一話＝本話頁數重填（**不記憶**：每話頁數不同，沿用上一話的值只會提早停在錯的地方）。
    LaunchedEffect(chapterName) {
        targetPagesDraft = ""
    }

    // 換站＝重讀該站的設定（畫布寬度 + 裁切）。沒有 host（about:blank）＝回預設。
    LaunchedEffect(siteHost) {
        siteSetting = if (siteHost == null) CaptureSiteSetting() else siteSettingProvider(loadedUrl)
    }

    // 該站存的畫布寬度變了（換站重讀 / 其他地方寫入）＝同步滑桿值 ⇒ WebView 寬度隨即重新量測套用。
    // ★ 不再有「套用」副作用（舊 setInitialScale + reload 那套已移除）：寬度純由 [canvasFraction] 在佈局階段生效。
    LaunchedEffect(siteSetting.scale) {
        scaleDraft = siteSetting.scale.coerceIn(CAPTURE_SCALE_MIN, CAPTURE_SCALE_MAX)
    }

    // 開「新漫畫」panel ＝草稿同步當前書名。
    LaunchedEffect(mangaPanelExpanded) {
        if (mangaPanelExpanded) bookDraft = bookName
    }

    // 開「新漫畫」panel（或書名換了）＝撈該書已存封面顯示縮圖（沒有＝清掉舊預覽）。
    // 剛存完封面時 coverPreviewUri 由 captureCover 直接設好，這裡不覆蓋（mangaPanelExpanded 沒變不重跑）。
    LaunchedEffect(mangaPanelExpanded, bookName) {
        if (mangaPanelExpanded) coverPreviewUri = coverProvider(bookName.ifBlank { bookDraft })
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
    BackHandler(
        enabled = listSheet != null || coverCropMode || cropSetupMode || tapSetupMode || reviewMode ||
            singleShotMode || navigator.canGoBack,
    ) {
        when {
            // 全屏清單開著＝先關清單（回瀏覽 panel），不關畫面 / 不上一頁。
            listSheet != null -> listSheet = null
            // 裁切設定中按返回＝取消（不存草稿），不關畫面。
            cropSetupMode -> cropSetupMode = false
            // 點擊位置設定中按返回＝取消（不存草稿），不關畫面。
            tapSetupMode -> tapSetupMode = false
            // 封面框選中按返回＝取消框選（回工具列），不關畫面。
            coverCropMode -> {
                coverCropMode = false
                cropStart = null
                cropEnd = null
            }
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
            // 畫布縮窄時 **水平置中**（左右留白對稱，透出外層 Box 的 colorScheme.background）。
            contentAlignment = Alignment.Center,
        ) {
            WebView(
                state = state,
                // ★ 畫布寬度（2026-07 改法，見 [canvasFraction] 的 KDoc）：直接縮 **WebView view 的佈局寬度**，
                // 不碰任何縮放 API。網頁 responsive 依較窄寬度重排 → 圖等比縮小 → 整頁高度變小、一屏塞得下。
                // ⚠️ 護欄：只換 Modifier，composable 位置 / key / 其餘參數全不變 ⇒ interop 節點**不重建**
                // （庫內 `BoxWithConstraints(modifier)` 只是重新量測、subcomposition 沿用同一個 AndroidView；
                // 即使真被重建，`factory` 也會拿 [webViewHolder] 那顆回填 ⇒ 不會白頁）。
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(canvasFraction),
                navigator = navigator,
                onCreated = { wv ->
                    wv.setDefaultSettings()
                    // 透明背景：未載入頁面時讓底層系統色透出（不是全白 html 畫布）。
                    wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webView = wv
                },
                client = webClient,
                // ★ 復用同一顆 WebView（見 [webViewHolder]）：interop 節點重建時不新建、頁面 / 捲動 / 登入全保留。
                // 若舊節點還沒把它從 parent 摘掉，先摘再交還（免 addView 撞 "already has a parent"）。
                factory = { ctx ->
                    webViewHolder.webView
                        ?.also { (it.parent as? ViewGroup)?.removeView(it) }
                        ?: android.webkit.WebView(ctx).also { webViewHolder.webView = it }
                },
                // 節點被丟棄時把 content 歸零：重建後內部 collect 收到 NavigatorOnly＝NO-OP，
                // 不會拿舊網址覆蓋 WebView 現在停的那一頁（同 WebViewScreenContent 的既有寫法）。
                onDispose = { state.content = WebContent.NavigatorOnly },
            )
        }

        // ── 裁切範圍常駐預覽（灰階遮罩）────────────────────────────────────────────
        // 該站設了去頭去尾就**一直**把會被裁掉的頭尾塗半透明灰，擷取時隨時看得到裁切範圍，不必開設定確認。
        // 三條硬性質：
        // ① **不吃觸控**：整層只有 Box + Canvas、**零 clickable / 零 pointerInput** ⇒ 在 Compose 的 hit test 裡
        //    根本不是候選節點，觸控直接落到下面的 WebView（照常捲頁 / 點連結 / 之後的自動翻頁點擊）。
        // ② **截圖不入鏡**：跟其他 overlay 同一個 `!hideOverlayForCapture` gate ⇒ 抓乾淨幀那兩個 frame 內不 render。
        //    真正的裁切是存檔時在 bitmap 上做（CaptureScreenModel.cropForSave），與這層無關、不會裁兩次。
        // ③ **對齊畫布**：與 WebView 同樣的系統列 padding + 同樣的置中 [canvasFraction] 寬 ⇒ 只塗畫布那塊、
        //    不塗左右留白，比例也與存檔時用的「佔畫面高度比例」一致。
        // 只在正常擷取模式畫：REVIEW / SINGLE_SHOT 不畫；封面框選、裁切設定模式各有自己的遮罩，不疊兩層。
        if (siteSetting.hasCrop && mode == CaptureMode.CAPTURING &&
            !hideOverlayForCapture && !coverCropMode && !cropSetupMode
        ) {
            val cropMaskColor = Color.Black.copy(alpha = 0.45f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(canvasFraction),
                ) {
                    val topH = size.height * siteSetting.cropTop.coerceIn(0f, 1f)
                    val bottomY = size.height * (1f - siteSetting.cropBottom.coerceIn(0f, 1f))
                    if (topH > 0f) {
                        drawRect(color = cropMaskColor, topLeft = Offset(0f, 0f), size = Size(size.width, topH))
                    }
                    if (bottomY < size.height) {
                        drawRect(
                            color = cropMaskColor,
                            topLeft = Offset(0f, bottomY),
                            size = Size(size.width, size.height - bottomY),
                        )
                    }
                }
            }
        }

        // ── 自動翻頁點擊位置的常駐小標記（件 1 後半）────────────────────────────────
        // 該站開了自動翻頁且設過位置 → 一直在那個點畫一個**小的半透明圓**，讓使用者一眼確認「等下會點這裡」。
        // 性質與上面的裁切遮罩一模一樣（也是刻意抄它的作法）：
        // ① **不吃觸控**：整層只有 Box + Canvas、零 clickable / 零 pointerInput ⇒ 觸控直接落到 WebView。
        // ② **截圖不入鏡**：同一個 `!hideOverlayForCapture` gate（模擬點擊是打在 WebView 上、與這層無關）。
        // ③ **對齊畫布**：與 WebView 同樣的系統列 padding + 同樣的置中 [canvasFraction] 寬 ⇒ 這裡畫的
        //    (x%, y%) 與 [dispatchTapAt] 乘上 `wv.width/height` 得到的落點是同一個位置。
        // 設定模式（tapSetupMode）自己有大標記，不疊兩層。
        if (siteSetting.autoTapReady && mode == CaptureMode.CAPTURING &&
            !hideOverlayForCapture && !coverCropMode && !cropSetupMode && !tapSetupMode
        ) {
            val markerColor = MaterialTheme.colorScheme.primary
            val markerX = siteSetting.tapX ?: TAP_DEFAULT_X
            val markerY = siteSetting.tapY ?: TAP_DEFAULT_Y
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(canvasFraction),
                ) {
                    val center = Offset(markerX * size.width, markerY * size.height)
                    val radius = TAP_MARKER_IDLE_RADIUS.toPx()
                    drawCircle(color = markerColor.copy(alpha = 0.22f), radius = radius, center = center)
                    drawCircle(
                        color = markerColor.copy(alpha = 0.6f),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
        }

        // ★ 截圖進行中：不 render 任何浮動 overlay（頂部 bar / 底部 bar / 收起小鈕 / panel / 對話框）。
        // 確認模式時整片被面板蓋住，浮動工具列一併不畫（免壓在面板下方漏出來）。
        // 封面框選模式（coverCropMode）/ 裁切設定模式（cropSetupMode）/ 點擊位置設定模式（tapSetupMode）
        // 也整個藏起工具列 → 使用者看得到畫面拖框 / 拖線 / 拖標記（見下方三個 overlay）。
        if (!hideOverlayForCapture && !reviewMode && !coverCropMode && !cropSetupMode && !tapSetupMode) {
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
                                        pagePanelExpanded = false
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
                                        // 有填本話頁數＝「已截 5/16 頁」，沒填＝維持原本的「已截 N 頁」。
                                        text = if (capturedTarget != null) {
                                            stringResource(
                                                MR.strings.capture_continuous_count_target,
                                                capturedCount,
                                                capturedTarget,
                                            )
                                        } else {
                                            stringResource(MR.strings.capture_continuous_count, capturedCount)
                                        },
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
                                                pagePanelExpanded = false
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
                                                pagePanelExpanded = false
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
                                    // 6) 頁面設定（逐站的畫布寬度% + 去頭去尾裁切）：要有網址（host 是設定的 key）。
                                    IconButton(
                                        onClick = {
                                            pagePanelExpanded = !pagePanelExpanded
                                            if (pagePanelExpanded) {
                                                browseExpanded = false
                                                mangaPanelExpanded = false
                                                chapterPanelExpanded = false
                                            }
                                        },
                                        enabled = hasUrl,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Tune,
                                            contentDescription = stringResource(MR.strings.capture_page_settings),
                                            tint = if (pagePanelExpanded) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                LocalContentColor.current
                                            },
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
                                // 我的最愛快選（置頂、最多 [BROWSE_BOOKMARK_PREVIEW] 筆，panel 不爆版）：每筆＝別名（主）
                                // + 網址（次要小字，ellipsis）；點一筆＝載入並收 panel、右側叉叉＝移除。完整清單（含大量）
                                // 走下方導覽列「我的最愛」全屏入口。清單空＝整區不顯示。
                                if (bookmarks.isNotEmpty()) {
                                    Text(
                                        text = stringResource(MR.strings.capture_bookmarks),
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                                    )
                                    bookmarks.take(BROWSE_BOOKMARK_PREVIEW).forEach { bm ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(MaterialTheme.shapes.small)
                                                .clickable { go(urlOverride = bm.url) },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Star,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .padding(start = 4.dp, end = 8.dp)
                                                    .size(18.dp),
                                            )
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(vertical = 6.dp),
                                            ) {
                                                Text(
                                                    text = bm.alias,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = bm.url,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    bookmarks = bookmarks.filterNot { it.url == bm.url }
                                                    onRemoveBookmark(bm.url)
                                                },
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Close,
                                                    contentDescription = stringResource(MR.strings.action_delete),
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
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

                                // 導覽列：上一頁 / 下一頁 / 我的最愛（全屏）/ 歷史（全屏）/ 清除 Cookie。
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
                                    // 我的最愛：開全屏清單（管理大量最愛；先重讀 pref 取最新）。
                                    IconButton(
                                        onClick = {
                                            bookmarks = bookmarksProvider()
                                            listSheet = CaptureListSheet.BOOKMARKS
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.CollectionsBookmark,
                                            contentDescription = stringResource(MR.strings.capture_bookmarks),
                                        )
                                    }
                                    // 歷史：開全屏清單（容納大量紀錄；先重讀 pref 納入剛造訪的網址）。
                                    IconButton(
                                        onClick = {
                                            history = urlHistoryProvider()
                                            listSheet = CaptureListSheet.HISTORY
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.History,
                                            contentDescription = stringResource(MR.strings.capture_url_history),
                                        )
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    IconButton(onClick = { showClearCookiesDialog = true }) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteSweep,
                                            contentDescription = stringResource(MR.strings.pref_clear_cookies),
                                        )
                                    }
                                }
                                // 網址歷史清單改成全屏可捲清單（件 4，見下方 listSheet overlay），不再擠在 panel 裡。
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
                                    // 框選封面（需先有書名＝存檔目標夾）：進封面框選模式，工具列整個藏起讓使用者拖框。
                                    TextButton(
                                        onClick = {
                                            cropStart = null
                                            cropEnd = null
                                            coverCropMode = true
                                        },
                                        enabled = bookDraft.isNotBlank(),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Crop,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = stringResource(MR.strings.capture_select_cover),
                                            modifier = Modifier.padding(start = 4.dp),
                                        )
                                    }
                                }

                                // 封面縮圖預覽（存過封面才顯示）＋「重框」再來一次。coilReloadKey 破快取（同 uri 重存要換 key）。
                                val previewUri = coverPreviewUri
                                if (previewUri != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(previewUri)
                                                .memoryCacheKey("$previewUri#$coverReloadKey")
                                                .diskCacheKey("$previewUri#$coverReloadKey")
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = stringResource(MR.strings.capture_cover_preview),
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(width = 44.dp, height = 60.dp)
                                                .clip(MaterialTheme.shapes.small),
                                        )
                                        TextButton(
                                            onClick = {
                                                cropStart = null
                                                cropEnd = null
                                                coverCropMode = true
                                            },
                                            modifier = Modifier.padding(start = 8.dp),
                                        ) {
                                            Text(text = stringResource(MR.strings.capture_cover_reframe))
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    Button(
                                        onClick = {
                                            val newBook = bookDraft.trim()
                                            // 換一本書＝原本的章名不再適用（避免誤截進 <新書>/<舊章名>）→ 清空，
                                            // 使用者接著走「新話數」panel（該書的已截話數/建議也才對得上）。
                                            if (newBook != bookName) onChapterNameChange("")
                                            onBookNameChange(newBook)
                                            // 記漫畫來源網址（當下網址＝漫畫首頁/目錄頁），供日後「繼續擷取」。
                                            // 取址走共用的 currentUrl()（webView.url 空 → 退回 loadedUrl）；
                                            // 無效網址回 null、底層 writeMangaMeta 也會再擋一次（件 1a）。
                                            onWriteMangaMeta(newBook, currentUrl())
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

                    // 頁面設定 panel（階段 4）：**逐站**的畫布寬度% + 去頭去尾裁切入口。
                    // 兩者都以當前網址的 host 為 key 記住，換到該站自動套用。
                    if (pagePanelExpanded && !singleShotMode && !continuousRunning) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = barColor,
                            contentColor = barContentColor,
                            shape = MaterialTheme.shapes.large,
                            shadowElevation = 3.dp,
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                                    // 加了自動翻頁一整段後可能超過一屏 → panel 內部可捲（不擠爆版面）。
                                    .heightIn(max = PAGE_PANEL_MAX_HEIGHT)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                // 這組設定套用在哪一站（讓使用者知道是逐站記憶、不是全域）。
                                Text(
                                    text = stringResource(
                                        MR.strings.capture_site_settings_for,
                                        siteHost.orEmpty(),
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                // ① 畫布寬度%：寬螢幕上 100%（滿版）會讓漫畫太高、一屏放不下 → 把**網頁畫布本身
                                // 縮窄並置中**（左右對稱留白、透出畫面底色），網頁 responsive 重排後整頁變矮、
                                // 一屏塞得下。★ 拖曳即時生效、**不重載**（不掉捲動位置）；放開滑桿（或按 −/＋）
                                // 才寫回 pref 做逐站記憶。
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(MR.strings.capture_canvas_width),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "$scaleDraft%",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(
                                        onClick = {
                                            val next = (scaleDraft - CAPTURE_SCALE_STEP)
                                                .coerceIn(CAPTURE_SCALE_MIN, CAPTURE_SCALE_MAX)
                                            scaleDraft = next
                                            commitSiteSetting(siteSetting.copy(scale = next))
                                        },
                                        enabled = scaleDraft > CAPTURE_SCALE_MIN,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Remove,
                                            contentDescription = stringResource(MR.strings.capture_canvas_narrower),
                                        )
                                    }
                                    Slider(
                                        value = scaleDraft.toFloat(),
                                        onValueChange = { scaleDraft = it.roundToInt() },
                                        onValueChangeFinished = {
                                            commitSiteSetting(siteSetting.copy(scale = scaleDraft))
                                        },
                                        valueRange = CAPTURE_SCALE_MIN.toFloat()..CAPTURE_SCALE_MAX.toFloat(),
                                        steps = (CAPTURE_SCALE_MAX - CAPTURE_SCALE_MIN) / CAPTURE_SCALE_STEP - 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = {
                                            val next = (scaleDraft + CAPTURE_SCALE_STEP)
                                                .coerceIn(CAPTURE_SCALE_MIN, CAPTURE_SCALE_MAX)
                                            scaleDraft = next
                                            commitSiteSetting(siteSetting.copy(scale = next))
                                        },
                                        enabled = scaleDraft < CAPTURE_SCALE_MAX,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Add,
                                            contentDescription = stringResource(MR.strings.capture_canvas_wider),
                                        )
                                    }
                                }

                                // ② 去頭去尾：顯示目前設定 + 進入拖線的裁切設定模式。
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = if (siteSetting.hasCrop) {
                                            stringResource(
                                                MR.strings.capture_crop_current,
                                                (siteSetting.cropTop * 100).roundToInt(),
                                                (siteSetting.cropBottom * 100).roundToInt(),
                                            )
                                        } else {
                                            stringResource(MR.strings.capture_crop_none)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = {
                                            cropTopDraft = siteSetting.cropTop
                                            cropBottomDraft = siteSetting.cropBottom
                                            cropSetupMode = true
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ContentCut,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = stringResource(MR.strings.capture_crop_setup),
                                            modifier = Modifier.padding(start = 4.dp),
                                        )
                                    }
                                }

                                // ③ 自動翻頁（逐站）：連續擷取存完一頁後自動點該站的「下一頁」→ 全自動擷取。
                                // 開關 + 點擊位置 + 點擊延遲 + 本話頁數，全放這裡（都與「這一站怎麼截」同一組設定）。
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(MR.strings.capture_autotap),
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        Text(
                                            text = stringResource(MR.strings.capture_autotap_summary),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = siteSetting.autoTap,
                                        onCheckedChange = { commitSiteSetting(siteSetting.copy(autoTap = it)) },
                                    )
                                }

                                if (siteSetting.autoTap) {
                                    // 點擊位置（比例座標、逐站記憶）：顯示目前值 + 進入拖標記的設定模式。
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = if (siteSetting.hasTapPoint) {
                                                stringResource(
                                                    MR.strings.capture_tap_point_current,
                                                    ((siteSetting.tapX ?: 0f) * 100).roundToInt(),
                                                    ((siteSetting.tapY ?: 0f) * 100).roundToInt(),
                                                )
                                            } else {
                                                stringResource(MR.strings.capture_tap_point_none)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(
                                            onClick = {
                                                tapXDraft = siteSetting.tapX ?: TAP_DEFAULT_X
                                                tapYDraft = siteSetting.tapY ?: TAP_DEFAULT_Y
                                                tapSetupMode = true
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.TouchApp,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Text(
                                                text = stringResource(MR.strings.capture_tap_point_setup),
                                                modifier = Modifier.padding(start = 4.dp),
                                            )
                                        }
                                    }

                                    // 點擊延遲：存完一頁 → 等多久才點。太短會在頁面還沒載完就點、太長浪費時間。
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(MR.strings.capture_tap_delay),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text(
                                            text = "$tapDelayDraft ms",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        IconButton(
                                            onClick = {
                                                val next = (tapDelayDraft - CAPTURE_TAP_DELAY_STEP)
                                                    .coerceIn(CAPTURE_TAP_DELAY_MIN, CAPTURE_TAP_DELAY_MAX)
                                                tapDelayDraft = next
                                                commitSiteSetting(siteSetting.copy(tapDelayMs = next))
                                            },
                                            enabled = tapDelayDraft > CAPTURE_TAP_DELAY_MIN,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Remove,
                                                contentDescription = stringResource(
                                                    MR.strings.capture_tap_delay_shorter,
                                                ),
                                            )
                                        }
                                        Slider(
                                            value = tapDelayDraft.toFloat(),
                                            onValueChange = { tapDelayDraft = it.roundToInt() },
                                            onValueChangeFinished = {
                                                commitSiteSetting(siteSetting.copy(tapDelayMs = tapDelayDraft))
                                            },
                                            valueRange = CAPTURE_TAP_DELAY_MIN.toFloat()..
                                                CAPTURE_TAP_DELAY_MAX.toFloat(),
                                            steps = (CAPTURE_TAP_DELAY_MAX - CAPTURE_TAP_DELAY_MIN) /
                                                CAPTURE_TAP_DELAY_STEP - 1,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(
                                            onClick = {
                                                val next = (tapDelayDraft + CAPTURE_TAP_DELAY_STEP)
                                                    .coerceIn(CAPTURE_TAP_DELAY_MIN, CAPTURE_TAP_DELAY_MAX)
                                                tapDelayDraft = next
                                                commitSiteSetting(siteSetting.copy(tapDelayMs = next))
                                            },
                                            enabled = tapDelayDraft < CAPTURE_TAP_DELAY_MAX,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Add,
                                                contentDescription = stringResource(
                                                    MR.strings.capture_tap_delay_longer,
                                                ),
                                            )
                                        }
                                    }
                                    Text(
                                        text = stringResource(MR.strings.capture_tap_delay_summary),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                // ④ 本話頁數（選填、**per-session 不記憶**）：截滿即自動停止進確認頁。
                                // 刻意**不**綁自動翻頁——手動翻頁時「這話 16 頁、截滿自動停」一樣好用
                                // （model 的停止條件①與自動翻頁無關）。留空＝不設上限，行為完全同以前。
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = targetPagesDraft,
                                    onValueChange = { input ->
                                        targetPagesDraft = input.filter { it.isDigit() }.take(4)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(MR.strings.capture_target_pages)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                                Text(
                                    text = stringResource(MR.strings.capture_target_pages_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                    // 自動翻頁開著時提示改成「自動翻頁中」（不必叫使用者翻頁）；有填本話頁數就顯示 X/N。
                    val autoTurning = siteSetting.autoTapReady
                    Text(
                        text = when {
                            justCaptured != null ->
                                stringResource(MR.strings.capture_continuous_saved_hint, justCaptured)
                            autoTurning && capturedTarget != null -> stringResource(
                                MR.strings.capture_continuous_hint_auto_target,
                                capturedCount,
                                capturedTarget,
                            )
                            autoTurning ->
                                stringResource(MR.strings.capture_continuous_hint_auto, capturedCount)
                            capturedTarget != null -> stringResource(
                                MR.strings.capture_continuous_hint_target,
                                capturedCount,
                                capturedTarget,
                            )
                            else -> stringResource(MR.strings.capture_continuous_hint, capturedCount)
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

            // 加入最愛對話框：輸入別名（預設帶 host）→ 確定＝addBookmark（同 url 覆蓋別名並移到最前）。
            val dialogUrl = bookmarkDialogUrl
            if (dialogUrl != null) {
                AlertDialog(
                    onDismissRequest = { bookmarkDialogUrl = null },
                    title = { Text(stringResource(MR.strings.capture_bookmark_add)) },
                    text = {
                        Column {
                            Text(
                                text = dialogUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = bookmarkAliasDraft,
                                onValueChange = { bookmarkAliasDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(MR.strings.capture_bookmark_alias)) },
                                singleLine = true,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onAddBookmark(dialogUrl, bookmarkAliasDraft)
                                bookmarks = bookmarksProvider()
                                bookmarkDialogUrl = null
                            },
                            enabled = bookmarkAliasDraft.isNotBlank(),
                        ) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { bookmarkDialogUrl = null }) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    },
                )
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

        // ── 封面框選 overlay（件 1）─────────────────────────────────────────────
        // 疊在**仍然活著**的 WebView 上。★ 2026-07：可拖曳/遮罩那層**對齊 WebView 的實際 bounds**
        // （同樣的系統列 padding + 同樣的置中 [canvasFraction] 寬）——畫布縮窄時左右留白不屬於截圖 bitmap，
        // 使用者本就不該框到那裡；對齊後拖出來的座標天生落在 bitmap 內（下方 clamp 只當保險）。
        // 提示 / 動作列仍走全螢幕（操作 UI 不跟著縮）。
        // 截圖前一併藏起（!hideOverlayForCapture gate）→ 框選框本身不會入鏡（護欄：截圖零 overlay）。
        if (coverCropMode && !hideOverlayForCapture && !reviewMode) {
            val cropStrokeColor = MaterialTheme.colorScheme.primary
            Box(modifier = Modifier.fillMaxSize()) {
                // 與 WebView 同一塊區域（系統列 padding 一致）→ 內層再取畫布寬度、置中。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(canvasFraction)
                            // 這層的 window 原點＝WebView 的 window 原點（captureCover 用它換算 bitmap 座標）。
                            .onGloballyPositioned { cropOverlayOrigin = it.positionInWindow() }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume() // 吃掉觸控 → 框選期間 WebView 不捲/不點
                                    cropStart = down.position
                                    cropEnd = down.position
                                    drag(down.id) { change ->
                                        change.consume()
                                        cropEnd = change.position
                                    }
                                }
                            },
                    ) {
                        // dim 四周 + 選取框：不用 BlendMode（避免清到底下），改畫「選取框以外的四塊」半透明遮罩。
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val dim = Color.Black.copy(alpha = 0.5f)
                            val s = cropStart
                            val e = cropEnd
                            if (s == null || e == null) {
                                drawRect(color = dim)
                            } else {
                                val l = minOf(s.x, e.x)
                                val t = minOf(s.y, e.y)
                                val r = maxOf(s.x, e.x)
                                val b = maxOf(s.y, e.y)
                                drawRect(color = dim, topLeft = Offset(0f, 0f), size = Size(size.width, t))
                                drawRect(
                                    color = dim,
                                    topLeft = Offset(0f, b),
                                    size = Size(size.width, size.height - b),
                                )
                                drawRect(color = dim, topLeft = Offset(0f, t), size = Size(l, b - t))
                                drawRect(color = dim, topLeft = Offset(r, t), size = Size(size.width - r, b - t))
                                drawRect(
                                    color = cropStrokeColor,
                                    topLeft = Offset(l, t),
                                    size = Size(r - l, b - t),
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                            }
                        }
                    }
                }

                // 頂部提示：拖曳框選封面範圍。
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(8.dp),
                    color = barColor,
                    contentColor = barContentColor,
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 3.dp,
                ) {
                    Text(
                        text = stringResource(MR.strings.capture_cover_crop_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                // 底部動作：截取封面（有框才可按）/ 取消。
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(8.dp),
                    color = barColor,
                    contentColor = barContentColor,
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { captureCover() },
                            enabled = cropStart != null && cropEnd != null,
                        ) {
                            Icon(imageVector = Icons.Outlined.Crop, contentDescription = null)
                            Text(
                                text = stringResource(MR.strings.capture_cover_capture),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        TextButton(
                            onClick = {
                                coverCropMode = false
                                cropStart = null
                                cropEnd = null
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    }
                }
            }
        }

        // ── 去頭去尾裁切設定 overlay（件 3-A：手動兩線）────────────────────────────
        // 疊在**仍然活著**的 WebView 上。★ 2026-07：拖線/遮罩那層與 WebView 完全同一塊（系統列 padding + 置中的
        // [canvasFraction] 寬）⇒ 遮罩只蓋在**畫布**上，一眼看得出「裁的是畫布這塊」；高度不受畫布寬度影響，
        // 線的位置除以本層高度＝要存的比例（與截圖 bitmap 的高度座標系一致，captureWebView 抓的就是 WebView 那塊）。
        // 兩條線之間＝保留區，上下＝半透明遮罩（＝存檔時會被裁掉的部分）。截圖時本層一併藏起（護欄：截圖零 overlay），
        // **實際裁切是存檔時在 bitmap 上做**（見 CaptureScreenModel.cropForSave），與 overlay 無關。
        if (cropSetupMode && !hideOverlayForCapture && !reviewMode) {
            val cropLineColor = MaterialTheme.colorScheme.primary
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(canvasFraction)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume() // 吃掉觸控 → 拖線期間 WebView 不捲/不點
                                    val h = size.height.toFloat()
                                    if (h <= 0f) return@awaitEachGesture
                                    // 按下點離哪條線近就拖哪條（頂線／底線）。
                                    val topY = cropTopDraft * h
                                    val bottomY = (1f - cropBottomDraft) * h
                                    val dragTop = abs(down.position.y - topY) <= abs(down.position.y - bottomY)
                                    fun moveTo(y: Float) {
                                        val f = (y / h).coerceIn(0f, 1f)
                                        if (dragTop) {
                                            cropTopDraft = f.coerceAtMost(
                                                (1f - cropBottomDraft - CROP_MIN_KEEP_FRACTION).coerceAtLeast(0f),
                                            )
                                        } else {
                                            cropBottomDraft = (1f - f).coerceAtMost(
                                                (1f - cropTopDraft - CROP_MIN_KEEP_FRACTION).coerceAtLeast(0f),
                                            )
                                        }
                                    }
                                    moveTo(down.position.y)
                                    drag(down.id) { change ->
                                        change.consume()
                                        moveTo(change.position.y)
                                    }
                                }
                            },
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val dim = Color.Black.copy(alpha = 0.55f)
                            val topY = cropTopDraft * size.height
                            val bottomY = (1f - cropBottomDraft) * size.height
                            // 會被裁掉的頭尾＝遮罩；中間保留區維持透明（看得到真實網頁）。
                            drawRect(color = dim, topLeft = Offset(0f, 0f), size = Size(size.width, topY))
                            drawRect(
                                color = dim,
                                topLeft = Offset(0f, bottomY),
                                size = Size(size.width, size.height - bottomY),
                            )
                            val stroke = 2.dp.toPx()
                            drawRect(
                                color = cropLineColor,
                                topLeft = Offset(0f, topY - stroke / 2),
                                size = Size(size.width, stroke),
                            )
                            drawRect(
                                color = cropLineColor,
                                topLeft = Offset(0f, bottomY - stroke / 2),
                                size = Size(size.width, stroke),
                            )
                            // 把手：兩條線中央各畫一段粗條，讓人知道可以拖。
                            val handleW = 56.dp.toPx()
                            val handleH = 6.dp.toPx()
                            drawRect(
                                color = cropLineColor,
                                topLeft = Offset((size.width - handleW) / 2, topY - handleH / 2),
                                size = Size(handleW, handleH),
                            )
                            drawRect(
                                color = cropLineColor,
                                topLeft = Offset((size.width - handleW) / 2, bottomY - handleH / 2),
                                size = Size(handleW, handleH),
                            )
                        }
                    }
                }

                // 頂部提示（含目前比例）。工具列類元件走全螢幕寬（不跟著畫布縮），自帶系統列 padding。
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(8.dp),
                    color = barColor,
                    contentColor = barContentColor,
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 3.dp,
                ) {
                    Text(
                        text = stringResource(
                            MR.strings.capture_crop_hint,
                            (cropTopDraft * 100).roundToInt(),
                            (cropBottomDraft * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                // 底部動作：自動偵測 / 儲存 / 不裁切 / 取消。
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(8.dp),
                    color = barColor,
                    contentColor = barContentColor,
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 3.dp,
                ) {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(onClick = { autoDetectCrop() }, enabled = !cropAutoBusy) {
                            Text(text = stringResource(MR.strings.capture_crop_auto))
                        }
                        Button(
                            onClick = {
                                commitSiteSetting(
                                    siteSetting.copy(cropTop = cropTopDraft, cropBottom = cropBottomDraft),
                                )
                                cropSetupMode = false
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_save))
                        }
                        TextButton(
                            onClick = {
                                cropTopDraft = 0f
                                cropBottomDraft = 0f
                                commitSiteSetting(siteSetting.copy(cropTop = 0f, cropBottom = 0f))
                                cropSetupMode = false
                            },
                        ) {
                            Text(text = stringResource(MR.strings.capture_crop_clear))
                        }
                        TextButton(onClick = { cropSetupMode = false }) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    }
                }
            }
        }

        // ── 自動翻頁的點擊位置設定 overlay（件 1）──────────────────────────────────
        // 疊在**仍然活著**的 WebView 上（不重建、不 loadUrl）。可拖曳的圓形標記＋十字準心：拖到該站
        // 「下一頁」按鈕的位置 → 儲存。★ 與裁切設定 overlay 同一套座標系規則：這層與 WebView 完全同一塊
        // （系統列 padding + 置中的 [canvasFraction] 寬），所以「標記位置 ÷ 本層寬高」＝「點擊位置 ÷ WebView 寬高」
        // ⇒ 存下來的比例座標乘回 `wv.width/height` 就是同一個點（見 [dispatchTapAt]）。
        // 不畫 dim（使用者要看清楚網頁上的「下一頁」鈕才對得準）；截圖時整層藏起（護欄：截圖零 overlay）。
        if (tapSetupMode && !hideOverlayForCapture && !reviewMode) {
            val markerColor = MaterialTheme.colorScheme.primary
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(canvasFraction)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume() // 吃掉觸控 → 設定期間 WebView 不捲/不點
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    if (w <= 0f || h <= 0f) return@awaitEachGesture
                                    // 點哪就把標記移到哪（不必先精準按在標記上），之後可拖曳微調。
                                    fun moveTo(p: Offset) {
                                        tapXDraft = (p.x / w).coerceIn(0f, 1f)
                                        tapYDraft = (p.y / h).coerceIn(0f, 1f)
                                    }
                                    moveTo(down.position)
                                    drag(down.id) { change ->
                                        change.consume()
                                        moveTo(change.position)
                                    }
                                }
                            },
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val center = Offset(tapXDraft * size.width, tapYDraft * size.height)
                            val radius = TAP_MARKER_SETUP_RADIUS.toPx()
                            drawCircle(color = markerColor.copy(alpha = 0.28f), radius = radius, center = center)
                            drawCircle(
                                color = markerColor,
                                radius = radius,
                                center = center,
                                style = Stroke(width = 2.dp.toPx()),
                            )
                            // 十字準心：讓使用者對得準按鈕中心（圓的中心不好目測）。
                            val arm = radius * 1.6f
                            val hair = 1.5.dp.toPx()
                            drawLine(
                                color = markerColor,
                                start = Offset(center.x - arm, center.y),
                                end = Offset(center.x + arm, center.y),
                                strokeWidth = hair,
                            )
                            drawLine(
                                color = markerColor,
                                start = Offset(center.x, center.y - arm),
                                end = Offset(center.x, center.y + arm),
                                strokeWidth = hair,
                            )
                        }
                    }
                }

                // 頂部提示（含目前比例）。工具列類元件走全螢幕寬（不跟著畫布縮），自帶系統列 padding。
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(8.dp),
                    color = barColor,
                    contentColor = barContentColor,
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 3.dp,
                ) {
                    Text(
                        text = stringResource(
                            MR.strings.capture_tap_point_hint,
                            (tapXDraft * 100).roundToInt(),
                            (tapYDraft * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                // 底部動作：儲存 / 清除位置 / 取消。
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(8.dp),
                    color = barColor,
                    contentColor = barContentColor,
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 3.dp,
                ) {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Button(
                            onClick = {
                                commitSiteSetting(siteSetting.copy(tapX = tapXDraft, tapY = tapYDraft))
                                tapSetupMode = false
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_save))
                        }
                        TextButton(
                            onClick = {
                                commitSiteSetting(siteSetting.copy(tapX = null, tapY = null))
                                tapSetupMode = false
                            },
                        ) {
                            Text(text = stringResource(MR.strings.capture_tap_point_clear))
                        }
                        TextButton(onClick = { tapSetupMode = false }) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    }
                }
            }
        }

        // ── 全屏清單（歷史 / 我的最愛）overlay（件 4）─────────────────────────────
        // 塞在瀏覽 panel 容量太小 → 點導覽列入口開這層全屏可捲清單（LazyColumn 捲全部、容納大量紀錄）。
        // ★ 用「同 composition 內的 overlay」而非 push 新 Screen：護欄「WebView 常駐」——push 會 dispose 本
        // composition → WebView 重建（捲動 / 登入 / JS 全丟）。與截圖同一組 gate（!hideOverlay/!review/!coverCrop）
        // → 截圖不入鏡。底層工具列仍在（被不透明 Surface 蓋住）→ 用無漣漪 clickable 吸收空白處觸控、避免穿透誤觸。
        val sheet = listSheet
        if (sheet != null && !hideOverlayForCapture && !reviewMode && !coverCropMode) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {}
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    // 頂列：返回（關清單）+ 標題。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { listSheet = null }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(MR.strings.action_close),
                            )
                        }
                        Text(
                            text = stringResource(
                                if (sheet == CaptureListSheet.HISTORY) {
                                    MR.strings.capture_url_history
                                } else {
                                    MR.strings.capture_bookmarks
                                },
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }

                    when (sheet) {
                        // 歷史：title（主，空退回 url）+ url（次要小字）；點載入、加入最愛、刪除。
                        CaptureListSheet.HISTORY -> {
                            if (history.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(MR.strings.capture_history_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(items = history, key = { it.url }) { entry ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { go(urlOverride = entry.url) },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.History,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .padding(start = 12.dp, end = 12.dp)
                                                    .size(20.dp),
                                            )
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(vertical = 10.dp),
                                            ) {
                                                Text(
                                                    text = entry.title.ifBlank { entry.url },
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = entry.url,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            // 加入最愛：別名預設帶標題（沒有就帶 host）。
                                            IconButton(
                                                onClick = {
                                                    bookmarkAliasDraft = entry.title
                                                        .ifBlank { defaultBookmarkAlias(entry.url) }
                                                    bookmarkDialogUrl = entry.url
                                                },
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.StarBorder,
                                                    contentDescription = stringResource(
                                                        MR.strings.capture_bookmark_add,
                                                    ),
                                                )
                                            }
                                            // 刪除該筆歷史。
                                            IconButton(
                                                onClick = {
                                                    history = history.filterNot { it.url == entry.url }
                                                    onRemoveUrl(entry.url)
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
                        // 我的最愛：alias（主）+ url（次要小字）；點載入、刪除。
                        CaptureListSheet.BOOKMARKS -> {
                            if (bookmarks.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(MR.strings.capture_bookmarks_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(items = bookmarks, key = { it.url }) { bm ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { go(urlOverride = bm.url) },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Star,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .padding(start = 12.dp, end = 12.dp)
                                                    .size(20.dp),
                                            )
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(vertical = 10.dp),
                                            ) {
                                                Text(
                                                    text = bm.alias,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = bm.url,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    bookmarks = bookmarks.filterNot { it.url == bm.url }
                                                    onRemoveBookmark(bm.url)
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
