package eu.kanade.tachiyomi.data.nightread

import com.hippo.unifile.UniFile
import li.joye.yakuyomi.nightread.NightRead
import li.joye.yakuyomi.nightread.NightTier

/** 閱讀器裡一章的夜讀狀態（見 [NightPages.availability]）。 */
enum class NightAvailability {
    /** 至少一頁有完成標記（新格式 std 或三檔格式 l1）：檔位切換可用。 */
    READY,

    /** 只有舊版單檔：顯示舊圖、檔位鈕灰，點了可重新產生夜讀版。 */
    LEGACY,

    /** 沒有夜讀版：檔位鈕灰，點了可產生。 */
    NONE,

    /** 壓縮檔章或線上章：不支援夜讀（要先下載成鬆散資料夾）。 */
    UNSUPPORTED,
}

/**
 * 夜讀版頁圖的**檔名慣例、查找與定位**：產生端（`PageTranslator.renderNightChapter`）、顯示端（reader 的 loader）、
 * 章節列與佇列的「有沒有夜讀版」掃描都照這裡算，別各自拼字串。檔名規則與查找都是純函式（JVM 單元測試見 NightPagesTest）。
 *
 * 都放在 `<章節夾>/.yakuyomi/`，保留**完整頁檔名（含副檔名）**。三種格式並存、**已產生的章不必重做**：
 *  - **兩檔（現行，2026-10-01 起寫的都是這種）**，見 [NightLevel]：
 *     - `012.jpg.night.std.webp`：「標準」（引擎 L2），一定有，也是「這頁完成」的標記（新鮮度看它）。
 *     - `012.jpg.night.more.webp`：「更多」（引擎 L3），只在與標準不同時才有（相同＝輸出逐位元相同，不重存）。
 *  - **三檔（只讀）**：2026-10-01 當天三檔版產生的 `012.jpg.night.l1.webp`（一定有、完成標記）／`.l2`（與 l1 不同才有）／
 *    `.l3`（與 l2 不同才有）。標準讀 l2…l1、更多讀 l3…l1 中存在的最高檔（與三檔時代的 L2／L3 完全同義）。
 *  - **舊版單檔（只讀）**：`012.jpg.night.webp`（三檔之前產生的）。兩檔都顯示它、檔位鈕灰，可重新產生。
 *  - 暫存一律是「最終名 + `.tmp`」，reader 永遠不認。
 * 產生端只寫兩檔格式；新的標準檔落地後刪掉同頁的三檔與舊版單檔（[oldFormatNames]）。同頁新舊並存（刪到一半被殺）時
 * **有 std 就只看兩檔格式**，三檔與舊版單檔視而不見（[resolveName]），下次產生開跑時清掉（[leftovers]）。
 *
 * **不去副檔名**：同章可能同時有 `012.jpg` 與 `012.png`（來源混格式、或重下載換了格式留下舊檔），去副檔名會讓兩頁算到
 * 同一組夜讀檔、互相蓋掉；帶著副檔名就一頁一組、天然不碰撞。重繪素材（`<base>.orig.<副檔名>`、舊版 `<base>.orig.webp`
 * 等，見 `OriginalMaterial`）沿用它自己去副檔名的 base，兩套慣例並存無妨。WebP **無損**——暗底上有損壓縮會出色帶。
 *
 * **為什麼放 `.yakuyomi/`**：reader 列頁只看章節夾頂層的圖檔，子夾自動被忽略 → 夜讀版不會被當成多出來的一頁；
 * 同時跟重繪素材同一個子夾、刪章時整夾一起走，不留孤兒檔。
 *
 * **新鮮度**：成品頁被覆寫（重翻／重繪／即時翻落地）後夜讀版就是舊畫面。產生端用 [isFresh]（比 [markerName]：有 std 看
 * std、否則看 l1）決定要不要重做、覆寫端呼叫 `invalidateNightPage` 刪掉這頁所有夜讀檔（[artifactNames]：標記照查找優先序
 * 反過來刪、附屬檔最後）；兩道都有，reader 才不會端出過期的暗色頁。
 *
 * **規則版本**（[RULES_VERSION]＝nightread 的 `NightRead.RULES_VERSION`，2026-10-03 起）：app 更新改了夜讀規則時，已產生的頁要
 * 標「可更新」、由使用者一鍵重新產生（不自動重做）。每頁換檔完成（std 落地）後，產生端在旁邊寫一個**空的版本記號**
 * `012.jpg.night.rules1`（[rulesStampName]）。這頁的版本（[pageRulesVersion]）＝有 std 時同頁記號裡最大的那個，沒有記號＝0；
 * 只有三檔格式或舊版單檔的頁＝0（記版本之前產生的）。版本 < [RULES_VERSION] 的頁算舊版：章節列標「可更新」（[summarize]）、
 * 產生端當它不新鮮、重做（[isPageOutdated]）——所以同一章有新有舊時只補舊的那幾頁。
 *  - 記號是另一個檔、不是改夜讀檔的檔名：reader 的查找與刪除順序（上面那些）一個字都不用動，舊版 app 也不認得它、不會刪它。
 *  - 記號寫在 std **之後**：中途被殺最多讓新頁看起來像舊版（下次多做一次），不會讓舊頁冒充新版。std 被刪（作廢、重新產生）時
 *    記號可以留著——沒有 std 的記號不算數，下次產生開跑時清掉（[staleStamps]）；之後同版本再產生會重用同名記號。
 *  - 記號不是夜讀檔（[isNightArtifact] 不認），也不會被當成重繪素材（`OriginalMaterial` 只認 `.orig.`）。
 */
