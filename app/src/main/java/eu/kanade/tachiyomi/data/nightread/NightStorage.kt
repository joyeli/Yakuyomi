package eu.kanade.tachiyomi.data.nightread

/**
 * 設定 › 夜讀「儲存空間」的純邏輯（不碰 Android／檔案；JVM 單元測試見 NightStorageTest）：哪些檔算夜讀檔、一章佔多少、
 * 總計、要清哪幾章、刪除順序、清完之後還剩什麼。掃描與刪除本身在 [NightStorageService]。
 *
 * **只算夜讀自己的檔**（都在 `<章節夾>/.yakuyomi/`，檔名慣例見 [NightPages]）：
 *  - 夜讀版各格式（兩檔 std／more、三檔 l1–l3、舊版單檔）與它們的暫存（`.tmp`）；
 *  - 規則版本記號（`<頁>.night.rules<N>`）與產生端的探測檔（[NightPages.STAMP_PROBE]）。
 * 翻譯素材一律不算、不刪：重繪用的原圖（`.orig.<副檔名>`）、遮罩（`.mask.png`）、素材 json、給夜讀用的文字框
 * （`.nightboxes.json`，是翻譯時存的素材），還有章節夾頂層的頁圖、manifest、錯誤紀錄。
 */
object NightStorage {

    /** 是不是夜讀自己的檔（見類別說明）。 */
    fun isNightFile(name: String): Boolean =
        NightPages.isNightArtifact(name) || NightPages.isRulesStamp(name) || name == NightPages.STAMP_PROBE

    /**
     * 掃描時這個名字（下載夾裡的來源夾、書夾、章節夾，或本機來源的書夾、章節）值不值得往下找 `.yakuyomi/`：
     * 隱藏檔（`.nomedia` 等）與一看就是檔案的名字（壓縮檔、圖片、`details.json` 之類）跳過——SAF 上對檔案「找子夾」也是一次
     * 查詢，大書庫省不少。章節夾名可能含點（`Ch. 10.5`），所以只看常見副檔名、不看「有沒有點」。
     */
    fun canHoldNightFiles(name: String): Boolean {
        if (name.isEmpty() || name.startsWith(".")) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext !in FILE_EXTENSIONS
    }

    private val FILE_EXTENSIONS = setOf(
        "cbz", "zip", "cbr", "rar", "cb7", "7z", "cbt", "tar", "epub", "pdf",
        "jpg", "jpeg", "png", "webp", "gif", "avif", "heif", "heic", "jxl", "bmp",
        "json", "xml", "txt", "tmp",
    )

    /** `.yakuyomi/` 裡的一個檔與它的大小（位元組；讀不到大小＝0）。 */
    data class NightFile(val name: String, val bytes: Long)

    /**
     * 一章的夜讀檔。[key]＝章節夾的識別（掃描時的相對路徑，下載章 `d/<來源夾>/<書夾>/<章節夾>`、本機來源
     * `l/<書夾>/<章節夾>`）；[chapterId]／[read]＝對到資料庫的那一話（對不到＝null／false：不知道讀過沒有，
     * 「只清已讀」不碰它）。
     */
    data class ChapterUsage(
        val key: String,
        val chapterId: Long?,
        val read: Boolean,
        val files: List<NightFile>,
    ) {
        val bytes: Long get() = files.sumOf { it.bytes }
    }

    /**
     * 把一個 `.yakuyomi/` 的列表（檔名＋大小）整理成這章的夜讀用量：只留夜讀檔、負的大小當 0、同名只算一次。
     * 一個夜讀檔都沒有 → null（這章不列入）。
     */
    fun usageOf(key: String, chapterId: Long?, read: Boolean, listing: List<NightFile>): ChapterUsage? {
        val files = listing.asSequence()
            .filter { isNightFile(it.name) }
            .distinctBy { it.name }
            .map { if (it.bytes < 0) it.copy(bytes = 0) else it }
            .toList()
        return if (files.isEmpty()) null else ChapterUsage(key, chapterId, read, files)
    }

    /** [chapters] 話、共 [bytes] 位元組。 */
    data class Totals(val chapters: Int, val bytes: Long) {
        companion object {
            val ZERO = Totals(0, 0L)
        }
    }

    /** 全部（[all]）與其中已讀的話（[read]）的總計。 */
    data class Summary(val all: Totals, val read: Totals)

    fun summarize(usages: List<ChapterUsage>): Summary {
        val withFiles = usages.filter { it.files.isNotEmpty() }
        val read = withFiles.filter { it.read }
        return Summary(
            all = Totals(withFiles.size, withFiles.sumOf { it.bytes }),
            read = Totals(read.size, read.sumOf { it.bytes }),
        )
    }

    /**
     * 要清的章：[readOnly]＝只清對到資料庫而且已讀的話（`chapter.read`）；否則全部。[skip]＝正在產生夜讀版的話
     * （章 id），不動——產生端此刻正在寫這個資料夾，刪了只會留下半章。
     */
    fun selectForClear(usages: List<ChapterUsage>, readOnly: Boolean, skip: Set<Long>): List<ChapterUsage> =
        usages.filter { u ->
            u.files.isNotEmpty() &&
                (!readOnly || u.read) &&
                (u.chapterId == null || u.chapterId !in skip)
        }

