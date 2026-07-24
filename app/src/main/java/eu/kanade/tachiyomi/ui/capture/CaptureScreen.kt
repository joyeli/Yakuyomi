package eu.kanade.tachiyomi.ui.capture

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.capture.CaptureReviewScreenContent
import eu.kanade.presentation.capture.CaptureScreenContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.storage.DiskUtil
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

// 網址列輸入歷史保留上限（與 MoreScreenModel 一致）。
private const val MAX_WEBVIEW_URL_HISTORY = 20

// 封面檔名：對齊 LocalCoverManager 的 DEFAULT_COVER_NAME＝存書名夾根的 `cover.jpg`，LocalSource 才認得
// （find() 找 nameWithoutExtension=="cover" 的圖）。存這個名字 → 書櫃自動顯示封面。
private const val COVER_NAME = "cover.jpg"

/**
 * 連續截圖狀態：是否進行中 + 本 session 已截頁數（給 UI 顯示「已截 N 頁」）+
 * [justCapturedPage]＝剛存下的頁碼（非 null 時 UI 顯示「已擷取第 N 頁 · 請翻下一頁」提示，[CAPTURE_PAUSE_MS] 後回 null）。
 */
data class ContinuousCaptureState(
    val running: Boolean = false,
    val count: Int = 0,
    val justCapturedPage: Int? = null,
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
    // 「繼續擷取」帶入的書名（詳情頁 overflow 入口）：非空時進畫面即設 [CaptureScreenModel.bookName]，
    // 漸進解鎖直接到 S2（書名已定、只差設話數）；null/空＝全新擷取（走 S0）。★ 這個書名必須讓
    // saveCapture 的 safeBook=buildValidFilename(book) 對回原夾（詳見詳情頁 MangaScreenModel.buildContinueCaptureArgs）。
    private val initialBook: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { CaptureScreenModel() }

        // 「繼續擷取」：進畫面把帶入的書名塞進 model（一次性；bookName 非空 → 漸進解鎖到 S2）。
        LaunchedEffect(Unit) {
            initialBook?.trim()?.takeIf { it.isNotEmpty() }?.let { screenModel.bookName = it }
        }
        // 確認面板的 model 與擷取畫面同壽命（不再是獨立 Screen 的 model）：邏輯完全沿用，
        // 只在每次進入確認模式時 configure 目標章夾 + 本次 session 頁碼。
        val reviewModel = rememberScreenModel(tag = "capture-review") { CaptureReviewScreenModel() }
        val continuous by screenModel.continuous.collectAsState()
        val reviewState by reviewModel.state.collectAsState()

        // 目前模式 + 單張模式（重截/插入）的目標；shotToken 每次進單張模式遞增，供內容層重新 loadUrl。
        var mode by remember { mutableStateOf(CaptureMode.CAPTURING) }
        var reCaptureTarget by remember { mutableStateOf<ReCaptureTarget?>(null) }
        var insertTarget by remember { mutableStateOf<InsertTarget?>(null) }
        var shotToken by remember { mutableIntStateOf(0) }

        /** 進確認模式：WebView 原地不動，只是被確認面板蓋住。 */
        fun enterReview() {
            reCaptureTarget = null
            insertTarget = null
            mode = CaptureMode.REVIEW
            reviewModel.configure(screenModel.bookName, screenModel.chapterName, screenModel.sessionPages)
        }

        LaunchedEffect(Unit) {
            reviewModel.events.collectLatest { event ->
                when (event) {
                    // 儲存完成＝整個擷取流程結束，這時才離開畫面（WebView 到此才銷毀）。
                    is CaptureReviewEvent.OpenManga -> navigator.replace(MangaScreen(event.mangaId))
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

        val target = reCaptureTarget
        val insert = insertTarget

        CaptureScreenContent(
            onNavigateUp = navigator::pop,
            initialUrl = initialUrl,
            mode = mode,
            bookName = screenModel.bookName,
            onBookNameChange = { screenModel.bookName = it },
            chapterName = screenModel.chapterName,
            onChapterNameChange = { screenModel.chapterName = it },
            // 「新話數」panel 的已截話數總覽 / 話數建議來源：掃該書夾下的話夾名稱。
            existingChaptersProvider = { book -> screenModel.existingChapters(book) },
            onCapture = when {
                target != null -> {
                    { bitmap, url ->
                        screenModel.saveReCapture(bitmap, url, target.safeBook, target.safeChapter, target.pageName)
                    }
                }
                insert != null -> {
                    { bitmap, url ->
                        screenModel.saveInsert(bitmap, url, insert.safeBook, insert.safeChapter, insert.insertAtPage)
                    }
                }
                else -> screenModel::saveCapture
            },
            continuousRunning = continuous.running,
            capturedCount = continuous.count,
            justCapturedPage = continuous.justCapturedPage,
            onStartContinuous = screenModel::startContinuous,
            onStopContinuous = screenModel::stopContinuous,
            // 按停止＝進確認模式（不 push Screen、WebView 續活）。
            onEnterReview = ::enterReview,
            reCaptureTargetPage = target?.pageNumber,
            insertTargetPage = insert?.insertAtPage,
            // 單張模式：該頁有記網址才開回去；沒有就保持 WebView 現狀（不是每個站的網址都帶頁資訊）。
            singleShotUrl = target?.url ?: insert?.url,
            singleShotToken = shotToken,
            // 重截 / 插入皆為單張、成功或取消後回確認模式並重掃（顯示更新後的序）。
            onReCaptureDone = ::enterReview,
            onSingleShotCancel = ::enterReview,
            // 確認模式按系統返回＝繼續擷取（回擷取模式、不刪頁）。
            onReviewContinue = { mode = CaptureMode.CAPTURING },
            // 網址列輸入歷史（帶出歷史清單 + 逐筆刪除 + 造訪時記錄；帶頁面標題）。
            urlHistoryProvider = { screenModel.webViewUrlHistory() },
            onAddUrl = { url, title -> screenModel.addWebViewUrl(url, title) },
            onRemoveUrl = { screenModel.removeWebViewUrl(it) },
            // 我的最愛（手動存常用站 + 命名別名；置頂快選、與自動歷史分開）。
            bookmarksProvider = { screenModel.listBookmarks() },
            onAddBookmark = { url, alias -> screenModel.addBookmark(url, alias) },
            onRemoveBookmark = { screenModel.removeBookmark(it) },
            // 封面框選：裁好的 bitmap + bitmap 座標系的裁切框 + 當前書名 → 存書名夾根 cover.jpg，回 uri（縮圖預覽）。
            onSaveCover = { bitmap, rect, book -> screenModel.saveCover(bitmap, rect, book) },
            // 開「新漫畫」panel 時撈該書已存的封面（重進顯示縮圖）。
            coverProvider = { book -> screenModel.findCoverUri(book) },
            // 「新漫畫」確定時記漫畫來源網址（供日後「繼續擷取」）。
            onWriteMangaMeta = { book, url -> screenModel.writeMangaMeta(book, url) },
            // 確認面板＝疊在常駐 WebView 上的一層 composable（原本的獨立 Screen 內容，邏輯不變）。
            reviewContent = {
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
 * [insertAtPage]＝新頁要落的頁碼；存檔時把該頁碼（含）以上的既有頁 +1 騰位（見 [CaptureScreenModel.saveInsert]）。
 * [url]＝被長按那頁記錄的網址（相鄰頁 URL，供插入時開回附近；可能為 null＝該頁當初取不到網址）。
 * 章夾用已 sanitise 的 [safeBook] / [safeChapter] 定位（與存檔時同一套安全檔名）。
 */
data class InsertTarget(
    val safeBook: String,
    val safeChapter: String,
    val insertAtPage: Int,
    val url: String? = null,
)

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

class CaptureScreenModel(
    private val context: Application = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
) : ScreenModel {

    // 書名 / 章名手動輸入（此步先不做選書流程）。
    var bookName by mutableStateOf("")
    var chapterName by mutableStateOf("")

    // Yakuyomi：擷取畫面網址列的輸入歷史（**帶頁面標題**）——存 UiPreferences.captureUrlHistory，JSON 陣列
    // `[{"url":..,"title":..}]`、最近的在最前。與 More 共用的純 url 歷史（lastWebViewUrls）分開（見 UiPreferences
    // 註解）。add/remove 仿 MoreScreenModel（去重 → 放最前 → 截斷上限）。

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
        screenModelScope.launch(Dispatchers.IO) {
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
     */
    fun startContinuous(
        compareGrabber: FrameGrabber,
        cleanGrabber: FrameGrabber,
        urlProvider: () -> String?,
    ) {
        if (bookName.isBlank() || chapterName.isBlank()) return
        if (continuousJob?.isActive == true) return
        continuousJob = screenModelScope.launch {
            var prev: IntArray? = null // 前一幀縮圖（判穩定）
            var lastCaptured: IntArray? = null // 上次已截那頁的縮圖（判換頁 + 去重）
            // 上次「抓了乾淨幀卻是空白/黑頁」而丟棄的畫面：同一張黑頁不再反覆抓乾淨幀（免無謂閃爍）。
            var lastRejected: IntArray? = null
            _sessionPages.clear()
            _continuous.update { it.copy(running = true, count = 0, justCapturedPage = null) }
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
                        _continuous.update { it.copy(count = it.count + 1, justCapturedPage = result.page) }
                        // ⑤ 暫停偵測 + 顯示「已擷取第 N 頁 · 請翻下一頁」；期間畫面可能被翻動 →
                        // prev 歸零，回去後重新建立穩定基準。
                        prev = null
                        delay(CAPTURE_PAUSE_MS)
                        _continuous.update { it.copy(justCapturedPage = null) }
                        continue
                    }
                    prev = thumb
                    delay(CAPTURE_INTERVAL_MS)
                }
            } finally {
                _continuous.update { it.copy(running = false, justCapturedPage = null) }
            }
        }
    }

    /** 停止連續截圖（使用者按停止 / 畫面離開 / 生命週期 ON_STOP 都走這；idempotent）。 */
    fun stopContinuous() {
        continuousJob?.cancel()
        continuousJob = null
        _continuous.update { it.copy(running = false, justCapturedPage = null) }
    }

    override fun onDispose() {
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
                ?: error("Local source directory unavailable")
            val safeBook = DiskUtil.buildValidFilename(book)
            val safeChapter = DiskUtil.buildValidFilename(chapter)
            val mangaDir = base.findFile(safeBook)?.takeIf { it.isDirectory }
                ?: base.createDirectory(safeBook)
                ?: error("Cannot create manga directory")
            val chapterDir = mangaDir.findFile(safeChapter)?.takeIf { it.isDirectory }
                ?: mangaDir.createDirectory(safeChapter)
                ?: error("Cannot create chapter directory")

            val page = nextPageNumber(chapterDir)
            val name = "%03d.png".format(page)
            val file = chapterDir.createFile(name) ?: error("Cannot create page file")
            openTruncating(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            updateMetaUrl(chapterDir, page, url)
            // ★ 安全網（件 1）：確保**漫畫層** meta（.yakuyomi_manga）存在——供日後「繼續擷取」開回原站。
            // 不再只靠「新漫畫 panel 按確定」那一條（continue-capture 帶著書名進來根本不開該 panel、就漏寫）；
            // 只在缺檔且有有效網址時補寫（write-if-absent，保留 panel 當初記的目錄/首頁網址）。
            ensureMangaMeta(mangaDir, url)

            CaptureSaveResult.Saved(page, file.uri.toString())
        }.getOrElse { CaptureSaveResult.Failed(it.message) }
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
                ?: error("Local source directory unavailable")
            val mangaDir = base.findFile(safeBook)?.takeIf { it.isDirectory }
                ?: error("Manga directory not found")
            val chapterDir = mangaDir.findFile(safeChapter)?.takeIf { it.isDirectory }
                ?: error("Chapter directory not found")

            val file = chapterDir.findFile(pageName)
                ?: chapterDir.createFile(pageName)
                ?: error("Cannot create page file")
            openTruncating(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val page = pageName.substringBeforeLast('.').toIntOrNull() ?: 0
            updateMetaUrl(chapterDir, page, url)
            // 安全網同 [saveCapture]：純靠重截補頁的書也要有漫畫層 meta（否則「繼續擷取」開不回原站）。
            ensureMangaMeta(mangaDir, url)
            CaptureSaveResult.Saved(page, file.uri.toString())
        }.getOrElse { CaptureSaveResult.Failed(it.message) }
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
                ?: error("Local source directory unavailable")
            val mangaDir = base.findFile(safeBook)?.takeIf { it.isDirectory }
                ?: error("Manga directory not found")
            val chapterDir = mangaDir.findFile(safeChapter)?.takeIf { it.isDirectory }
                ?: error("Chapter directory not found")

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
            val file = chapterDir.findFile(name) ?: chapterDir.createFile(name) ?: error("Cannot create page file")
            openTruncating(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

            // 安全網同 [saveCapture]：純靠插入補頁的書也要有漫畫層 meta。
            ensureMangaMeta(mangaDir, url)
            CaptureSaveResult.Saved(insertAtPage, file.uri.toString())
        }.getOrElse { CaptureSaveResult.Failed(it.message) }
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

/** 存檔結果：成功（頁碼 + 路徑）／未填書名章名／失敗（訊息）。 */
sealed interface CaptureSaveResult {
    data class Saved(val page: Int, val path: String) : CaptureSaveResult
    data object MissingName : CaptureSaveResult
    data class Failed(val message: String?) : CaptureSaveResult
}