object NightPages {

    /** 目前的規則版本（nightread 的 `NightRead.RULES_VERSION`）：產生端寫這個、章節列與設定頁拿它判舊版。 */
    const val RULES_VERSION: Int = NightRead.RULES_VERSION

    /** 章節內的素材子夾（與 `PageTranslator.MATERIALS_DIR` 同名——同一個夾）。 */
    const val DIR = ".yakuyomi"

    /** 舊版單檔的尾綴（接在**完整**頁檔名之後）：`012.jpg.night.webp`。 */
    const val LEGACY_SUFFIX = ".night.webp"

    /** 暫存檔的尾綴（接在最終名之後）：`012.jpg.night.std.webp.tmp`。reader 不認它。 */
    const val TMP_SUFFIX = ".tmp"

    /** 任何夜讀檔（兩檔、三檔各檔位、舊版單檔，以及它們的暫存）。只錨定結尾：頁名本身含 `.night.` 也不會誤判。 */
    private val ARTIFACT = Regex("""\.night(\.(?:l[123]|std|more))?\.webp(\.tmp)?$""")

    /** 夜讀最終檔（不含暫存），第 1 組＝頁檔名。 */
    private val FINAL = Regex("""^(.+)\.night(\.(?:l[123]|std|more))?\.webp$""")

    /** 規則版本記號的中段（接在完整頁檔名之後、版本號之前）。 */
    private const val RULES_MARK = ".night.rules"

    /** 規則版本記號：第 1 組＝頁檔名、第 2 組＝版本（1–6 位數字）。 */
    private val RULES_STAMP = Regex("""^(.+)\.night\.rules(\d{1,6})$""")

    /**
     * 產生端開跑前試寫的探測檔（`.yakuyomi/` 內）：試建這個空檔、確認檔名沒被儲存位置改掉、再刪掉，確定這裡寫得出版本記號
     * 才開始做。寫不出記號時每頁都會一直是舊版，使用者每按一次更新就整章重做一次，所以寧可整章報錯。它不是記號（沒有版本號）、
     * 不是夜讀檔、也不是重繪素材，誰都不認它；被殺而留下也無害，下次探測重用同一個檔。
     */
    const val STAMP_PROBE = "night.rules.probe"

