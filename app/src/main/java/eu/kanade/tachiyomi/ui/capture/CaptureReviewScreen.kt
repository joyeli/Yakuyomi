package eu.kanade.tachiyomi.ui.capture

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.unifile.UniFile
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/*
 * Yakuyomi 擷取漫畫：連續截圖「停止」後的**確認模式**。
 *
 * ★ 2026-07 重構：這裡**不再是獨立 Screen**。確認頁被拆成「model（本檔）＋ 一個 composable 面板
 * （[eu.kanade.presentation.capture.CaptureReviewScreenContent]）」，由 [CaptureScreen] 以 [CaptureMode]
 * 模式切換的方式疊在**常駐的 WebView 之上**——推獨立 Screen 會讓 CaptureScreen 的 composition 被 dispose、
 * WebView 連同捲動/登入/JS 狀態一併重建（「繼續擷取」回去變 about:blank）。model 的邏輯（掃圖/重編號/
 * 刪選取/放棄 session/插入/重截/儲存）原封不動沿用，只是改成可 [configure] 重設書名/章名/session 頁碼。
 *
 * 純檢視 + 剔除壞頁 + 儲存：掃 `<local>/<書名>/<章名>/` 下的截圖，3 欄網格 + 順序標號 + 勾選刪除，
 * 儲存時把剩餘頁重新編號成連續 001/002…（無缺號）→ 跳到該 local 漫畫詳情頁。
 * 只操作該書/章夾內的圖檔，不碰別處。
 */

/**
 * 確認頁一張截圖：底層 [UniFile] + 顯示名 + 該頁記錄的網址 [url]（讀同名 `.url` sidecar，沒有＝null）。
 * [uri] 字串當網格 key / 選取集合的元素。
 */
data class CapturePage(val file: UniFile, val name: String, val url: String? = null) {
    val uri: String = file.uri.toString()
}

/**
 * 確認頁狀態：載入中 / 目前頁清單 / 已勾選（uri 字串集合）/ 儲存中。
 * [reloadKey] 每次重掃遞增，供縮圖破 coil 快取（重截同檔名覆蓋後顯示新圖，不留舊快取殘影）。
 * [sessionPageCount]＝本次連續截圖存下的頁數（>0 才顯示「放棄這次截圖」），由 [CaptureReviewViewModel.configure] 設。
 * [stopReason]/[stopDetail]＝連續擷取**自己**停下來的原因（按停止進來＝null）：頂端顯示一行提示，
 * 讓使用者知道「為什麼突然跳到這裡」（尤其是存檔失敗那種以前完全無聲的情況）。
 */
data class CaptureReviewState(
    val loading: Boolean = true,
    val pages: List<CapturePage> = emptyList(),
    val selected: Set<String> = emptySet(),
    val saving: Boolean = false,
    val reloadKey: Int = 0,
    val sessionPageCount: Int = 0,
    val stopReason: CaptureStopReason? = null,
    val stopDetail: CaptureSaveError? = null,
)

/**
 * 一次性事件（由 [CaptureScreen] 收）：
 * - [OpenManga]＝儲存完成 → 離開整個擷取畫面、跳該 local 漫畫詳情（此時 WebView 才銷毀）。
 * - [Back]＝結束確認模式回到擷取模式（放棄這次截圖後）。
 * - [Error]＝出錯了但**留在確認頁**（例：儲存時找不到 local 漫畫）→ 畫面層吐一則訊息，使用者可重試。
 * - [ReCapture] / [Insert]＝切到單張擷取模式（WebView 原地不動、只在該頁有記網址時 loadUrl 過去）。
 */
sealed interface CaptureReviewEvent {
    data class OpenManga(val mangaId: Long) : CaptureReviewEvent
    data object Back : CaptureReviewEvent
    data class Error(val messageRes: StringResource) : CaptureReviewEvent
    data class ReCapture(val target: ReCaptureTarget) : CaptureReviewEvent
    data class Insert(val target: InsertTarget) : CaptureReviewEvent
}

