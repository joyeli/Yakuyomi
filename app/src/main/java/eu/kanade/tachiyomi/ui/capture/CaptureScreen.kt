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
class CaptureScreen(private val initialUrl: String = "") : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { CaptureScreenModel() }
        val continuous by screenModel.continuous.collectAsState()

        CaptureScreenContent(
            onNavigateUp = navigator::pop,
            initialUrl = initialUrl,
            bookName = screenModel.bookName,
            onBookNameChange = { screenModel.bookName = it },
            chapterName = screenModel.chapterName,
            onChapterNameChange = { screenModel.chapterName = it },
            onCapture = screenModel::saveCapture,
            continuousRunning = continuous.running,
            capturedCount = continuous.count,
            onStartContinuous = screenModel::startContinuous,
            onStopContinuous = screenModel::stopContinuous,
        )
    }
}

class CaptureScreenModel(
    private val context: Application = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
) : ScreenModel {

    // 書名 / 章名手動輸入（此步先不做選書流程）。
    var bookName by mutableStateOf("")
    var chapterName by mutableStateOf("")

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
     */
    fun startContinuous(grabber: FrameGrabber) {
        if (bookName.isBlank() || chapterName.isBlank()) return
        if (continuousJob?.isActive == true) return
        continuousJob = screenModelScope.launch {
            var prev: IntArray? = null // 前一幀縮圖（判穩定）
            var lastCaptured: IntArray? = null // 上次已截那頁的縮圖（判換頁 + 去重）
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
                            if (saveCapture(frame) is CaptureSaveResult.Saved) {
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
     * I/O 全在 IO thread；SAF 走 ContentResolver "wt" 截斷寫（file:// 用一般串流）。
     */
    suspend fun saveCapture(bitmap: Bitmap): CaptureSaveResult = withIOContext {
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

            CaptureSaveResult.Saved(page, file.uri.toString())
        }.getOrElse { CaptureSaveResult.Failed(it.message) }
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
