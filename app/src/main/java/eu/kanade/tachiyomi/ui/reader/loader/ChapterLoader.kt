package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 */
class ChapterLoader(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val manga: Manga,
    private val source: Source,
) {

    // 即時翻譯（reader 邊讀邊翻）所需：偏好（開關 + 分類過濾）+ 常駐引擎服務（就緒檢查）。Injekt 取 process singleton。
    private val translationPreferences: TranslationPreferences = Injekt.get()
    private val translationEngineService: TranslationEngineService = Injekt.get()
    private val translationManager: TranslationManager = Injekt.get()

    // 即時翻譯分類過濾：用書庫分類（包含/排除）決定哪些書要即時翻（鏡射下載「新章分類」）。suspend 取分類。
    private val getCategories: GetCategories = Injekt.get()

    /**
     * Assigns the chapter's page loader and loads the its pages. Returns immediately if the chapter
     * is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter) {
        if (chapterIsReady(chapter)) {
            return
        }

        chapter.state = ReaderChapter.State.Loading
        withIOContext {
            logcat { "Loading pages for ${chapter.chapter.name}" }
            try {
                val loader = getPageLoader(chapter)
                chapter.pageLoader = loader

                val pages = loader.getPages()
                    .onEach { it.chapter = chapter }

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }

                // If the chapter is partially read, set the starting page to the last the user read
                // otherwise use the requested page.
                if (!chapter.chapter.read) {
                    chapter.requestedPage = chapter.chapter.last_page_read
                }

                chapter.state = ReaderChapter.State.Loaded(pages)
            } catch (e: Throwable) {
                chapter.state = ReaderChapter.State.Error(e)
                throw e
            }
        }
    }

    /**
     * Checks [chapter] to be loaded based on present pages and loader in addition to state.
     */
    private fun chapterIsReady(chapter: ReaderChapter): Boolean {
        return chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null
    }

    /**
     * Returns the page loader to use for this [chapter].
     *
     * suspend：[shouldTranslateLive] 的分類過濾需 `getCategories.await`（suspend）。唯一呼叫端是 [loadChapter]
     * 的 `withIOContext` 區塊，已在 suspend 環境，故改 suspend 無副作用。
     */
    private suspend fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val dbChapter = chapter.chapter
        val isDownloaded = downloadManager.isChapterDownloaded(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            manga.source,
            skipCache = true,
        )
        val base = when {
            isDownloaded -> DownloadPageLoader(
                chapter,
                manga,
                source,
                downloadManager,
                downloadProvider,
            )
            source is LocalSource -> source.getFormat(chapter.chapter).let { format ->
                when (format) {
                    is Format.Directory -> DirectoryPageLoader(format.file)
                    is Format.Archive -> ArchivePageLoader(format.file.archiveReader(context))
                    is Format.Epub -> EpubPageLoader(format.file.epubReader(context))
                }
            }
            source is HttpSource -> HttpPageLoader(chapter, source)
            source is StubSource -> error(context.stringResource(MR.strings.source_not_installed, source.toString()))
            else -> error(context.stringResource(MR.strings.loader_not_implemented_error))
        }
        // 即時翻譯：符合條件（**只含已下載章**）就把 base loader 包進 [TranslatingPageLoader]（整章排入翻譯佇列、
        // loader 只當顯示層）；否則照常用 base。線上（未下載）章**不**包裝（走原生 [HttpPageLoader]、顯示線上原圖、
        // 不自動下載/翻譯）；線上的即時翻改由 [eu.kanade.tachiyomi.ui.reader.ReaderViewModel] 對「正在讀的章」
        // 下載 + 完成後重載進此已下載路徑（reload 後章變已下載 → 此處才包裝）。
        // TranslatingPageLoader 需 manga/source/downloadProvider 解析下載章目錄與逐頁檔（查 manifest + 換頁）、
        // 並需 translationManager 把整章排入受管理佇列（背景翻、清單顯示翻譯中、可暫停/取消/重試）。
        return if (shouldTranslateLive(chapter, isDownloaded)) {
            TranslatingPageLoader(base, chapter, manga, source, downloadProvider, translationManager)
        } else {
            base
        }
    }

    /**
     * 是否該對這章「即時翻譯」（reader 邊讀邊翻）。**只包已下載章**，全部條件 AND：
     *  - 章**已下載**（[isDownloaded]）。線上（未下載）章一律不包（見下）。
     *  - 即時翻譯開關開（[TranslationPreferences.liveTranslate]）。
     *  - 引擎就緒（[TranslationEngineService.isReady]＝key + 3 模型齊；**不再**綁「下載時翻譯章節」開關）。
     *  - 本書的書庫分類通過「即時翻譯分類」過濾（包含/排除，鏡射下載新章分類；都不設＝全部）。
     *  - 章**尚未**整章翻好（[TranslationManager.isTranslated]）；已翻好的章交給 [DownloadPageLoader] 直接吐已覆蓋的譯圖，
     *    不必再即時翻一次（省 ~100MB 引擎 + 每頁推論）。
     *
     * **線上路徑（未下載）改走「下載 + 重載」**：未下載章**不再**包進 [TranslatingPageLoader]（舊版的同 session
     * 串流改指不可靠）。線上的即時翻改由 [eu.kanade.tachiyomi.ui.reader.ReaderViewModel] 處理——只對「正在讀的章」
     * 觸發下載，下載完成後重載該章（章變已下載 → 此處才包進 [TranslatingPageLoader] 走可靠的已下載路徑）。
     *
     * **分類過濾邏輯**抽成共用 helper [categoryAllowed]，與 [ReaderViewModel] 的線上判定共用同一份語義。
     */
    suspend fun shouldTranslateLive(chapter: ReaderChapter, isDownloaded: Boolean): Boolean {
        if (!isDownloaded) return false
        if (!translationPreferences.translationMasterEnabled.get()) return false
        if (!translationPreferences.liveTranslate.get()) return false
        if (!translationEngineService.isReady()) return false
        if (!autoTranslateAllowed()) return false

        // 已整章翻好 → 不即時翻（DownloadPageLoader 直接服務已覆蓋的譯圖）。toDomainChapter null（無 id）時保守不即時翻。
        val domainChapter = chapter.chapter.toDomainChapter() ?: return false
        if (translationManager.isTranslated(manga, domainChapter)) return false
        return true
    }

    /**
     * 「自動翻譯」是否該套用到本書（即時翻 + 線上自動翻共用）。兩道過濾 AND：
     *  - **來源排除**（per-source，[TranslationPreferences.translationSourcesExclude]）：命中＝不翻（全域硬排除）。
     *  - **分類過濾**（包含/排除，語義對齊下載「新章分類」）：包含非空→書至少屬其一才翻；命中任一排除→不翻；都空→全部翻。
     * 抽成 public 方法供 [shouldTranslateLive]（已下載）與
     * [eu.kanade.tachiyomi.ui.reader.ReaderViewModel] 的線上即時翻 gate 共用同一份語義
     * （[ReaderViewModel] 持有同一個 [ChapterLoader] 實例，直接呼叫此方法）。手動翻不查此。
     */
    suspend fun autoTranslateAllowed(): Boolean {
        if (translationManager.isSourceExcluded(manga)) return false
        val mangaCats = getCategories.await(manga.id).map { it.id.toString() }.toSet()
        val include = translationPreferences.liveTranslateCategories.get()
        val exclude = translationPreferences.liveTranslateCategoriesExclude.get()
        return (include.isEmpty() || mangaCats.any { it in include }) && mangaCats.none { it in exclude }
    }

    /**
     * 引擎是否就緒（key + 3 模型；**不含**「下載時翻譯章節」總開關）。與 [shouldTranslateLive] 用同一判定，
     * 供 [eu.kanade.tachiyomi.ui.reader.ReaderViewModel] 線上即時翻 gate 共用——避免線上路徑誤用含 translationEnabled
     * 的 [TranslationManager.isReady] 而把「只開即時翻、沒開下載時翻」的使用者擋成「未啟動」。
     */
    fun engineReady(): Boolean = translationEngineService.isReady()
}
