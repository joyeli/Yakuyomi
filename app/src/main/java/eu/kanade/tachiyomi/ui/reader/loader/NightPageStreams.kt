package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.nightread.NightLevel
import eu.kanade.tachiyomi.data.nightread.NightPages
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.service.TranslationPreferences
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
 * reader 顯示端的夜讀：loader 的 stream lambda 每次解碼都經 [open] 決定送夜讀檔還是原圖，並記下**每頁實際送出的檔名**——
 * 換檔位時 [pagesToReload] 拿它跟「新設定下該送的檔」逐頁比，只重畫真的會變的頁（單一真理來源：loader 送了什麼、
 * 就以它為準，VM 不另外猜）。
 *
 * - **有效夜讀模式＝夜讀總開關 AND reader 夜讀模式**（總開關關時一律原圖，免得卡在畫面上沒有入口能關的夜讀頁）。
 * - **檔位**＝偏好 `nightread_fill_level`（[NightLevel.fromPref]：標準／更多，舊值 l1／protect／aggressive 照對應），
 *   每次解碼都讀。
 * - **查找**＝[NightPages.resolveName]：兩檔格式（std／more）優先，其次 2026-10-01 的三檔格式（l1…l3），再其次舊版
 *   單檔；都沒有 → 原圖。已產生的章不必重做。
 * - **檔名快取**（[NightPages.Index]）：`.yakuyomi/` 列一次就重用（非主要儲存上一次 findFile 等於列整個資料夾）。
 *   快取可能過期：這頁在快取裡查不到可送的檔（沒有夜讀檔，或只有產生到一半的孤兒 more／l2／l3）時探一次 std（佇列
 *   現在只產兩檔格式；接住列目錄之後才產出的頁，探到就重列）；開檔失敗（檔已被重新產生刪掉或換掉）先讓快取失效、重列
 *   再找一次，還是不行才退原圖。查得到但已過時（快取時只有 std、重新產生後多了 more）這裡看不出來：由 ReaderViewModel
 *   在這章產生中每做完一頁、以及整章做完時讓快取失效。
 * - 任何例外都吞掉、退原圖：夜讀壞了也絕不影響原圖顯示（§11 不變式）。
 *
 * [chapterDir]＝鬆散章節夾（loader 列頁時設定）；null 時退化用 [open] 的 fallback（頁檔的 parentFile）。
 * 頁檔名每頁算一次就快取（SAF 上取名字是一次查詢）。
 */
internal class NightPageStreams {

    private val readerPreferences: ReaderPreferences by injectLazy()
    private val translationPreferences: TranslationPreferences by injectLazy()

    /** 這章 `.yakuyomi/` 的夜讀檔名快取。 */
    val index = NightPages.Index()

    /** 鬆散章節夾；null＝還沒解析到（或不是鬆散夾）。 */
    @Volatile
    var chapterDir: UniFile? = null

    /**
     * page.index → 這頁最近一次解碼實際送出的夜讀檔名（[ORIGINAL]＝原圖；[PENDING]＝正在開、還不知道送哪個）。
     * 沒解碼過的頁不在表裡。
     */
    private val served = ConcurrentHashMap<Int, String>()

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

    /**
     * 第 [pageIndex] 頁的串流：有效夜讀模式開著且這頁在目前檔位有夜讀檔 → 夜讀檔，否則 [original]。
     * [dirFallback]：[chapterDir] 沒有時的退路（例如頁檔的 parentFile；SAF 文件 URI 拿不到 parent，所以只是退路）。
     */
    fun open(pageIndex: Int, dirFallback: () -> UniFile? = { null }, original: () -> InputStream): InputStream {
        // 一進來就標「開檔中」：讀檔位、探查、開檔可能要幾百 ms（SAF 上 findFile＝列整個資料夾），期間換了檔位時
        // [pagesToReload] 要把這頁算進去（否則第一次解碼的頁不在表裡、被略過，停在舊檔位）
        served[pageIndex] = PENDING
        val night = if (effectiveNight()) {
            try {
                openNight(pageIndex, dirFallback)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "夜讀版讀取失敗，退回原圖" }
                null
            }
        } else {
            null
        }
        served[pageIndex] = night?.first ?: ORIGINAL
        return night?.second ?: original()
    }

    private fun openNight(pageIndex: Int, dirFallback: () -> UniFile?): Pair<String, InputStream>? {
        // 退路找到的資料夾記下來：之後的頁與 [pagesToReload] 都用同一個
        val dir = chapterDir ?: dirFallback()?.takeIf { it.isDirectory }?.also { chapterDir = it } ?: return null
        val page = pageName(pageIndex) ?: return null
        val tier = currentTier()
        var files = index.files(dir)
        if (NightPages.resolveName(files.keys, page, tier) == null) {
            // 這頁在快取裡查不到可送的檔（沒有夜讀檔，或快取剛好抓到產生途中的孤兒 more）：探一次 std（佇列只產兩檔格式），
            // 接住佇列在列目錄之後才產出的頁；探到就重列
            val probe = dir.findFile(NightPages.DIR)?.findFile(NightPages.levelName(page, NightLevel.STANDARD))
            if (probe != null) files = index.refresh(dir)
        }
        var name = NightPages.resolveName(files.keys, page, tier) ?: return null
        files[name]?.let { f -> runCatching { f.openInputStream() }.getOrNull() }?.let { return name to it }
        // 開檔失敗：快取過期（檔被重新產生刪掉、或換成新檔）→ 重列一次再找；仍失敗才退原圖
        files = index.refresh(dir)
        name = NightPages.resolveName(files.keys, page, tier) ?: return null
        return files[name]?.openInputStream()?.let { name to it }
    }

    /**
     * 換檔位（或開關）後要重畫哪些頁：重列一次 `.yakuyomi/`（同時更新 [index]），逐頁比「上次實際送出的檔」與
     * 「目前設定下會送的檔」，只回不同的頁。沒解碼過的頁不回（之後首次解碼本來就讀當時的設定）；正在開檔的頁（[PENDING]）
     * 一律回（它可能讀到的是換之前的設定）。IO，呼叫端在 IO 跑。
     */
    fun pagesToReload(pages: List<ReaderPage>): List<ReaderPage> {
        val on = effectiveNight()
        val tier = currentTier()
        val dir = chapterDir
        val names = if (on && dir != null) index.refresh(dir).keys else emptySet()
        return pages.filter { p ->
            val sent = served[p.index] ?: return@filter false
            if (sent == PENDING) return@filter true
            val want = if (on) pageName(p.index)?.let { NightPages.resolveName(names, it, tier) } else null
            (sent.takeIf { it != ORIGINAL }) != want
        }
    }

    /** 第 [pageIndex] 頁最近一次送出的夜讀檔名；null＝送的是原圖或還沒解碼過。 */
    fun servedNightName(pageIndex: Int): String? = served[pageIndex]?.takeIf { it != ORIGINAL && it != PENDING }

    private companion object {
        /** [served] 裡代表「送的是原圖」（ConcurrentHashMap 不收 null）。 */
        const val ORIGINAL = ""

        /** [served] 裡代表「正在開檔、還不知道送哪個」（不可能是檔名：檔名不含換行）。 */
        const val PENDING = "\n"
    }
}
