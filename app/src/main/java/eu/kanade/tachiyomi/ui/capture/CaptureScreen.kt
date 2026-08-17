package eu.kanade.tachiyomi.ui.capture

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.capture.CaptureReviewScreenContent
import eu.kanade.presentation.capture.CaptureScreenContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 抓一幀 WebView 畫面：呼叫端（Composable）把 [eu.kanade.presentation.webview.captureWebView] 綁好
 * webView / window 後傳進來，model 只管「要一張 Bitmap」；截不到回傳 null。回呼可在任意執行緒觸發
 * （PixelCopy 走主執行緒 Handler），model 端用 suspend 包裝統一在主執行緒發起。
 */
typealias FrameGrabber = (onResult: (Bitmap?) -> Unit) -> Unit

// ── 半自動連續截圖的可調常數（好調、集中放頂層）───────────────────────────────
// 抓幀頻率：500ms 一次（全螢幕 PixelCopy，別更密以免發熱/卡）。
private const val CAPTURE_INTERVAL_MS = 500L

// 比對縮圖邊長（灰階 THUMB×THUMB），只用小圖算差、快。
private const val THUMB_SIZE = 32

// 差異度量＝縮圖灰階「平均絕對差」(MAD)，單位＝亮度 0–255。選 MAD 而非 aHash：量值連續、好在真機調門檻，
// 且不會像 aHash 在均值附近的二值量化那樣因微小亮度抖動產生大漢明跳動（誤判閃爍）。
// 穩定門檻：當前幀 vs 前一幀 < 此值 ⇒ 畫面已靜止（翻頁/載入結束）。
private const val STABLE_THRESHOLD = 2.0

// 換頁門檻：當前幀 vs「上次已截那頁」> 此值 ⇒ 真的換頁了（去重：停在同頁不會重截）。
private const val CHANGE_THRESHOLD = 10.0

// 空白/黑頁門檻：縮圖亮度「值域(max-min)」< 此值 ⇒ 近乎純色（載入過場黑頁 / 純白頁）⇒ 跳過不截。
// 漫畫頁通常黑白對比大、值域 >100；載入全黑或純白頁值域 ≈0。真機可調。
private const val BLANK_RANGE_THRESHOLD = 36

// 存完一頁後暫停偵測的時間：期間顯示「已擷取第 N 頁 · 請翻下一頁」，也順便避開使用者翻頁動作中的中間幀。
private const val CAPTURE_PAUSE_MS = 1800L

// 網址列輸入歷史保留上限（與 MoreViewModel 一致）。
private const val MAX_WEBVIEW_URL_HISTORY = 20

// 封面檔名：對齊 LocalCoverManager 的 DEFAULT_COVER_NAME＝存書名夾根的 `cover.jpg`，LocalSource 才認得
// （find() 找 nameWithoutExtension=="cover" 的圖）。存這個名字 → 書櫃自動顯示封面。
private const val COVER_NAME = "cover.jpg"

// ── 逐站設定（畫布寬度% + 去頭去尾裁切）的可調常數 ──────────────────────────────
// 畫布寬度可調範圍（%）：低於 50% 內容太小、高於 100% 沒有意義（100＝網站原本的 fit 寬度）。
const val CAPTURE_SCALE_MIN = 50
const val CAPTURE_SCALE_MAX = 100

// 畫布寬度 −/＋ 一次的級距（%）。
const val CAPTURE_SCALE_STEP = 5

// 裁切後最少要留幾 px 高（防呆：設定壞掉時寧可整張存、不要存出一條線）。
private const val MIN_CROP_KEEP_PX = 32

// ── 自動翻頁（連續擷取時模擬點擊網頁的「下一頁」）的可調常數 ──────────────────
// 存完一頁 → 等多久才派送點擊（ms）。太短會在頁面還沒載完就點、太長浪費時間；逐站可調（見 [CaptureSiteSetting]）。
const val CAPTURE_TAP_DELAY_MIN = 200
const val CAPTURE_TAP_DELAY_MAX = 3000
const val CAPTURE_TAP_DELAY_STEP = 100
const val CAPTURE_TAP_DELAY_DEFAULT = 800

// 派送點擊後再等多久還沒偵測到新頁，就判定「這次點擊沒作用」＝ tapDelay + 這個寬限（ms）。
// 只有畫面**仍與上次截的那頁一模一樣**才算無效（載入中/尚未穩定的新頁會延長等待、不重點 → 免跳頁）。
private const val CAPTURE_TAP_GRACE_MS = 3000L

// 連續幾次點擊都沒讓畫面換頁就自動停止（到最後一頁 / 位置點錯 / 頁數填錯時的保險，避免無限空轉）。
private const val CAPTURE_MAX_IDLE_TAPS = 2

// 連續幾頁「存檔失敗」就自動停止（避免整話翻完才發現一張都沒存）。1 次可能只是偶發的 SAF 失敗，
// 2 次就該停下來告訴使用者；書名/章名沒填那種「設定不完整」不吃這個門檻、直接停。
private const val CAPTURE_MAX_SAVE_FAILS = 2

/**
 * 自動翻頁的點擊派送器：連續擷取存完一頁（或補點）時由 model 呼叫，回 true＝已派送。
 * 實作在畫面層（[eu.kanade.presentation.capture.CaptureScreenContent]）——需要 WebView 的**本地座標**
 * 與主執行緒，且**只用 `dispatchTouchEvent`、不注入 JS、不碰 DOM**（本工具的護欄）。
 */
typealias PageTapper = suspend () -> Boolean

/**
 * 開始連續擷取的呼叫（畫面層 → model）：比較幀 / 乾淨幀 / 網址讀取器 /
 * 本話頁數（選填，null＝不設上限）/ 點擊延遲（ms）/ 自動翻頁點擊器（null＝不自動翻頁）。
 */
typealias StartContinuous = (
    compareGrabber: FrameGrabber,
    cleanGrabber: FrameGrabber,
    urlProvider: () -> String?,
    targetPages: Int?,
    tapDelayMs: Int,
    autoTap: PageTapper?,
) -> Unit

/**
 * 連續擷取為什麼停下來（一次性事件，帶進確認頁顯示一行提示）。
 * - [TARGET_REACHED]＝截滿使用者填的本話頁數。
 * - [TAP_NO_EFFECT]＝自動翻頁連點 [CAPTURE_MAX_IDLE_TAPS] 次畫面都沒換（到最後一頁 / 位置點錯）。
 * - [SAVE_FAILED]＝存檔失敗（書名章名沒填＝立刻停；寫檔失敗＝連 2 頁都失敗才停），細節見
 *   [ContinuousCaptureState.stopDetail]。★ 這條以前不存在：存檔一直失敗時迴圈會每 500ms 重試到天荒地老、
 *   進度永遠停在「已截 0 頁」，使用者可能翻完整話才發現一張都沒存。
 * - [MANUAL]＝使用者按停止 / 畫面離開 / app 進背景（畫面層**不**據此自動進確認頁，按停止那條路自己會進）。
 */
enum class CaptureStopReason { TARGET_REACHED, TAP_NO_EFFECT, SAVE_FAILED, MANUAL }

/**
 * 連續截圖狀態：是否進行中 + 本 session 已截頁數（給 UI 顯示「已截 N 頁」）+
 * [justCapturedPage]＝剛存下的頁碼（非 null 時 UI 顯示「已擷取第 N 頁 · 請翻下一頁」提示，[CAPTURE_PAUSE_MS] 後回 null）。
 *
 * [targetPages]＝本話頁數（使用者選填，null＝沒設）：有值時 UI 顯示「已截 5/16 頁」、截滿即自動停止。
 * [stopReason]＝這一輪為什麼停（見 [CaptureStopReason]）；非 null 且非 [CaptureStopReason.MANUAL] 時畫面層
 * 比照按停止進確認頁並顯示原因，消費後要呼叫 [CaptureViewModel.consumeStopReason] 歸零（一次性事件）。
 * [stopDetail]＝[CaptureStopReason.SAVE_FAILED] 時的失敗細項（供確認頁講清楚是哪一種失敗）。
 */
data class ContinuousCaptureState(
    val running: Boolean = false,
    val count: Int = 0,
    val justCapturedPage: Int? = null,
    val targetPages: Int? = null,
    val stopReason: CaptureStopReason? = null,
    val stopDetail: CaptureSaveError? = null,
)

/**
 * 擷取畫面的模式（★ WebView 常駐的關鍵）：三個模式共用**同一個 composition、同一顆 WebView**，
 * 確認 / 重截 / 插入都不再 push 新 Screen（push＝CaptureScreen composition 被 dispose＝WebView 重建、
 * 捲動 / 登入 / JS 狀態全丟）。
 * - [CAPTURING]＝正常擷取（工具列 + 連續截圖）。
 * - [REVIEW]＝確認面板全屏蓋在 WebView 上（WebView 仍活著、停在按停止時那頁）。
 * - [SINGLE_SHOT]＝單張重截或插入（只留一顆擷取鈕；該頁有記網址才 loadUrl 過去、沒有就保持現狀）。
 */
enum class CaptureMode { CAPTURING, REVIEW, SINGLE_SHOT }

/**
 * Yakuyomi 擷取漫畫（B1a-1 骨架）：內建 WebView 開任意網站 → 「截這頁」→ 存成 LocalSource 的
 * 一本漫畫 / 一話的鬆散頁圖，證明「截圖 → local 漫畫 → 書庫看得到 + 能翻譯」整條路通。
 *
 * 此步範圍嚴格限定：書名 / 章名手動輸入、存整張截圖（不裁切）、單一 session 頁碼遞增。
 * 裁切、選書流程、話數建議、cover/metadata 皆為後續步驟。
 *
 * B1b 半自動連續截圖：使用者手動翻頁，app 用 frame-diff 雙門檻（穩定 + 換頁）自動偵測、自動截存。
 *
 * ★ WebView 常駐（2026-07 重構）：確認頁 / 重截 / 插入**全部改成本畫面內的 [CaptureMode] 切換**，
 * 不再 push 新 Screen —— 底層 WebView 從進畫面到「儲存」為止都活在同一個 composition 裡，
 * 捲動位置 / 登入 cookie / JS 狀態一路保留，「繼續擷取」回去就是按停止時那一頁。
 */
