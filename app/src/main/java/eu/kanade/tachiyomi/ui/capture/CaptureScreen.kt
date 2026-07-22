package eu.kanade.tachiyomi.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.presentation.capture.CaptureScreenContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.math.abs

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

/** 連續截圖狀態：是否進行中 + 本 session 已截頁數（給 UI 顯示「已截 N 頁」）。 */
data class ContinuousCaptureState(val running: Boolean = false, val count: Int = 0)

/**
 * Yakuyomi 擷取漫畫（B1a-1 骨架）：內建 WebView 開任意網站 → 「截這頁」→ 存成 LocalSource 的
 * 一本漫畫 / 一話的鬆散頁圖，證明「截圖 → local 漫畫 → 書庫看得到 + 能翻譯」整條路通。
 *
 * 此步範圍嚴格限定：書名 / 章名手動輸入、存整張截圖（不裁切）、單一 session 頁碼遞增。
 * 裁切、選書流程、話數建議、cover/metadata 皆為後續步驟。
 *
 * B1b 半自動連續截圖：使用者手動翻頁，app 用 frame-diff 雙門檻（穩定 + 換頁）自動偵測、自動截存。
 */
class CaptureScreen(
    private val initialUrl: String = "",
    private val reCaptureTarget: ReCaptureTarget? = null,
    private val insertTarget: InsertTarget? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { CaptureScreenModel() }
        val continuous by screenModel.continuous.collectAsState()
        val target = reCaptureTarget
        val insert = insertTarget

        CaptureScreenContent(
            onNavigateUp = navigator::pop,
            initialUrl = when {
                target != null -> target.url ?: initialUrl
                // 插入模式：從被長按那頁的網址開起（使用者由該頁捲到要插入的頁再截）；取不到＝about:blank。
                insert != null -> insert.url ?: "about:blank"
                else -> initialUrl
            },
            bookName = screenModel.bookName,
            onBookNameChange = { screenModel.bookName = it },
            chapterName = screenModel.chapterName,
            onChapterNameChange = { screenModel.chapterName = it },
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
            onStartContinuous = screenModel::startContinuous,
            onStopContinuous = screenModel::stopContinuous,
            // 停止後 push 確認頁時帶本次 session 截的頁碼（供「放棄這次截圖」只刪這批）。
            sessionPages = { screenModel.sessionPages },
            reCaptureTargetPage = target?.pageNumber,
            insertTargetPage = insert?.insertAtPage,
            // 重截 / 插入皆為單張、成功後退回確認頁（其 LaunchedEffect 重掃顯示更新後的序）。
            onReCaptureDone = navigator::pop,
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

class CaptureScreenModel(
    private val context: Application = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
) : ScreenModel {

    // 書名 / 章名手動輸入（此步先不做選書流程）。
    var bookName by mutableStateOf("")
    var chapterName by mutableStateOf("")

    // 本次連續截圖存下的頁碼（每次 startContinuous 重置）；供確認頁「放棄這次截圖」只刪這批、
    // 不誤刪接續截圖前該章夾既有的頁。快照回傳（toList）避免與迴圈的 add 撞併發修改。
    private val _sessionPages = mutableListOf<Int>()
    val sessionPages: List<Int> get() = _sessionPages.toList()

    // 連續截圖：狀態 + 驅動迴圈的 job。
    private val _continuous = MutableStateFlow(ContinuousCaptureState())
    val continuous: StateFlow<ContinuousCaptureState> = _continuous.asStateFlow()
    private var continuousJob: Job? = null

    /**
     * 開始半自動連續截圖：開一條 coroutine，每 [CAPTURE_INTERVAL_MS] 用 [grabber] 抓一幀 →
     * 縮成灰階小圖算 MAD → 「畫面靜止(穩定) AND 內容與上次已截頁不同(換頁)」才存 → 更新「上次已截縮圖」。
     * 首次尚未截過任何頁時 [changed] 恆真，故一旦畫面靜止即截第一張。
     * 需先填書名 / 章名（呼叫端已擋一次，這裡再守一次）；重複呼叫不會疊開。
     *
     * 抓幀在主執行緒（PixelCopy 需 window）、比對在 [Dispatchers.Default]、存檔在 IO（[saveCapture] 內建）。
     * [urlProvider] 在主執行緒讀當前 WebView 網址（供每張截圖記進整章 meta）；取不到＝null（不記）。
     */
    fun startContinuous(grabber: FrameGrabber, urlProvider: () -> String?) {
        if (bookName.isBlank() || chapterName.isBlank()) return
        if (continuousJob?.isActive == true) return
        continuousJob = screenModelScope.launch {
            var prev: IntArray? = null // 前一幀縮圖（判穩定）
            var lastCaptured: IntArray? = null // 上次已截那頁的縮圖（判換頁 + 去重）
            _sessionPages.clear()
            _continuous.update { it.copy(running = true, count = 0) }
            try {
                while (isActive) {
                    val frame = grabFrame(grabber)
                    if (frame != null) {
                        val thumb = withContext(Dispatchers.Default) { thumbLuma(frame) }
                        // 載入過場的黑頁/純色幀跳過（不當有效頁），但仍更新 prev 維持穩定判斷連續：
                        // 黑頁 → 真頁載入中（跟黑頁比不穩定）不截 → 載入完靜止才截。
                        val blank = isBlank(thumb)
                        val stable = prev?.let { mad(thumb, it) < STABLE_THRESHOLD } ?: false
                        val changed = lastCaptured?.let { mad(thumb, it) > CHANGE_THRESHOLD } ?: true
                        if (!blank && stable && changed) {
                            // WebView 網址須在主執行緒讀（PixelCopy 抓幀已在主執行緒，這裡另起一次快讀）。
                            val url = withUIContext { urlProvider() }
                            val result = saveCapture(frame, url)
                            if (result is CaptureSaveResult.Saved) {
                                _sessionPages.add(result.page)
                                lastCaptured = thumb
                                _continuous.update { it.copy(count = it.count + 1) }
                            }
                        }
                        prev = thumb
                        if (!frame.isRecycled) frame.recycle()
                    }
                    delay(CAPTURE_INTERVAL_MS)
                }
            } finally {
                _continuous.update { it.copy(running = false) }
            }
        }
    }

    /** 停止連續截圖（使用者按停止 / 畫面離開 / 生命週期 ON_STOP 都走這；idempotent）。 */
    fun stopContinuous() {
        continuousJob?.cancel()
        continuousJob = null
        _continuous.update { it.copy(running = false) }
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
            val chapterDir = base.findFile(safeBook)?.takeIf { it.isDirectory }
                ?.findFile(safeChapter)?.takeIf { it.isDirectory }
                ?: error("Chapter directory not found")

            val file = chapterDir.findFile(pageName)
                ?: chapterDir.createFile(pageName)
                ?: error("Cannot create page file")
            openTruncating(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val page = pageName.substringBeforeLast('.').toIntOrNull() ?: 0
            updateMetaUrl(chapterDir, page, url)
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
            val chapterDir = base.findFile(safeBook)?.takeIf { it.isDirectory }
                ?.findFile(safeChapter)?.takeIf { it.isDirectory }
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

/** 存檔結果：成功（頁碼 + 路徑）／未填書名章名／失敗（訊息）。 */
sealed interface CaptureSaveResult {
    data class Saved(val page: Int, val path: String) : CaptureSaveResult
    data object MissingName : CaptureSaveResult
    data class Failed(val message: String?) : CaptureSaveResult
}
