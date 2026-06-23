package eu.kanade.tachiyomi.data.translation

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.core.archive.archiveReader
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.service.TranslationPreferences
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
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
) {
    private val counts = ConcurrentHashMap<Long, Int>()

    // 正在背景掃描的 mangaId（避免重複排程）。
    private val scanning = ConcurrentHashMap.newKeySet<Long>()

    // 掃描在背景 IO 跑，絕不阻塞書庫 combine 的首次發射。
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _changes = MutableStateFlow(0)

    /** 版本計數：每次失效 +1（StateFlow 初始就有值，書庫 combine 才不會卡等）。 */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /**
     * 某本已翻章數。**非阻塞**：已算過走快取；沒算過立刻回 0、同時在背景掃，掃完若 >0 才 bump [changes]
     * 讓書庫重組套上真值。避免在書庫 flow 上同步掃資料夾/開壓縮檔（本地多話 zip 會讓每次開 app 卡轉圈）。
     */
    fun getTranslatedCount(manga: Manga): Int {
        counts[manga.id]?.let { return it }
        if (scanning.add(manga.id)) {
            scope.launch {
                val count = scan(manga)
                counts[manga.id] = count
                scanning.remove(manga.id)
                // 多數本＝0（含本地書），靜默快取不打擾；只有真的有已翻章才觸發書庫重算徽章。
                if (count > 0) _changes.update { it + 1 }
            }
        }
        return 0
    }

    private suspend fun scan(manga: Manga): Int {
        // 排除在翻譯設定外的本（來源排除 or 分類排除）＝不會被翻、徽章必為 0 → 連掃都不用掃（含本地書）。
        if (!isTranslatable(manga)) return 0
        val source = sourceManager.getOrStub(manga.source)
        val mangaDir = provider.findMangaDir(manga.title, source) ?: return 0
        return mangaDir.listFiles().orEmpty().count { hasMarker(it) }
    }

    /** 對齊 [eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader.autoTranslateAllowed]：來源排除 + 即時翻分類 include/exclude。 */
    private suspend fun isTranslatable(manga: Manga): Boolean {
        if (manga.source.toString() in translationPreferences.translationSourcesExclude.get()) return false
        val cats = getCategories.await(manga.id).map { it.id.toString() }.toSet()
        val include = translationPreferences.liveTranslateCategories.get()
        val exclude = translationPreferences.liveTranslateCategoriesExclude.get()
        return (include.isEmpty() || cats.any { it in include }) && cats.none { it in exclude }
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

    /** 清掉某本的快取（翻完一章 / 刪下載後叫）。下次查會重新背景掃。 */
    fun invalidate(mangaId: Long) {
        counts.remove(mangaId)
        scanning.remove(mangaId)
        _changes.update { it + 1 }
    }

    /** 清掉全部快取。 */
    fun invalidateAll() {
        counts.clear()
        scanning.clear()
        _changes.update { it + 1 }
    }

    companion object {
        private const val MARKER = ".yakuyomi_translated"
    }
}