class CaptureScreen(
    private val initialUrl: String = "",
    // 「繼續擷取」帶入的書名（詳情頁 overflow 入口）：非空時進畫面即設 [CaptureViewModel.bookName]，
    // 漸進解鎖直接到 S2（書名已定、只差設話數）；null/空＝全新擷取（走 S0）。★ 這個書名必須讓
    // saveCapture 的 safeBook=buildValidFilename(book) 對回原夾（詳見詳情頁 MangaViewModel.buildContinueCaptureArgs）。
    private val initialBook: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = viewModel<CaptureViewModel>()

        // 「繼續擷取」：進畫面把帶入的書名塞進 model（一次性；bookName 非空 → 漸進解鎖到 S2）。
        LaunchedEffect(Unit) {
            initialBook?.trim()?.takeIf { it.isNotEmpty() }?.let { viewModel.bookName = it }
        }
        // 確認面板的 model 與擷取畫面同壽命（不再是獨立 Screen 的 model）：邏輯完全沿用，
        // 只在每次進入確認模式時 configure 目標章夾 + 本次 session 頁碼。
        val reviewModel = viewModel<CaptureReviewViewModel>(key = "capture-review")
        val continuous by viewModel.continuous.collectAsState()
        val reviewState by reviewModel.state.collectAsState()

        // 目前模式 + 單張模式（重截/插入）的目標；shotToken 每次進單張模式遞增，供內容層重新 loadUrl。
        var mode by remember { mutableStateOf(CaptureMode.CAPTURING) }
        var reCaptureTarget by remember { mutableStateOf<ReCaptureTarget?>(null) }
        var insertTarget by remember { mutableStateOf<InsertTarget?>(null) }
        var shotToken by remember { mutableIntStateOf(0) }

        /**
         * 進確認模式：WebView 原地不動，只是被確認面板蓋住。
         * [stopReason]/[stopDetail]＝連續擷取**自己**停下來的原因（按停止 / 重截插入完成回來＝null，不顯示提示）。
         */
        fun enterReview(
            stopReason: CaptureStopReason? = null,
            stopDetail: CaptureSaveError? = null,
        ) {
            reCaptureTarget = null
            insertTarget = null
            mode = CaptureMode.REVIEW
            reviewModel.configure(
                viewModel.bookName,
                viewModel.chapterName,
                viewModel.sessionPages,
                stopReason,
                stopDetail,
            )
        }

        LaunchedEffect(Unit) {
            reviewModel.events.collectLatest { event ->
                when (event) {
                    // 儲存完成＝整個擷取流程結束，這時才離開畫面（WebView 到此才銷毀）。
                    is CaptureReviewEvent.OpenManga -> navigator.replace(MangaScreen(event.mangaId))
                    // 儲存失敗＝**停在確認頁**（不靜默把人踢回擷取模式），只吐一則可讀訊息讓使用者重試。
                    is CaptureReviewEvent.Error -> context.toast(event.messageRes)
                    // 放棄這次截圖（或儲存找不到漫畫）→ 回擷取模式續用同一顆 WebView。
                    CaptureReviewEvent.Back -> {
                        reCaptureTarget = null
                        insertTarget = null
                        mode = CaptureMode.CAPTURING
                    }
                    is CaptureReviewEvent.ReCapture -> {
                        insertTarget = null
                        reCaptureTarget = event.target
                        shotToken++
                        mode = CaptureMode.SINGLE_SHOT
                    }
                    is CaptureReviewEvent.Insert -> {
                        reCaptureTarget = null
                        insertTarget = event.target
                        shotToken++
                        mode = CaptureMode.SINGLE_SHOT
                    }
                }
            }
        }

        // 連續擷取的**自動**停止（截滿本話頁數 / 連續點擊沒反應 / 存檔一直失敗）＝比照按停止：
        // 進確認模式帶 sessionPages，並把「為什麼停」帶進去讓確認頁講清楚。
        // [CaptureStopReason.MANUAL]（按停止 / 生命週期）不在此處理——那條路自己會呼叫 enterReview。
        // 一次性事件：切完模式即 consume 歸零，免 recomposition 重複觸發。
        LaunchedEffect(continuous.stopReason) {
            val reason = continuous.stopReason
            if (reason != null && reason != CaptureStopReason.MANUAL) {
                enterReview(reason, continuous.stopDetail)
                viewModel.consumeStopReason()
            }
        }

        val target = reCaptureTarget
        val insert = insertTarget

        CaptureScreenContent(
            onNavigateUp = navigator::pop,
            initialUrl = initialUrl,
            mode = mode,
            bookName = viewModel.bookName,
            onBookNameChange = { viewModel.bookName = it },
            chapterName = viewModel.chapterName,
            onChapterNameChange = { viewModel.chapterName = it },
            // 「新話數」panel 的已截話數總覽 / 話數建議來源：掃該書夾下的話夾名稱。
            existingChaptersProvider = { book -> viewModel.existingChapters(book) },
            onCapture = when {
                target != null -> {
                    { bitmap, url ->
                        viewModel.saveReCapture(bitmap, url, target.safeBook, target.safeChapter, target.pageName)
                    }
                }
                insert != null -> {
                    { bitmap, url ->
                        viewModel.saveInsert(bitmap, url, insert.safeBook, insert.safeChapter, insert.insertAtPage)
                    }
                }
                else -> viewModel::saveCapture
            },
            continuousRunning = continuous.running,
            capturedCount = continuous.count,
            // 本話頁數（使用者選填）：有值時進度顯示「已截 5/16 頁」。
            capturedTarget = continuous.targetPages,
            justCapturedPage = continuous.justCapturedPage,
            onStartContinuous = viewModel::startContinuous,
            onStopContinuous = viewModel::stopContinuous,
            // 按停止＝進確認模式（不 push Screen、WebView 續活）；使用者主動停＝不顯示停止原因提示。
            onEnterReview = { enterReview() },
            reCaptureTargetPage = target?.pageNumber,
            insertTargetPage = insert?.insertAtPage,
            // 單張模式：該頁有記網址才開回去；沒有就保持 WebView 現狀（不是每個站的網址都帶頁資訊）。
            singleShotUrl = target?.url ?: insert?.url,
            singleShotToken = shotToken,
            // 重截 / 插入皆為單張、成功或取消後回確認模式並重掃（顯示更新後的序）。
            onReCaptureDone = { enterReview() },
            onSingleShotCancel = { enterReview() },
            // 確認模式按系統返回＝繼續擷取（回擷取模式、不刪頁）。
            onReviewContinue = { mode = CaptureMode.CAPTURING },
            // 網址列輸入歷史（帶出歷史清單 + 逐筆刪除 + 造訪時記錄；帶頁面標題）。
            urlHistoryProvider = { viewModel.webViewUrlHistory() },
            onAddUrl = { url, title -> viewModel.addWebViewUrl(url, title) },
            onRemoveUrl = { viewModel.removeWebViewUrl(it) },
            // 我的最愛（手動存常用站 + 命名別名；置頂快選、與自動歷史分開）。
            bookmarksProvider = { viewModel.listBookmarks() },
            onAddBookmark = { url, alias -> viewModel.addBookmark(url, alias) },
            onRemoveBookmark = { viewModel.removeBookmark(it) },
            // 封面框選：裁好的 bitmap + bitmap 座標系的裁切框 + 當前書名 → 存書名夾根 cover.jpg，回 uri（縮圖預覽）。
            onSaveCover = { bitmap, rect, book -> viewModel.saveCover(bitmap, rect, book) },
            // 開「新漫畫」panel 時撈該書已存的封面（重進顯示縮圖）。
            coverProvider = { book -> viewModel.findCoverUri(book) },
            // 「新漫畫」確定時記漫畫來源網址（供日後「繼續擷取」）。
            onWriteMangaMeta = { book, url -> viewModel.writeMangaMeta(book, url) },
            // 逐站設定（畫布寬度% + 去頭去尾裁切）：以當前網址的 host 為 key 讀寫。
            siteSettingProvider = { url -> viewModel.siteSettingFor(url) },
            onSaveSiteSetting = { url, setting -> viewModel.saveSiteSetting(url, setting) },
            // 確認面板＝疊在常駐 WebView 上的一層 composable（原本的獨立 Screen 內容，邏輯不變）。
            // 參數 onAdjustTapPoint 由**內容層**提供（點擊位置設定模式的 state 住在那裡）：確認頁的
            // 「自動翻頁沒反應」提示列附一顆鈕直通該模式。
            reviewContent = { onAdjustTapPoint ->
                CaptureReviewScreenContent(
                    state = reviewState,
                    onToggleSelect = reviewModel::toggleSelection,
                    onReCapture = reviewModel::reCapture,
                    onInsert = reviewModel::insert,
                    onDeleteSelected = reviewModel::deleteSelected,
                    onSave = reviewModel::save,
                    // 繼續擷取＝回擷取模式（不儲存 / 不重編號 / 不跳詳情）；WebView 還在按停止時那頁，
                    // 由使用者自己按「開始」續截，新頁碼由存檔時掃章夾 max+1 天然接續。
                    onContinueCapture = { mode = CaptureMode.CAPTURING },
                    onDiscardSession = reviewModel::discardSession,
                    // 本次 session 沒截到新頁時第三顆動作＝「取消擷取」：沒東西可刪，直接離開整個擷取工具。
                    onExitCapture = navigator::pop,
                    // 停止原因＝自動翻頁沒反應時，提示列那顆「調整點擊位置」→ 回擷取模式並直接進位置設定。
                    onAdjustTapPoint = onAdjustTapPoint,
                )
            },
        )
    }
}

/**
 * 重截目標：確認頁點某頁重截時帶進 [CaptureScreen] 的參數。null＝正常擷取模式（完全不變）。
 * [url]＝該頁存檔當下記錄的網址（可能為 null＝當初取不到），[pageName]＝要覆蓋的檔名（如 `003.png`）。
 * 章夾用已 sanitise 的 [safeBook] / [safeChapter] 定位（與存檔時同一套安全檔名）。
 */
data class ReCaptureTarget(
    val url: String?,
    val safeBook: String,
    val safeChapter: String,
    val pageName: String,
) {
    val pageNumber: Int get() = pageName.substringBeforeLast('.').toIntOrNull() ?: 0
}

/**
 * 插入目標：確認頁長按某頁選「在此頁前/後插入」時帶進 [CaptureScreen] 的參數。null＝非插入模式。
 * [insertAtPage]＝新頁要落的頁碼；存檔時把該頁碼（含）以上的既有頁 +1 騰位（見 [CaptureViewModel.saveInsert]）。
 * [url]＝被長按那頁記錄的網址（相鄰頁 URL，供插入時開回附近；可能為 null＝該頁當初取不到網址）。
 * 章夾用已 sanitise 的 [safeBook] / [safeChapter] 定位（與存檔時同一套安全檔名）。
 */
data class InsertTarget(
    val safeBook: String,
    val safeChapter: String,
    val insertAtPage: Int,
    val url: String? = null,
)

