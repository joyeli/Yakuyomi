package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.core.common.util.system.ImageUtil

/**
 * Loader used to load a chapter from a directory given on [file].
 */
internal class DirectoryPageLoader(val file: UniFile) : PageLoader(), NightPageSource {

    /**
     * 夜讀顯示端（檔位查找、檔名快取、每頁送出的檔名）：stream lambda 每次解碼都經它決定送夜讀檔還是原圖 →
     * 切換開關或檔位後 reload 立即生效。章節夾＝[file]。
     */
    private val night = NightPageStreams().apply { chapterDir = file }

    override val nightStreams: NightPageStreams get() = night

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        return file.listFiles()
            ?.filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
            ?.sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }
            ?.mapIndexed { i, pageFile ->
                night.registerPage(i) { pageFile.name }
                // 夜讀：每次解碼都重查（開關 + 檔位 + 夜讀檔是否存在），沒夜讀版退回原檔。
                val streamFn = { night.open(i) { pageFile.openInputStream() } }
                ReaderPage(i).apply {
                    stream = streamFn
                    status = Page.State.Ready
                }
            }
            .orEmpty()
    }
}
