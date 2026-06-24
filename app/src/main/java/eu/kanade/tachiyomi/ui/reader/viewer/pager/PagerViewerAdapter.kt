package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import tachiyomi.core.common.util.system.logcat

/**
 * Pager adapter used by this [viewer] to where [ViewerChapters] updates are posted.
 */
class PagerViewerAdapter(private val viewer: PagerViewer) : ViewPagerAdapter() {

    /**
     * List of currently set items.
     */
    var items: MutableList<Any> = mutableListOf()
        private set

    /**
     * Holds preprocessed items so they don't get removed when changing chapter
     */
    private var preprocessed: MutableMap<Int, InsertPage> = mutableMapOf()

    var nextTransition: ChapterTransition.Next? = null
        private set

    var currentChapter: ReaderChapter? = null

    /**
     * Context that has been wrapped to use the correct theme values based on the
     * current app theme and reader background color
     */
    private var readerThemedContext = viewer.activity.createReaderThemeContext()

    /**
     * Updates this adapter with the given [chapters]. It handles setting a few pages of the
     * next/previous chapter to allow seamless transitions and inverting the pages if the viewer
     * has R2L direction.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean) {
        val newItems = mutableListOf<Any>()

        // Forces chapter transition if there is missing chapters
        val prevHasMissingChapters = calculateChapterGap(chapters.currChapter, chapters.prevChapter) > 0
        val nextHasMissingChapters = calculateChapterGap(chapters.nextChapter, chapters.currChapter) > 0

        // Add previous chapter pages and transition
        chapters.prevChapter?.pages?.let(newItems::addAll)

        // Skip transition page if the chapter is loaded & current page is not a transition page
        if (prevHasMissingChapters || forceTransition || chapters.prevChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
        }

        var insertPageLastPage: InsertPage? = null

        // Add current chapter.
        val currPages = chapters.currChapter.pages
        if (currPages != null) {
            val pages = currPages.toMutableList()

            val lastPage = pages.last()

            // Insert preprocessed pages into current page list
            preprocessed.keys.sortedDescending()
                .forEach { key ->
                    if (lastPage.index == key) {
                        insertPageLastPage = preprocessed[key]
                    }
                    preprocessed[key]?.let { pages.add(key + 1, it) }
                }

            newItems.addAll(pages)
        }

        currentChapter = chapters.currChapter

        // Add next chapter transition and pages.
        nextTransition = ChapterTransition.Next(chapters.currChapter, chapters.nextChapter)
            .also {
                if (
                    nextHasMissingChapters ||
                    forceTransition ||
                    chapters.nextChapter?.state !is ReaderChapter.State.Loaded
                ) {
                    newItems.add(it)
                }
            }

        chapters.nextChapter?.pages?.let(newItems::addAll)

        // Resets double-page splits, else insert pages get misplaced
        items.filterIsInstance<InsertPage>().also { items.removeAll(it) }

        // Yakuyomi：對開模式把同章連續頁配對成版面（封面單獨、不跨章）。
        val assembled = if (viewer.isDoublePage) pairItems(newItems) else newItems

        if (viewer.isRtl) {
            assembled.reverse()
        }

        preprocessed = mutableMapOf()
        items = assembled
        notifyDataSetChanged()

        // Will skip insert page otherwise
        if (insertPageLastPage != null) {
            viewer.moveToPage(insertPageLastPage)
        }
    }

    /**
     * Returns the amount of items of the adapter.
     */
    override fun getCount(): Int {
        return items.size
    }

    /**
     * Creates a new view for the item at the given [position].
     */
    override fun createView(container: ViewGroup, position: Int): View {
        return when (val item = items[position]) {
            is PagePair -> PagerDoublePageHolder(readerThemedContext, viewer, item)
            is ReaderPage -> PagerPageHolder(readerThemedContext, viewer, item)
            is ChapterTransition -> PagerTransitionHolder(readerThemedContext, viewer, item)
            else -> throw NotImplementedError("Holder for ${item.javaClass} not implemented")
        }
    }

    /** Yakuyomi：對開「位移（shift）」的章 id 集合——使用者按 shift 鈕，把該章的配對起點位移一頁，對齊跨頁。 */
    private val shiftedChapters = HashSet<Long>()

