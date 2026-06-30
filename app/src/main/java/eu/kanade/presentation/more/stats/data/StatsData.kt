package eu.kanade.presentation.more.stats.data

import java.time.LocalDate

sealed interface StatsData {

    data class Overview(
        val libraryMangaCount: Int,
        val completedMangaCount: Int,
        val totalReadDuration: Long,
    ) : StatsData

    data class Titles(
        val globalUpdateItemCount: Int,
        val startedMangaCount: Int,
        val localMangaCount: Int,
    ) : StatsData

    data class Chapters(
        val totalChapterCount: Int,
        val readChapterCount: Int,
        val downloadCount: Int,
    ) : StatsData

    data class Trackers(
        val trackedTitleCount: Int,
        val meanScore: Double,
        val trackerCount: Int,
    ) : StatsData

    /** Yakuyomi：閱讀的每日時間統計（依 history.last_read 按日分桶；可回填舊資料）。累計值仍由既有 Chapters section 提供。 */
    data class Reading(
        val days: List<Day>,
    ) : StatsData {
        // mangaIds＝當日讀過的不重複作品（畫面端對期間取 union 才是期間真 distinct；每日 distinct 加總會重複計）。
        data class Day(
            val date: LocalDate,
            val chapters: Int,
            val mangaIds: List<Long>,
        )
    }

    /** Yakuyomi：翻譯統計。累計總量 + 每日明細（畫面端依 period 過濾聚合，不分時段、只記不計價）。 */
    data class Translation(
        val translatedChapters: Int,
        val translatedPages: Int,
        val promptTokens: Long,
        val completionTokens: Long,
        val days: List<Day>,
    ) : StatsData {
        data class Day(
            val date: LocalDate,
            val chapters: Int,
            val pages: Int,
            val promptTokens: Long,
            val completionTokens: Long,
        )
    }
}