    /** 兩檔格式：`012.jpg` + 標準 → `012.jpg.night.std.webp`；更多 → `012.jpg.night.more.webp`。 */
    fun levelName(pageFileName: String, level: NightLevel): String = "$pageFileName.night.${level.fileKey}.webp"

    /** 三檔格式（只讀）：`012.jpg` + L2 → `012.jpg.night.l2.webp`。 */
    fun tierName(pageFileName: String, tier: NightTier): String = "$pageFileName.night.${tier.key}.webp"

    /** `012.jpg` → `012.jpg.night.webp`（舊版單檔）。 */
    fun legacyName(pageFileName: String): String = pageFileName + LEGACY_SUFFIX

    /** 最終名 → 暫存名（`<最終名>.tmp`）。 */
    fun tmpName(finalName: String): String = finalName + TMP_SUFFIX

    /** 是否任一種夜讀檔（含暫存）。不會誤中重繪素材 `.orig.<副檔名>`（含舊版 `.orig.webp` 與暫存）／`.mask.png`／`.json`。 */
    fun isNightArtifact(name: String): Boolean = ARTIFACT.containsMatchIn(name)

    /** 夜讀暫存檔（產生到一半、或中途被殺留下的）。 */
    fun isNightTmp(name: String): Boolean = name.endsWith(TMP_SUFFIX) && isNightArtifact(name)

    /** 夜讀最終檔（任一格式，不含暫存）。 */
    fun isFinalNightFile(name: String): Boolean = isNightArtifact(name) && !name.endsWith(TMP_SUFFIX)

    /** 最終檔名 → 它屬於哪一頁（`012.jpg.night.more.webp` → `012.jpg`）；不是夜讀最終檔 → null。 */
    fun pageOf(name: String): String? = FINAL.find(name)?.groupValues?.get(1)

    /** 兩檔格式的檔名，**標準（完成標記）在前**：std、more。 */
    fun currentNames(pageFileName: String): List<String> = NightLevel.entries.map { levelName(pageFileName, it) }

    /** 舊格式的檔名（新標準檔落地後要清掉的），**標記在前**：l1、l2、l3、舊版單檔。 */
    fun oldFormatNames(pageFileName: String): List<String> =
        NightTier.entries.map { tierName(pageFileName, it) } + legacyName(pageFileName)

    /**
     * 一頁所有可能的夜讀最終檔，也是**整頁作廢時的刪除順序**：舊版單檔、l1、std、more、l2、l3。
     *  - 三個完成標記照**查找優先序反過來**刪（舊版單檔 < l1 < std，見 [resolveName]）：先刪的一定是被更高格式遮住、
     *    本來就看不到的；輪到正在顯示的那個標記時，比它低的都已經刪了，這頁直接變成「沒有夜讀版」。刪到一半的每個狀態，
     *    reader 看到的不是原本那組、就是原圖——不會在 std 刪掉後退回同頁更舊的三檔或舊版單檔（過期重做時那些也是舊畫面）。
     *  - 附屬檔（more、l2、l3）放在所有標記之後：標記都沒了它們就是孤兒、reader 不認，順序無所謂。每種格式仍是標記在附屬檔
     *    之前，閱讀器不會把殘留的 more 配上別的 std、或殘留的 l2 配上別的 l1。
     * （規格原寫 std、more、l1、l2、l3、舊版單檔；同頁新舊並存時——例如 std 改名後才發現頁圖過期——先刪 std 會讓 reader
     * 退回舊的三檔或單檔，被殺或刪不掉時就一直端出過期圖。這個順序同樣「標記先於附屬檔」，只多了這一層保證。）
     */
    fun artifactNames(pageFileName: String): List<String> = listOf(
        legacyName(pageFileName),
        tierName(pageFileName, NightTier.L1),
        levelName(pageFileName, NightLevel.STANDARD),
        levelName(pageFileName, NightLevel.MORE),
        tierName(pageFileName, NightTier.L2),
        tierName(pageFileName, NightTier.L3),
    )