class CaptureReviewViewModel(
    private val storageManager: StorageManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    // 寫整章 meta（.yakuyomi_meta.json）需 context 開截斷串流（見 [writeMeta]）。
    private val context: Application = Injekt.get(),
) : ViewModel() {

    // 章夾定位用「安全檔名」（與 CaptureViewModel.saveCapture 落地時同一套 sanitise）。
    // ★ 改成 var：本 model 的實例跟著常駐的 CaptureScreen 活著（不再每次進確認頁 new 一個），
    // 進入確認模式時由 [configure] 設定當下的書名/章名/本次 session 頁碼。
    private var safeBook = ""
    private var safeChapter = ""
    private var title = ""

    // 本次連續截圖存下的頁碼（供 [discardSession] 只刪這批）。
    private var sessionPages: List<Int> = emptyList()

    private val _state = MutableStateFlow(CaptureReviewState())
    val state: StateFlow<CaptureReviewState> = _state.asStateFlow()

    private val _events = Channel<CaptureReviewEvent>()
    val events = _events.receiveAsFlow()

    /**
     * 進入確認模式前設定目標章夾與本次 session 頁碼，並重掃該章夾。
     * 每次由 [CaptureScreen] 切到 [CaptureMode.REVIEW] 時呼叫（含連續截圖停止、單張重截/插入完成後回來）。
     * 重設勾選（上一輪的選取不該殘留），[loadPages] 會把 loading 關掉並帶新的 reloadKey 破縮圖快取。
     */
    fun configure(
        bookName: String,
        chapterName: String,
        sessionPages: List<Int>,
        stopReason: CaptureStopReason? = null,
        stopDetail: CaptureSaveError? = null,
    ) {
        this.safeBook = DiskUtil.buildValidFilename(bookName.trim())
        this.safeChapter = DiskUtil.buildValidFilename(chapterName.trim())
        this.title = bookName.trim()
        this.sessionPages = sessionPages.toList()
        _state.update {
            CaptureReviewState(
                loading = true,
                reloadKey = it.reloadKey,
                sessionPageCount = this.sessionPages.size,
                stopReason = stopReason,
                stopDetail = stopDetail,
            )
        }
        loadPages()
    }

    /** 定位 `<local>/<safeBook>/<safeChapter>/`（任一層缺 → null）。 */
    private fun chapterDir(): UniFile? =
        storageManager.getLocalSourceDirectory()
            ?.findFile(safeBook)?.takeIf { it.isDirectory }
            ?.findFile(safeChapter)?.takeIf { it.isDirectory }

    /**
     * 掃該章夾內的圖檔（png/jpg/webp）、依名稱排序（截圖零填充 3 位 → 字串排序＝頁序）。
     * 網址先讀整章 meta（`.yakuyomi_meta.json`，[readMeta] 一次）→ 每頁 `map[basename]`；
     * meta 沒有該頁 → **fallback** 讀舊 `NNN.url` sidecar（相容既有那批、不用重截）；都無＝null。
     */
    private fun scanPages(dir: UniFile): List<CapturePage> {
        val files = dir.listFiles().orEmpty()
        val meta = readMeta(dir)
        return files
            .filter { !it.isDirectory && isImageName(it.name) }
            .sortedBy { it.name.orEmpty().lowercase() }
            .map { file ->
                val base = file.name.orEmpty().substringBeforeLast('.')
                val url = meta[base]
                    ?: files.firstOrNull { it.name == "$base.url" }
                        ?.let { sidecar -> runCatching { readSidecar(sidecar) }.getOrNull() }
                CapturePage(file, file.name.orEmpty(), url)
            }
    }

    private fun readSidecar(file: UniFile): String? =
        file.openInputStream().use { it.readBytes().toString(Charsets.UTF_8).trim() }
            .takeIf { it.isNotEmpty() }

    fun loadPages() {
        viewModelScope.launch {
            val pages = withIOContext { chapterDir()?.let(::scanPages).orEmpty() }
            _state.update { s ->
                val uris = pages.map { it.uri }.toSet()
                s.copy(
                    loading = false,
                    pages = pages,
                    selected = s.selected.intersect(uris),
                    reloadKey = s.reloadKey + 1,
                )
            }
        }
    }

    /** 對某頁發起重截：帶該頁記錄的網址 [CapturePage.url] + 章夾定位 + 檔名，切到單張擷取模式。 */
    fun reCapture(page: CapturePage) {
        viewModelScope.launch {
            _events.send(
                CaptureReviewEvent.ReCapture(ReCaptureTarget(page.url, safeBook, safeChapter, page.name)),
            )
        }
    }

    /**
     * 在某頁前/後插入一張新截圖：算出插入位置頁碼（before＝該頁頁碼 N、after＝N+1）→ 切到單張擷取模式，
     * 並帶被長按那頁的網址 [CapturePage.url]（有記網址才 loadUrl 過去、否則 WebView 保持現狀讓使用者自己捲）。
     * 頁碼取自檔名（`003.png` → 3；解析不到＝忽略）。實際騰位與存檔在 [CaptureViewModel.saveInsert]。
     */
    fun insert(page: CapturePage, before: Boolean) {
        val pageNo = page.name.substringBeforeLast('.').toIntOrNull() ?: return
        val insertAt = if (before) pageNo else pageNo + 1
        viewModelScope.launch {
            _events.send(CaptureReviewEvent.Insert(InsertTarget(safeBook, safeChapter, insertAt, page.url)))
        }
    }

    /** 切換某頁的勾選（要刪的）。 */
    fun toggleSelection(uri: String) {
        _state.update { s ->
            val sel = s.selected.toMutableSet()
            if (!sel.add(uri)) sel.remove(uri)
            s.copy(selected = sel)
        }
    }

    /** 刪除已勾選的圖檔（連同 legacy `.url` sidecar + 整章 meta 內該頁 entry，免留孤兒）→ 重新掃、更新網格。 */
    fun deleteSelected() {
        val selected = _state.value.selected
        if (selected.isEmpty()) return
        viewModelScope.launch {
            withIOContext {
                val dir = chapterDir() ?: return@withIOContext
                val meta = readMeta(dir)
                _state.value.pages
                    .filter { it.uri in selected }
                    .forEach { page ->
                        runCatching { page.file.delete() }
                        val base = page.name.substringBeforeLast('.')
                        runCatching { dir.findFile("$base.url")?.delete() }
                        meta.remove(base)
                    }
                writeMeta(context, dir, meta)
            }
            _state.update { it.copy(selected = emptySet()) }
            loadPages()
        }
    }

    /**
     * 放棄這次連續截圖：刪掉本 session 截的頁（[sessionPages] 對應的 `%03d.png`／其他副檔名同 basename 圖 + `%03d.url`），
     * 只在該章夾內、存在才刪、找不到＝no-op；**不動接續截圖前既有的其他頁** → 發 Back 事件回擷取模式（WebView 不動）。
     */
    fun discardSession() {
        viewModelScope.launch {
            withIOContext {
                val dir = chapterDir() ?: return@withIOContext
                val targets = sessionPages.map { "%03d".format(it) }.toSet()
                dir.listFiles().orEmpty()
                    .filter { !it.isDirectory }
                    .filter {
                        val name = it.name.orEmpty()
                        val base = name.substringBeforeLast('.')
                        base in targets && (isImageName(name) || name.endsWith(".url"))
                    }
                    .forEach { file -> runCatching { file.delete() } }
                // 整章 meta 同步移除本 session 頁的網址 entry（免留孤兒）。
                val meta = readMeta(dir)
                targets.forEach { meta.remove(it) }
                writeMeta(context, dir, meta)
            }
            _events.send(CaptureReviewEvent.Back)
        }
    }

    /**
     * 把剩餘頁重新編號成連續 001/002…（兩階段防覆蓋）→ 找到該 local 漫畫 → 發導覽事件。
     *
     * ★ 找不到漫畫時（2026-07 修）：**留在確認頁**、復位 [CaptureReviewState.saving]、發 [CaptureReviewEvent.Error]
     * 讓畫面層講一聲。舊版直接發 [CaptureReviewEvent.Back]＝畫面無聲切回擷取模式，使用者以為自己誤觸，
     * 也不知道重新編號其實已經做完了。
     */
    fun save() {
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val mangaId = withIOContext {
                chapterDir()?.let { renumber(it) }
                findLocalMangaId()
            }
            if (mangaId != null) {
                _events.send(CaptureReviewEvent.OpenManga(mangaId))
            } else {
                _state.update { it.copy(saving = false) }
                _events.send(CaptureReviewEvent.Error(MR.strings.capture_review_save_failed))
            }
        }
    }

    /**
     * 依目前順序把整章重新編號成連續 001/002…。兩階段避免 001→002 覆蓋衝突：
     * 先全部改成暫名 `__tmp_NNN`，再由暫名（保留順序）改成最終 `NNN`。副檔名逐檔保留。
     * legacy `.url` sidecar 跟著圖一起連帶改名（同一暫名索引，不留孤兒）；整章 meta 則於重排後**整批重建**：
     * 新頁碼 001,002… 對應 [scanPages] 當前順序第 1,2… 頁的網址（scanPages 已把 meta/legacy 網址填進 [CapturePage.url]）。
     */
    private fun renumber(dir: UniFile) {
        // 重排前先抓當前順序（含每頁網址）——後面拿來重建 meta。
        val ordered = scanPages(dir)
        // 階段一：目前順序 → 暫名（零填充保順序）；圖 + 其 legacy .url sidecar 用同一索引一起改。
        ordered.forEachIndexed { i, page ->
            renameOrCopy(dir, page.file, "%s%03d.%s".format(TMP_PREFIX, i + 1, extOf(page.name)))
            val base = page.name.substringBeforeLast('.')
            dir.listFiles().orEmpty().firstOrNull { it.name == "$base.url" }?.let { sidecar ->
                renameOrCopy(dir, sidecar, "%s%03d.url".format(TMP_PREFIX, i + 1))
            }
        }
        // 階段二：暫名（依名稱＝順序）→ 最終 NNN；.url 亦在其列（extOf 保留副檔名 url）。
        dir.listFiles().orEmpty()
            .filter { !it.isDirectory && it.name.orEmpty().startsWith(TMP_PREFIX) }
            .sortedBy { it.name.orEmpty() }
            .forEach { file ->
                val name = file.name.orEmpty()
                val idx = name.removePrefix(TMP_PREFIX).substringBefore('.').toIntOrNull() ?: return@forEach
                renameOrCopy(dir, file, "%03d.%s".format(idx, extOf(name)))
            }
        // 重建整章 meta：新頁碼 i+1 ← 原順序第 i 頁的網址（空網址略過）。整批截斷回寫 → 與新編號一致、無孤兒。
        val rebuilt = mutableMapOf<String, String>()
        ordered.forEachIndexed { i, page ->
            val u = page.url?.trim().orEmpty()
            if (u.isNotEmpty()) rebuilt["%03d".format(i + 1)] = u
        }
        writeMeta(context, dir, rebuilt)
    }

    /** 改名；[UniFile.renameTo] 失敗（回 false / 丟例外）→ 退回 copy 到新名 + 刪舊檔。 */
    private fun renameOrCopy(dir: UniFile, file: UniFile, newName: String) {
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
     * 找該 local 漫畫並取 id：LocalSource 的 manga url ＝書名夾名（[safeBook]）。
     * [NetworkToLocalManga] 依 (source, url) 找既有列，沒有就插一列並回傳帶 id 的 [Manga]。
     */
    private suspend fun findLocalMangaId(): Long? = runCatching {
        val manga = Manga.create().copy(url = safeBook, title = title, source = LocalSource.ID)
        networkToLocalManga(manga).id
    }.getOrNull()

    private fun extOf(name: String): String = name.substringAfterLast('.', "png")

    private fun isImageName(name: String?): Boolean =
        (name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXTS

    companion object {
        private const val TMP_PREFIX = "__tmp_"
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp")
    }
}
