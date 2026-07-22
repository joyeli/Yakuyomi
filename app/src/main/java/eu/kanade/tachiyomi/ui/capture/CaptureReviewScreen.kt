package eu.kanade.tachiyomi.ui.capture

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.presentation.capture.CaptureReviewScreenContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Yakuyomi 擷取漫畫（B1c 第一步）：連續截圖「停止」後的確認頁。
 *
 * 純檢視 + 剔除壞頁 + 儲存：掃 `<local>/<書名>/<章名>/` 下的截圖，3 欄網格 + 順序標號 + 勾選刪除，
 * 儲存時把剩餘頁重新編號成連續 001/002…（無缺號）→ 跳到該 local 漫畫詳情頁。
 * 先不做重截 / 插入 / 缺頁提示（後續步驟）。只操作該書/章夾內的圖檔，不碰別處。
 */
class CaptureReviewScreen(
    private val bookName: String,
    private val chapterName: String,
    // 本次連續截圖存下的頁碼（自 CaptureScreen 帶入）；供「放棄這次截圖」只刪這批、不誤刪既有頁。空＝不顯示放棄入口。
    private val sessionPages: List<Int> = emptyList(),
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { CaptureReviewScreenModel(bookName, chapterName, sessionPages) }
        val state by screenModel.state.collectAsState()

        // 每次進入（含從重截 pop 回來）重掃該章夾——Voyager 隱藏頁會 dispose composition，返回時
        // 此 LaunchedEffect(Unit) 重跑 → 重截覆蓋的新圖被重新載入顯示。
        LaunchedEffect(Unit) { screenModel.loadPages() }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    is CaptureReviewEvent.OpenManga -> navigator.replace(MangaScreen(event.mangaId))
                    CaptureReviewEvent.Back -> navigator.pop()
                    is CaptureReviewEvent.ReCapture -> navigator.push(CaptureScreen(reCaptureTarget = event.target))
                    is CaptureReviewEvent.Insert -> navigator.push(CaptureScreen(insertTarget = event.target))
                }
            }
        }

        CaptureReviewScreenContent(
            state = state,
            onNavigateUp = navigator::pop,
            onToggleSelect = screenModel::toggleSelection,
            onReCapture = screenModel::reCapture,
            onInsert = screenModel::insert,
            onDeleteSelected = screenModel::deleteSelected,
            onSave = screenModel::save,
            sessionPageCount = sessionPages.size,
            onDiscardSession = screenModel::discardSession,
        )
    }
}

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
 */
data class CaptureReviewState(
    val loading: Boolean = true,
    val pages: List<CapturePage> = emptyList(),
    val selected: Set<String> = emptySet(),
    val saving: Boolean = false,
    val reloadKey: Int = 0,
)

/** 一次性導覽事件：儲存後開漫畫詳情頁、（找不到漫畫時）退回上一頁、開重截或插入畫面。 */
sealed interface CaptureReviewEvent {
    data class OpenManga(val mangaId: Long) : CaptureReviewEvent
    data object Back : CaptureReviewEvent
    data class ReCapture(val target: ReCaptureTarget) : CaptureReviewEvent
    data class Insert(val target: InsertTarget) : CaptureReviewEvent
}

class CaptureReviewScreenModel(
    bookName: String,
    chapterName: String,
    // 本次連續截圖存下的頁碼（供 [discardSession] 只刪這批）。
    private val sessionPages: List<Int> = emptyList(),
    private val storageManager: StorageManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    // 寫整章 meta（.yakuyomi_meta.json）需 context 開截斷串流（見 [writeMeta]）。
    private val context: Application = Injekt.get(),
) : ScreenModel {

    // 章夾定位用「安全檔名」（與 CaptureScreenModel.saveCapture 落地時同一套 sanitise）。
    private val safeBook = DiskUtil.buildValidFilename(bookName.trim())
    private val safeChapter = DiskUtil.buildValidFilename(chapterName.trim())
    private val title = bookName.trim()

    private val _state = MutableStateFlow(CaptureReviewState())
    val state: StateFlow<CaptureReviewState> = _state.asStateFlow()

    private val _events = Channel<CaptureReviewEvent>()
    val events = _events.receiveAsFlow()

    // 首次載入與返回重載都由畫面的 LaunchedEffect(Unit) 呼叫 loadPages()（見 CaptureReviewScreen.Content）。

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
        screenModelScope.launch {
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

    /** 對某頁發起重截：帶該頁記錄的網址 [CapturePage.url] + 章夾定位 + 檔名，push 到重截畫面。 */
    fun reCapture(page: CapturePage) {
        screenModelScope.launch {
            _events.send(
                CaptureReviewEvent.ReCapture(ReCaptureTarget(page.url, safeBook, safeChapter, page.name)),
            )
        }
    }

    /**
     * 在某頁前/後插入一張新截圖：算出插入位置頁碼（before＝該頁頁碼 N、after＝N+1）→ push 插入模式的擷取畫面，
     * 並帶被長按那頁的網址 [CapturePage.url]（讓擷取畫面從相鄰頁開起、使用者捲到要插入的頁再截）。
     * 頁碼取自檔名（`003.png` → 3；解析不到＝忽略）。實際騰位與存檔在 [CaptureScreenModel.saveInsert]。
     */
    fun insert(page: CapturePage, before: Boolean) {
        val pageNo = page.name.substringBeforeLast('.').toIntOrNull() ?: return
        val insertAt = if (before) pageNo else pageNo + 1
        screenModelScope.launch {
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
        screenModelScope.launch {
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
     * 只在該章夾內、存在才刪、找不到＝no-op；**不動接續截圖前既有的其他頁** → 發 Back 事件退回上一頁。
     */
    fun discardSession() {
        screenModelScope.launch {
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

    /** 把剩餘頁重新編號成連續 001/002…（兩階段防覆蓋）→ 找到該 local 漫畫 → 發導覽事件。 */
    fun save() {
        if (_state.value.saving) return
        screenModelScope.launch {
            _state.update { it.copy(saving = true) }
            val mangaId = withIOContext {
                chapterDir()?.let { renumber(it) }
                findLocalMangaId()
            }
            if (mangaId != null) {
                _events.send(CaptureReviewEvent.OpenManga(mangaId))
            } else {
                _events.send(CaptureReviewEvent.Back)
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