    /**
     * 一章夜讀檔的刪除順序（只排 [isNightFile] 認得的檔，其他名字丟掉）：每頁照 [NightPages.artifactNames]（完成標記照
     * 查找優先序反過來、附屬檔最後——刪到一半被殺時，閱讀器看到的不是原本那組就是原圖，不會退回同頁更舊的格式），
     * 接著那頁的暫存，再來所有規則版本記號（記號沒有 std 就不算數，最後刪不影響判斷），探測檔最後。
     * 頁與頁之間照頁檔名排序（結果固定，方便測試與紀錄）。
     */
    fun deletionOrder(names: Collection<String>): List<String> {
        val all = names.filter { isNightFile(it) }.toSet()
        val pages = sortedSetOf<String>()
        val stamps = sortedSetOf<String>()
        var probe = false
        for (n in all) {
            when {
                n == NightPages.STAMP_PROBE -> probe = true
                NightPages.isRulesStamp(n) -> stamps += n
                else -> (NightPages.pageOf(n) ?: NightPages.pageOf(n.removeSuffix(NightPages.TMP_SUFFIX)))
                    ?.let { pages += it }
            }
        }
        val out = ArrayList<String>(all.size)
        for (page in pages) {
            val finals = NightPages.artifactNames(page)
            finals.filterTo(out) { it in all }
            finals.map { NightPages.tmpName(it) }.filterTo(out) { it in all }
        }
        // 認得是夜讀檔、卻對不上上面任何一頁的名字（理論上沒有；保險起見照樣刪）
        val placed = out.toHashSet()
        all.filter { it !in placed && it !in stamps && it != NightPages.STAMP_PROBE }.sorted().forEach { out += it }
        out += stamps
        if (probe) out += NightPages.STAMP_PROBE
        return out
    }

    /**
     * 清完之後的用量表：[failed]＝這次清過的章（key）→ 刪不掉的檔名（空集合＝整章清乾淨）。清過的章只留刪不掉的檔、
     * 一個都不剩就拿掉；沒清的章原封不動。
     */
    fun afterClear(usages: List<ChapterUsage>, failed: Map<String, Set<String>>): List<ChapterUsage> =
        usages.mapNotNull { u ->
            val bad = failed[u.key] ?: return@mapNotNull u
            val left = u.files.filter { it.name in bad }
            if (left.isEmpty()) null else u.copy(files = left)
        }

    /** 一章清掉了多少位元組：清之前的檔扣掉刪不掉的（[failed]）。 */
    fun freedBytes(usage: ChapterUsage, failed: Set<String>): Long =
        usage.files.filter { it.name !in failed }.sumOf { it.bytes }

    /**
     * 一章重新量過的結果（掃描之後夜讀檔變了的章，只重算它、不重掃全庫）：資料庫的 [chapterId] 那一話現在在 [key]
     * （找不到章節夾＝null），量到的用量 [usage]（沒有夜讀檔、或章節夾不在＝null：這章從表裡拿掉）。
     */
    data class Remeasured(val chapterId: Long, val key: String?, val usage: ChapterUsage?)

    /**
     * 把掃描之後記下的變動套進用量表：[forgetIds]＝檔案整個沒了的章（刪掉、或換成新下載的原圖）、[forgetPrefixes]＝整本被刪
     * 的書夾（key 前綴）、[remeasured]＝重新量過的章。同一話舊的那筆（對 chapterId 或對 key）一律換成重新量的結果；
     * 其他章原封不動。
     */
    fun applyChanges(
        usages: List<ChapterUsage>,
        forgetIds: Set<Long>,
        forgetPrefixes: List<String>,
        remeasured: List<Remeasured>,
    ): List<ChapterUsage> {
        val remeasuredIds = remeasured.mapTo(HashSet()) { it.chapterId }
        val remeasuredKeys = remeasured.mapNotNullTo(HashSet()) { it.key }
        val kept = usages.filterNot { u ->
            (u.chapterId != null && (u.chapterId in forgetIds || u.chapterId in remeasuredIds)) ||
                u.key in remeasuredKeys ||
                forgetPrefixes.any { u.key.startsWith(it) }
        }
        return kept + remeasured.mapNotNull { it.usage }
    }

    /**
     * 用最新的資料庫對照表（[indexChapterDirs]）更新每章的「是哪一話、讀過沒有」（使用者之後又讀了幾話，「只清已讀」才算得準）。
     * 對照表裡沒有的章（書不在書庫、沒讀過、不在佇列）沿用原本的。
     */
    fun remapRefs(usages: List<ChapterUsage>, index: Map<String, ChapterRef>): List<ChapterUsage> =
        usages.map { u ->
            val ref = index[u.key] ?: return@map u
            if (ref.id == u.chapterId && ref.read == u.read) u else u.copy(chapterId = ref.id, read = ref.read)
        }

    /** 資料庫裡的一話：章 id 與讀過沒有。 */
    data class ChapterRef(val id: Long, val read: Boolean)

    /**
     * 章節夾名 → 資料庫的話。[chapters]＝每話一筆：(這話所有可能的章節夾 key，**主要的在前**, 那一話)。
     * 下載章的候選名有好幾個（現行名、壓縮檔名、舊版沒雜湊的名字、另一種非 ASCII 設定下的名字，見
     * `DownloadProvider.getValidChapterDirNames`），舊版沒雜湊的名字在同名不同網址的兩話會撞在一起，所以**依候選名的
     * 順位**登記：先登記所有話的第 1 順位，再第 2 順位……同一個名字已經被較前順位（或同順位較早）的話佔走就不覆蓋。
     */
    fun indexChapterDirs(chapters: List<Pair<List<String>, ChapterRef>>): Map<String, ChapterRef> {
        val out = HashMap<String, ChapterRef>()
        val depth = chapters.maxOfOrNull { it.first.size } ?: 0
        for (rank in 0 until depth) {
            for ((names, ref) in chapters) {
                val name = names.getOrNull(rank) ?: continue
                out.putIfAbsent(name, ref)
            }
        }
        return out
    }
}
