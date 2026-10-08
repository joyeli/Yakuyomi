package eu.kanade.tachiyomi.data.translation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 夜讀用的**譯後頁文字框檔**（2026-10-06 起）：檔名、內容格式、與「完整素材」的分界、夜讀取文字框的先後
 * （純函式；JVM 測試見 NightTextBoxesTest）。
 *
 * **為什麼要有它**：夜讀處理譯後頁時，會把翻譯素材裡每行原文的四邊形併進自己的偵測結果（`PageTranslator.nightExtraLines`）。
 * 不併的話，泡裡的短中文（「咦」「是的」）偵測不到，那顆泡就維持灰底黑字（36.2 話有 5 顆泡靠它救回）。完整素材（保留素材或
 * 即時翻譯開著時存）有這些框；兩個開關都關、夜讀開著時以前只存去字遮罩，框就沒了。使用者 2026-10-06 決定：夜讀開著就另存
 * 這份小檔（每頁約 5 KB），只給夜讀用。
 *
 * **檔名**：`<章節夾>/.yakuyomi/<完整頁檔名>.nightboxes.json`，例如 `012.jpg` → `012.jpg.nightboxes.json`。
 *  - 用**完整頁檔名**（同夜讀檔）：同章有 `012.jpg` 與 `012.png` 時不會互相蓋掉。
 *  - 結尾用 `.json`：儲存位置認得這個副檔名，不會替檔名補副檔名（完整素材的 `<base>.json` 一直這樣寫、沒出過事）。
 *    代價是**任何「找 `.json`」的掃描都要先排除它**：完整素材 json 一律用 [isMaterialsJson] 判，不要自己寫 `endsWith(".json")`。
 *  - 不會被夜讀檔（`NightPages`：`.night.std.webp` 之類、`.night.rules<N>`）或原圖素材（`OriginalMaterial`：`.orig.`）的規則認走，
 *    也不會被當成任何一頁的完整素材 json（那是去掉副檔名的 `<base>.json`）。
 *
 * **它不是完整素材**：重繪、還原原圖、改去字法升級全庫、「翻譯這頁」判斷頁檔是不是我們的成品，都只看完整素材 json
 * （[materialsJsonName]、[isMaterialsJson]）與原圖素材；只有文字框（與遮罩）的頁對它們來說就是「沒有素材」，跟以前一樣。
 *
 * **內容**（[Boxes]）：版本、頁圖寬高、寫入時頁檔的位元組數、同時寫的去字遮罩檔位元組數、每行原文四邊形（與完整素材 json
 * 各文字區的 `quads` 依序攤平後完全相同——同一頁不管拿哪一份，夜讀拿到的框逐點相同）。寬高與頁檔位元組數是**防過期**：頁檔
 * 之後被換掉（擷取重截／插頁改名、沒存任何素材的重翻）而這份沒跟著重寫，對不上就不用（[pick] 回 [Source.STALE_BOXES]；同時
 * 寫的去字遮罩也一起不用）。讀不到頁檔位元組數時不寫這份檔、讀的時候也當對不上（[fits]）——截圖頁常常尺寸全部相同，只比
 * 寬高擋不住別張頁。遮罩位元組數防的是遮罩檔被換掉（遮罩寫失敗留下上一次的、同章 `012.jpg` 與 `012.png` 共用
 * `012.mask.png`）：對不上就不帶遮罩（[maskUse]），框照用。夜讀的紅線是「絕不塗錯」，用錯頁的框或遮罩比不用糟。
 *
 * **生命週期**：只有「保留素材、即時翻譯都關，夜讀開著」時翻成的頁才寫（翻譯落地、去字遮罩之後；這頁已有完整素材 json 時
 * 不寫，遮罩也不動）。之後這頁改存完整素材（開了保留素材後重翻、reader「翻譯這頁」）→ 寫完完整 json 就刪掉它（完整素材
 * 接手）。頁不在了 → 夜讀產生端開跑時清掉（[orphans]）。刪章時跟整個 `.yakuyomi/` 一起走。這個改動之前翻的頁沒有這份檔，
 * 也補不出來（翻譯當下的框沒存下來；那些頁已在已翻清單裡、翻譯佇列不會再翻，要刪掉下載重新下載翻譯才會有）。
 *
 * **它也證明頁檔是我們寫的譯圖**：對得上的文字框檔＝頁檔一個位元組不差就是當初翻譯落地的那張。reader「翻譯這頁」靠它拒絕
 * 拿譯圖再翻一次（[PageTranslator.translateSinglePage]；這種頁沒有原圖素材，再翻只會把譯圖當原圖存起來、好的框也被換掉）。
 */
