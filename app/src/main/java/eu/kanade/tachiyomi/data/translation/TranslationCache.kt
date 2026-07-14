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
) {
    // 「整章翻完」判準委派 PageTranslator.isChapterTranslated（單一真理來源）；lazy＝多數本徽章=0、根本不建。
    private val pageTranslator by lazy { PageTranslator(context) }

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
        // 排除的來源＝不會被翻、徽章必為 0 → 連掃都不用掃（便宜過濾，保留冷啟動效能）。
        if (!isTranslatable(manga)) return 0
        val source = sourceManager.getOrStub(manga.source)
        val mangaDir = provider.findMangaDir(manga.title, source) ?: return 0
        return mangaDir.listFiles().orEmpty().count { hasMarker(it) }
    }

    /**
     * 「已翻成果」是歷史事實 → 只用**來源排除**這個便宜過濾跳過整個被排除來源（保留冷啟動 perf）；
     * **不**用即時翻分類過濾——被即時翻分類排除、但用「下載時翻」或手動翻成的章仍有 marker、徽章/篩選該照顯示
     * （即時翻分類只管「未來即時翻範圍」，不該過濾「過去已翻成果」的可見性）。
     */
    private fun isTranslatable(manga: Manga): Boolean =
        manga.source.toString() !in translationPreferences.translationSourcesExclude.get()

    // ★ 判「整章翻完」而非「marker 存在」：中斷/部分翻的章 manifest 只含已翻的部分頁 → marker 在但 isChapterTranslated=false
    //   → 徽章/篩選不再把「翻到一半就中止」的章算成已翻（對齊章指示器 TranslationManager.isTranslated）。
    private fun hasMarker(file: UniFile): Boolean = if (file.isDirectory) {
        pageTranslator.isChapterTranslated(file) // 鬆散資料夾：manifest 需涵蓋所有現有圖頁
    } else {
        runCatching {
            // CBZ：archive 內有 marker entry（整章判準需解壓、CBZ 已預設關/罕見，維持 marker 存在判準）
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