    /**
     * 顯示 [level] 時這頁要讀哪個檔（純函式，[names]＝`.yakuyomi/` 內的檔名集合）：
     *  - 有 std（兩檔格式）：標準 → std；更多 → more，沒有就 std。同頁殘留的三檔／舊版單檔不看。
     *  - 沒有 std、有 l1（三檔格式）：取 l{level.tier}…l1 中存在的最高那一檔（標準＝l2 或 l1、更多＝l3、l2 或 l1）。
     *  - 都沒有、但有舊版單檔：回舊版單檔（兩檔都是它）。
     *  - 都沒有：null（顯示原圖）。孤兒（沒有 std 的 more、沒有 l1 的 l2／l3，產生到一半被殺留下的）視而不見。
     */
    fun resolveName(names: Set<String>, pageFileName: String, level: NightLevel): String? {
        if (levelName(pageFileName, NightLevel.STANDARD) in names) {
            for (k in level.ordinal downTo 0) {
                val n = levelName(pageFileName, NightLevel.entries[k])
                if (n in names) return n
            }
        }
        if (tierName(pageFileName, NightTier.L1) in names) {
            for (k in level.tier.ordinal downTo 0) {
                val n = tierName(pageFileName, NightTier.entries[k])
                if (n in names) return n
            }
        }
        return legacyName(pageFileName).takeIf { it in names }
    }

    /**
     * 這頁的完成標記（新鮮度比它）：有 std → std；否則有 l1 → l1；都沒有 → null（只有舊版單檔或孤兒＝不算完成，
     * 產生端會重做、升級成兩檔）。
     */
    fun markerName(names: Set<String>, pageFileName: String): String? =
        levelName(pageFileName, NightLevel.STANDARD).takeIf { it in names }
            ?: tierName(pageFileName, NightTier.L1).takeIf { it in names }

    /**
     * [names]（`.yakuyomi/` 內的夜讀最終檔）裡**永遠不會被顯示**、可以直接刪的檔（產生端開跑時清掉）：
     *  - 有 std 的頁：它的 l1／l2／l3／舊版單檔（被兩檔取代、刪到一半被殺留下的）。
     *  - 沒有 std 的頁：它的 more（孤兒）；再沒有 l1 的話，它的 l2／l3（孤兒）。
     * 回傳的都不是任何 [resolveName] 的結果，刪除順序無所謂。
     */
    fun leftovers(names: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (n in names) {
            val page = pageOf(n) ?: continue
            val dead = when {
                // 有 std：被它取代的舊格式
                levelName(page, NightLevel.STANDARD) in names -> n in oldFormatNames(page)
                // 沒有 std：孤兒 more
                n == levelName(page, NightLevel.MORE) -> true
                // 也沒有 l1：孤兒 l2／l3（有 l1 的三檔照讀）
                tierName(page, NightTier.L1) in names -> false
                else -> n == tierName(page, NightTier.L2) || n == tierName(page, NightTier.L3)
            }
            if (dead) out += n
        }
        return out
    }

    // —— 規則版本記號（見類別說明「規則版本」） ——

    /** `012.jpg` + 1 → `012.jpg.night.rules1`（空檔，只看在不在）。 */
    fun rulesStampName(pageFileName: String, version: Int = RULES_VERSION): String = "$pageFileName$RULES_MARK$version"

    /** 是不是規則版本記號（不是夜讀檔、也不是重繪素材）。 */
    fun isRulesStamp(name: String): Boolean = RULES_STAMP.matches(name)

    /** 記號檔名 → (頁檔名, 版本)；不是記號 → null。 */
    fun parseRulesStamp(name: String): Pair<String, Int>? =
        RULES_STAMP.matchEntire(name)?.let { it.groupValues[1] to it.groupValues[2].toInt() }