/**
 * 逐站（host）的擷取設定：**畫布寬度%** ＋ **去頭去尾裁切比例**（階段 4）。
 *
 * - [scale]＝網頁畫布寬度百分比（[CAPTURE_SCALE_MIN]–[CAPTURE_SCALE_MAX]）。100＝WebView 滿版；
 *   小於 100＝**WebView view 自己變窄並置中**（左右留白），網頁 responsive 依較窄寬度重排 → 圖等比縮小 →
 *   整頁變矮，寬螢幕上一整頁漫畫塞得進一屏、不必上下捲。
 *   ★ 套用方式＝**純 Compose 佈局寬度**（`Modifier.fillMaxWidth(fraction)`），立即生效、不 reload、
 *   **不注入 JS、不碰 DOM**。舊版用 [android.webkit.WebView.setInitialScale] 真機實測無效（頁面自己的
 *   `<meta name="viewport">` 在 `useWideViewPort` 下勝出）＝已移除，詳見 CaptureScreenContent 的 `canvasFraction`。
 * - [cropTop] / [cropBottom]＝**存檔前**從畫面上/下各裁掉多少，單位是**佔畫面高度的比例 0.0–1.0**
 *   （存比例不存像素 → 換解析度 / 旋轉 / 摺疊展開都適用）。0＝不裁。
 * - [autoTap] / [tapX] / [tapY] / [tapDelayMs]＝**自動翻頁**：連續擷取存完一頁後，隔 [tapDelayMs] 由畫面層
 *   對 WebView 派送一次模擬點擊（[PageTapper]），點在該站「下一頁」按鈕上 → 全自動連續擷取。
 *   座標同樣**存比例 0.0–1.0**（佔 WebView 寬/高），null＝還沒設定過位置（此時開關即使開著也不會點）。
 *   ★ 只用 `dispatchTouchEvent`（等同使用者自己點那裡），不注入 JS、不讀 DOM。
 * - [autoTrim]＝**自動修邊**（第二層裁切，**預設開**）：在上面的固定裁切之後、存檔之前，逐頁動態把上下
 *   「大片單色空白」修掉（見 [autoTrimBounds]）。固定裁切是照**正常頁**設的，遇到雙開頁（fit 寬度後高度只有
 *   一半）或彩頁/短頁時，圖片下方會留一大片網站背景、反而把網站頁尾（上一章/下一頁按鈕列）截進來；
 *   自動修邊讓這種頁自動貼齊內容。對正常頁＝no-op（沒有大片空白就不動作，見三道保守護欄）。
 */
data class CaptureSiteSetting(
    val scale: Int = CAPTURE_SCALE_MAX,
    val cropTop: Float = 0f,
    val cropBottom: Float = 0f,
    val autoTap: Boolean = false,
    val tapX: Float? = null,
    val tapY: Float? = null,
    val tapDelayMs: Int = CAPTURE_TAP_DELAY_DEFAULT,
    val autoTrim: Boolean = true,
) {
    /** 全預設（沒縮放、沒裁切、沒自動翻頁、自動修邊仍開著）＝不必寫進 pref。 */
    val isDefault: Boolean
        get() = scale >= CAPTURE_SCALE_MAX && cropTop <= 0f && cropBottom <= 0f &&
            !autoTap && tapX == null && tapY == null && tapDelayMs == CAPTURE_TAP_DELAY_DEFAULT &&
            autoTrim

    /** 有裁切設定（存檔時要動刀）。 */
    val hasCrop: Boolean
        get() = cropTop > 0f || cropBottom > 0f

    /** 設定過點擊位置（兩軸皆有值）＝自動翻頁才點得下去。 */
    val hasTapPoint: Boolean
        get() = tapX != null && tapY != null

    /** 這一站現在真的會自動翻頁（開關開著 ＋ 位置已設）。 */
    val autoTapReady: Boolean
        get() = autoTap && hasTapPoint
}

/** 網址 → 逐站設定的 key：小寫 host、去掉 `www.`；空白 / about:blank / 解析不到 host ⇒ null。 */
fun captureHostOf(url: String?): String? {
    val trimmed = url?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed == "about:blank") return null
    return runCatching { Uri.parse(trimmed).host }
        .getOrNull()
        ?.lowercase()
        ?.removePrefix("www.")
        ?.takeIf { it.isNotEmpty() }
}

/** 我的最愛的一筆：常用站網址 + 使用者命名的別名（別名空白時退回顯示網址）。 */
data class CaptureBookmark(
    val url: String,
    val alias: String,
)

/**
 * 網址歷史的一筆：造訪過的網址 + 當下 WebView 的原生標題（[android.webkit.WebView.getTitle]，非 JS/DOM）。
 * [title] 可能為空（頁面尚未載到標題 / 相容舊純 url 歷史）→ UI 退回顯示 [url]。
 */
data class CaptureUrlEntry(
    val url: String,
    val title: String,
)

