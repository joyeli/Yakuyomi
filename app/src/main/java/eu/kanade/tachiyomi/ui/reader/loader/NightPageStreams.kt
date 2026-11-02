package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.nightread.NightLevel
import eu.kanade.tachiyomi.data.nightread.NightPages
import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import eu.kanade.tachiyomi.data.translation.PairHoldFiles
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 能端出夜讀版的 loader（已下載鬆散章 [DownloadPageLoader]、本機資料夾章 [DirectoryPageLoader]，以及包著它們的
 * [TranslatingPageLoader]）。[nightStreams] 為 null＝這章不支援夜讀。
 */
internal interface NightPageSource {
    val nightStreams: NightPageStreams?
}

/**
 * reader 顯示端的夜讀：loader 的 stream lambda 每次解碼都經 [open] 決定送什麼（夜讀檔、頁檔、或合併處理 hold 中的原圖素材）、
 * 顯示時要不要壓暗，並記下**每頁實際送出的狀態**（[ServedState]：種類、夜讀檔名、壓暗倍率）——換檔位、改壓暗設定、選頁補換、
 * 章層級放行時拿它跟「現在該送的」逐頁比，只重畫真的會變的頁（單一真理來源：loader 送了什麼、就以它為準，VM 不另外猜）。
 * 送什麼的規則在 [NightServeDecision]（純函式）。
 *
 * - **有效夜讀模式＝夜讀總開關 AND reader 夜讀模式**（總開關關時不送夜讀檔、也不壓暗，免得卡在畫面上沒有入口能關的夜讀頁）。
 * - **檔位**＝偏好 `nightread_fill_level`（[NightLevel.fromPref]：標準／更多，舊值 l1／protect／aggressive 照對應），
 *   每次解碼都讀。
 * - **查找**＝[NightPages.resolveName]：兩檔格式（std／more）優先，其次 2026-10-01 的三檔格式（l1…l3），再其次舊版
 *   單檔；都沒有 → 頁檔。已產生的章不必重做。
 * - **壓暗（q2）**：有效夜讀模式、「沒有夜讀版的頁先壓暗」開、送的不是夜讀檔 → 記下倍率 `1 − 目前檔位的壓暗值/100`
 *   （標準、更多各自一個值），holder 解碼後用 [displayDim] 取來套在畫面上（頁圖上疊一層半透明黑的前景，
 *   `ReaderPageImageView.setDisplayDim`）。**只影響顯示、絕不寫檔**。
 * - **合併處理的 hold（q3）**：[chapterId] 這章的章層級 hold 生效（[TranslationManager.isHoldActive]），而且這頁有 hold 記號
 *   （`.yakuyomi` 子夾內的 `<頁檔名>.pairhold` 或 `.pairhold-temp`）、原圖素材在、還沒有夜讀版、這個 loader 沒放行過它 →
 *   **白天與夜讀模式都送原圖素材**（`<base>.orig.<副檔名>`），夜讀模式再照上面壓暗。hold 生效的章每次解碼前重列一次
 *   `.yakuyomi`（剛落地的記號可能不在快取裡）。章層級 hold 沒生效時遇到「有記號、有原圖、沒夜讀版」的頁 → 送頁檔並記成
 *   **已放行**（[released]，黏著到 loader 回收、或這頁又被翻譯落地一次（[unreleasePage]）為止）：之後夜讀恢復也不會把畫面上的
 *   譯圖換回原圖。原圖素材開檔失敗 → 退回頁檔。佇列還沒從磁碟還原完時一律當成 hold 生效（見 [chapterHoldActive]）。
 * - **檔名快取**（[NightPages.Index]，內容＝[NightPages.listForReader]）：`.yakuyomi/` 列一次就重用（非主要儲存上一次 findFile
 *   等於列整個資料夾）。快取可能過期：這頁在快取裡查不到可送的夜讀檔時探一次 std（接住列目錄之後才產出的頁，探到就重列）；
 *   開檔失敗（檔已被重新產生刪掉、或頁剛被翻譯覆寫而作廢）先重列、再照新名單決策一次，還是不行才退頁檔。查得到但已過時
 *   （快取時只有 std、重新產生後多了 more）這裡看不出來：由 ReaderViewModel 在夜讀每做完一頁、這章產生中每做完一頁、以及
 *   整章做完時讓快取失效。
 * - **白天、這章 hold 沒生效、佇列裡也沒有它的翻譯＋夜讀合併工作**時完全不列目錄（白天解碼不多花一次查詢）。
 * - 任何例外都吞掉、退頁檔：夜讀壞了也絕不影響原圖顯示（§11 不變式）。
 *
 * [chapterDir]＝鬆散章節夾（loader 列頁時設定）；null 時退化用 [open] 的 fallback（頁檔的 parentFile）。
 * [chapterId]＝這章的 id（ChapterLoader 建好 loader 後設定）；null＝不做 hold 判斷。
 * 頁檔名每頁算一次就快取（SAF 上取名字是一次查詢）。
 */
