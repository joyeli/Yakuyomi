package eu.kanade.tachiyomi.data.nightread

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * 設定 › 夜讀「儲存空間」：算夜讀版佔了多少空間、清掉全部或只清已讀的話（純邏輯在 [NightStorage]）。
 * 由 `TranslationManager` 持有（[eu.kanade.tachiyomi.data.translation.TranslationManager.nightStorage]），夜讀佇列的狀態
 * 經建構子的回呼取得。
 *
 * **掃哪裡**：整棵下載夾（`<下載>/<來源>/<書>/<章>/.yakuyomi/`）加本機來源（`<本機>/<書>/<章>/.yakuyomi/`）——連不在書庫、
 * 只剩下載的書也算進去，「全部清除」才真的清乾淨。每章只問一次儲存位置（[listMaterials]：裝置儲存直接組出 `.yakuyomi` 的
 * document id、一次查詢連名字大小一起拿，不存在就是查不到；不先 findFile——那在 SAF 上又是兩次查詢）。章對到資料庫的哪一話
 * （讀過沒有）靠下載夾命名規則反推：書庫的書、讀過但不在書庫的書、佇列裡的書，有下載的才查章節（見
 * [NightStorage.indexChapterDirs]）；對不到的章「只清已讀」不碰。
 *
 * **全庫只掃一次**：大書庫在 SAF 上掃一次要好幾分鐘，所以只有這次開 app 第一次進設定 › 夜讀、上次掃描失敗、或使用者點用量那一列
 * 時才全庫掃。之後夜讀檔變了的章由佇列通知（[chapterChanged]：每章跑完；[forgetChapters]：刪章、重新下載；[forgetManga]：整本
 * 刪掉），進頁面時只重算那幾章，再用資料庫重對一次「讀過沒有」（不碰檔案）。在 app 外動了檔案的話，點用量那一列重新計算。
 * 全庫掃描先等「重新產生舊版夜讀頁」的掃描跑完（[awaitOtherScan]；兩個一起掃全庫只會更慢）。
 *
 * **清除**：只刪夜讀自己的檔（[NightStorage.isNightFile]），翻譯素材、原圖、manifest 都不碰；正在產生夜讀版的話跳過（產生端
 * 正在寫那個資料夾），提示裡另外列出。清完後 [onCleared] 讓章節列與「已有夜讀版」狀態重掃。
 * 所有操作（掃描、重算、清除）經同一把 [opMutex] 依序做：掃描中按清除會等掃完再清。
 */