class CaptureViewModel(
    private val context: Application = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
) : ViewModel() {

    // 書名 / 章名手動輸入（此步先不做選書流程）。
    var bookName by mutableStateOf("")
    var chapterName by mutableStateOf("")

    // Yakuyomi：擷取畫面網址列的輸入歷史（**帶頁面標題**）——存 UiPreferences.captureUrlHistory，JSON 陣列
    // `[{"url":..,"title":..}]`、最近的在最前。與 More 共用的純 url 歷史（lastWebViewUrls）分開（見 UiPreferences
    // 註解）。add/remove 仿 MoreViewModel（去重 → 放最前 → 截斷上限）。

    /**
     * 讀出歷史清單（最近的在最前，帶標題）。新 pref（captureUrlHistory）有資料就用它；
     * 還空時**讀取時相容**回退讀 More 的舊純 url 歷史（標題留空、不在讀取時寫檔），待下次 add/remove 才以新格式落地。
     * 解析失敗（任一 pref 內容壞掉）回空清單。
     */
    fun webViewUrlHistory(): List<CaptureUrlEntry> {
        val fromNew = parseUrlEntries(uiPreferences.captureUrlHistory.get())
        if (fromNew.isNotEmpty()) return fromNew
        return parseLegacyUrls(uiPreferences.lastWebViewUrls.get())
    }

    /** 解析新格式 `[{url,title}]`（也容忍夾雜舊的純 url 字串元素）。 */
    private fun parseUrlEntries(raw: String): List<CaptureUrlEntry> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                when (val el = arr.opt(i)) {
                    is JSONObject -> {
                        val url = el.optString("url").trim()
                        if (url.isNotEmpty()) add(CaptureUrlEntry(url, el.optString("title").trim()))
                    }
                    is String -> {
                        val url = el.trim()
                        if (url.isNotEmpty()) add(CaptureUrlEntry(url, ""))
                    }
                    else -> {}
                }
            }
        }
    }.getOrElse { emptyList() }

    /** 解析 More 舊格式（純 url 字串陣列 `["a","b"]`）→ 標題留空的 entry。 */
    private fun parseLegacyUrls(raw: String): List<CaptureUrlEntry> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val url = arr.optString(i).trim()
                if (url.isNotEmpty() && url != "about:blank") add(CaptureUrlEntry(url, ""))
            }
        }
    }.getOrElse { emptyList() }

    /**
     * 記錄一筆網址（造訪／輸入送出時）：忽略空白 / about:blank；移除既有同值 → 帶標題加到最前 → 截斷上限。
     * [title] 空白時**保留該網址原本記過的標題**（頁面剛開始載入時 title 常還沒到，onPageFinished 再補）。
     */
    fun addWebViewUrl(url: String, title: String = "") {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed == "about:blank") return
        // 順手記下「目前在哪一站」：存檔時若那一頁沒記到網址（url==null），仍能取得逐站裁切設定。
        captureHostOf(trimmed)?.let { activeHost = it }
        val current = webViewUrlHistory()
        val cleanTitle = title.trim().takeIf { it != "about:blank" }.orEmpty()
        val keptTitle = cleanTitle.ifEmpty { current.firstOrNull { it.url == trimmed }?.title.orEmpty() }
        val updated = (listOf(CaptureUrlEntry(trimmed, keptTitle)) + current.filterNot { it.url == trimmed })
            .take(MAX_WEBVIEW_URL_HISTORY)
        writeUrlHistory(updated)
    }

    /** 逐筆刪除歷史中的某筆網址。 */
    fun removeWebViewUrl(url: String) {
        writeUrlHistory(webViewUrlHistory().filterNot { it.url == url })
    }

    private fun writeUrlHistory(list: List<CaptureUrlEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("url", it.url).put("title", it.title)) }
        uiPreferences.captureUrlHistory.set(arr.toString())
    }

    // ── 我的最愛（手動存常用站 + 命名別名）─────────────────────────────────────
    // 與自動記錄的網址歷史不同：這是使用者手動加、命名別名（例：m.manhuagui.com → 「看漫画」），
    // 置頂快選。存進 UiPreferences.captureBookmarks（JSON 陣列，最新在最前）；用 org.json 解析／序列化。

    /** 讀出我的最愛（最新加入的在最前）；解析失敗回空清單、別名空白時退回顯示網址。 */
    fun listBookmarks(): List<CaptureBookmark> = runCatching {
        val arr = JSONArray(uiPreferences.captureBookmarks.get())
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                if (url.isEmpty()) continue
                val alias = obj.optString("alias").trim().ifEmpty { url }
                add(CaptureBookmark(url, alias))
            }
        }
    }.getOrElse { emptyList() }

    /** 加入／更新一筆最愛：忽略空白 / about:blank；同 url 覆蓋別名並移到最前；別名空白＝退回網址。 */
    fun addBookmark(url: String, alias: String) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty() || trimmedUrl == "about:blank") return
        val trimmedAlias = alias.trim().ifEmpty { trimmedUrl }
        val updated = listOf(CaptureBookmark(trimmedUrl, trimmedAlias)) +
            listBookmarks().filterNot { it.url == trimmedUrl }
        writeBookmarks(updated)
    }

    /** 逐筆移除某筆最愛（依 url）。 */
    fun removeBookmark(url: String) {
        writeBookmarks(listBookmarks().filterNot { it.url == url })
    }

    private fun writeBookmarks(list: List<CaptureBookmark>) {
        val arr = JSONArray()
        list.forEach { bm ->
            arr.put(JSONObject().put("url", bm.url).put("alias", bm.alias))
        }
        uiPreferences.captureBookmarks.set(arr.toString())
    }

    // ── 逐站設定：畫布寬度% + 去頭去尾裁切（階段 4）──────────────────────────────
    // 存 UiPreferences.captureSiteSettings（JSON 物件 `{host:{scale,cropTop,cropBottom}}`），key＝正規化 host。
    // 讀寫都很輕（一次一個小物件），不快取，避免與設定畫面/多處寫入不同步。

    /** 目前所在站台的 host（由 [addWebViewUrl] 隨導覽更新）：存檔那頁沒記到網址時的 fallback。 */
    @Volatile
    var activeHost: String? = null

    /** 讀某網址（取其 host）的逐站設定；沒設定過 / 解析失敗 ⇒ 預設值（不縮放、不裁切）。 */
    fun siteSettingFor(url: String?): CaptureSiteSetting {
        val host = captureHostOf(url) ?: return CaptureSiteSetting()
        return readSiteSettings()[host] ?: CaptureSiteSetting()
    }

    /** 寫某網址（取其 host）的逐站設定；全預設＝把該站整筆移除（pref 不留垃圾）。取不到 host ⇒ 不動作。 */
    fun saveSiteSetting(url: String?, setting: CaptureSiteSetting) {
        val host = captureHostOf(url) ?: return
        val map = readSiteSettings().toMutableMap()
        if (setting.isDefault) map.remove(host) else map[host] = setting
        val obj = JSONObject()
        map.forEach { (h, s) ->
            val o = JSONObject()
                .put("scale", s.scale)
                .put("cropTop", s.cropTop.toDouble())
                .put("cropBottom", s.cropBottom.toDouble())
                .put("autoTap", s.autoTap)
                .put("tapDelayMs", s.tapDelayMs)
                .put("autoTrim", s.autoTrim)
            // 位置沒設定過就整個 key 不寫（讀回來＝null＝未設定，與「設在 0,0」區分得開）。
            s.tapX?.let { o.put("tapX", it.toDouble()) }
            s.tapY?.let { o.put("tapY", it.toDouble()) }
            obj.put(h, o)
        }
        uiPreferences.captureSiteSettings.set(obj.toString())
    }

    /** 讀出比例座標欄位：沒有該 key / 值不在 0–1 ⇒ null（＝未設定）。 */
    private fun JSONObject.optFraction(key: String): Float? =
        if (has(key)) optDouble(key, -1.0).toFloat().takeIf { it in 0f..1f } else null

    private fun readSiteSettings(): Map<String, CaptureSiteSetting> = runCatching {
        val obj = JSONObject(uiPreferences.captureSiteSettings.get())
        buildMap {
            for (key in obj.keys()) {
                val o = obj.optJSONObject(key) ?: continue
                put(
                    key,
                    CaptureSiteSetting(
                        scale = o.optInt("scale", CAPTURE_SCALE_MAX).coerceIn(CAPTURE_SCALE_MIN, CAPTURE_SCALE_MAX),
                        cropTop = o.optDouble("cropTop", 0.0).toFloat().coerceIn(0f, 1f),
                        cropBottom = o.optDouble("cropBottom", 0.0).toFloat().coerceIn(0f, 1f),
                        autoTap = o.optBoolean("autoTap", false),
                        tapX = o.optFraction("tapX"),
                        tapY = o.optFraction("tapY"),
                        tapDelayMs = o.optInt("tapDelayMs", CAPTURE_TAP_DELAY_DEFAULT)
                            .coerceIn(CAPTURE_TAP_DELAY_MIN, CAPTURE_TAP_DELAY_MAX),
                        // 舊資料（沒有這個 key）＝跟著新預設開啟自動修邊。
                        autoTrim = o.optBoolean("autoTrim", true),
                    ),
                )
            }
        }
    }.getOrElse { emptyMap() }

    /** 存檔時要用的逐站設定：[url] 取不到 host 時退回 [activeHost]（那頁沒記到網址仍套得到該站設定）。 */
    private fun settingForSave(url: String?): CaptureSiteSetting =
        siteSettingFor(url ?: activeHost?.let { "https://$it" })

    /**
     * 存檔前套用該站的**去頭去尾**裁切（第一層＝位置固定的網站 UI；只切上下、寬度不動）。
     * 沒設定 / 設定切完剩不到 [MIN_CROP_KEEP_PX] ⇒ 原樣回傳**同一顆 bitmap**（呼叫端據此判斷要不要 recycle）。
     * ★ 封面框選（[saveCover]）刻意不套用——那是使用者自己框的範圍。
     */
    private fun cropForSave(bitmap: Bitmap, setting: CaptureSiteSetting): Bitmap {
        if (bitmap.isRecycled) return bitmap
        if (!setting.hasCrop) return bitmap
        val h = bitmap.height
        val top = (h * setting.cropTop).roundToInt().coerceIn(0, h)
        val bottom = (h * (1f - setting.cropBottom)).roundToInt().coerceIn(0, h)
        if (top == 0 && bottom == h) return bitmap
        if (bottom - top < MIN_CROP_KEEP_PX) return bitmap
        // createBitmap(src,0,top,w,h') 覆蓋整張時會回傳同一物件 → 呼叫端一律用 !== 判斷才 recycle。
        return runCatching { Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top) }.getOrDefault(bitmap)
    }

    /**
     * 存檔前的**第二層裁切＝自動修邊**（逐頁動態）：在固定裁切後的安全區內，把上下大片單色空白（＝網站頁面
     * 背景）修掉，讓雙開頁 / 彩頁 / 短頁自動貼齊內容、不把網站頁尾截進來。邊界演算法見 [autoTrimBounds]
     * （含三道保守護欄）；沒有大片空白＝回傳**同一顆 bitmap**（正常頁 no-op、呼叫端用 !== 判斷才 recycle）。
     */
    private fun autoTrimForSave(bitmap: Bitmap): Bitmap {
        if (bitmap.isRecycled) return bitmap
        val (top, bottom) = autoTrimBounds(bitmap)
        if (top <= 0 && bottom <= 0) return bitmap
        val height = bitmap.height - top - bottom
        if (height < MIN_CROP_KEEP_PX) return bitmap
        return runCatching { Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height) }.getOrDefault(bitmap)
    }

    /**
     * 依逐站設定寫檔：**先固定裁切、再自動修邊**（[autoTrim] 關就跳過第二層）。
     * 中間產生的 bitmap 用完即回收，原圖（呼叫端還要用）不動；每一層都可能回傳上一層的同一顆物件
     * （`Bitmap.createBitmap` 覆蓋整張時回傳原物件）⇒ 一律用 `!==` 判斷才 recycle。
     */
    private fun writePage(file: UniFile, bitmap: Bitmap, url: String?) {
        val setting = settingForSave(url)
        val cropped = cropForSave(bitmap, setting)
        val out = if (setting.autoTrim) autoTrimForSave(cropped) else cropped
        try {
            openTruncating(file).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            if (out !== cropped && !out.isRecycled) out.recycle()
            if (cropped !== bitmap && !cropped.isRecycled) cropped.recycle()
        }
    }

    /**
     * 掃 `<local>/<safeBook>/` 下的**話夾名稱**（給「新話數」panel 的「已截話數總覽 + 話數建議」用）。
     * 只列目錄、不看內容；書名空 / 該書還沒有夾子 / local 目錄不可用 → 空清單。
     * 排序見 [CHAPTER_ORDER]（數字話照數值、非數字話排後面）。
     */
    suspend fun existingChapters(book: String = bookName): List<String> = withIOContext {
        val safeBook = DiskUtil.buildValidFilename(book.trim())
        if (safeBook.isEmpty()) return@withIOContext emptyList()
        runCatching {
            storageManager.getLocalSourceDirectory()
                ?.findFile(safeBook)?.takeIf { it.isDirectory }
                ?.listFiles().orEmpty()
                .filter { it.isDirectory }
                .mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
                .sortedWith(CHAPTER_ORDER)
        }.getOrElse { emptyList() }
    }

    /**
     * 把 [bitmap] 依 [cropRect]（**已由呼叫端換算到 bitmap 座標系**）裁出封面，存成
     * `<local>/<safeBook>/cover.jpg`（JPEG q90、覆蓋既有）。存**書名夾根**（非話夾）→ [COVER_NAME] 對齊
     * LocalCoverManager，書櫃自動顯示。書名夾不存在就建。回傳 cover 檔 uri 字串（供縮圖預覽）；失敗回 null。
     *
     * [cropRect] 再 clamp 一次（呼叫端已 clamp、這裡防禦性再守）；裁切範圍面積 <=0 → null。
     * SAF 走 ContentResolver `"wt"` 截斷寫（覆蓋舊封面不留舊尾）。
     */
    suspend fun saveCover(bitmap: Bitmap, cropRect: Rect, book: String): String? = withIOContext {
        val safeBook = DiskUtil.buildValidFilename(book.trim())
        if (safeBook.isEmpty() || bitmap.isRecycled) return@withIOContext null
        val left = cropRect.left.coerceIn(0, bitmap.width)
        val top = cropRect.top.coerceIn(0, bitmap.height)
        val right = cropRect.right.coerceIn(0, bitmap.width)
        val bottom = cropRect.bottom.coerceIn(0, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return@withIOContext null
        runCatching {
            val base = storageManager.getLocalSourceDirectory()
                ?: error("Local source directory unavailable")
            val mangaDir = base.findFile(safeBook)?.takeIf { it.isDirectory }
                ?: base.createDirectory(safeBook)
                ?: error("Cannot create manga directory")
            // createBitmap(src, 0,0,W,H) 覆蓋整張時會回傳同一物件；用完別 recycle 掉呼叫端還要用的 bitmap。
            val cropped = Bitmap.createBitmap(bitmap, left, top, w, h)
            val file = mangaDir.findFile(COVER_NAME)?.takeIf { it.isFile }
                ?: mangaDir.createFile(COVER_NAME)
                ?: error("Cannot create cover file")
            openTruncating(file).use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (cropped != bitmap && !cropped.isRecycled) cropped.recycle()
            file.uri.toString()
        }.getOrNull()
    }

    /** 找該書已存的封面 uri（`<local>/<safeBook>/cover.jpg`）供重進「新漫畫」panel 時顯示縮圖；沒有回 null。 */
    suspend fun findCoverUri(book: String): String? = withIOContext {
        val safeBook = DiskUtil.buildValidFilename(book.trim())
        if (safeBook.isEmpty()) return@withIOContext null
        runCatching {
            storageManager.getLocalSourceDirectory()
                ?.findFile(safeBook)?.takeIf { it.isDirectory }
                ?.findFile(COVER_NAME)?.takeIf { it.isFile }
                ?.uri?.toString()
        }.getOrNull()
    }

    /**
     * 記漫畫來源網址到**書名夾根**的 [MANGA_META_FILE]（供日後「繼續擷取」開回原站；這批只寫、不接讀取入口）。
     * fire-and-forget（IO thread）；書名夾不存在就建。[url] 空白＝不寫（不建空夾）。
     */
    fun writeMangaMeta(book: String, url: String?) {
        val trimmed = url?.trim().orEmpty()
        // about:blank／空白＝不是有效來源網址，寫了會讓「繼續擷取」開回 about:blank（等於沒修）。
        if (trimmed.isEmpty() || trimmed == "about:blank") return
        val safeBook = DiskUtil.buildValidFilename(book.trim())
        if (safeBook.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val base = storageManager.getLocalSourceDirectory() ?: return@launch
                val mangaDir = base.findFile(safeBook)?.takeIf { it.isDirectory }
                    ?: base.createDirectory(safeBook)
                    ?: return@launch
                writeMangaMeta(context, mangaDir, trimmed)
            }
        }
    }

    /** 讀某書記下的來源網址（下批「繼續擷取」用；這批先建好、不接入口）。找不到回 null。 */
    suspend fun readMangaMeta(book: String): String? = withIOContext {
        val safeBook = DiskUtil.buildValidFilename(book.trim())
        if (safeBook.isEmpty()) return@withIOContext null
        runCatching {
            storageManager.getLocalSourceDirectory()
                ?.findFile(safeBook)?.takeIf { it.isDirectory }
                ?.let { readMangaMeta(it) }
        }.getOrNull()
    }

    // 本次連續截圖存下的頁碼（每次 startContinuous 重置）；供確認頁「放棄這次截圖」只刪這批、
    // 不誤刪接續截圖前該章夾既有的頁。快照回傳（toList）避免與迴圈的 add 撞併發修改。
    private val _sessionPages = mutableListOf<Int>()
    val sessionPages: List<Int> get() = _sessionPages.toList()

    // 連續截圖：狀態 + 驅動迴圈的 job。
    private val _continuous = MutableStateFlow(ContinuousCaptureState())
    val continuous: StateFlow<ContinuousCaptureState> = _continuous.asStateFlow()
    private var continuousJob: Job? = null

    /**
     * 開始半自動連續截圖。★ 2026-07 改寫（消除閃爍）：**偵測階段不隱藏工具列**，只有真的要存檔那一刻才隱藏。
     *
     * 迴圈：每 [CAPTURE_INTERVAL_MS] 用 [compareGrabber] 抓一張「比較幀」（**含浮動工具列、零閃爍**）→
     * 縮成灰階小圖算 MAD →「畫面靜止(穩定) AND 內容與上次已截頁不同(換頁)」才進存檔流程：
     * 用 [cleanGrabber] 抓一張「乾淨幀」（隱藏 overlay → 等兩 frame → 截 → 還原）→ **在乾淨幀上判 [isBlank]**
     * （載入過場黑頁/純色 → 丟棄、不存、不更新 lastCaptured）→ 存檔 → 暫停偵測 [CAPTURE_PAUSE_MS]
     * （UI 顯示「已擷取第 N 頁 · 請翻下一頁」）→ 回比較階段。
     *
     * 工具列是靜態的、前後比較幀都有它 ⇒ 不影響 frame-diff；`lastCaptured` 因此存的是**比較幀**縮圖
     * （與比較基準同一種畫面，含工具列），不是乾淨幀，否則每次都會因「有沒有工具列」的差異誤判成換頁。
     * 結果：閃爍由「每 500ms 一次」降到「每頁一次、約 2~3 frame」，且發生在使用者正在看提示的時候。
     *
     * 首次尚未截過任何頁時 changed 恆真，故一旦畫面靜止即截第一張。
     * 需先填書名 / 章名（呼叫端已擋一次，這裡再守一次）；重複呼叫不會疊開。
     *
     * 抓幀在主執行緒（PixelCopy 需 window）、比對在 [Dispatchers.Default]、存檔在 IO（[saveCapture] 內建）。
     * [urlProvider] 在主執行緒讀當前 WebView 網址（供每張截圖記進整章 meta）；取不到＝null（不記）。
     *
     * ★ 自動翻頁（[autoTap] 非 null 時）：存完一頁、顯示完提示後 `delay(tapDelayMs)` → 派送一次模擬點擊
     * （點在該站「下一頁」鈕）→ 回到上面的 frame-diff，新頁穩定就自動截下一張，全程免手動。
     * **三重停止條件**（避免無限空轉）：
     * ① 截滿 [targetPages]（使用者填的本話頁數，null＝不設上限）；
     * ② 連續 [CAPTURE_MAX_IDLE_TAPS] 次點擊後畫面仍**與上次截的那頁一模一樣**（到最後一頁 / 位置點錯）；
     * ③ 使用者按停止（[stopContinuous]，行為不變）。
     * ★ ④ **存檔失敗**（2026-07 補）：[CaptureSaveResult.MissingName] 立刻停（設定不完整、重試無意義）、
     * [CaptureSaveResult.Failed] 連 [CAPTURE_MAX_SAVE_FAILS] 頁都失敗才停。舊版這兩種結果都直接落到
     * 「等 500ms 再試」，於是整話翻完進度還停在「已截 0 頁」都沒人告訴使用者。
     * ①②④ 會把 [ContinuousCaptureState.stopReason] 設成對應的 [CaptureStopReason]，畫面層據此比照按停止
     * 進確認頁並顯示原因。
     */
    fun startContinuous(
        compareGrabber: FrameGrabber,
        cleanGrabber: FrameGrabber,
        urlProvider: () -> String?,
        targetPages: Int? = null,
        tapDelayMs: Int = CAPTURE_TAP_DELAY_DEFAULT,
        autoTap: PageTapper? = null,
    ) {
        if (bookName.isBlank() || chapterName.isBlank()) return
        if (continuousJob?.isActive == true) return
        val target = targetPages?.takeIf { it > 0 }
        val tapDelay = tapDelayMs.coerceIn(CAPTURE_TAP_DELAY_MIN, CAPTURE_TAP_DELAY_MAX).toLong()
        continuousJob = viewModelScope.launch {
            var prev: IntArray? = null // 前一幀縮圖（判穩定）
            var lastCaptured: IntArray? = null // 上次已截那頁的縮圖（判換頁 + 去重）
            // 上次「抓了乾淨幀卻是空白/黑頁」而丟棄的畫面：同一張黑頁不再反覆抓乾淨幀（免無謂閃爍）。
            var lastRejected: IntArray? = null
            // 自動翻頁：派送點擊後「還在等新頁」的截止時刻（null＝沒在等）＋連續無效點擊次數。
            var tapDeadline: Long? = null
            var idleTaps = 0
            // 迴圈自己停下來（截滿頁數 / 點了沒反應 / 存檔失敗）→ finally 把它落進 state 給畫面層進確認頁。
            var stopReason: CaptureStopReason? = null
            var stopDetail: CaptureSaveError? = null
            // 連續存檔失敗次數（成功即歸零）：達 [CAPTURE_MAX_SAVE_FAILS] 就別再空轉了。
            var saveFails = 0
            _sessionPages.clear()
            _continuous.update {
                it.copy(
                    running = true,
                    count = 0,
                    justCapturedPage = null,
                    targetPages = target,
                    stopReason = null,
                    stopDetail = null,
                )
            }
            try {
                while (isActive) {
                    // ① 比較幀：不隱藏 overlay（零閃爍）。
                    val frame = grabFrame(compareGrabber)
                    if (frame == null) {
                        delay(CAPTURE_INTERVAL_MS)
                        continue
                    }
                    val thumb = withContext(Dispatchers.Default) { thumbLuma(frame) }
                    if (!frame.isRecycled) frame.recycle()

                    val stable = prev?.let { mad(thumb, it) < STABLE_THRESHOLD } ?: false
                    val changed = lastCaptured?.let { mad(thumb, it) > CHANGE_THRESHOLD } ?: true
                    val notRejected = lastRejected?.let { mad(thumb, it) > CHANGE_THRESHOLD } ?: true

                    // ★ 停止條件②：派送點擊後過了寬限還沒截到新頁。
                    // 判準看 [changed]（與上次**已截**那頁比）：畫面確實還一模一樣才算「這次點擊沒作用」；
                    // 若其實已經變了（新頁還在載 / 還沒穩定）就只延長等待、**絕不重點**（重點會直接跳掉一頁）。
                    val deadline = tapDeadline
                    if (autoTap != null && deadline != null && SystemClock.elapsedRealtime() > deadline) {
                        if (changed) {
                            tapDeadline = SystemClock.elapsedRealtime() + CAPTURE_TAP_GRACE_MS
                        } else {
                            idleTaps++
                            if (idleTaps >= CAPTURE_MAX_IDLE_TAPS) {
                                stopReason = CaptureStopReason.TAP_NO_EFFECT
                                break
                            }
                            // 還沒到上限＝再點一次（第一次可能剛好沒點中 / 頁面沒反應）。
                            tapDeadline = dispatchAutoTap(autoTap, tapDelay)
                            prev = null // 重點後重建穩定基準
                            delay(CAPTURE_INTERVAL_MS)
                            continue
                        }
                    }

                    if (!stable || !changed || !notRejected) {
                        prev = thumb
                        delay(CAPTURE_INTERVAL_MS)
                        continue
                    }

                    // ② 確定要存了才抓乾淨幀（隱藏 overlay 就這一次）。
                    val clean = grabFrame(cleanGrabber)
                    if (clean == null) {
                        prev = thumb
                        delay(CAPTURE_INTERVAL_MS)
                        continue
                    }
                    val cleanThumb = withContext(Dispatchers.Default) { thumbLuma(clean) }
                    // ③ 空白/黑頁判斷在**乾淨幀**上做（比較幀含工具列會墊高亮度值域、判不準）。
                    if (isBlank(cleanThumb)) {
                        if (!clean.isRecycled) clean.recycle()
                        lastRejected = thumb
                        prev = thumb
                        delay(CAPTURE_INTERVAL_MS)
                        continue
                    }

                    // ④ 存檔（WebView 網址須在主執行緒讀）。
                    val url = withUIContext { urlProvider() }
                    val result = saveCapture(clean, url)
                    if (!clean.isRecycled) clean.recycle()
                    if (result is CaptureSaveResult.Saved) {
                        _sessionPages.add(result.page)
                        lastCaptured = thumb // ★ 存比較幀縮圖（與比較基準一致）
                        lastRejected = null
                        saveFails = 0 // 成功即歸零（門檻看的是「連續」失敗）
                        val captured = _continuous.value.count + 1
                        _continuous.update { it.copy(count = captured, justCapturedPage = result.page) }
                        // ⑤ 暫停偵測 + 顯示「已擷取第 N 頁 · 請翻下一頁」；期間畫面可能被翻動 →
                        // prev 歸零，回去後重新建立穩定基準。
                        prev = null
                        // 這一頁是真的翻過來的 ⇒ 自動翻頁的「無效點擊」計數歸零。
                        tapDeadline = null
                        idleTaps = 0
                        delay(CAPTURE_PAUSE_MS)
                        _continuous.update { it.copy(justCapturedPage = null) }
                        // ★ 停止條件①：截滿使用者填的本話頁數 → 自動停（畫面層進確認頁）。
                        if (target != null && captured >= target) {
                            stopReason = CaptureStopReason.TARGET_REACHED
                            break
                        }
                        // ★ 自動翻頁：延遲後派送點擊，之後交回上面的 frame-diff 偵測新頁。
                        if (autoTap != null) {
                            delay(tapDelay)
                            tapDeadline = dispatchAutoTap(autoTap, tapDelay)
                        }
                        continue
                    }

                    // ★ 停止條件④：存檔沒成功（2026-07 補；舊版直接落到下面的「等 500ms 再試」＝無限重試、
                    // 進度永遠 0 頁、使用者毫無所悉）。
                    when (result) {
                        // 書名 / 章名沒填＝設定不完整，再試一百次也一樣 → 立刻停。
                        CaptureSaveResult.MissingName -> {
                            stopReason = CaptureStopReason.SAVE_FAILED
                            stopDetail = CaptureSaveError.MISSING_NAME
                        }
                        // 寫檔失敗可能只是偶發（SAF 短暫失敗）→ 連 [CAPTURE_MAX_SAVE_FAILS] 頁失敗才停。
                        is CaptureSaveResult.Failed -> {
                            saveFails++
                            if (saveFails >= CAPTURE_MAX_SAVE_FAILS) {
                                stopReason = CaptureStopReason.SAVE_FAILED
                                stopDetail = result.reason
                            }
                        }
                    }
                    if (stopReason != null) break

                    prev = thumb
                    delay(CAPTURE_INTERVAL_MS)
                }
            } finally {
                // 停止原因：迴圈自己判定的優先；被 [stopContinuous] 取消時本地變數是 null，保留它已寫進去的
                // [CaptureStopReason.MANUAL]（cancel() 非同步、finally 晚一步跑，不能無條件覆蓋）。
                val reason = stopReason
                val detail = stopDetail
                _continuous.update {
                    it.copy(
                        running = false,
                        justCapturedPage = null,
                        stopReason = reason ?: it.stopReason,
                        stopDetail = if (reason != null) detail else it.stopDetail,
                    )
                }
            }
        }
    }

    /**
     * 派送一次自動翻頁點擊，回傳「等新頁」的截止時刻（[SystemClock.elapsedRealtime] 基準）。
     * 派送失敗（WebView 還沒量到寬高）就不加 [tapDelay]，讓寬限更快到期 → 早一輪累進無效計數 → 早點自動停。
     */
    private suspend fun dispatchAutoTap(autoTap: PageTapper, tapDelay: Long): Long {
        val tapped = autoTap()
        return SystemClock.elapsedRealtime() + (if (tapped) tapDelay else 0L) + CAPTURE_TAP_GRACE_MS
    }

    /**
     * 停止連續截圖（使用者按停止 / 畫面離開 / 生命週期 ON_STOP 都走這；idempotent）。
     * 標成 [CaptureStopReason.MANUAL]＝畫面層**不**自動進確認頁（按停止那條路自己會進、也不顯示原因提示）。
     */
    fun stopContinuous() {
        continuousJob?.cancel()
        continuousJob = null
        _continuous.update {
            it.copy(
                running = false,
                justCapturedPage = null,
                stopReason = CaptureStopReason.MANUAL,
                stopDetail = null,
            )
        }
    }

    /** 畫面層消費完 [ContinuousCaptureState.stopReason]（已切進確認頁）後歸零，免重複觸發。 */
    fun consumeStopReason() {
        continuousJob = null
        _continuous.update { it.copy(stopReason = null, stopDetail = null) }
    }

    override fun onCleared() {
        continuousJob?.cancel()
    }

    /** 把 [FrameGrabber] 的 callback 包成 suspend；在主執行緒發起（讀 view 屬性 / fallback draw 需主執行緒）。 */
    private suspend fun grabFrame(grabber: FrameGrabber): Bitmap? = withUIContext {
        suspendCancellableCoroutine { cont ->
            grabber { bmp ->
                if (cont.isActive) {
                    cont.resume(bmp)
                } else if (bmp != null && !bmp.isRecycled) {
                    bmp.recycle() // 已取消卻仍回幀 → 別讓它洩漏
                }
            }
        }
    }

    /** 把整張 [bitmap] 縮成 [THUMB_SIZE]² 灰階亮度陣列（0–255）；縮圖及時 recycle、不動原圖。 */
    private fun thumbLuma(bitmap: Bitmap): IntArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, THUMB_SIZE, THUMB_SIZE, true)
        val px = IntArray(THUMB_SIZE * THUMB_SIZE)
        scaled.getPixels(px, 0, THUMB_SIZE, 0, 0, THUMB_SIZE, THUMB_SIZE)
        // createScaledBitmap 目標尺寸==原尺寸時會回傳同一物件；此處縮到 32² 幾乎不可能相等，仍防禦性判斷。
        if (scaled != bitmap && !scaled.isRecycled) scaled.recycle()
        return IntArray(px.size) { i ->
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000 // 亮度 0–255（Rec.601）
        }
    }

    /** 兩縮圖的平均絕對差（Mean Absolute Difference，0–255）。 */
    private fun mad(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }

    /** 縮圖是否近乎純色（載入過場黑頁 / 純白 / 單色）：亮度值域(max-min) < [BLANK_RANGE_THRESHOLD]。 */
    private fun isBlank(thumb: IntArray): Boolean {
        var min = 255
        var max = 0
        for (v in thumb) {
            if (v < min) min = v
            if (v > max) max = v
        }
        return (max - min) < BLANK_RANGE_THRESHOLD
    }

    /**
     * 把 [bitmap] 存成 LocalSource 的 `<local>/<書名>/<章名>/NNN.png`（零填充、頁碼在該章內遞增）。
     * 頁碼＝掃該章夾既有 `NNN.*` 取最大值 +1（換章名自然接續該章、重進畫面也不覆蓋）。
     * 存完把該頁網址記進**整章一個** meta 檔 `.yakuyomi_meta.json`（[updateMetaUrl]，取代舊 `NNN.url` sidecar）；
     * [url] 為 null/空＝取不到網址（該頁不記，不影響閱讀）。
     * I/O 全在 IO thread；SAF 走 ContentResolver "wt" 截斷寫（file:// 用一般串流）。
     */
    suspend fun saveCapture(bitmap: Bitmap, url: String?): CaptureSaveResult = withIOContext {
        val book = bookName.trim()
        val chapter = chapterName.trim()
        if (book.isEmpty() || chapter.isEmpty()) {
            return@withIOContext CaptureSaveResult.MissingName
        }
        runCatching {
            val base = storageManager.getLocalSourceDirectory()
                ?: throw CaptureSaveException(CaptureSaveError.NO_STORAGE)
            val safeBook = DiskUtil.buildValidFilename(book)
            val safeChapter = DiskUtil.buildValidFilename(chapter)
            val mangaDir = base.findFile(safeBook)?.takeIf { it.isDirectory }
                ?: base.createDirectory(safeBook)
                ?: throw CaptureSaveException(CaptureSaveError.MANGA_DIR)
            val chapterDir = mangaDir.findFile(safeChapter)?.takeIf { it.isDirectory }
                ?: mangaDir.createDirectory(safeChapter)
                ?: throw CaptureSaveException(CaptureSaveError.CHAPTER_DIR)

            val page = nextPageNumber(chapterDir)
            val name = "%03d.png".format(page)
            val file = chapterDir.createFile(name)
                ?: throw CaptureSaveException(CaptureSaveError.WRITE)
            // 依該站的「去頭去尾」設定裁掉上下（沒設定＝原樣存）。
            writePage(file, bitmap, url)
            updateMetaUrl(chapterDir, page, url)
            // ★ 安全網（件 1）：確保**漫畫層** meta（.yakuyomi_manga）存在——供日後「繼續擷取」開回原站。
            // 不再只靠「新漫畫 panel 按確定」那一條（continue-capture 帶著書名進來根本不開該 panel、就漏寫）；
            // 只在缺檔且有有效網址時補寫（write-if-absent，保留 panel 當初記的目錄/首頁網址）。
            ensureMangaMeta(mangaDir, url)

            CaptureSaveResult.Saved(page, file.uri.toString())
        }.getOrElse { it.toCaptureFailure() }
    }

    /**
     * 重截：覆蓋既有頁 [pageName]（如 `003.png`）並更新其在整章 meta（`.yakuyomi_meta.json`）記的網址。
     * 不新增頁碼、不掃 next。章夾用已 sanitise 的 [safeBook] / [safeChapter] 定位；找不到章夾＝失敗。
     */
    suspend fun saveReCapture(
        bitmap: Bitmap,
        url: String?,
        safeBook: String,
        safeChapter: String,
        pageName: String,
    ): CaptureSaveResult = withIOContext {
        runCatching {
            val base = storageManager.getLocalSourceDirectory()
                ?: throw CaptureSaveException(CaptureSaveError.NO_STORAGE)
            val mangaDir = base.findFile(safeBook)?.takeIf { it.isDirectory }
                ?: throw CaptureSaveException(CaptureSaveError.MANGA_DIR)
            val chapterDir = mangaDir.findFile(safeChapter)?.takeIf { it.isDirectory }
                ?: throw CaptureSaveException(CaptureSaveError.CHAPTER_DIR)

            val file = chapterDir.findFile(pageName)
                ?: chapterDir.createFile(pageName)
                ?: throw CaptureSaveException(CaptureSaveError.WRITE)
            writePage(file, bitmap, url)

            val page = pageName.substringBeforeLast('.').toIntOrNull() ?: 0
            updateMetaUrl(chapterDir, page, url)
            // 安全網同 [saveCapture]：純靠重截補頁的書也要有漫畫層 meta（否則「繼續擷取」開不回原站）。
            ensureMangaMeta(mangaDir, url)
            CaptureSaveResult.Saved(page, file.uri.toString())
        }.getOrElse { it.toCaptureFailure() }
    }

    /**
     * 插入：在 [insertAtPage] 位置插一張新截圖。先把該章夾內頁碼 >= [insertAtPage] 的既有頁圖（含 legacy `.url`）
     * 由**尾端往前逐一 +1** 改名騰位（降序處理 → 目標名恆空、不覆蓋），並把整章 meta 內 key >= [insertAtPage]
     * 的網址同步 +1 搬位、放入新頁 [url]，最後存新截頁 `%03d.png`.format(insertAtPage)。
     * null-safe、只碰該章夾；找不到章夾＝失敗。頁碼可能留下與插入前一致的間隙，交由確認頁儲存時的 renumber 收斂成連續。
     */
    suspend fun saveInsert(
        bitmap: Bitmap,
        url: String?,
        safeBook: String,
        safeChapter: String,
        insertAtPage: Int,
    ): CaptureSaveResult = withIOContext {
        runCatching {
            val base = storageManager.getLocalSourceDirectory()
                ?: throw CaptureSaveException(CaptureSaveError.NO_STORAGE)
            val mangaDir = base.findFile(safeBook)?.takeIf { it.isDirectory }
                ?: throw CaptureSaveException(CaptureSaveError.MANGA_DIR)
            val chapterDir = mangaDir.findFile(safeChapter)?.takeIf { it.isDirectory }
                ?: throw CaptureSaveException(CaptureSaveError.CHAPTER_DIR)

            // 騰位：頁碼 >= insertAtPage 的圖（含 legacy .url sidecar）皆 +1，降序（尾端先）避免改名撞到既有目標名。
            // meta 檔（.yakuyomi_meta.json）basename 非數字 → 不被此迴圈掃到、不會被誤改名。
            chapterDir.listFiles().orEmpty()
                .filter { !it.isDirectory }
                .mapNotNull { f ->
                    val name = f.name.orEmpty()
                    val n = name.substringBeforeLast('.').toIntOrNull()
                    if (n != null && n >= insertAtPage) Triple(n, f, name) else null
                }
                .sortedByDescending { it.first }
                .forEach { (n, f, name) ->
                    renameOrCopyFile(chapterDir, f, "%03d.%s".format(n + 1, name.substringAfterLast('.', "png")))
                }

            // meta 同步騰位：key >= insertAtPage 的網址 +1 搬位，再放入新頁 url。
            val meta = readMeta(chapterDir)
            val shifted = mutableMapOf<String, String>()
            for ((key, u) in meta) {
                val n = key.toIntOrNull()
                if (n != null && n >= insertAtPage) shifted["%03d".format(n + 1)] = u else shifted[key] = u
            }
            val trimmedUrl = url?.trim().orEmpty()
            val insertKey = "%03d".format(insertAtPage)
            if (trimmedUrl.isNotEmpty()) shifted[insertKey] = trimmedUrl else shifted.remove(insertKey)
            writeMeta(context, chapterDir, shifted)

            val name = "%03d.png".format(insertAtPage)
            val file = chapterDir.findFile(name)
                ?: chapterDir.createFile(name)
                ?: throw CaptureSaveException(CaptureSaveError.WRITE)
            writePage(file, bitmap, url)

            // 安全網同 [saveCapture]：純靠插入補頁的書也要有漫畫層 meta。
            ensureMangaMeta(mangaDir, url)
            CaptureSaveResult.Saved(insertAtPage, file.uri.toString())
        }.getOrElse { it.toCaptureFailure() }
    }

    /** 改名；[UniFile.renameTo] 失敗（回 false / 丟例外）→ 退回 copy 到新名 + 刪舊檔。 */
    private fun renameOrCopyFile(dir: UniFile, file: UniFile, newName: String) {
        if (runCatching { file.renameTo(newName) }.getOrDefault(false)) return
        val dest = dir.createFile(newName) ?: return
        runCatching {
            file.openInputStream().use { input ->
                dest.openOutputStream().use { output -> input.copyTo(output) }
            }
            file.delete()
        }
    }

    /**
     * 把單頁的網址寫進整章 meta（`.yakuyomi_meta.json`，見 [CaptureMeta]）：讀現有 map → 設/移除該頁 key
     * （`%03d`.format(page)）→ 整檔截斷回寫。[url] 為 null/空白＝該頁不記（既有記錄一併移除）。
     * best-effort（[writeMeta] 內建吞例外），取不到網址不影響存圖。
     */
    private fun updateMetaUrl(chapterDir: UniFile, page: Int, url: String?) {
        val map = readMeta(chapterDir)
        val key = "%03d".format(page)
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) map.remove(key) else map[key] = trimmed
        writeMeta(context, chapterDir, map)
    }

    /**
     * 缺**有效內容**就補寫漫畫層 meta：[mangaDir]＝書名夾。[url] 為有效來源網址時，**讀得到既有的有效 url 才 skip**，
     * 否則覆寫（保留「新漫畫 panel」當初記的首頁/目錄網址，不被逐頁的深層網址覆蓋）。best-effort、吞例外。
     *
     * ★ 為何不是「檔在不在」（2026-07 修）：舊版寫過 `{"url":"about:blank"}` 的檔（或被 LocalSource 刪到只剩空殼、
     * 內容壞掉的檔）永遠通不過驗證卻擋住補寫 → 那本書的「繼續擷取」永遠修不回來。改成看內容。
     */
    private fun ensureMangaMeta(mangaDir: UniFile, url: String?) {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "about:blank") return
        runCatching {
            // 頂層 readMangaMeta(UniFile)：新檔名優先、讀到舊檔名會順手 migrate；回 null＝沒有有效來源網址。
            if (readMangaMeta(mangaDir) != null) return
            writeMangaMeta(context, mangaDir, trimmed)
        }
    }

    private fun nextPageNumber(chapterDir: UniFile): Int =
        (
            chapterDir.listFiles().orEmpty()
                .mapNotNull { it.name?.substringBeforeLast('.')?.toIntOrNull() }
                .maxOrNull() ?: 0
            ) + 1

    // 覆寫用截斷串流：SAF DocumentFile 走 ContentResolver "wt"（避免 DocumentFile "w" 不截斷留舊尾）；
    // file:// 用一般 openOutputStream（FileOutputStream 本就截斷、且 "wt" 對 file:// 不落地）。新建檔通常為空、
    // 截斷與否無差，但沿用 PageTranslator 同一套規則保持一致、對重進畫面重存同名也安全。
    private fun openTruncating(f: UniFile): OutputStream =
        if (f.uri.scheme == "file") {
            f.openOutputStream()
        } else {
            checkNotNull(context.contentResolver.openOutputStream(f.uri, "wt")) {
                "Cannot open output stream for ${f.uri}"
            }
        }
}

