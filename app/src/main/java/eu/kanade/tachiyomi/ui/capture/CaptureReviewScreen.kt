package eu.kanade.tachiyomi.ui.capture

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
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { CaptureReviewScreenModel(bookName, chapterName) }
        val state by screenModel.state.collectAsState()

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    is CaptureReviewEvent.OpenManga -> navigator.replace(MangaScreen(event.mangaId))
                    CaptureReviewEvent.Back -> navigator.pop()
                }
            }
        }

        CaptureReviewScreenContent(
            state = state,
            onNavigateUp = navigator::pop,
            onToggleSelect = screenModel::toggleSelection,
            onDeleteSelected = screenModel::deleteSelected,
            onSave = screenModel::save,
        )
    }
}

/** 確認頁一張截圖：底層 [UniFile] + 顯示名；[uri] 字串當網格 key / 選取集合的元素。 */
data class CapturePage(val file: UniFile, val name: String) {
    val uri: String = file.uri.toString()
}

/** 確認頁狀態：載入中 / 目前頁清單 / 已勾選（uri 字串集合）/ 儲存中。 */
data class CaptureReviewState(
    val loading: Boolean = true,
    val pages: List<CapturePage> = emptyList(),
    val selected: Set<String> = emptySet(),
    val saving: Boolean = false,
)

/** 一次性導覽事件：儲存後開漫畫詳情頁，或（找不到漫畫時）退回上一頁。 */
sealed interface CaptureReviewEvent {
    data class OpenManga(val mangaId: Long) : CaptureReviewEvent
    data object Back : CaptureReviewEvent
}

class CaptureReviewScreenModel(
    bookName: String,
    chapterName: String,
    private val storageManager: StorageManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) : ScreenModel {

    // 章夾定位用「安全檔名」（與 CaptureScreenModel.saveCapture 落地時同一套 sanitise）。
    private val safeBook = DiskUtil.buildValidFilename(bookName.trim())
    private val safeChapter = DiskUtil.buildValidFilename(chapterName.trim())
    private val title = bookName.trim()

    private val _state = MutableStateFlow(CaptureReviewState())
    val state: StateFlow<CaptureReviewState> = _state.asStateFlow()

    private val _events = Channel<CaptureReviewEvent>()
    val events = _events.receiveAsFlow()

    init {
        loadPages()
    }

    /** 定位 `<local>/<safeBook>/<safeChapter>/`（任一層缺 → null）。 */
    private fun chapterDir(): UniFile? =
        storageManager.getLocalSourceDirectory()
            ?.findFile(safeBook)?.takeIf { it.isDirectory }
            ?.findFile(safeChapter)?.takeIf { it.isDirectory }

    /** 掃該章夾內的圖檔（png/jpg/webp）、依名稱排序（截圖零填充 3 位 → 字串排序＝頁序）。 */
    private fun scanPages(dir: UniFile): List<CapturePage> =
        dir.listFiles().orEmpty()
            .filter { !it.isDirectory && isImageName(it.name) }
            .sortedBy { it.name.orEmpty().lowercase() }
            .map { CapturePage(it, it.name.orEmpty()) }

    fun loadPages() {
        screenModelScope.launch {
            val pages = withIOContext { chapterDir()?.let(::scanPages).orEmpty() }
            _state.update { s ->
                val uris = pages.map { it.uri }.toSet()
                s.copy(loading = false, pages = pages, selected = s.selected.intersect(uris))
            }
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

    /** 刪除已勾選的圖檔 → 重新掃、更新網格。 */
    fun deleteSelected() {
        val selected = _state.value.selected
        if (selected.isEmpty()) return
        screenModelScope.launch {
            withIOContext {
                _state.value.pages
                    .filter { it.uri in selected }
                    .forEach { runCatching { it.file.delete() } }
            }
            _state.update { it.copy(selected = emptySet()) }
            loadPages()
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
     */
    private fun renumber(dir: UniFile) {
        // 階段一：目前順序 → 暫名（零填充保順序）。
        scanPages(dir).forEachIndexed { i, page ->
            renameOrCopy(dir, page.file, "%s%03d.%s".format(TMP_PREFIX, i + 1, extOf(page.name)))
        }
        // 階段二：暫名（依名稱＝順序）→ 最終 NNN。
        dir.listFiles().orEmpty()
            .filter { !it.isDirectory && it.name.orEmpty().startsWith(TMP_PREFIX) }
            .sortedBy { it.name.orEmpty() }
            .forEach { file ->
                val name = file.name.orEmpty()
                val idx = name.removePrefix(TMP_PREFIX).substringBefore('.').toIntOrNull() ?: return@forEach
                renameOrCopy(dir, file, "%03d.%s".format(idx, extOf(name)))
            }
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