object NightTextBoxes {

    /** 目前寫的格式版本。讀的時候只認 1..[VERSION]（更新的版本＝看不懂，當作沒有）。 */
    const val VERSION = 1

    /** 文字框檔的尾綴（接在**完整**頁檔名之後）。 */
    const val SUFFIX = ".nightboxes.json"

    /**
     * 文字框檔名一定含的一段。儲存位置萬一把檔名改成 `012.jpg.nightboxes (1).json`（同名衝突時），它就不再以 [SUFFIX] 結尾；
     * 判「是不是完整素材 json」時看這一段，那種檔也不會被當成完整素材（[isMaterialsJson]）。寫入端另外檢查建出來的檔名
     * 一字不差，名字不對就刪掉（`PageTranslator.writeExactNamed`），正常不會留下這種檔。
     */
    private const val STEM = ".nightboxes"

    private const val JSON_SUFFIX = ".json"

    /**
     * 「這頁的文字框可能是夜讀開跑之後才寫好的」的寬限（毫秒，見 [mayHaveLateBoxes]）：頁檔覆蓋到寫好文字框之間只有遮罩編碼
     * 與兩次寫檔（正常一秒內）；另外吸收儲存位置的修改時間粒度（FAT 類 2 秒）與些微時鐘差。
     */
    const val LATE_SLACK_MS = 30_000L

    private val JSON = Json { ignoreUnknownKeys = true }

    /** `012.jpg` → `012.jpg.nightboxes.json`。 */
    fun fileName(pageFileName: String): String = pageFileName + SUFFIX

    /** 是不是文字框檔（只看尾綴）。 */
    fun isBoxesFile(name: String): Boolean = name.endsWith(SUFFIX)

    /** 文字框檔名 → 它屬於哪一頁（`012.jpg.nightboxes.json` → `012.jpg`）；不是文字框檔、或頁名是空的 → null。 */
    fun pageOf(name: String): String? =
        if (isBoxesFile(name)) name.dropLast(SUFFIX.length).takeIf { it.isNotEmpty() } else null

    // —— 完整素材 json 的分界 ——

    /**
     * 這頁完整素材 json 的檔名（`012.jpg` → `012.json`；去掉副檔名的 base，與 `<base>.mask.png`、`<base>.orig.<副檔名>` 同 base）。
     * 有它＝這頁有重繪素材（重繪的工作清單、「翻譯這頁」判成品都看它）。
     */
    fun materialsJsonName(pageFileName: String): String = OriginalMaterial.base(pageFileName) + JSON_SUFFIX

    /**
     * 素材夾裡的這個檔是不是**完整素材 json**（掃描「這章有沒有素材、存的是哪個去字法」用）：`.json` 結尾、不是文字框檔
     * （含被儲存位置改成 `… (1).json` 的文字框檔，看 [STEM]）、也不是暫存。文字框檔同樣是 `.json` 結尾，所以掃描一定要用
     * 這個判，不能只看副檔名。
     */
    fun isMaterialsJson(name: String): Boolean =
        name.endsWith(JSON_SUFFIX) && name.length > JSON_SUFFIX.length && !name.contains(STEM)

    // —— 內容 ——

    /**
     * 文字框檔的內容。[width]／[height]＝翻譯時引擎輸入（頁圖原尺寸解碼）的寬高，四邊形座標就在這個空間；
     * [pageBytes]＝寫入當下頁檔（譯圖）的位元組數（寫入端讀不到就不寫這份檔，所以正常一定 > 0）；
     * [maskBytes]＝同時寫的去字遮罩檔 `<base>.mask.png` 的位元組數，0＝遮罩沒寫成（夜讀不帶遮罩）；
     * [quads]＝每行原文四邊形（每行 4 點、每點 [x, y]）。
     */
    @Serializable
    data class Boxes(
        @SerialName("v") val version: Int,
        @SerialName("w") val width: Int,
        @SerialName("h") val height: Int,
        @SerialName("bytes") val pageBytes: Long,
        @SerialName("mask") val maskBytes: Long = 0L,
        val quads: List<List<List<Float>>>,
    )