// ── 去頭去尾分界的自動偵測（件 3-B 輔助）────────────────────────────────────
// 純函式（不碰 I/O、不碰 Compose）：吃一張畫面截圖 → 猜「頂線 / 底線」的比例，供裁切模式**預先擺好**兩條線
// 讓使用者確認/微調（不直接套用）。做法：只看截圖**中央直條**（避開左右邊欄/捲軸），逐列算亮度值域
// （max−min）：值域小＝近乎純色（網站 nav / 標題 / footer 底色），值域大＝漫畫內容。從畫面中央往上、
// 往下掃，遇到「連續數列近純色」就把邊界定在該純色帶的**內側**。啟發式而已，偵測不到就回 0（不裁）。

// 近純色門檻（亮度值域 0–255）：漫畫內容列通常 >100，純色底 ≈0。
private const val CROP_DETECT_UNIFORM_RANGE = 24

// 連續幾個「取樣列」都近純色才算邊界（避免被漫畫裡的一小條留白騙走）。
private const val CROP_DETECT_RUN_ROWS = 6

// 取樣間隔（每幾列取一列）與每列取樣點數（取中央 50% 寬）。
private const val CROP_DETECT_ROW_STEP = 4
private const val CROP_DETECT_COL_SAMPLES = 24

// 單邊最多裁掉的比例（防誤判把漫畫本體吃掉）。
private const val CROP_DETECT_MAX_FRACTION = 0.4f