internal class NightPageStreams {

    private val readerPreferences: ReaderPreferences by injectLazy()
    private val translationPreferences: TranslationPreferences by injectLazy()
    private val translationManager: TranslationManager by injectLazy()

    /** 這章 `.yakuyomi/` 的檔名快取（夜讀檔、規則版本記號、hold 記號、原圖素材）。 */
    val index = NightPages.Index()

    /** 鬆散章節夾；null＝還沒解析到（或不是鬆散夾）。 */
    @Volatile
    var chapterDir: UniFile? = null

    /** 這章的 id（ChapterLoader 建好 loader 後設定，DirectoryPageLoader、DownloadPageLoader 都有）。null＝不做 hold 判斷。 */
    @Volatile
    var chapterId: Long? = null

    /** 這頁實際送了什麼。 */
    enum class Served {
        /** 頁檔本身（可能是原圖，也可能已是譯圖）。 */
        PAGE,

        /** 目前檔位的夜讀檔。 */
        NIGHT,

        /** 合併處理 hold 中：`.yakuyomi` 子夾裡的原圖素材（白天與夜讀模式都送它）。 */
        HELD_ORIGINAL,
    }

    /** 一頁最近一次解碼送出的狀態：[nightName]＝送夜讀檔時的檔名（其餘 null）；[dim]＝顯示時的亮度倍率（1＝不壓暗）。 */
    data class ServedState(val kind: Served, val nightName: String?, val dim: Float)

    /** page.index → 這頁最近一次解碼送出的狀態。沒解碼過的頁不在表裡。 */
    private val servedStates = ConcurrentHashMap<Int, ServedState>()

    /** 正在開檔、還不知道送哪個的頁（讀設定、探查、開檔可能要幾百 ms；SAF 上 findFile＝列整個資料夾）。 */
    private val pending: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** 這個 loader 已放行過的頁（黏著：之後章層級 hold 又生效也不再 hold，見類別說明）。 */
    private val released: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** page.index → 頁檔名（算一次就快取）。 */
    private val pageNames = ConcurrentHashMap<Int, String>()

    /** page.index → 怎麼算頁檔名（loader 列頁時登記；便宜的 lambda，真的要名字時才呼叫）。 */
    private val nameFns = ConcurrentHashMap<Int, () -> String?>()

    /** 有效夜讀模式＝夜讀總開關 AND reader 夜讀模式。 */
    fun effectiveNight(): Boolean =
        translationPreferences.nightReadEnabled.get() && readerPreferences.nightReadMode.get()

    /** 目前要顯示的檔位（偏好 `nightread_fill_level`）。 */
    fun currentTier(): NightLevel = NightLevel.fromPref(translationPreferences.nightReadFillLevel.get())

    /** 登記第 [pageIndex] 頁怎麼取頁檔名（loader 列頁時呼叫）。 */
    fun registerPage(pageIndex: Int, name: () -> String?) {
        nameFns[pageIndex] = name
    }

    private fun pageName(pageIndex: Int): String? {
        pageNames[pageIndex]?.let { return it }
        val n = runCatching { nameFns[pageIndex]?.invoke() }.getOrNull() ?: return null
        pageNames[pageIndex] = n
        return n
    }

