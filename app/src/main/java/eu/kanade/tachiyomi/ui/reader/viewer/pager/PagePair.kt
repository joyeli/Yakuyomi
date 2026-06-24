package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * Yakuyomi：對開（double-page）模式下的一個版面單位＝並排的兩頁（[second] 為 null＝單獨一頁，如封面）。
 * [first] 永遠是閱讀順序較前（page number 較小）的那頁；左右擺放由 holder 依方向決定。
 */
class PagePair(
    val first: ReaderPage,
    val second: ReaderPage?,
) {
    /** 進度/頁碼代表頁＝較後那頁（讓「讀到這」前進到 pair 的最後一頁）。 */
    val representative: ReaderPage get() = second ?: first
}