/**
 * 自動偵測去頭去尾分界。回傳 `(cropTop, cropBottom)`＝上/下各要裁掉的**畫面高度比例**（0f–[CROP_DETECT_MAX_FRACTION]）；
 * 偵測不到該邊就回 0（不裁）。輸入為 WebView 畫面截圖（ARGB_8888）。
 */
fun detectCropBounds(bitmap: Bitmap): Pair<Float, Float> {
    val w = bitmap.width
    val h = bitmap.height
    if (bitmap.isRecycled || w <= 0 || h <= CROP_DETECT_ROW_STEP * CROP_DETECT_RUN_ROWS * 2) return 0f to 0f
    val x0 = w / 4
    val x1 = w - w / 4
    val step = ((x1 - x0) / CROP_DETECT_COL_SAMPLES).coerceAtLeast(1)
    val rowCount = h / CROP_DETECT_ROW_STEP
    val uniform = BooleanArray(rowCount)
    val row = IntArray(w)
    for (r in 0 until rowCount) {
        val y = (r * CROP_DETECT_ROW_STEP).coerceAtMost(h - 1)
        runCatching { bitmap.getPixels(row, 0, w, 0, y, w, 1) }.getOrElse { return 0f to 0f }
        var min = 255
        var max = 0
        var x = x0
        while (x < x1) {
            val c = row[x]
            val luma = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            if (luma < min) min = luma
            if (luma > max) max = luma
            x += step
        }
        uniform[r] = (max - min) < CROP_DETECT_UNIFORM_RANGE
    }

    val center = rowCount / 2
    // 往上找：純色帶的**下緣**（第一列非純色的位置）＝頂線。
    var top = 0f
    var run = 0
    for (r in center - 1 downTo 0) {
        if (uniform[r]) {
            run++
            if (run >= CROP_DETECT_RUN_ROWS) {
                top = ((r + run) * CROP_DETECT_ROW_STEP).toFloat() / h
                break
            }
        } else {
            run = 0
        }
    }
    // 往下找：純色帶的**上緣**＝底線；轉成「從底部裁掉多少」。
    var bottom = 0f
    run = 0
    for (r in center + 1 until rowCount) {
        if (uniform[r]) {
            run++
            if (run >= CROP_DETECT_RUN_ROWS) {
                bottom = 1f - ((r - run + 1) * CROP_DETECT_ROW_STEP).toFloat() / h
                break
            }
        } else {
            run = 0
        }
    }
    return top.coerceIn(0f, CROP_DETECT_MAX_FRACTION) to bottom.coerceIn(0f, CROP_DETECT_MAX_FRACTION)
}