    /** 一次解碼／一次比對裡要用到的設定（讀一次，前後一致）。 */
    private class Settings(
        val effective: Boolean,
        val tier: NightLevel,
        val dimEnabled: Boolean,
        val dimPercent: Int,
        /** 這章的章層級 hold 生效（佇列還沒還原完時也當成生效，見 [chapterHoldActive]）。 */
        val holdActive: Boolean,
        /**
         * 每次解碼都要重列 `.yakuyomi`（佇列確定這章 hold 生效：剛落地的 hold 記號可能不在快取裡）。佇列還沒還原完時不必：那時
         * 不會有新落地的頁，列一次的快取就夠（別讓每張頁的解碼都多一次列目錄）。
         */
        val holdRefresh: Boolean,
        /** 這章可能有 hold 記號（hold 生效，或佇列裡有它的翻譯＋夜讀合併工作）：白天也要列目錄判黏著放行。 */
        val holdRelevant: Boolean,
    ) {
        /** 送 [kind] 時的壓暗倍率。 */
        fun dimFor(kind: Served): Float = NightServeDecision.dimMultiplier(effective, dimEnabled, dimPercent, kind)
    }

    private fun settings(): Settings {
        val tier = currentTier()
        val cid = chapterId
        val holdKnown = cid != null && runCatching { translationManager.isHoldActive(cid) }.getOrDefault(false)
        val holdActive = cid != null && (holdKnown || chapterHoldActive(cid))
        return Settings(
            effective = effectiveNight(),
            tier = tier,
            dimEnabled = translationPreferences.nightDimPending.get(),
            dimPercent = dimPercentFor(translationPreferences, tier),
            holdActive = holdActive,
            holdRefresh = holdKnown,
            holdRelevant = holdActive || (cid != null && combinedInQueue(cid)),
        )
    }

    /**
     * 這章的章層級 hold 生效（[TranslationManager.isHoldActive]）。佇列還沒從磁碟還原完（[TranslationManager.restoreSettled]）
     * 時看不出來 → 一律當成生效：有 hold 記號、原圖在、還沒有夜讀版的頁先送原圖、不記成已放行；還原完 ReaderViewModel 會把
     * 不在 hold 的章裡這種頁重畫成譯圖（否則白天先露出譯圖、還原後選頁又翻回原圖；夜讀模式下則被黏著放行、違反等兩樣都好）。
     */
    private fun chapterHoldActive(chapterId: Long): Boolean = runCatching {
        translationManager.isHoldActive(chapterId) || !translationManager.restoreSettled.value
    }.getOrDefault(false)

    /**
     * 佇列裡這章有沒有翻譯＋夜讀的合併工作（翻譯項帶合併意圖、還沒失敗；不加鎖、不做 IO：讀最近一份快照）。只用來決定白天要
     * 不要列目錄判黏著放行——取消、做完、夜讀總開關關、夜讀段放棄時佇列端會刪掉這章的 hold 記號，所以不在佇列的章不必看。
     */
    private fun combinedInQueue(chapterId: Long): Boolean = runCatching {
        translationManager.queueState.value.any { item ->
            item.chapter.id == chapterId &&
                item.kind != TranslationItem.Kind.NIGHT &&
                item.withNight &&
                item.status != TranslationItem.Status.ERROR
        }
    }.getOrDefault(false)