    /** [names] 裡每頁記號的**最大**版本（頁檔名 → 版本）。不管那頁有沒有 std；std 的判斷在 [pageRulesVersion]。 */
    fun rulesStamps(names: Iterable<String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (n in names) {
            val (page, v) = parseRulesStamp(n) ?: continue
            if (v > (out[page] ?: -1)) out[page] = v
        }
        return out
    }

    /**
     * 這頁夜讀版的規則版本（純函式，[names]＝`.yakuyomi/` 內的檔名、要含記號；[stamps]＝[rulesStamps] 的結果，同一章多頁共用時
     * 先算好傳進來）：
     *  - 有 std（兩檔格式）：同頁記號的最大版本，沒有記號＝0。
     *  - 沒有 std、有 l1（三檔格式）或舊版單檔：0（記版本之前產生的；同頁殘留的記號不算——它不是替這些檔寫的）。
     *  - 都沒有（沒有夜讀版，含只剩孤兒 more／l2／l3 或只剩記號）：null。
     */
    fun pageRulesVersion(
        names: Set<String>,
        pageFileName: String,
        stamps: Map<String, Int> = rulesStamps(names),
    ): Int? = when {
        levelName(pageFileName, NightLevel.STANDARD) in names -> stamps[pageFileName] ?: 0
        tierName(pageFileName, NightTier.L1) in names || legacyName(pageFileName) in names -> 0
        else -> null
    }

    /**
     * 這頁有夜讀版、而且是舊規則產生的（版本 < [current]）。產生端把這種頁當不新鮮、重做；沒有夜讀版的頁不算舊版
     * （要不要做由完成標記決定）。
     */
    fun isPageOutdated(
        names: Set<String>,
        pageFileName: String,
        stamps: Map<String, Int> = rulesStamps(names),
        current: Int = RULES_VERSION,
    ): Boolean = pageRulesVersion(names, pageFileName, stamps)?.let { it < current } ?: false

    /**
     * 可以直接刪的記號（產生端開跑時清掉；不影響任何頁的版本判斷）：
     *  - 孤兒：同頁沒有 std（夜讀版被作廢、重做時刪了、或只剩三檔格式／舊版單檔）。
     *  - 被取代：同頁有更大版本的記號。
     */
    fun staleStamps(names: Iterable<String>): Set<String> {
        val set = names as? Set<String> ?: names.toHashSet()
        val max = rulesStamps(set)
        val out = LinkedHashSet<String>()
        for (n in set) {
            val (page, v) = parseRulesStamp(n) ?: continue
            if (levelName(page, NightLevel.STANDARD) !in set || v < (max[page] ?: v)) out += n
        }
        return out
    }

    /**
     * [names] 裡屬於**已經不在這章**的頁（[pages]＝章節夾頂層的頁檔名）的夜讀最終檔與記號——頁被換掉或刪掉後留下的。reader
     * 永遠不會顯示它們，但章節列會因為它們一直把這章算成「可更新」（產生端只做在的頁、永遠補不到它們），所以產生端開跑時清掉。
     * 暫存不在這裡（另外清）。
     */
    fun filesOfMissingPages(names: Iterable<String>, pages: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (n in names) {
            val page = pageOf(n) ?: parseRulesStamp(n)?.first ?: continue
            if (page !in pages) out += n
        }
        return out
    }