// ── 自動修邊（第二層裁切：逐頁動態修掉上下大片空白）────────────────────────────
// 純函式（不碰 I/O、不碰 Compose），與 [detectCropBounds] 同一套掃描手法（中央直條、取樣列、亮度值域），
// 但用途相反：detectCropBounds 是**設定時**猜位置固定的網站 UI 分界（一次、寫進逐站 pref），
// autoTrimBounds 是**每頁存檔時**修掉當頁多出來的網站背景空白（雙開頁 fit 寬度後只有半頁高、彩頁/短頁）。
//
// 為什麼需要它：固定裁切線是照「正常頁」設的；雙開頁的圖只有一半高 ⇒ 下方一大片是網站背景，
// 固定的底線落在網站頁尾（上一章/下一頁按鈕列、版權列）之上 → 那些 UI 反而被截進來。

// 近純色門檻（亮度值域 max−min，0–255）：比 [CROP_DETECT_UNIFORM_RANGE] 更嚴，因為這裡是**逐頁動刀**，
// 寧可不修也不要咬到畫面（漫畫內容列值域通常 >100，網站底色 ≈0，漸層背景也多在 10 以內）。
private const val AUTO_TRIM_UNIFORM_RANGE = 16

// 取樣間隔（每幾列取一列）與每列取樣點數（取中央 50% 寬，避開左右邊欄/捲軸）。
private const val AUTO_TRIM_ROW_STEP = 3
private const val AUTO_TRIM_COL_SAMPLES = 24