    /**
     * 第 [pageIndex] 頁的串流（送什麼見類別說明與 [NightServeDecision]），同時記下送出的狀態（[servedStates]）。
     * [dirFallback]：[chapterDir] 沒有時的退路（例如頁檔的 parentFile；SAF 文件 URI 拿不到 parent，所以只是退路）。
     * [original]＝頁檔本身。
     */
    fun open(pageIndex: Int, dirFallback: () -> UniFile? = { null }, original: () -> InputStream): InputStream {
        // 一進來就標「開檔中」：期間換了檔位、改了壓暗時 [pagesToReload] 要把這頁算進去（否則第一次解碼的頁不在表裡、被略過，
        // 停在舊設定）
        pending += pageIndex
        try {
            val s = try {
                settings()
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "讀夜讀設定失敗，送頁檔" }
                null
            }
            val picked = if (s == null) {
                null
            } else {
                try {
                    pick(pageIndex, dirFallback, s)
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "夜讀版／原圖素材讀取失敗，退回頁檔" }
                    null
                }
            }
            servedStates[pageIndex] = picked?.first ?: ServedState(Served.PAGE, null, s?.dimFor(Served.PAGE) ?: 1f)
            return picked?.second ?: original()
        } finally {
            pending -= pageIndex
        }
    }

    /**
     * 決定並開檔：回 (送出的狀態, 串流)；串流 null＝送頁檔（呼叫端開 original）。整個 null＝不必判斷（白天、這章沒有 hold 也沒有合併工作），
     * 送頁檔、不壓暗。
     */
    private fun pick(pageIndex: Int, dirFallback: () -> UniFile?, s: Settings): Pair<ServedState, InputStream?>? {
        if (!s.effective && !s.holdRelevant) return null
        // 退路找到的資料夾記下來：之後的頁與 [pagesToReload] 都用同一個
        val dir = chapterDir ?: dirFallback()?.takeIf { it.isDirectory }?.also { chapterDir = it } ?: return null
        val page = pageName(pageIndex) ?: return null
        // hold 生效的章：剛落地的 hold 記號可能不在快取裡 → 每次都重列
        var files = if (s.holdRefresh) index.refresh(dir) else index.files(dir)
        if (s.effective && !s.holdRefresh && NightPages.resolveName(files.keys, page, s.tier) == null) {
            // 這頁在快取裡查不到可送的檔（沒有夜讀檔，或快取剛好抓到產生途中的孤兒 more）：探一次 std（佇列只產兩檔格式），
            // 接住佇列在列目錄之後才產出的頁；探到就重列
            val probe = dir.findFile(NightPages.DIR)?.findFile(NightPages.levelName(page, NightLevel.STANDARD))
            if (probe != null) files = index.refresh(dir)
        }
        for (attempt in 0..1) {
            val d = decideFor(pageIndex, page, files.keys, s)
            if (d.markReleased) released += pageIndex
            val name = when (d.kind) {
                Served.PAGE -> return ServedState(Served.PAGE, null, d.dim) to null
                Served.NIGHT -> NightPages.resolveName(files.keys, page, s.tier)
                Served.HELD_ORIGINAL -> OriginalMaterial.resolve(page, files.keys)?.name
            } ?: break
            val stream = files[name]?.let { f -> runCatching { f.openInputStream() }.getOrNull() }
            if (stream != null) {
                return ServedState(d.kind, name.takeIf { d.kind == Served.NIGHT }, d.dim) to stream
            }
            if (attempt == 0) {
                // 開檔失敗：快取過期（檔被重新產生刪掉、或頁剛被翻譯覆寫而作廢）→ 重列一次、照新名單再決策
                files = index.refresh(dir)
            } else if (d.kind == Served.HELD_ORIGINAL) {
                logcat(LogPriority.WARN) { "合併處理：原圖素材開檔失敗，退回頁檔（$page）" }
            }
        }
        return ServedState(Served.PAGE, null, s.dimFor(Served.PAGE)) to null
    }

    private fun decideFor(pageIndex: Int, page: String, names: Set<String>, s: Settings): NightServeDecision.Result =
        NightServeDecision.decide(
            effectiveNight = s.effective,
            holdActive = s.holdActive,
            hasHoldMarker = PairHoldFiles.hasMarker(names, page),
            hasOriginal = OriginalMaterial.resolve(page, names) != null,
            nightDone = NightPages.resolveName(names, page, NightLevel.STANDARD) != null,
            nightForTier = NightPages.resolveName(names, page, s.tier) != null,
            stickyReleased = pageIndex in released,
            dimEnabled = s.dimEnabled,
            dimPercent = s.dimPercent,
        )

    /**
     * 「現在該送的狀態」（不開檔、不改放行集合）：[names]＝`.yakuyomi` 的檔名（null＝不必看：白天且沒有 hold 也沒有合併工作、或沒有章節夾）。
     */
    private fun want(pageIndex: Int, names: Set<String>?, s: Settings): ServedState {
        val page = if (names != null) pageName(pageIndex) else null
        if (names == null || page == null) return ServedState(Served.PAGE, null, s.dimFor(Served.PAGE))
        val d = decideFor(pageIndex, page, names, s)
        val nightName = if (d.kind == Served.NIGHT) NightPages.resolveName(names, page, s.tier) else null
        return ServedState(d.kind, nightName, d.dim)
    }

    private fun differs(sent: ServedState, want: ServedState): Boolean =
        sent.kind != want.kind || sent.nightName != want.nightName || sent.dim != want.dim

    /**
     * 換檔位、改壓暗設定、開關後要重畫哪些頁：重列一次 `.yakuyomi/`（同時更新 [index]），逐頁比「上次實際送出的狀態」與
     * 「目前設定下會送的狀態」（種類、夜讀檔名、壓暗倍率；標準與更多的壓暗值不同，換檔位時沒有夜讀檔的頁也可能要重畫），
     * 只回不同的頁。沒解碼過的頁不回（之後首次解碼本來就讀當時的設定）；正在開檔的頁一律回（它可能讀到的是換之前的設定）。
     * IO，呼叫端在 IO 跑。
     */
    fun pagesToReload(pages: List<ReaderPage>): List<ReaderPage> {
        val s = settings()
        val dir = chapterDir
        val names = if ((s.effective || s.holdRelevant) && dir != null) index.refresh(dir).keys else null
        return pages.filter { p ->
            if (p.index in pending) return@filter true
            val sent = servedStates[p.index] ?: return@filter false
            differs(sent, want(p.index, names, s))
        }
    }

    /**
     * 選頁補換（ReaderViewModel.onPageSelected，同即時翻譯 gotcha #10：離開畫面的 holder 收不到 reload，變當前頁時補一次）：
     * 「現在該送的」與上次實際送的不同（種類、夜讀檔名、壓暗）→ true。白天、這章沒 hold 時不列目錄：該送的一定是不壓暗的頁檔。
     * 沒解碼過、正在開檔的頁 → false（它解碼時本來就讀當時的狀態）。IO。
     */
    fun needsRefresh(pageIndex: Int): Boolean {
        if (pageIndex in pending) return false
        val sent = servedStates[pageIndex] ?: return false
        val s = settings()
        if (!s.effective && !s.holdActive) return sent.kind != Served.PAGE || sent.dim != 1f
        val dir = chapterDir ?: return false
        val names = if (s.holdRefresh) index.refresh(dir).keys else index.files(dir).keys
        return differs(sent, want(pageIndex, names, s))
    }

    /**
     * 這頁現在解碼會不會被 hold（送原圖素材）：章層級 hold 生效、這個 loader 沒放行過它，而且重列後的 `.yakuyomi` 有它的
     * hold 記號、有原圖素材、還沒有夜讀版。即時翻譯 loader 收到「這頁翻好了」時用它決定要不要換上譯圖。IO（會重列）。
     */
    fun wouldHold(pageIndex: Int): Boolean {
        val cid = chapterId ?: return false
        if (!chapterHoldActive(cid)) return false
        if (pageIndex in released) return false
        val dir = chapterDir ?: return false
        val page = pageName(pageIndex) ?: return false
        val names = index.refresh(dir).keys
        return PairHoldFiles.hasMarker(names, page) &&
            OriginalMaterial.resolve(page, names) != null &&
            NightPages.resolveName(names, page, NightLevel.STANDARD) == null
    }

    /**
     * 畫面上這頁其實是原圖（翻譯落地前解碼的頁檔），而這頁現在被 hold：把上次送出的狀態記成 [Served.HELD_ORIGINAL]（內容
     * 一樣是原圖），章層級放行時 [heldPages] 才會把它算進去重畫成譯圖。沒解碼過的頁不動。
     */
    fun markShowingOriginal(pageIndex: Int) {
        servedStates.computeIfPresent(pageIndex) { _, s -> s.copy(kind = Served.HELD_ORIGINAL, nightName = null) }
    }

    /** 把這頁記成已放行（黏著；這頁的夜讀結果事件到了時呼叫）。 */
    fun releasePage(pageIndex: Int) {
        released += pageIndex
    }

    /**
     * 這頁又被翻譯落地了一次（新的一代：可能帶著新的 hold 記號）：拿掉「已放行」，讓它重新可被 hold。放行只對應當時那一代的
     * hold——不拿掉的話，例如翻譯失敗頁先用原圖做了夜讀版（放行過）、之後重試翻譯成功，這頁會在新夜讀版做好前就露出譯圖。
     * 不會把畫面上的譯圖換回原圖：剛落地時畫面上本來就是舊的（原圖或舊夜讀版）。
     */
    fun unreleasePage(pageIndex: Int) {
        released -= pageIndex
    }

    /** 上次送 [Served.HELD_ORIGINAL] 的頁（章層級放行時重畫它們）。 */
    fun heldPages(): List<Int> = servedStates.entries.filter { it.value.kind == Served.HELD_ORIGINAL }.map { it.key }

    /** 頁檔名 → page.index；不在這章 → null。IO（還沒取過名字的頁要取一次，取過就快取）。 */
    fun indexOfPage(pageFileName: String): Int? {
        pageNames.entries.firstOrNull { it.value == pageFileName }?.let { return it.key }
        for (i in nameFns.keys.sorted()) {
            if (pageNames.containsKey(i)) continue
            if (pageName(i) == pageFileName) return i
        }
        return null
    }

    /** 第 [pageIndex] 頁最近一次送出的狀態；null＝還沒解碼過。 */
    fun served(pageIndex: Int): ServedState? = servedStates[pageIndex]

    /** 第 [pageIndex] 頁最近一次送出時的壓暗倍率；1＝不壓暗，沒解碼過也是 1。 */
    fun servedDim(pageIndex: Int): Float = servedStates[pageIndex]?.dim ?: 1f

    /** 第 [pageIndex] 頁最近一次送出的夜讀檔名；null＝送的不是夜讀檔或還沒解碼過。 */
    fun servedNightName(pageIndex: Int): String? = servedStates[pageIndex]?.nightName

    companion object {
        /** 目前檔位的壓暗值（變暗 %，夾到 0–[NightServeDecision.MAX_DIM_PERCENT]）：標準、更多各自一個偏好。 */
        private fun dimPercentFor(prefs: TranslationPreferences, tier: NightLevel): Int = when (tier) {
            NightLevel.STANDARD -> prefs.nightDimStandard.get()
            NightLevel.MORE -> prefs.nightDimMore.get()
        }.coerceIn(0, NightServeDecision.MAX_DIM_PERCENT)

        /**
         * 沒有 `nightStreams` 的 loader（線上章、壓縮檔章）顯示時的壓暗倍率：同一套條件，只是這頁永遠沒有夜讀檔
         * （有效夜讀模式且壓暗開 → 依目前檔位壓暗）。讀偏好、不做 IO。
         */
        fun plainDim(): Float = runCatching {
            val prefs = Injekt.get<TranslationPreferences>()
            val reader = Injekt.get<ReaderPreferences>()
            val effective = prefs.nightReadEnabled.get() && reader.nightReadMode.get()
            val tier = NightLevel.fromPref(prefs.nightReadFillLevel.get())
            NightServeDecision.dimMultiplier(
                effectiveNight = effective,
                dimEnabled = prefs.nightDimPending.get(),
                dimPercent = dimPercentFor(prefs, tier),
                kind = Served.PAGE,
            )
        }.getOrDefault(1f)

        /**
         * holder 解碼完這頁後要套的壓暗倍率（[eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.setDisplayDim]）：
         * 有 `nightStreams` 的 loader 讀它剛記下的 [servedDim]；沒有的照 [plainDim]。分割頁（[InsertPage]）用 parent 的。
         */
        fun displayDim(page: ReaderPage): Float {
            val p = (page as? InsertPage)?.parent ?: page
            val streams = runCatching { (p.chapter.pageLoader as? NightPageSource)?.nightStreams }.getOrNull()
            return streams?.servedDim(p.index) ?: plainDim()
        }
    }
}