    /**
     * 產生端開跑時要刪的檔（純函式；[names]＝`.yakuyomi/` 內的所有檔名，重繪素材等其他檔照樣傳進來、會被略過）：
     *  - 夜讀暫存（[isNightTmp]，上次中途被殺留下的）；
     *  - 永遠不會被顯示的最終檔（[leftovers]）；
     *  - 已經不在這章的頁留下的夜讀檔與記號（[filesOfMissingPages]）。[pageNames]＝章節夾頂層的檔名；null＝這次列目錄不可信
     *    （例如列出來是空的），這一項就不清——否則一次列目錄失敗會把整章的夜讀版刪光；
     *  - 上面刪完後的可清記號（[staleStamps]）。
     * 刪不掉的也當作已刪（reader 本來就不認、下次開跑再清），所以這一輪看得到的＝[names] 的夜讀最終檔與記號扣掉這些
     * （[needsRender] 拿那一份判斷）。
     */
    fun cleanupPlan(names: Iterable<String>, pageNames: Set<String>?): Set<String> {
        val out = LinkedHashSet<String>()
        val kept = HashSet<String>()
        for (n in names) {
            when {
                isNightTmp(n) -> out += n
                isFinalNightFile(n) || isRulesStamp(n) -> kept += n
            }
        }
        val dead = leftovers(kept) + (pageNames?.let { filesOfMissingPages(kept, it) } ?: emptySet())
        out += dead
        kept -= dead
        out += staleStamps(kept)
        return out
    }

    /**
     * 這頁這一輪要不要做（純函式；[names]＝開跑清完後 `.yakuyomi/` 的夜讀最終檔與記號，[stamps]＝[rulesStamps]）：沒有完成標記
     * （[markerName]）、標記不算數（[markerOk] 回 false：比頁圖舊，或早於強制重做的時間）、或是舊規則產生的（[isPageOutdated]）
     * 就要做。章節列標「可更新」的頁，這裡一定回 true。
     */
    fun needsRender(
        names: Set<String>,
        pageFileName: String,
        stamps: Map<String, Int> = rulesStamps(names),
        current: Int = RULES_VERSION,
        markerOk: (marker: String) -> Boolean,
    ): Boolean {
        val marker = markerName(names, pageFileName) ?: return true
        return !markerOk(marker) || isPageOutdated(names, pageFileName, stamps, current)
    }

    /** 一章的夜讀狀態（只看檔名；不支援的章由呼叫端另判）。 */
    enum class ChapterState {
        /** 至少一頁有完成標記（std 或 l1）：檔位切換可用。 */
        READY,

        /** 沒有任何完成標記、但有舊版單檔：顯示舊圖，檔位鈕灰、可重新產生。 */
        LEGACY,

        /** 都沒有。 */
        NONE,
    }

    /**
     * 一章的夜讀摘要：[state] 同 [chapterState]；[outdated]＝至少一頁有夜讀版而且是舊規則產生的（[isPageOutdated]）——
     * 章節列的「可更新」、設定頁「重新產生舊版夜讀頁」的計數都看它。只有舊版單檔、只有三檔格式的章一定是 true。
     */
    data class ChapterSummary(val state: ChapterState, val outdated: Boolean)

    /**
     * [names]＝`.yakuyomi/` 內的檔名（要含記號，否則每頁都算舊版）。一次掃完：狀態照 [chapterState] 的規則，舊版照
     * [isPageOutdated]。孤兒、暫存、只剩記號的頁都不算有夜讀版、也不算舊版。
     */
    fun summarize(names: Iterable<String>, current: Int = RULES_VERSION): ChapterSummary {
        val set = names as? Set<String> ?: names.toHashSet()
        val stamps = rulesStamps(set)
        var ready = false
        var legacy = false
        var outdated = false
        for (n in set) {
            val page = pageOf(n) ?: continue
            when (n) {
                levelName(page, NightLevel.STANDARD), tierName(page, NightTier.L1) -> ready = true
                legacyName(page) -> legacy = true
                else -> continue // more／l2／l3：跟著標記走，不單獨算
            }
            if (!outdated && isPageOutdated(set, page, stamps, current)) outdated = true
        }
        val state = when {
            ready -> ChapterState.READY
            legacy -> ChapterState.LEGACY
            else -> ChapterState.NONE
        }
        return ChapterSummary(state, outdated)
    }