// 護欄①：連續空白帶要大於總高這個比例才修（小白邊＝漫畫本身的留白，不動）。
private const val AUTO_TRIM_MIN_BAND_FRACTION = 0.08f

// 護欄②：單邊最多修掉的比例（防止整頁被吃）。
private const val AUTO_TRIM_MAX_SIDE_FRACTION = 0.6f

// 護欄③：修完剩餘高度至少要佔安全區這個比例，否則整個放棄（判定異常，例如整頁近純色的載入中畫面）。
private const val AUTO_TRIM_MIN_KEEP_FRACTION = 0.2f

/**
 * 算出當頁要修掉的上下空白：回傳 `(topPx, bottomPx)`＝上/下各修掉幾**像素**；不修＝`0 to 0`。
 *
 * 做法：取畫面**中央 50% 直條**，每 [AUTO_TRIM_ROW_STEP] 列取一列、每列取 [AUTO_TRIM_COL_SAMPLES] 個點算
 * 亮度值域（max−min）；值域 < [AUTO_TRIM_UNIFORM_RANGE] ⇒ 該列視為「空白列」。由**頂端往下**、**底端往上**
 * 各數出「從邊緣開始的連續空白帶」，邊界取該空白帶的內側**再退一個取樣間隔**（取樣列之間沒掃到的列也留給內容，
 * 寧可少修）。三道保守護欄見 [AUTO_TRIM_MIN_BAND_FRACTION] / [AUTO_TRIM_MAX_SIDE_FRACTION] /
 * [AUTO_TRIM_MIN_KEEP_FRACTION]。
 *
 * 成本：只讀中央直條的取樣列（1080×2400 約 40 萬像素、數毫秒），一頁一次，可忽略。輸入為存檔用的 bitmap
 * （ARGB_8888）；不修改也不回收輸入。
 */
fun autoTrimBounds(bitmap: Bitmap): Pair<Int, Int> {
    val w = bitmap.width
    val h = bitmap.height
    if (bitmap.isRecycled || w <= 0 || h < AUTO_TRIM_ROW_STEP * 8) return 0 to 0
    val x0 = w / 4
    val stripW = (w - w / 4 - x0).coerceAtLeast(1)
    val step = (stripW / AUTO_TRIM_COL_SAMPLES).coerceAtLeast(1)
    val rowCount = h / AUTO_TRIM_ROW_STEP
    if (rowCount < 8) return 0 to 0
    val strip = IntArray(stripW)
    val uniform = BooleanArray(rowCount)
    for (r in 0 until rowCount) {
        val y = (r * AUTO_TRIM_ROW_STEP).coerceAtMost(h - 1)
        // 只讀中央直條那半條（不是整列）：省一半像素、也自動避開左右邊欄。讀失敗（尺寸異常）＝放棄修邊。
        runCatching { bitmap.getPixels(strip, 0, stripW, x0, y, stripW, 1) }.getOrElse { return 0 to 0 }
        var min = 255
        var max = 0
        var i = 0
        while (i < stripW) {
            val c = strip[i]
            val luma = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            if (luma < min) min = luma
            if (luma > max) max = luma
            i += step
        }
        uniform[r] = (max - min) < AUTO_TRIM_UNIFORM_RANGE
    }

    // 從頂端往下數連續空白列；從底端往上同理。
    var topRun = 0
    while (topRun < rowCount && uniform[topRun]) topRun++
    var bottomRun = 0
    while (bottomRun < rowCount - topRun && uniform[rowCount - 1 - bottomRun]) bottomRun++

    // 邊界退一個取樣間隔：取樣列之間沒掃到的列可能已經是內容（寧可少修一點）。
    var top = ((topRun - 1) * AUTO_TRIM_ROW_STEP).coerceAtLeast(0)
    var bottom = (h - (rowCount - bottomRun + 1) * AUTO_TRIM_ROW_STEP).coerceAtLeast(0)

    // 護欄①：空白帶不夠大就不修（漫畫本身的小白邊不動）。
    val minBand = (h * AUTO_TRIM_MIN_BAND_FRACTION).roundToInt()
    if (top < minBand) top = 0
    if (bottom < minBand) bottom = 0
    if (top <= 0 && bottom <= 0) return 0 to 0

    // 護欄③：**先**判「修完剩太少＝異常」再套單邊上限——順序不能反。若先夾 60% 再判剩餘，
    // 整頁近純色的載入中畫面（空白帶＝整頁）會被夾成「只修 60%」而通過剩餘檢查，反而存出一條空白。
    val kept = h - top - bottom
    if (kept < (h * AUTO_TRIM_MIN_KEEP_FRACTION).roundToInt() || kept < MIN_CROP_KEEP_PX) return 0 to 0

    // 護欄②：單邊上限（夾完 kept 只會變大，不必重判）。
    val maxSide = (h * AUTO_TRIM_MAX_SIDE_FRACTION).roundToInt()
    return top.coerceAtMost(maxSide) to bottom.coerceAtMost(maxSide)
}

// ── 話數（章名）解析 / 格式化 / 建議 ────────────────────────────────────────
// 純函式（不碰 I/O、不碰 Compose），給「新話數」panel 算建議用；抽在這裡也方便日後單測。

/** 純數字話名的樣式：`12`、`12.1`、`012.25`（整數 or 一個小數點）。其餘（`第一話` / `extra`）視為非數字話。 */
private val CHAPTER_NUMBER_RE = Regex("""\d+(\.\d+)?""")

/**
 * 已截話數的排序：能解析成數字的照數值排前（`01` < `1.5` < `02` < `10`），
 * 解析不到數字的（`extra`、`第一話`）排在後面照名稱排。
 */
private val CHAPTER_ORDER = compareBy<String>(
    { captureChapterNumber(it) ?: Double.MAX_VALUE },
    { it.lowercase() },
)

/** 話名 → 數字；只接受純數字話名（見 [CHAPTER_NUMBER_RE]），其餘回 null（不參與「最後一話」推算）。 */
fun captureChapterNumber(name: String): Double? =
    name.trim().takeIf { CHAPTER_NUMBER_RE.matches(it) }?.toDoubleOrNull()

/**
 * 數字 → 話名字串：**整數部分預設補零到 2 位**（`1` → `01`、`12` → `12`），超過 99 就照實際長度
 * （`100` → `100`，不強制補）；小數只保留 1 位（`12.1`、整數部分同樣補到 2 位）、`.0` 不顯示。
 */
fun formatCaptureChapterName(value: Double): String {
    val rounded = (value * 10.0).roundToLong() / 10.0
    val intPart = floor(rounded).toInt()
    val frac = ((rounded - intPart) * 10).roundToInt().coerceIn(0, 9)
    val intStr = if (intPart < 100) "%02d".format(intPart) else intPart.toString()
    return if (frac == 0) intStr else "$intStr.$frac"
}

/**
 * 依「已截的最後一話」（[existing] 中數值最大的純數字話）推建議話名：
 * - 最後一話是整數 `12` → `13`(n+1) / `12.1`(+0.1) / `12.5`(+0.5)
 * - 最後一話是小數 `12.1` → `12.2`(+0.1) / `13`（下一個整數）——小數通常 +0.1（`12.4` 的下一話多半是 `12.5`）
 * - 完全沒有已截話（或全是非數字話名）→ `01`
 * 使用者仍可在輸入框手動改（建議只是按鈕）。
 */
fun suggestCaptureChapterNames(existing: List<String>): List<String> {
    val last = existing.mapNotNull(::captureChapterNumber).maxOrNull()
        ?: return listOf(formatCaptureChapterName(1.0))
    val isInteger = abs(last - floor(last)) < 1e-6
    val candidates = if (isInteger) {
        listOf(last + 1.0, last + 0.1, last + 0.5)
    } else {
        listOf(last + 0.1, floor(last) + 1.0)
    }
    return candidates.map(::formatCaptureChapterName).distinct()
}

/**
 * 存檔失敗的**可辨識**原因（每一項對應一則可翻譯訊息）。
 *
 * ★ 為什麼要有它（2026-07）：舊版 `Failed(message)` 帶的是 `error("Local source directory unavailable")`
 * 這類英文例外訊息，直接被 toast 出去——使用者看到一句英文、也不知道下一步該做什麼。改成列舉後
 * 畫面層自己挑字串（[messageRes]），連續擷取停下來的原因也講得出是哪一種失敗。
 */
enum class CaptureSaveError(val messageRes: StringResource) {
    /** 書名 / 章名沒填（[CaptureSaveResult.MissingName] 的對應項，供停止原因共用同一套訊息）。 */
    MISSING_NAME(MR.strings.capture_missing_name),

    /** 取不到 local 來源目錄＝還沒設定儲存位置（或該位置已失效）。 */
    NO_STORAGE(MR.strings.capture_save_error_no_storage),

    /** 書名夾建不出來 / 找不到。 */
    MANGA_DIR(MR.strings.capture_save_error_manga_dir),

    /** 章夾建不出來 / 找不到。 */
    CHAPTER_DIR(MR.strings.capture_save_error_chapter_dir),

    /** 圖檔建不出來 / 寫入失敗（空間不足、SAF 權限被撤等）。 */
    WRITE(MR.strings.capture_save_error_write),
}

/** 存檔流程內部用的例外：把失敗點標成 [CaptureSaveError]，由各 save* 的 runCatching 收斂成結果。 */
private class CaptureSaveException(val reason: CaptureSaveError) : Exception(reason.name)

/** 存檔結果：成功（頁碼 + 路徑）／未填書名章名／失敗（可辨識原因 + 原始訊息供 log）。 */
sealed interface CaptureSaveResult {
    data class Saved(val page: Int, val path: String) : CaptureSaveResult
    data object MissingName : CaptureSaveResult
    data class Failed(val reason: CaptureSaveError, val message: String? = null) : CaptureSaveResult
}

/** 例外 → 存檔結果：本檔丟的 [CaptureSaveException] 帶原因，其餘（IO/SAF 例外）一律歸到寫入失敗。 */
private fun Throwable.toCaptureFailure(): CaptureSaveResult.Failed =
    CaptureSaveResult.Failed((this as? CaptureSaveException)?.reason ?: CaptureSaveError.WRITE, message)
