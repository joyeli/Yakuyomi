package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.core.archive.archiveReader
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.injectLazy

/**
 * Loader used to load a chapter from the downloaded chapters.
 */
internal class DownloadPageLoader(
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: Source,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
) : PageLoader() {

    private val context: Application by injectLazy()

    private var archivePageLoader: ArchivePageLoader? = null

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val dbChapter = chapter.chapter
        val chapterPath = downloadProvider.findChapterDir(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            source,
        )
        return if (chapterPath?.isFile == true) {
            getPagesFromArchive(chapterPath)
        } else {
            getPagesFromDirectory()
        }
    }

    override fun recycle() {
        super.recycle()
        archivePageLoader?.recycle()
    }

    private suspend fun getPagesFromArchive(file: UniFile): List<ReaderPage> {
        val loader = ArchivePageLoader(file.archiveReader(context)).also { archivePageLoader = it }
        return loader.getPages()
    }

    private fun getPagesFromDirectory(): List<ReaderPage> {
        val pages = downloadManager.buildPageList(source, manga, chapter.chapter.toDomainChapter()!!)
        return pages.map { page ->
            ReaderPage(page.index, page.url, page.imageUrl) {
                context.contentResolver.openInputStream(page.uri ?: Uri.EMPTY)!!
            }.apply {
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        archivePageLoader?.loadPage(page)
    }

    /**
     * 重繪後刷新單頁（讓覆蓋後的新圖「即時」上畫，不必離開章節重進）。
     *
     * 根因：本 loader 跟 [HttpPageLoader] 不同，**沒有 async queue / consumer**——`loadPage` 對目錄頁
     * 等於 no-op（頁在 [getPages] 就已是 [Page.State.Ready]）。所以單純把狀態設成 [Page.State.Queue]
     * 不會有人把它推回 Ready，頁會卡在轉圈圈；而 holder 的 `statusFlow.collectLatest { Ready -> setImage() }`
     * 只在「**重新**發出 Ready」時才會再 decode 一次（StateFlow 同值不重發）。
     *
     * 解法：在這裡直接驅動一次 `→ Ready` 轉換來觸發 holder 重 decode。頁的 `stream` 每次都重開檔案
     * （目錄頁＝`openInputStream(uri)`），所以讀到的是覆蓋後的新位元組；SSIV `setImage` 會整個 reset、
     * 不留舊 bitmap，故無快取殘影（目錄頁走 `BufferedSource` 直接 decode、無 Coil 記憶體快取）。
     *
     * 注意呼叫端（[eu.kanade.tachiyomi.ui.reader.ReaderViewModel.reRenderPage]）已先把狀態設成 Queue 並在
     * 數秒 IO 期間維持（轉圈圈當「重繪中」指示，且保證 Queue 確實被 collect 到），所以這裡設 Ready 必為
     * 真正的 Queue→Ready 轉換 → 觸發重 decode。即使在其他狀態（如錯誤重試鈕）呼叫，只要不是已在 Ready，
     * 設 Ready 同樣是真轉換、會重 decode；最壞情況（本已 Ready）才不重畫，不致出錯。
     */
    override fun retryPage(page: ReaderPage) {
        page.status = Page.State.Ready
    }
}
