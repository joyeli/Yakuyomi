package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.translation.model.TranslationItem

// 翻譯＋夜讀「合併處理」（v2，2026-10-09：一條線）：一章一個翻譯項帶合併意圖（[NightLink]），翻譯那條消費者把它當成一條
// 管線跑——每頁 翻譯 → 落地 → 這頁的夜讀 → 才算完整輸出（[PageTranslator.translateChapterCombined]，排程在 [CombinedLine]）。
// 不另建夜讀項、不配對。本檔放排程用的純函式、常數與檔名慣例（JVM 測試見 CombinedNightTest），狀態本身在 TranslationManager。

/** 排入翻譯的觸發來源（決定合併意圖，見 [CombinedNight.linkFor]）。 */
enum class TranslateTrigger {
    /** 章節列翻譯鈕、多選翻譯、閱讀器「開始翻譯這話」。 */
    MANUAL,

    /** 下載完成時的「下載時翻譯」。 */
    DOWNLOAD,

    /** 即時翻譯（讀到這章）、線上章下載完成的即時路徑。 */
    LIVE,

    /** 即時翻譯的跨章預取。 */
    PREFETCH,
}

/**
 * 翻譯項的合併意圖（持久化成 [code]）：[NONE] 只翻譯；[AUTO] 自動路徑（要過夜讀的分類／來源範圍）；[EXPLICIT] 明確要求
 * （不看夜讀範圍）。只升不降。
 */
enum class NightLink(val code: Int) {
    NONE(0),
    AUTO(1),
    EXPLICIT(2),
    ;