    /** [names]＝`.yakuyomi/` 內的檔名。有 std 或 l1 → READY；否則有舊版單檔 → LEGACY；否則 NONE。孤兒、暫存不算。 */
    fun chapterState(names: Iterable<String>): ChapterState = summarize(names).state

    /**
     * 閱讀器裡「這一章」的夜讀狀態（懸浮鈕的檔位可不可用、點了做什麼）。[supported]＝鬆散資料夾章（已下載或本機資料夾）；
     * 壓縮檔章、線上章 → [NightAvailability.UNSUPPORTED]。優先序：UNSUPPORTED > READY > LEGACY > NONE。
     */
    fun availability(supported: Boolean, names: Iterable<String>): NightAvailability = when {
        !supported -> NightAvailability.UNSUPPORTED
        else -> when (chapterState(names)) {
            ChapterState.READY -> NightAvailability.READY
            ChapterState.LEGACY -> NightAvailability.LEGACY
            ChapterState.NONE -> NightAvailability.NONE
        }
    }

    /** 這頁（[pageFileName]）在 [names] 裡有沒有任何夜讀最終檔（含孤兒、舊版單檔）。 */
    fun hasAnyArtifact(names: Set<String>, pageFileName: String): Boolean =
        artifactNames(pageFileName).any { it in names }

    /**
     * 列 [chapterDir]`/.yakuyomi/` 裡的夜讀**最終檔與規則版本記號**（name → UniFile，不含暫存與重繪素材）。夾不在＝空。
     * 一次 listFiles（SAF 上一次 provider 查詢），比逐頁 findFile 便宜得多。記號放進來是給 [summarize] 判「可更新」用；
     * [resolveName]／[availability] 本來就不認它。
     */
    fun listFinal(chapterDir: UniFile): Map<String, UniFile> {
        val dir = chapterDir.findFile(DIR)?.takeIf { it.isDirectory } ?: return emptyMap()
        return dir.listFiles().orEmpty()
            .mapNotNull { f -> f.name?.takeIf { isFinalNightFile(it) || isRulesStamp(it) }?.let { it to f } }
            .toMap()
    }

    /**
     * 夜讀版 [night]（完成標記，[markerName]）是否比成品頁 [page] 新（不比它舊就算新鮮）。
     * 用修改時間而非內容雜湊：頁圖每次覆寫都會更新 mtime，而夜讀版一定在覆寫之後才產生；比對雜湊得整張讀進來、划不來。
     */
    fun isFresh(night: UniFile, page: UniFile): Boolean = night.lastModified() >= page.lastModified()

    /**
     * 一章 `.yakuyomi/` 的夜讀檔名快取（name → UniFile），給 reader 的 loader 用：每次解碼都 findFile 在非主要儲存上
     * 等於每次列整個資料夾，這裡列一次就重用。快取可能過期（佇列剛產出新頁、或重新產生時刪了舊檔）：呼叫端在
     * 「這頁查不到可送的檔」時探一次 std、在開檔失敗時 [invalidate] 後重列（見 `NightPageStreams`）。執行緒安全（整份換掉）。
     */
    class Index {
        private class Snapshot(val dir: UniFile, val files: Map<String, UniFile>)

        @Volatile
        private var snapshot: Snapshot? = null

        /** 讓快取失效（下次 [files] 會重列）。 */
        fun invalidate() {
            snapshot = null
        }

        /** 快取的檔名表；沒有或屬於別的資料夾 → 重列。 */
        fun files(chapterDir: UniFile): Map<String, UniFile> {
            val s = snapshot
            if (s != null && (s.dir === chapterDir || s.dir.uri == chapterDir.uri)) return s.files
            return refresh(chapterDir)
        }

        /** 重列一次 `.yakuyomi/` 並換掉快取，回傳新檔名表。 */
        fun refresh(chapterDir: UniFile): Map<String, UniFile> {
            val files = listFinal(chapterDir)
            snapshot = Snapshot(chapterDir, files)
            return files
        }
    }
}
