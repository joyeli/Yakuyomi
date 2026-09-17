package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Loader used to load a chapter from a directory given on [file].
 */
internal class DirectoryPageLoader(val file: UniFile) : PageLoader() {

    private val translationPreferences: TranslationPreferences = Injekt.get()

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        // Yakuyomi 夜讀：翻譯時產生的暗色版存在 .yakuyomi/<頁>.night.webp。
        val nightDir = file.findFile(MATERIALS_DIR)
        return file.listFiles()
            ?.filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
            ?.sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }
            ?.mapIndexed { i, file ->
                val nightName = (file.name ?: "").substringBeforeLast('.') + NIGHT_SUFFIX
                // ⚠️ 在 lambda 裡判斷而不是在這裡決定：切換夜讀後 ReaderPage.reload() 會重跑這個
                // lambda，於是同一頁換讀另一個檔。沒有夜讀版的頁自動退回正常版。
                val streamFn = {
                    val night = if (translationPreferences.nightReadMode.get()) {
                        nightDir?.findFile(nightName)
                    } else {
                        null
                    }
                    (night ?: file).openInputStream()
                }
                ReaderPage(i).apply {
                    stream = streamFn
                    status = Page.State.Ready
                }
            }
            .orEmpty()
    }

    private companion object {
        const val MATERIALS_DIR = ".yakuyomi"
        const val NIGHT_SUFFIX = ".night.webp"
    }
}