    companion object {
        /** 持久化代碼 → 意圖；認不得（新版寫的、或壞資料）→ [NONE]。 */
        fun fromCode(code: Int): NightLink = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** 一頁夜讀結果確定（過期、被丟回待做都不算）。 */
enum class NightPageResult {
    /** 夜讀版做好了。 */
    DONE,

    /** 彩頁略過（照「略過彩色頁」）。 */
    SKIPPED,

    /** 這頁做不出來（例外、OOM 補跑仍 OOM、舊夜讀檔刪不掉）。 */
    FAILED,
}

/**
 * 一頁的夜讀結果確定（合併輪＝這頁完整輸出；一般夜讀項也每頁發）。[held]＝這個結果放行了這頁的 hold 記號（白天也要重畫）。
 */
data class NightPageEvent(
    val chapterId: Long,
    val pageName: String,
    val result: NightPageResult,
    val held: Boolean,
)

object CombinedNight {
    /**
     * 線位比夜讀同時頁數多幾個：翻譯最多領先夜讀這麼多頁。0＝線位數等於夜讀同時頁數（使用者 2026-10 定案：併發 2 時最多翻到 2、夜讀 0）。
     * 真機測試 LEAD=1 時第 3 頁翻好後要等夜讀 28.6 s、Java heap 峰值到 450／512 MB。
     */
    const val TRANSLATE_LEAD = 0

    /**
     * 合併輪裡翻譯中的第 2 頁起，夜讀放行每頁多替翻譯留的 heap：翻譯期間 jH 峰值 167 MB（同時 2 頁）扣掉 app 本身約 55 MB，
     * 每頁約 56 MB。第 1 頁由夜讀放行既有的 RESERVE_BYTES（96 MB）涵蓋。
     */
    const val TRANSLATE_PAGE_RESERVE_BYTES = 56L * 1024 * 1024

    /** 合併輪、還沒開多緒（LOCKED）夜讀模型組時，系統可用記憶體低於這個值 → 夜讀改用單緒不進鎖的模型組（約 1.7 GB 加餘裕）。 */
    const val MIN_AVAIL_BYTES = 2_700L * 1024 * 1024

    /**
     * 合併輪、多緒模型組已經開著時的門檻：那組的用量已經算在系統可用記憶體裡，只比餘裕（再拿 [MIN_AVAIL_BYTES] 比會重複扣）。
     */
    const val MIN_AVAIL_LOCKED_OPEN_BYTES = 1_024L * 1024 * 1024

    /** 一輪收尾補掃的次數上限（頁一直被改寫時不無限循環）。 */
    const val MAX_SWEEPS = 2

    /**
     * 排入時的合併意圖：手動（[TranslateTrigger.MANUAL]）合併 → [NightLink.EXPLICIT]；下載時翻譯合併 → [NightLink.AUTO]；
     * 即時與預取在「即時翻譯時一併產生夜讀版」開著時 → [NightLink.AUTO]；其餘 [NightLink.NONE]。
     */
    fun linkFor(trigger: TranslateTrigger, manualCombined: Boolean, liveNight: Boolean): NightLink = when (trigger) {
        TranslateTrigger.MANUAL -> if (manualCombined) NightLink.EXPLICIT else NightLink.NONE
        TranslateTrigger.DOWNLOAD -> if (manualCombined) NightLink.AUTO else NightLink.NONE
        TranslateTrigger.LIVE, TranslateTrigger.PREFETCH -> if (liveNight) NightLink.AUTO else NightLink.NONE
    }

    /** 只升不降（NONE < AUTO < EXPLICIT）。 */
    fun upgrade(current: NightLink, requested: NightLink): NightLink =
        if (requested.code > current.code) requested else current

    /**
     * 一輪開跑時能不能開夜讀階段：意圖不是 NONE、夜讀總開關開、夜讀模型齊、鬆散夾、素材夾建得出來，而且 [NightLink.AUTO] 要過
     * 夜讀的分類／來源範圍（[nightScopeAllowed]；[NightLink.EXPLICIT] 不看）。
     */
    fun canRunNightStage(
        link: NightLink,
        nightEnabled: Boolean,
        nightModelsReady: Boolean,
        looseFolder: Boolean,
        canStoreNightFiles: Boolean,
        nightScopeAllowed: Boolean,
    ): Boolean = link != NightLink.NONE &&
        nightEnabled &&
        nightModelsReady &&
        looseFolder &&
        canStoreNightFiles &&
        (link == NightLink.EXPLICIT || nightScopeAllowed)

    /** 線位數：夜讀同時頁數（至少 1）加 [TRANSLATE_LEAD]。 */
    fun slots(nightCap: Int): Int = maxOf(1, nightCap) + TRANSLATE_LEAD

    /** 夜讀放行多替翻譯留的 heap：翻譯中第 2 頁起每頁 [TRANSLATE_PAGE_RESERVE_BYTES]。 */
    fun extraReserve(translatingPages: Int): Long = maxOf(0, translatingPages - 1) * TRANSLATE_PAGE_RESERVE_BYTES

    /**
     * 章層級 hold 生效（閱讀器據此決定有 hold 記號的頁要不要繼續送原圖）：翻譯總開關開、夜讀總開關開、這章的翻譯項帶合併意圖，
     * 而且——翻譯中時看這一輪夜讀階段開著沒（[nightActive]；呼叫端在剛挑起、閘門還在判的那段也傳 true，否則被 hold 的頁會在
     * 這段空窗被閱讀器黏著放行）；排隊中時看磁碟上可能有沒有這章的 hold 記號（[mayHaveHolds]）；失敗一律不生效。暫停不影響
     * （線停了、那幾頁還沒完整輸出）。
     */
    fun holdActive(
        masterOn: Boolean,
        nightOn: Boolean,
        link: NightLink,
        status: TranslationItem.Status,
        nightActive: Boolean,
        mayHaveHolds: Boolean,
    ): Boolean {
        if (!masterOn || !nightOn || link == NightLink.NONE) return false
        return when (status) {
            TranslationItem.Status.TRANSLATING -> nightActive
            TranslationItem.Status.QUEUE -> mayHaveHolds
            TranslationItem.Status.ERROR -> false
        }
    }

    /** 這章的夜讀版會由合併工作做（排隊中，或翻譯中而且這一輪夜讀階段開著；閘門還在判的那段呼叫端也傳 true）。 */
    fun covers(link: NightLink, status: TranslationItem.Status, nightActive: Boolean): Boolean =
        link != NightLink.NONE &&
            (
                status == TranslationItem.Status.QUEUE ||
                    (status == TranslationItem.Status.TRANSLATING && nightActive)
                )

    /**
     * 正在跑的只翻譯輪要不要停下重挑成合併輪：翻譯中、這一輪沒開夜讀階段、新意圖不是 NONE，而且意圖真的升了、或夜讀總開關
     * 剛打開（[nightSwitchedOn]）。[stageBlocked]＝這一輪開跑時閘門因為章本身不行而沒過（不是鬆散資料夾、建不出素材夾）：
     * 重挑後閘門照樣不過，壓縮檔章還會丟掉整輪的解壓與翻譯進度，所以不重挑。
     */
    fun restartForNight(
        status: TranslationItem.Status,
        nightActive: Boolean,
        oldLink: NightLink,
        newLink: NightLink,
        nightSwitchedOn: Boolean,
        stageBlocked: Boolean = false,
    ): Boolean = status == TranslationItem.Status.TRANSLATING &&
        !nightActive &&
        !stageBlocked &&
        newLink != NightLink.NONE &&
        (newLink != oldLink || nightSwitchedOn)

    /** 合併輪裡一頁要做什麼。 */
    enum class PagePlan {
        /** 不在翻譯清單：翻譯 → 落地 → 夜讀。 */
        TRANSLATE,

        /** 在清單裡、夜讀版不新鮮或沒有：只做夜讀。 */
        NIGHT,

        /** 在清單裡、夜讀版新鮮：已經完整輸出。 */
        DONE,
    }

    /** 一頁的計畫（[inManifest]＝在翻譯清單、[nightFresh]＝夜讀版新鮮）。不在清單的頁一律重翻（落地會作廢舊夜讀版）。 */
    fun planPage(inManifest: Boolean, nightFresh: Boolean): PagePlan = when {
        !inManifest -> PagePlan.TRANSLATE
        nightFresh -> PagePlan.DONE
        else -> PagePlan.NIGHT
    }

    /**
     * q4：整話翻完後要用原圖補做夜讀版的頁＝翻譯失敗的頁（[failed]，照它的順序、去重）扣掉讀不出來或中斷復原失敗的頁
     * （[unreliable]）。翻譯清單一頁都沒有（[manifestPages] ＜ 1，整話全失敗）→ 不補。
     */
    fun <P> q4Pages(failed: List<P>, unreliable: Set<P>, manifestPages: Int): List<P> {
        if (manifestPages < 1) return emptyList()
        return failed.filter { it !in unreliable }.distinct()
    }

    /**
     * 收尾補掃要再送夜讀的頁（照 [pages] 的章內順序）：在翻譯清單、夜讀版不新鮮、不在夜讀段手上（[inFlight]）、這一輪夜讀沒
     * FAILED 過（[nightFailedThisRound]）。
     */
    fun <P> sweepPages(
        pages: List<P>,
        manifest: Set<P>,
        nightFresh: Set<P>,
        inFlight: Set<P>,
        nightFailedThisRound: Set<P>,
    ): List<P> = pages.filter {
        it in manifest && it !in nightFresh && it !in inFlight && it !in nightFailedThisRound
    }

    /**
     * 還原持久佇列時，舊版（合併處理 v1 的 debug 版）留下的配對夜讀項要不要留：同章有合併工作 → 丟掉（合併工作會做）；
     * 其餘照一般整章夜讀項還原。
     */
    fun keepRestoredNightEntry(legacyPaired: Boolean, chapterHasCombined: Boolean): Boolean =
        !(legacyPaired && chapterHasCombined)
}

/**
 * hold 記號的檔名慣例（放在章節夾的 `.yakuyomi` 子夾；空檔，只看在不在）。合併輪落地一頁時，覆寫頁檔之前先存好原檔位元組
 * （`OriginalMaterial.exactName`），再寫這個記號：閱讀器看到記號（而且原圖在、這頁還沒有夜讀版）就繼續送原圖，等這頁夜讀結果
 * 確定才換上。
 *  - `<頁檔名>.pairhold`：原圖是正式素材（保留素材或即時翻譯本來就會存），hold 結束只刪記號。
 *  - `<頁檔名>.pairhold-temp`：原圖是為了 hold 暫存的，hold 結束連同原圖一起刪（同頁有完整素材 json 時原圖不刪）。
 * 記號不是夜讀檔、不是規則版本記號、不是原圖素材、也不是素材 json（各自的判斷都不認它，測試有驗）。檔名沿用 v1，舊記號照樣認得。
 */
object PairHoldFiles {
    const val KEEP_SUFFIX = ".pairhold"
    const val TEMP_SUFFIX = ".pairhold-temp"

    /** `012.jpg` → `012.jpg.pairhold`（[tempOriginal]＝false）或 `012.jpg.pairhold-temp`。 */
    fun markerName(pageFileName: String, tempOriginal: Boolean): String =
        pageFileName + if (tempOriginal) TEMP_SUFFIX else KEEP_SUFFIX

    /** 記號檔名 → (頁檔名, tempOriginal)；不是記號（或頁名是空的）→ null。只看結尾，頁名本身含 `.pairhold` 也解得對。 */
    fun parse(name: String): Pair<String, Boolean>? = when {
        name.endsWith(TEMP_SUFFIX) -> name.dropLast(TEMP_SUFFIX.length).takeIf { it.isNotEmpty() }?.let { it to true }
        name.endsWith(KEEP_SUFFIX) -> name.dropLast(KEEP_SUFFIX.length).takeIf { it.isNotEmpty() }?.let { it to false }
        else -> null
    }

    fun isMarker(name: String): Boolean = parse(name) != null

    /** [names]（`.yakuyomi` 內檔名）裡這頁有沒有任一種記號。 */
    fun hasMarker(names: Set<String>, pageFileName: String): Boolean =
        markerName(pageFileName, false) in names || markerName(pageFileName, true) in names
}
