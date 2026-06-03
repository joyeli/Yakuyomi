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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.core.archive.ZipWriter
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * M4 3b：已下載章的翻譯 manager（case1）。背景一章一章翻、就地覆蓋（§11），UI 觀察 [queue]/[active]。
 *
 * **跟隨磁碟實際格式**（不假設 CBZ；存 CBZ 還是鬆散資料夾由 mihon `saveChaptersAsCBZ` 決定）：
 *  - 鬆散資料夾 → 原地翻（[PageTranslator.translateChapter]，無重壓、無掉檔風險）。
 *  - CBZ → 解壓→翻→重壓回 CBZ，**§11-安全順序**：新 zip 寫好前原檔完好，最後才 delete+rename。
 * 下載即翻（case2）走 Downloader 既有 hook，不在這裡。
 */
class TranslationManager(private val context: Context) {

    private val pageTranslator = PageTranslator(context)
    private val downloadProvider: DownloadProvider = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainMutex = Mutex()

    private data class Job(val manga: Manga, val chapter: Chapter)
    private val jobs = ArrayDeque<Job>()

    private val _queue = MutableStateFlow<Set<Long>>(emptySet())  // 排隊中/翻譯中的章 id
    val queue: StateFlow<Set<Long>> = _queue.asStateFlow()
    private val _active = MutableStateFlow<Long?>(null)           // 正在翻的章 id
    val active: StateFlow<Long?> = _active.asStateFlow()

    fun isReady(): Boolean = pageTranslator.isReady()

    /** 排入翻譯（已下載章）。 */
    fun translate(manga: Manga, chapters: List<Chapter>) {
        if (chapters.isEmpty()) return
        synchronized(jobs) { chapters.forEach { jobs.addLast(Job(manga, it)) } }
        _queue.value = _queue.value + chapters.map { it.id }
        scope.launch { drain() }
    }

    /** 取消排隊中的章（翻譯中的那章不中斷）。 */
    fun cancel(chapterIds: List<Long>) {
        val ids = chapterIds.toSet()
        synchronized(jobs) { jobs.removeAll { it.chapter.id in ids } }
        _queue.value = _queue.value - ids
    }

    /** 已下載章是否已翻（鬆散＝manifest 覆蓋；CBZ＝archive 內有 marker entry）。 */
    fun isTranslated(manga: Manga, chapter: Chapter): Boolean {
        val dir = chapterDir(manga, chapter) ?: return false
        return if (dir.isDirectory) pageTranslator.isChapterTranslated(dir) else archiveHasMarker(dir)
    }

    private suspend fun drain() = drainMutex.withLock {
        while (true) {
            val job = synchronized(jobs) { jobs.removeFirstOrNull() } ?: break
            _active.value = job.chapter.id
            try {
                translateOne(job.manga, job.chapter)
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "翻譯章失敗 ${job.chapter.name}（原檔保留）" }
            } finally {
                _active.value = null
                _queue.value = _queue.value - job.chapter.id
            }
        }
    }

    private suspend fun translateOne(manga: Manga, chapter: Chapter) {
        val dir = chapterDir(manga, chapter) ?: return // 沒下載 → 不做（case2 走下載 hook）
        if (dir.isDirectory) {
            pageTranslator.translateChapter(dir) // 鬆散：原地翻
        } else {
            translateArchiveInPlace(dir) // CBZ：解壓→翻→安全重壓
        }
    }

    private fun chapterDir(manga: Manga, chapter: Chapter): UniFile? {
        val source = sourceManager.getOrStub(manga.source)
        return downloadProvider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source)
    }

    /** CBZ：解壓暫存→翻→重壓暫存 zip→驗證後才換掉原檔（§11：原檔到 rename 前都完好）。 */
    private suspend fun translateArchiveInPlace(cbz: UniFile) {
        val parent = cbz.parentFile ?: return
        val cbzName = cbz.name ?: return
        val tmp = File(context.cacheDir, "yakutr_${System.nanoTime()}").apply { mkdirs() }
        try {
            // 1. 解壓檔案 entry 到暫存夾
            cbz.archiveReader(context).use { reader ->
                val names = reader.useEntries { seq -> seq.filter { it.isFile }.map { it.name }.toList() }
                names.forEach { entryName ->
                    reader.getInputStream(entryName)?.use { input ->
                        File(tmp, File(entryName).name).outputStream().use { input.copyTo(it) }
                    }
                }
            }
            val tmpU = UniFile.fromFile(tmp) ?: return
            // 2. 翻（就地覆蓋暫存頁 + 寫 manifest）
            val n = pageTranslator.translateChapter(tmpU)
            val done = pageTranslator.isChapterTranslated(tmpU)
            if (n == 0 && !done) return // 沒翻成、也沒新標記（全失敗）→ 不動原檔、可重試
            // 3. 重壓到暫存 zip（原檔此時完好）
            val newZip = parent.createFile("$cbzName$TMP_SUFFIX") ?: return
            ZipWriter(context, newZip).use { w -> tmpU.listFiles()?.forEach { w.write(it) } }
            // 4. 換檔
            cbz.delete()
            newZip.renameTo(cbzName)
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "translateArchiveInPlace 失敗（原檔保留）" }
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** CBZ 內是否有 marker entry（已翻指標；逐頁 coverage 細查留後續）。 */
    private fun archiveHasMarker(cbz: UniFile): Boolean = runCatching {
        cbz.archiveReader(context).use { reader ->
            reader.useEntries { seq -> seq.any { File(it.name).name == MARKER_NAME } }
        }
    }.getOrDefault(false)

    companion object {
        private const val MARKER_NAME = ".yakuyomi_translated"
        private const val TMP_SUFFIX = ".yakutmp"
    }
}