    /** 切換某章的對開位移。 */
    fun toggleShift(chapterId: Long) {
        if (!shiftedChapters.add(chapterId)) shiftedChapters.remove(chapterId)
    }

    /**
     * Yakuyomi：本身是寬圖/跨頁（載入時偵測為 isWideImage）的頁——配對時讓它單獨佔整版、不跟鄰頁併接。
     * 用穩定鍵 (chapterId, page.index)（對齊 [shiftedChapters]），避免 ReaderPage 物件跨 reload/換章失配。
     */
    private val fullPageKeys = HashSet<Pair<Long?, Int>>()

    private fun isFullPage(page: ReaderPage) = (page.chapter.chapter.id to page.index) in fullPageKeys

    /** 標記一頁為寬圖（單獨佔版）。回傳是否為新標記（無新標記＝不需重配，避免無謂重建）。 */
    fun markFullPage(page: ReaderPage): Boolean = fullPageKeys.add(page.chapter.chapter.id to page.index)

    /**
     * Yakuyomi：對開模式把同章連續頁配對成 [PagePair]（章內第一頁＝封面單獨、不跨章配對、不配對同 index）。
     * 該章若被 shift（[shiftedChapters]）＝封面後第一頁也單獨，把之後的配對整體位移一頁（對齊被拆開的跨頁）。
     */
    private fun pairItems(source: List<Any>): MutableList<Any> {
        val result = mutableListOf<Any>()
        var i = 0
        while (i < source.size) {
            val item = source[i]
            if (item !is ReaderPage) {
                result.add(item)
                i++
                continue
            }
            // 封面單獨；shift 的章＝封面後第一頁(index 1)也單獨，使後續配對位移一頁；寬圖本身單獨佔整版。
            val shifted = item.chapter.chapter.id in shiftedChapters
            if (item.index == 0 || (shifted && item.index == 1) || isFullPage(item)) {
                result.add(PagePair(item, null))
                i++
                continue
            }
            val next = source.getOrNull(i + 1) as? ReaderPage
            if (next != null &&
                next.chapter == item.chapter &&
                next.index != 0 &&
                next.index != item.index && // 不配對同 index（避免 InsertPage/重複頁配成自己跟自己）
                !isFullPage(next) // 寬圖不被當配對的另一半
            ) {
                result.add(PagePair(item, next))
                i += 2
            } else {
                result.add(PagePair(item, null))
                i++
            }
        }
        return result
    }

    /** Yakuyomi：找出含 [page] 的 item 位置（對開時 page 被包在 [PagePair] 裡，indexOf 找不到）。 */
    fun positionOf(page: ReaderPage): Int = items.indexOfFirst { item ->
        item == page || (item is PagePair && (item.first == page || item.second == page))
    }

    /**
     * Returns the current position of the given [view] on the adapter.
     */
    override fun getItemPosition(view: Any): Int {
        if (view is PositionableView) {
            val position = items.indexOf(view.item)
            if (position != -1) {
                return position
            } else {
                logcat { "Position for ${view.item} not found" }
            }
        }
        return POSITION_NONE
    }

    fun onPageSplit(currentPage: Any?, newPage: InsertPage) {
        if (currentPage !is ReaderPage) return

        val currentIndex = items.indexOf(currentPage)

        // Put aside preprocessed pages for next chapter so they don't get removed when changing chapter
        if (currentPage.chapter.chapter.id != currentChapter?.chapter?.id) {
            preprocessed[newPage.index] = newPage
            return
        }

        val placeAtIndex = when (viewer) {
            is L2RPagerViewer,
            is VerticalPagerViewer,
            -> currentIndex + 1
            else -> currentIndex
        }

        // It will enter a endless cycle of insert pages
        if (viewer is R2LPagerViewer && placeAtIndex - 1 >= 0 && items[placeAtIndex - 1] is InsertPage) {
            return
        }

        // Same here it will enter a endless cycle of insert pages
        if (items[placeAtIndex] is InsertPage) {
            return
        }

        items.add(placeAtIndex, newPage)

        notifyDataSetChanged()
    }

    fun cleanupPageSplit() {
        val insertPages = items.filterIsInstance<InsertPage>()
        items.removeAll(insertPages)
        notifyDataSetChanged()
    }

    fun refresh() {
        readerThemedContext = viewer.activity.createReaderThemeContext()
    }
}