    /** 寫入用：組成 json 字串。 */
    fun encode(width: Int, height: Int, pageBytes: Long, maskBytes: Long, quads: List<List<List<Float>>>): String =
        JSON.encodeToString(
            Boxes(VERSION, width, height, pageBytes.coerceAtLeast(0L), maskBytes.coerceAtLeast(0L), quads),
        )

    /** 讀取用：解不開、版本看不懂、寬高不合理 → null（當作沒有這份檔）。 */
    fun decode(text: String): Boxes? =
        runCatching { JSON.decodeFromString<Boxes>(text) }.getOrNull()
            ?.takeIf { it.version in 1..VERSION && it.width > 0 && it.height > 0 }

    // —— 夜讀取文字框的先後 ——

    /** 這頁夜讀的額外文字框從哪來（[pick]）。 */
    enum class Source {
        /** 完整素材 json（優先；有它就不看文字框檔）。 */
        MATERIALS,

        /** 文字框檔（沒有完整素材 json 時）。 */
        BOXES,

        /** 有文字框檔、但跟這張頁對不上（寬高或位元組數不同＝頁檔換過），或讀不出來、解不開：不用（遮罩也不帶）。 */
        STALE_BOXES,

        /** 兩份都沒有（日文頁、這個改動之前只存遮罩的頁、沒存素材的頁）。 */
        NONE,
    }

    /** [pick] 的結果：來源與要併進偵測的四邊形（已濾掉不是 4 點、或點少於 2 個座標的）。 */
    data class Pick(val source: Source, val quads: List<List<List<Float>>>)

    /**
     * 夜讀這頁要併進偵測的文字框：
     *  1. 有完整素材 json（[materials] 非 null）→ 用它各文字區的四邊形，依序攤平（跟以前完全一樣，不檢查尺寸；頁已還原成原圖時
     *     照樣用——那是日文原文的位置，本來就有字）。
     *  2. 沒有 → 文字框檔（[boxes]）：寬高要等於這張頁的原尺寸（[pageWidth]×[pageHeight]），頁檔位元組數（[pageBytes]）也要
     *     等於檔裡記的（[fits]）。對不上 → [Source.STALE_BOXES]、空清單。
     *  3. 文字框檔在（[boxesPresent]）卻讀不出來、解不開（[boxes]＝null）→ 同樣 [Source.STALE_BOXES]：檔在＝這頁應該是只存
     *     夜讀素材的譯後頁，但這次拿不準，框與遮罩都不帶（寫到一半的檔、壞掉的檔都走這裡）。
     *  4. 都沒有 → [Source.NONE]、空清單。
     */
    fun pick(
        materials: PageMaterials?,
        boxes: Boxes?,
        boxesPresent: Boolean,
        pageWidth: Int,
        pageHeight: Int,
        pageBytes: Long,
    ): Pick = when {
        materials != null -> Pick(Source.MATERIALS, validQuads(materials.regions.flatMap { it.quads }))
        boxes == null -> Pick(if (boxesPresent) Source.STALE_BOXES else Source.NONE, emptyList())
        !fits(boxes, pageWidth, pageHeight, pageBytes) -> Pick(Source.STALE_BOXES, emptyList())
        else -> Pick(Source.BOXES, validQuads(boxes.quads))
    }

    /**
     * 文字框檔是不是這張頁的：寬高相同，而且頁檔位元組數相同。檔裡記的位元組數 ≤ 0（舊版寫入端讀不到時記 0）或現在讀不到
     * （[pageBytes] ≤ 0）都當對不上——截圖頁常常尺寸全部相同，只比寬高擋不住別張頁；寧可不用。
     */
    fun fits(boxes: Boxes, pageWidth: Int, pageHeight: Int, pageBytes: Long): Boolean =
        boxes.width == pageWidth && boxes.height == pageHeight &&
            boxes.pageBytes > 0L && pageBytes == boxes.pageBytes

    // —— 去字遮罩 ——

    /** 夜讀這頁怎麼帶去字遮罩（[maskUse]）。 */
    sealed interface MaskUse {
        /** 不帶。 */
        data object Skip : MaskUse

        /** 有遮罩檔就帶，不比大小（完整素材；這個改動之前只存遮罩的頁——跟以前一樣）。 */
        data object Unchecked : MaskUse

        /** 遮罩檔的位元組數要剛好是 [bytes] 才帶（文字框檔記的；對不上＝遮罩被換過，不帶）。 */
        data class Exact(val bytes: Long) : MaskUse
    }

