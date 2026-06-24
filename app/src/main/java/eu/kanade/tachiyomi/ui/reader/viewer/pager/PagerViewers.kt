package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.ReaderActivity

/**
 * Implementation of a left to right PagerViewer.
 */
class L2RPagerViewer(activity: ReaderActivity) : PagerViewer(activity) {
    /**
     * Creates a new left to right pager.
     */
    override fun createPager(): Pager {
        return Pager(activity)
    }
}

/**
 * Implementation of a right to left PagerViewer.
 */
class R2LPagerViewer(activity: ReaderActivity) : PagerViewer(activity) {

    override val isRtl get() = true

    /**
     * Creates a new right to left pager.
     */
    override fun createPager(): Pager {
        return Pager(activity)
    }

    /**
     * Moves to the next page. On a R2L pager the next page is the one at the left.
     */
    override fun moveToNext() {
        moveLeft()
    }

    /**
     * Moves to the previous page. On a R2L pager the previous page is the one at the right.
     */
    override fun moveToPrevious() {
        moveRight()
    }
}

/**
 * Yakuyomi：右至左「對開」PagerViewer——兩頁並排、右至左閱讀（manga 對開）。行為同 R2L，外加對開旗標
 * 讓 adapter 配對版面並用 [PagerDoublePageHolder]。
 */
class R2LDoublePagerViewer(activity: ReaderActivity) : PagerViewer(activity) {

    override val isRtl get() = true

    override val isDoublePage get() = true

    override fun createPager(): Pager {
        return Pager(activity)
    }

    override fun moveToNext() {
        moveLeft()
    }

    override fun moveToPrevious() {
        moveRight()
    }
}

/**
 * Yakuyomi：左至右「對開」PagerViewer——兩頁並排、左至右閱讀（西式漫畫對開）。
 */
class L2RDoublePagerViewer(activity: ReaderActivity) : PagerViewer(activity) {

    override val isDoublePage get() = true

    override fun createPager(): Pager {
        return Pager(activity)
    }
}

/**
 * Implementation of a vertical (top to bottom) PagerViewer.
 */
class VerticalPagerViewer(activity: ReaderActivity) : PagerViewer(activity) {
    /**
     * Creates a new vertical pager.
     */
    override fun createPager(): Pager {
        return Pager(activity, isHorizontal = false)
    }
}
