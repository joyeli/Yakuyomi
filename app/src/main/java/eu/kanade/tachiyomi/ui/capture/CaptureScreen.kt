package eu.kanade.tachiyomi.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.presentation.capture.CaptureScreenContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.OutputStream

/**
 * Yakuyomi 擷取漫畫（B1a-1 骨架）：內建 WebView 開任意網站 → 「截這頁」→ 存成 LocalSource 的
 * 一本漫畫 / 一話的鬆散頁圖，證明「截圖 → local 漫畫 → 書庫看得到 + 能翻譯」整條路通。
 *
 * 此步範圍嚴格限定：書名 / 章名手動輸入、存整張截圖（不裁切）、單一 session 頁碼遞增。
 * 裁切、選書流程、話數建議、cover/metadata 皆為後續步驟。
 */
class CaptureScreen(private val initialUrl: String = "") : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { CaptureScreenModel() }

        CaptureScreenContent(
            onNavigateUp = navigator::pop,
            initialUrl = initialUrl,
            bookName = screenModel.bookName,
            onBookNameChange = { screenModel.bookName = it },
            chapterName = screenModel.chapterName,
            onChapterNameChange = { screenModel.chapterName = it },
            onCapture = screenModel::saveCapture,
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
