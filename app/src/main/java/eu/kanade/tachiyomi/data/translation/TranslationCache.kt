package eu.kanade.tachiyomi.data.translation

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import mihon.core.archive.archiveReader
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 每本漫畫「已翻章數」的輕量快取（給書庫封面徽章用，對照 [eu.kanade.tachiyomi.data.download.DownloadCache]）。
 *
 * **懶算 + 快取**：首次查某本時掃它的下載夾、數有 `.yakuyomi_translated` marker 的章
 * （鬆散＝夾內檔、CBZ＝archive entry），之後走快取。翻完一章（[TranslationManager]）或刪下載
 * （`DownloadManager`）時 [invalidate] 該本、發 [changes] 讓書庫重算。
 */
class TranslationCache(
    private val context: Context,
    private val provider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) {
    private val counts = ConcurrentHashMap<Long, Int>()

    private val _changes = MutableStateFlow(0)

    /** 版本計數：每次失效 +1（StateFlow 初始就有值，書庫 combine 才不會卡等）。 */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** 某本已翻章數（懶算 + 快取；computeIfAbsent 確保同本只掃一次）。 */
    fun getTranslatedCount(manga: Manga): Int = counts.computeIfAbsent(manga.id) { scan(manga) }

    private fun scan(manga: Manga): Int {
        val source = sourceManager.getOrStub(manga.source)
        val mangaDir = provider.findMangaDir(manga.title, source) ?: return 0
        return mangaDir.listFiles().orEmpty().count { hasMarker(it) }
    }

    private fun hasMarker(file: UniFile): Boolean = if (file.isDirectory) {
        file.findFile(MARKER) != null // 鬆散資料夾：夾內有 manifest
    } else {
        runCatching {
            // CBZ：archive 內有 marker entry
            file.archiveReader(context).use { reader ->
                reader.useEntries { seq -> seq.any { File(it.name).name == MARKER } }
            }
        }.getOrDefault(false)
    }

    /** 清掉某本的快取（翻完一章 / 刪下載後叫）。 */
    fun invalidate(mangaId: Long) {
        counts.remove(mangaId)
        _changes.update { it + 1 }
    }

    /** 清掉全部快取。 */
    fun invalidateAll() {
        counts.clear()
        _changes.update { it + 1 }
    }

    companion object {
        private const val MARKER = ".yakuyomi_translated"
    }
}
