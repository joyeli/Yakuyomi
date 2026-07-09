package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * Yakuyomi：對開（double-page）模式下的一個版面單位＝並排的兩頁（[second] 為 null＝單獨一頁，如封面）。
 * [first] 永遠是閱讀順序較前（page number 較小）的那頁；左右擺放由 holder 依方向決定。
 *
 * **data class（值相等）是必要的**：`pairItems` 每次 `setChapters` 都產生新的 PagePair 實例；ViewPager 靠
 * `PagerViewerAdapter.getItemPosition → items.indexOf(holder.item)` 保留當前 view。若用參考相等，重配後
 * indexOf 找不到舊實例＝POSITION_NONE，當前 spread 被丟棄、`currentItem` 整數指到位移後的另一個 spread
 * （＝換話/非同步載入時「第一頁閃一下就跳到第二頁」的真因）。值相等後 indexOf 找得回同一 spread（跨重配
 * [ReaderPage] 實例穩定），與單頁模式一致。[representative] 是 computed，不參與 equals/hashCode。
 */
data class PagePair(
    val first: ReaderPage,
    val second: ReaderPage?,
) {
    /** 進度/頁碼代表頁＝較後那頁（讓「讀到這」前進到 pair 的最後一頁）。 */
    val representative: ReaderPage get() = second ?: first
}
