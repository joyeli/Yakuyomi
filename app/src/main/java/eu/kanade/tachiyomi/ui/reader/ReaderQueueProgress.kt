package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.translation.CombinedNight
import eu.kanade.tachiyomi.data.translation.model.QueuePoolKey
import eu.kanade.tachiyomi.data.translation.model.TranslationItem

// 佇列快照 → 閱讀器當前章的兩個進度（角落「翻譯中 X/Y」與夜讀懸浮鈕的進度環），純函式（JVM 單元測試見 ReaderQueueProgressTest）。
// 兩個指示器讀同一份來源：翻譯＋夜讀的合併工作只有一個進度（兩樣都好了的頁數），角落與進度環的 X/Y 一樣。

/**
 * [item] 是會一併產生這章夜讀版的合併工作（翻譯項帶合併意圖，而且排隊中、或這一輪正在一併產生；只翻譯的輪與失敗項不算，
 * 見 [CombinedNight.covers]），而且夜讀總開關開著（[nightEnabled]）。
 */
private fun isCombinedJob(item: TranslationItem, nightEnabled: Boolean): Boolean =
    nightEnabled && item.kind != TranslationItem.Kind.NIGHT &&
        CombinedNight.covers(item.nightLink, item.status, item.nightActive)

/**
 * 當前章（[chapterId]）在翻譯佇列的進度（角落小指示器）：找翻譯／重繪項（夜讀項不是翻譯，不算——否則同章排著夜讀會誤亮
 * 「翻譯中」）。排隊中＝[ReaderViewModel.LiveTranslateProgress.queued]；失敗不顯示（null，失敗在章節清單／佇列頁處理）；
 * 合併工作（[isCombinedJob]）＝[ReaderViewModel.LiveTranslateProgress.withNight]（翻譯中改顯示「翻譯＋夜讀中」）。
 */
internal fun liveTranslateProgressOf(
    queue: List<TranslationItem>,
    chapterId: Long?,
    nightEnabled: Boolean,
): ReaderViewModel.LiveTranslateProgress? {
    if (chapterId == null) return null
    val item = queue.firstOrNull { it.chapter.id == chapterId && it.kind != TranslationItem.Kind.NIGHT } ?: return null
    return when (item.status) {
        TranslationItem.Status.QUEUE, TranslationItem.Status.TRANSLATING -> ReaderViewModel.LiveTranslateProgress(
            done = item.done,
            total = item.total,
            queued = item.status == TranslationItem.Status.QUEUE,
            withNight = isCombinedJob(item, nightEnabled),
        )
        TranslationItem.Status.ERROR -> null
    }
}

/**
 * 當前章（[chapterId]）的夜讀進度（懸浮鈕進度環、長按選單「產生」鈕的顯示條件、點灰色檔位鈕的提示），依序：
 *  - 這章有**正在產生**的夜讀項 → 照它（真的在跑的是它）。
 *  - 這章的翻譯項是合併工作（[isCombinedJob]）→ 照它（[ReaderViewModel.NightProgress.combined]）：進度＝兩樣都好了的
 *    頁數（與角落指示器同一個數）；paused＝排隊中且被翻譯那邊的三層暫停任一層 hold 住（合併工作在翻譯 pool，夜讀 pool 的
 *    暫停不影響它）。同章還排著的夜讀項（只剩「以目前設定重新產生」會跟合併工作並存）等它做完才跑，不蓋過它。
 *  - 這章有排隊中的夜讀項（失敗不算：讓產生鈕成為重試入口）→ 照它；[ReaderViewModel.NightProgress.paused]＝被夜讀那邊的
 *    三層暫停任一層 hold 住（夜讀 pool、這本 × 夜讀、這一話）。等合併工作做完的夜讀項就是排隊中。
 *  - 都沒有 → null。
 */
internal fun nightProgressOf(
    queue: List<TranslationItem>,
    chapterId: Long?,
    nightEnabled: Boolean,
    translatePaused: Boolean,
    nightPaused: Boolean,
    pausedMangas: Set<QueuePoolKey>,
): ReaderViewModel.NightProgress? {
    if (chapterId == null) return null
    val night = queue.firstOrNull {
        it.kind == TranslationItem.Kind.NIGHT && it.chapter.id == chapterId &&
            it.status != TranslationItem.Status.ERROR
    }
    fun ofNight(night: TranslationItem) = ReaderViewModel.NightProgress(
        done = night.done,
        total = night.total,
        running = night.status == TranslationItem.Status.TRANSLATING,
        paused = night.status == TranslationItem.Status.QUEUE &&
            (nightPaused || night.paused || night.poolKey in pausedMangas),
    )
    if (night != null && night.status == TranslationItem.Status.TRANSLATING) return ofNight(night)
    // 跟 [liveTranslateProgressOf] 看同一項（這章第一個翻譯／重繪項），兩個指示器的 X/Y 才會一致
    val combined = queue.firstOrNull { it.chapter.id == chapterId && it.kind != TranslationItem.Kind.NIGHT }
        ?.takeIf { isCombinedJob(it, nightEnabled) }
        ?: return night?.let(::ofNight)
    return ReaderViewModel.NightProgress(
        done = combined.done,
        total = combined.total,
        running = combined.status == TranslationItem.Status.TRANSLATING,
        paused = combined.status == TranslationItem.Status.QUEUE &&
            (translatePaused || combined.paused || combined.poolKey in pausedMangas),
        combined = true,
    )
}