class NightStorageService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runningNightChapters: () -> Set<Long>,
    private val queuedMangas: () -> List<Manga>,
    /** 這話的章節夾（鬆散資料夾；不是資料夾、找不到＝null）。IO。 */
    private val chapterDirOf: (Manga, Chapter) -> UniFile?,
    /** 等另一個全庫掃描（重新產生舊版夜讀頁）跑完。 */
    private val awaitOtherScan: suspend () -> Unit,
    private val onCleared: (clearedIds: Set<Long>, all: Boolean, keepIds: Set<Long>) -> Unit,
) {
    private val storageManager: StorageManager by lazy { Injekt.get() }
    private val downloadProvider: DownloadProvider by lazy { Injekt.get() }
    private val downloadCache: DownloadCache by lazy { Injekt.get() }
    private val sourceManager: SourceManager by lazy { Injekt.get() }
    private val getFavorites: GetFavorites by lazy { Injekt.get() }
    private val mangaRepository: MangaRepository by lazy { Injekt.get() }
    private val getChaptersByMangaId: GetChaptersByMangaId by lazy { Injekt.get() }

    /** 一章的夜讀用量與它的 `.yakuyomi/` 夾（清除用）。 */
    class Found(val usage: NightStorage.ChapterUsage, val matDir: UniFile)

    sealed interface State {
        /** 還沒掃（這次開 app 沒進過設定 › 夜讀）。 */
        data object Idle : State

        /** 掃描中：[done]／[total] 本（[total]＝0：還在列書，總數未知）。 */
        data class Scanning(val done: Int, val total: Int) : State

        /** 清除中：[done]／[total] 話。 */
        data class Clearing(val done: Int, val total: Int) : State

        /** 掃完（或清完）：[found]＝有夜讀檔的章、[summary]＝總計。 */
        data class Done(val found: List<Found>, val summary: NightStorage.Summary) : State

        /** 掃描出錯（例如儲存位置的權限被收回）。 */
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 一次清除的結果（給設定頁提示）：清了 [chapters] 話、[bytes] 位元組；[failedChapters] 話有檔刪不掉；[skipped] 話正在產生、沒動。 */
    data class Cleared(val chapters: Int, val bytes: Long, val failedChapters: Int, val skipped: Int)

    private val _cleared = MutableSharedFlow<Cleared>(extraBufferCapacity = 4)
    val cleared: SharedFlow<Cleared> = _cleared.asSharedFlow()

    /** 掃描、重算、清除依序做（[result] 只在這把鎖裡讀寫）。 */
    private val opMutex = Mutex()

    /** 目前的結果；null＝沒有（還沒掃、或上次掃描失敗）＝下次要全庫掃。只在 [opMutex] 裡讀寫。 */
    private var result: List<Found>? = null

    private var refreshJob: Job? = null

    /** 掃描之後記下的變動（章 id → 最後一次的變動；同一話後來的蓋過先前的）。在 [changes] 自己的鎖下存取。 */
    private sealed interface Change {
        data object Forget : Change

        class Remeasure(val manga: Manga, val chapter: Chapter) : Change
    }

    private val changes = LinkedHashMap<Long, Change>()

    /** 整本被刪掉的書夾（key 前綴）。在 [changes] 的鎖下存取。 */
    private val forgottenPrefixes = ArrayList<String>()

    /** 這話的夜讀檔可能變了（夜讀跑過這章；翻譯／重繪覆寫頁圖時作廢了舊夜讀版）：下次只重算這話。 */
    fun chapterChanged(manga: Manga, chapter: Chapter) {
        synchronized(changes) { changes[chapter.id] = Change.Remeasure(manga, chapter) }
    }

    /** 這些話的檔案整個沒了（刪掉、或換成新下載的原圖）：下次從表裡拿掉。 */
    fun forgetChapters(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        synchronized(changes) { chapterIds.forEach { changes[it] = Change.Forget } }
    }

    /** 整本的下載夾被刪掉了（`DownloadManager.deleteManga`）：下次把這本的章全從表裡拿掉。本機來源的書不經這裡刪。 */
    fun forgetManga(manga: Manga) {
        if (manga.isLocal()) return
        val prefix = runCatching { "${downloadKeyPrefix(manga)}/" }.getOrNull() ?: return
        synchronized(changes) { forgottenPrefixes += prefix }
    }

    /**
     * 設定 › 夜讀 真的打開時呼叫；點用量那一列＝[force]（全庫重掃）。沒有結果（第一次、上次失敗）也全庫掃；否則只重算記下變動的
     * 章、重對「讀過沒有」。已在做就不重開。跑在呼叫端給的 scope（manager 的），離開設定頁不中斷。
     */
    @Synchronized
    fun refresh(force: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            opMutex.withLock { if (force || result == null) scanNow() else updateNow() }
        }
    }

    /**
     * 清除：[readOnly]＝只清已讀的話，否則全部。先把記下的變動套上（沒有結果就全庫掃），以磁碟現況為準。正在掃或正在清時排在後面。
     */
    fun clear(readOnly: Boolean) {
        scope.launch {
            opMutex.withLock {
                val found = (if (result == null) scanNow() else updateNow()) ?: return@withLock
                clearNow(found, readOnly)
            }
        }
    }

    /** 全庫掃一次、更新 [state]；失敗回 null（狀態＝[State.Failed]）。在 [opMutex] 裡呼叫。 */
    private suspend fun scanNow(): List<Found>? {
        _state.value = State.Scanning(0, 0)
        return try {
            awaitOtherScan()
            takeChanges() // 全庫重掃：之前記下的變動都包含在內；掃描途中才記下的留著，掃完再套
            val index = buildChapterIndex()
            val found = scan(index) { done, total -> _state.value = State.Scanning(done, total) }
            publish(applyChanges(found, index))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "掃描夜讀版佔用空間失敗" }
            result = null
            _state.value = State.Failed
            null
        }
    }

    /** 沿用上次的結果、只套記下的變動並重對「讀過沒有」（畫面照常顯示舊數字到算完）。在 [opMutex] 裡呼叫。 */
    private suspend fun updateNow(): List<Found>? {
        val base = result ?: return scanNow()
        return try {
            withContext(Dispatchers.IO) { publish(applyChanges(base, buildChapterIndex())) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "重算夜讀版佔用空間失敗" }
            result = null
            _state.value = State.Failed
            null
        }
    }

    private fun publish(found: List<Found>): List<Found> {
        result = found
        _state.value = State.Done(found, NightStorage.summarize(found.map { it.usage }))
        return found
    }

    private fun takeChanges(): Pair<Map<Long, Change>, List<String>> = synchronized(changes) {
        val out = LinkedHashMap(changes) to forgottenPrefixes.toList()
        changes.clear()
        forgottenPrefixes.clear()
        out
    }

    /** 把記下的變動套進 [base]（[NightStorage.applyChanges]），再用 [index] 重對每章是哪一話、讀過沒有。IO。 */
    private suspend fun applyChanges(base: List<Found>, index: Map<String, NightStorage.ChapterRef>): List<Found> {
        val (pending, prefixes) = takeChanges()
        val dirs = HashMap<String, UniFile>()
        base.forEach { dirs[it.usage.key] = it.matDir }
        val forgetIds = HashSet<Long>()
        val remeasured = ArrayList<NightStorage.Remeasured>()
        for ((id, change) in pending) {
            when (change) {
                Change.Forget -> forgetIds += id
                is Change.Remeasure -> {
                    currentCoroutineContext().ensureActive()
                    val r = remeasure(change.manga, change.chapter, index)
                    remeasured += r.first
                    r.second?.let { dir -> r.first.usage?.let { dirs[it.key] = dir } }
                }
            }
        }
        val usages = NightStorage.remapRefs(
            NightStorage.applyChanges(base.map { it.usage }, forgetIds, prefixes, remeasured),
            index,
        )
        return usages.mapNotNull { u -> dirs[u.key]?.let { Found(u, it) } }
    }

    /** 重新量一話：找章節夾、列它的 `.yakuyomi/`。找不到或沒有夜讀檔＝用量 null（從表裡拿掉）。 */
    private fun remeasure(
        manga: Manga,
        chapter: Chapter,
        index: Map<String, NightStorage.ChapterRef>,
    ): Pair<NightStorage.Remeasured, UniFile?> {
        val dir = runCatching { chapterDirOf(manga, chapter) }.getOrNull()
        val key = dir?.name?.let { name -> runCatching { chapterKey(manga, name) }.getOrNull() }
        if (dir == null || key == null) return NightStorage.Remeasured(chapter.id, null, null) to null
        val materials = listMaterials(dir) ?: return NightStorage.Remeasured(chapter.id, key, null) to null
        val read = index[key]?.read ?: chapter.read
        val usage = NightStorage.usageOf(key, chapter.id, read, materials.files)
            ?: return NightStorage.Remeasured(chapter.id, key, null) to null
        val matDir = materials.dir() ?: return NightStorage.Remeasured(chapter.id, key, null) to null
        return NightStorage.Remeasured(chapter.id, key, usage) to matDir
    }

    /** 下載章的書夾 key（`d/<來源夾>/<書夾>`，與 [scan] 列出來的名字同一套命名）。 */
    private fun downloadKeyPrefix(manga: Manga): String {
        val sourceDir = downloadProvider.getSourceDirName(sourceManager.getOrStub(manga.source))
        return "d/$sourceDir/${downloadProvider.getMangaDirName(manga.title)}"
    }

    /** 一話的 key（[scan] 的命名）：本機來源 `l/<書夾>/<章節夾>`（書的 url＝書夾名）、下載章 `d/<來源夾>/<書夾>/<章節夾>`。 */
    private fun chapterKey(manga: Manga, chapterDirName: String): String =
        if (manga.isLocal()) "l/${manga.url}/$chapterDirName" else "${downloadKeyPrefix(manga)}/$chapterDirName"

    private suspend fun clearNow(found: List<Found>, readOnly: Boolean) = withContext(Dispatchers.IO) {
        val usages = found.map { it.usage }
        val byKey = found.associateBy { it.usage.key }
        // 正在產生的話也先選進來、下面跳過並記進 skipped：提示才說得出「N 話正在產生，沒有動」，「全部清除」後的狀態也才會留著它們
        val targets = NightStorage.selectForClear(usages, readOnly, emptySet())
        val failed = HashMap<String, Set<String>>()
        val skippedIds = HashSet<Long>()
        var bytes = 0L
        var cleared = 0
        var failedChapters = 0
        _state.value = State.Clearing(0, targets.size)
        try {
            targets.forEachIndexed { i, u ->
                ensureActive()
                // 產生端此刻正在寫這個夾（每話都重問一次，縮小與佇列的競態）
                if (u.chapterId != null && u.chapterId in runningNightChapters()) {
                    skippedIds += u.chapterId
                } else {
                    val r = clearChapter(byKey.getValue(u.key).matDir, u.files.map { it.name })
                    failed[u.key] = r.failed
                    // 夾早就不在（章被刪了）：不是這次清掉的，不算話數也不算釋出的空間
                    if (!r.gone) {
                        bytes += NightStorage.freedBytes(u, r.failed)
                        cleared++
                        if (r.failed.isNotEmpty()) failedChapters++
                    }
                }
                _state.value = State.Clearing(i + 1, targets.size)
            }
        } finally {
            // 被取消也照實記下已經刪掉的（不然畫面上的總計會把刪掉的算回去）
            val clearedIds = targets.mapNotNullTo(HashSet()) { u -> u.chapterId?.takeIf { u.key in failed } }
            if (failed.isNotEmpty()) onCleared(clearedIds, !readOnly, skippedIds)
            val left = NightStorage.afterClear(usages, failed)
            publish(left.map { Found(it, byKey.getValue(it.key).matDir) })
        }
        logcat { "清除夜讀版 readOnly=$readOnly 章=$cleared 位元組=$bytes 失敗章=$failedChapters 跳過=${skippedIds.size}" }
        _cleared.tryEmit(Cleared(cleared, bytes, failedChapters, skippedIds.size))
    }

    /** [clearChapter] 的結果：[failed]＝刪不掉的檔名；[gone]＝`.yakuyomi/` 夾早就不在（章被刪了）。 */
    private class ChapterClear(val failed: Set<String>, val gone: Boolean)

    /**
     * 刪掉一章 `.yakuyomi/` 裡所有夜讀檔（以**現在**列出來的為準，掃描之後新產生的也一起刪），照 [NightStorage.deletionOrder]。
     * 刪除回報失敗、但檔案已經不在（例如翻譯同時覆寫這頁、先刪了它）＝算刪掉。夾在但列不出來＝當作掃描時的檔都沒刪掉（[scanned]）。
     */
    private fun clearChapter(matDir: UniFile, scanned: Collection<String>): ChapterClear {
        if (!runCatching { matDir.exists() }.getOrDefault(true)) return ChapterClear(emptySet(), gone = true)
        val current = runCatching { matDir.listFiles() }.getOrNull()
            ?.mapNotNull { f -> f.name?.let { it to f } }
            ?.toMap()
            ?: return ChapterClear(scanned.toSet(), gone = false)
        val failed = HashSet<String>()
        for (name in NightStorage.deletionOrder(current.keys)) {
            val f = current.getValue(name)
            val ok = runCatching { f.delete() }.getOrDefault(false) ||
                runCatching { !f.exists() }.getOrDefault(false)
            if (!ok) failed += name
        }
        return ChapterClear(failed, gone = false)
    }

    /** 掃下載夾與本機來源，列出有夜讀檔的章。[onProgress]＝(已掃本數, 總本數)。IO。 */
    private suspend fun scan(
        index: Map<String, NightStorage.ChapterRef>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<Found> = withContext(Dispatchers.IO) {
        // (key 前綴, 書夾)：下載夾兩層（來源／書）、本機來源一層（書）
        val mangaDirs = ArrayList<Pair<String, UniFile>>()
        storageManager.getDownloadsDirectory()?.listFiles().orEmpty().forEach { sourceDir ->
            ensureActive()
            val sourceName = sourceDir.name ?: return@forEach
            if (!NightStorage.canHoldNightFiles(sourceName)) return@forEach
            sourceDir.listFiles().orEmpty().forEach { mangaDir ->
                val mangaName = mangaDir.name
                if (mangaName != null && NightStorage.canHoldNightFiles(mangaName)) {
                    mangaDirs += "d/$sourceName/$mangaName" to mangaDir
                }
            }
        }
        storageManager.getLocalSourceDirectory()?.listFiles().orEmpty().forEach { mangaDir ->
            val mangaName = mangaDir.name
            if (mangaName != null && NightStorage.canHoldNightFiles(mangaName)) mangaDirs += "l/$mangaName" to mangaDir
        }
        val out = ArrayList<Found>()
        onProgress(0, mangaDirs.size)
        mangaDirs.forEachIndexed { i, (prefix, mangaDir) ->
            for (chapterDir in mangaDir.listFiles().orEmpty()) {
                ensureActive()
                val chapterName = chapterDir.name ?: continue
                if (!NightStorage.canHoldNightFiles(chapterName)) continue
                val materials = listMaterials(chapterDir) ?: continue
                val key = "$prefix/$chapterName"
                val ref = index[key]
                val usage = NightStorage.usageOf(key, ref?.id, ref?.read ?: false, materials.files) ?: continue
                val matDir = materials.dir() ?: continue
                out += Found(usage, matDir)
            }
            onProgress(i + 1, mangaDirs.size)
        }
        out
    }

    /**
     * 章節夾 key → 資料庫的話（讀過沒有）。書：書庫的、讀過但不在書庫的、佇列裡的（去重）；下載章只查有下載的書
     * （下載快取在記憶體，不碰檔案），本機來源的書照查（不在下載快取）。只查資料庫。
     */
    private suspend fun buildChapterIndex(): Map<String, NightStorage.ChapterRef> {
        val mangas = (getFavorites.await() + mangaRepository.getReadMangaNotInLibrary() + queuedMangas())
            .distinctBy { it.id }
        val entries = ArrayList<Pair<List<String>, NightStorage.ChapterRef>>()
        for (manga in mangas) {
            if (manga.isLocal()) {
                // 本機來源：書的 url＝書夾名、章的 url＝「書夾名/章節檔名」
                getChaptersByMangaId.await(manga.id).forEach { ch ->
                    val dirName = ch.url.removePrefix("${manga.url}/")
                    entries += listOf("l/${manga.url}/$dirName") to NightStorage.ChapterRef(ch.id, ch.read)
                }
            } else {
                if (downloadCache.getDownloadCount(manga) <= 0) continue
                val prefix = downloadKeyPrefix(manga)
                getChaptersByMangaId.await(manga.id).forEach { ch ->
                    val names = downloadProvider.getValidChapterDirNames(ch.name, ch.scanlator, ch.url)
                    entries += names.map { "$prefix/$it" } to NightStorage.ChapterRef(ch.id, ch.read)
                }
            }
        }
        return NightStorage.indexChapterDirs(entries)
    }

    /** 一章 `.yakuyomi/` 的列表（[files]）；[dir] 要清除時才建那個夾的 UniFile（建它不必再問儲存位置）。 */
    private class Materials(val files: List<NightStorage.NightFile>, val dir: () -> UniFile?)

    /**
     * 列章節夾 [chapterDir] 底下的 `.yakuyomi/`（檔名＋大小）。沒有這個夾、或列不出來 → null。
     *  - 一般檔案路徑：直接用 java.io.File。
     *  - 裝置儲存的 SAF（document id 是「父 id/名字」，UniFile 自己 findFile 也是這樣組子項）：直接組出 `.yakuyomi` 的 document id、一次查詢它的子項
     *    （名字與大小同一次拿）；夾不存在時 provider 回 null。不先 findFile——那要先問章節夾是不是資料夾、再問子項存不存在。
     *  - 其他 provider：findFile 找到夾、一次查詢列子項；查詢不行才退回 UniFile 逐檔（只對夜讀檔問大小）。
     */
    private fun listMaterials(chapterDir: UniFile): Materials? {
        val uri = chapterDir.uri
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val dir = uri.path?.let { File(it, NightPages.DIR) } ?: return null
            val children = dir.listFiles() ?: return null
            return Materials(children.map { NightStorage.NightFile(it.name, if (it.isFile) it.length() else 0L) }) {
                UniFile.fromFile(dir)
            }
        }
        if (uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == EXTERNAL_STORAGE_AUTHORITY) {
            val childId = runCatching { "${DocumentsContract.getDocumentId(uri)}/${NightPages.DIR}" }.getOrNull()
            if (childId != null) {
                val files = queryChildren(uri, childId) ?: return null
                return Materials(files) {
                    runCatching {
                        UniFile.fromUri(context, DocumentsContract.buildDocumentUriUsingTree(uri, childId))
                    }.getOrNull()
                }
            }
        }
        val matDir = runCatching { chapterDir.findFile(NightPages.DIR) }.getOrNull() ?: return null
        val files = (if (matDir.uri.scheme == ContentResolver.SCHEME_CONTENT) queryChildren(matDir.uri) else null)
            ?: runCatching { matDir.listFiles() }.getOrNull()?.mapNotNull { f ->
                val name = f.name ?: return@mapNotNull null
                val size = if (NightStorage.isNightFile(name)) runCatching { f.length() }.getOrDefault(0L) else 0L
                NightStorage.NightFile(name, size)
            }
            ?: return null
        return Materials(files) { matDir }
    }

    /** 一次 provider 查詢列出 [parentId]（預設＝[treeUri] 自己那個 document）的子項名稱與大小。查不到（不存在、權限）→ null。 */
    private fun queryChildren(
        treeUri: Uri,
        parentId: String? = null,
    ): List<NightStorage.NightFile>? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentId ?: DocumentsContract.getDocumentId(treeUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { c ->
            val out = ArrayList<NightStorage.NightFile>(c.count)
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                out += NightStorage.NightFile(name, if (c.isNull(1)) 0L else c.getLong(1))
            }
            out
        }
    }.getOrNull()

    private companion object {
        /**
         * 裝置儲存的 provider：document id 一定是「父 id/名字」，可以直接組子項 id。下載、媒體 provider 的 id 不一定是路徑
         * （例如 `msf:123`），照 findFile 走。
         */
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