    /**
     * 依 [pick] 的來源決定遮罩怎麼帶：完整素材 → [MaskUse.Unchecked]（頁已還原成原圖時呼叫端另外不帶）；文字框檔 → 檔裡記了
     * 遮罩位元組數就 [MaskUse.Exact]，沒記（遮罩沒寫成）→ 不帶；文字框檔不能用 → 不帶（同時寫的遮罩也拿不準）；兩份都沒有 →
     * 開章時看到遮罩檔（[hasMaskFile]）才帶、不比大小（這個改動之前只存遮罩的頁，跟以前一樣）。
     */
    fun maskUse(pick: Pick, boxes: Boxes?, hasMaskFile: Boolean): MaskUse = when (pick.source) {
        Source.MATERIALS -> MaskUse.Unchecked
        Source.BOXES -> boxes?.maskBytes?.takeIf { it > 0L }?.let { MaskUse.Exact(it) } ?: MaskUse.Skip
        Source.STALE_BOXES -> MaskUse.Skip
        Source.NONE -> if (hasMaskFile) MaskUse.Unchecked else MaskUse.Skip
    }

    // —— 夜讀開跑之後才寫好的素材（與翻譯同章並行時） ——

    /**
     * 開章時列的素材夾快照裡沒有這頁的文字框檔時，要不要再找一次（[PageTranslator] 的夜讀端）：
     *  - 讀素材之前翻譯正在跑（[translating]：`TranslationBusy`）→ 找。可能有頁正在落地（覆蓋頁檔 → 寫遮罩 → 寫文字框）。
     *  - 頁檔的修改時間（[pageMtime]）晚於這章夜讀開跑時間（[chapterStartedAt]）減去 [LATE_SLACK_MS] → 找：這頁是開跑前後才
     *    翻好的，文字框可能是列快照之後才寫的。讀不到修改時間（≤ 0）也找。
     *  - 其餘（很久以前下載或翻好的頁）不找：快照時就該看得到，省掉日文頁每頁一次列素材夾。
     */
    fun mayHaveLateBoxes(translating: Boolean, pageMtime: Long, chapterStartedAt: Long): Boolean =
        translating || pageMtime <= 0L || pageMtime >= chapterStartedAt - LATE_SLACK_MS

    /**
     * 夜讀換檔之後要不要作廢這頁（素材是讀完之後才寫好的）：讀素材時這頁沒有可用的框（[source] 是 [Source.NONE] 或
     * [Source.STALE_BOXES]），讀之前翻譯又正在跑（[translating]），而現在素材夾裡有了這頁的完整素材 json（[hasMaterialsJson]）
     * 或對得上這張頁的文字框檔（[boxesNow]，以 [pageWidth]×[pageHeight]／[pageBytes] 比）→ true：剛做好的夜讀版少了框，
     * 刪掉、留待下次補做；否則它的修改時間比頁檔新、會一直被當成新鮮版。
     * 讀素材之前翻譯沒在跑＝那一刻沒有任何頁正在落地；頁檔在記快照（讀素材之前）之後才被覆蓋的另由頁檔快照比對擋下。
     */
    fun appearedLate(
        source: Source,
        translating: Boolean,
        hasMaterialsJson: Boolean,
        boxesNow: Boxes?,
        pageWidth: Int,
        pageHeight: Int,
        pageBytes: Long,
    ): Boolean {
        if (!translating) return false
        if (source != Source.NONE && source != Source.STALE_BOXES) return false
        if (hasMaterialsJson) return true
        return boxesNow != null && fits(boxesNow, pageWidth, pageHeight, pageBytes)
    }

    /** 夜讀分群假設四點四邊形：不是 4 點、或有點少於 2 個座標的行丟掉（同以前 nightExtraLines 的過濾）。 */
    private fun validQuads(quads: List<List<List<Float>>>): List<List<List<Float>>> =
        quads.filter { q -> q.size == 4 && q.all { it.size >= 2 } }

    // —— 清理 ——

    /**
     * [names]（`.yakuyomi/` 內的檔名）裡屬於**已經不在這章**的頁的文字框檔（[pageNames]＝章節夾頂層的檔名）。
     * [pageNames]＝null（這次列目錄不可信）→ 一個都不刪（同 `NightPages.cleanupPlan`）。其他檔一律不碰。
     */
    fun orphans(names: Iterable<String>, pageNames: Set<String>?): Set<String> {
        if (pageNames == null) return emptySet()
        return names.filterTo(LinkedHashSet()) { n -> pageOf(n)?.let { it !in pageNames } == true }
    }
}
