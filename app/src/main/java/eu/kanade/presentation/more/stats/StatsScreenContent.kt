package eu.kanade.presentation.more.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.stats.components.StatsItem
import eu.kanade.presentation.more.stats.components.StatsOverviewItem
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.presentation.util.toDurationString
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.time.LocalDate
import java.util.Locale
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Composable
fun StatsScreenContent(
    state: StatsScreenState.Success,
    paddingValues: PaddingValues,
) {
    LazyColumn(
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        item {
            OverviewSection(state.overview)
        }
        item {
            TitlesStats(state.titles)
        }
        item {
            ChapterStats(state.chapters)
        }
        item {
            TrackerStats(state.trackers)
        }
        item {
            ReadingStats(state.reading)
        }
        item {
            TranslationStats(state.translation)
        }
    }
}

@Composable
private fun LazyItemScope.OverviewSection(
    data: StatsData.Overview,
) {
    val none = stringResource(MR.strings.none)
    val context = LocalContext.current
    val readDurationString = remember(data.totalReadDuration) {
        data.totalReadDuration
            .toDuration(DurationUnit.MILLISECONDS)
            .toDurationString(context, fallback = none)
    }
    SectionCard(MR.strings.label_overview_section) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            StatsOverviewItem(
                title = data.libraryMangaCount.toString(),
                subtitle = stringResource(MR.strings.in_library),
                icon = Icons.Outlined.CollectionsBookmark,
            )
            StatsOverviewItem(
                title = readDurationString,
                subtitle = stringResource(MR.strings.label_read_duration),
                icon = Icons.Outlined.Schedule,
            )
            StatsOverviewItem(
                title = data.completedMangaCount.toString(),
                subtitle = stringResource(MR.strings.label_completed_titles),
                icon = Icons.Outlined.LocalLibrary,
            )
        }
    }
}

@Composable
private fun LazyItemScope.TitlesStats(
    data: StatsData.Titles,
) {
    SectionCard(MR.strings.label_titles_section) {
        Row {
            StatsItem(
                data.globalUpdateItemCount.toString(),
                stringResource(MR.strings.label_titles_in_global_update),
            )
            StatsItem(
                data.startedMangaCount.toString(),
                stringResource(MR.strings.label_started),
            )
            StatsItem(
                data.localMangaCount.toString(),
                stringResource(MR.strings.label_local),
            )
        }
    }
}

@Composable
private fun LazyItemScope.ChapterStats(
    data: StatsData.Chapters,
) {
    SectionCard(MR.strings.chapters) {
        Row {
            StatsItem(
                data.totalChapterCount.toString(),
                stringResource(MR.strings.label_total_chapters),
            )
            StatsItem(
                data.readChapterCount.toString(),
                stringResource(MR.strings.label_read_chapters),
            )
            StatsItem(
                data.downloadCount.toString(),
                stringResource(MR.strings.label_downloaded),
            )
        }
    }
}

@Composable
private fun LazyItemScope.TrackerStats(
    data: StatsData.Trackers,
) {
    val notApplicable = stringResource(MR.strings.not_applicable)
    val meanScoreStr = remember(data.trackedTitleCount, data.meanScore) {
        if (data.trackedTitleCount > 0 && !data.meanScore.isNaN()) {
            // All other numbers are localized in English
            "%.2f ★".format(Locale.ENGLISH, data.meanScore)
        } else {
            notApplicable
        }
    }
    SectionCard(MR.strings.label_tracker_section) {
        Row {
            StatsItem(
                data.trackedTitleCount.toString(),
                stringResource(MR.strings.label_tracked_titles),
            )
            StatsItem(
                meanScoreStr,
                stringResource(MR.strings.label_mean_score),
            )
            StatsItem(
                data.trackerCount.toString(),
                stringResource(MR.strings.label_used),
            )
        }
    }
}

/** 時間維度選擇（以日為計數單位、由每日 raw 聚合）。All＝全部累計。 */
private enum class StatsPeriod(val labelRes: StringResource, val days: Int?) {
    Today(MR.strings.label_stats_period_today, 1),
    Week(MR.strings.label_stats_period_week, 7),
    Month(MR.strings.label_stats_period_month, 30),
    All(MR.strings.label_stats_period_all, null),
    ;

    /** 區間起日（含）；All＝null＝不限。 */
    fun fromDate(today: LocalDate): LocalDate? = days?.let { today.minusDays((it - 1).toLong()) }
}

private const val MAX_DAY_ROWS = 30

@Composable
private fun LazyItemScope.TranslationStats(
    data: StatsData.Translation,
) {
    // rememberSaveable：LazyColumn 的 item 捲出視野會被回收，用 remember 會重置成預設 7 天；
    // rememberSaveable 由 item 的 saveable state holder 保留，捲回來仍是使用者選的分頁。
    var period by rememberSaveable { mutableStateOf(StatsPeriod.Week) }
    val today = remember { LocalDate.now() }
    val filtered = remember(period, data.days) {
        val from = period.fromDate(today)
        data.days.filter { from == null || !it.date.isBefore(from) }
    }
    val chapters = filtered.sumOf { it.chapters }
    val pages = filtered.sumOf { it.pages }
    val prompt = filtered.sumOf { it.promptTokens }
    val completion = filtered.sumOf { it.completionTokens }

    SectionCard(MR.strings.label_translation_section) {
        // 時間維度切換（今天 / 近 7 天 / 近 30 天 / 全部）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            StatsPeriod.entries.forEach { p ->
                FilterChip(
                    selected = p == period,
                    onClick = { period = p },
                    label = {
                        Text(
                            text = stringResource(p.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(MaterialTheme.padding.small))

        // 區間摘要：章 / 頁 / Token 總計。
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            StatsItem(chapters.toString(), stringResource(MR.strings.label_translated_chapters))
            StatsItem(pages.toString(), stringResource(MR.strings.label_translated_pages))
            StatsItem(formatCount(prompt + completion), stringResource(MR.strings.label_tokens_used))
        }
        Text(
            text = stringResource(MR.strings.label_token_breakdown, formatCount(prompt), formatCount(completion)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
        )

        // 每日長條（頁數比例）；空＝提示。
        if (filtered.isEmpty()) {
            Text(
                text = stringResource(MR.strings.info_translation_stats_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MaterialTheme.padding.small),
            )
        } else {
            val maxPages = filtered.maxOf { it.pages }.coerceAtLeast(1)
            Column(
                modifier = Modifier.padding(top = MaterialTheme.padding.small),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                // 最近 MAX_DAY_ROWS 天、新到舊。
                filtered.takeLast(MAX_DAY_ROWS).asReversed().forEach { day ->
                    StatDayBar(
                        dateLabel = "${day.date.monthValue}/${day.date.dayOfMonth}",
                        fraction = (day.pages.toFloat() / maxPages).coerceIn(0f, 1f),
                        trailing = day.pages.toString(),
                    )
                }
            }
        }
    }
}

/** 通用每日長條：日期標籤 + 比例條 + 尾端數值。翻譯/閱讀統計共用。 */
@Composable
private fun StatDayBar(
    dateLabel: String,
    fraction: Float,
    trailing: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dateLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceAtLeast(0.03f))
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(36.dp)
                .padding(start = MaterialTheme.padding.extraSmall),
        )
    }
}

@Composable
private fun LazyItemScope.ReadingStats(
    data: StatsData.Reading,
) {
    // rememberSaveable：LazyColumn 的 item 捲出視野會被回收，用 remember 會重置成預設 7 天；
    // rememberSaveable 由 item 的 saveable state holder 保留，捲回來仍是使用者選的分頁。
    var period by rememberSaveable { mutableStateOf(StatsPeriod.Week) }
    val today = remember { LocalDate.now() }
    val filtered = remember(period, data.days) {
        val from = period.fromDate(today)
        data.days.filter { from == null || !it.date.isBefore(from) }
    }
    val chapters = filtered.sumOf { it.chapters }
    // 期間真 distinct 作品＝各日 mangaIds 取 union（不可用每日 distinct 加總、會重複計同一本）。
    val titles = filtered.flatMap { it.mangaIds }.distinct().size

    SectionCard(MR.strings.label_reading_activity_section) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            StatsPeriod.entries.forEach { p ->
                FilterChip(
                    selected = p == period,
                    onClick = { period = p },
                    label = {
                        Text(
                            text = stringResource(p.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(MaterialTheme.padding.small))

        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            StatsItem(chapters.toString(), stringResource(MR.strings.chapters))
            StatsItem(titles.toString(), stringResource(MR.strings.label_titles_section))
        }

        if (filtered.isEmpty()) {
            Text(
                text = stringResource(MR.strings.info_reading_stats_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MaterialTheme.padding.small),
            )
        } else {
            val maxCh = filtered.maxOf { it.chapters }.coerceAtLeast(1)
            Column(
                modifier = Modifier.padding(top = MaterialTheme.padding.small),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                filtered.takeLast(MAX_DAY_ROWS).asReversed().forEach { day ->
                    StatDayBar(
                        dateLabel = "${day.date.monthValue}/${day.date.dayOfMonth}",
                        fraction = (day.chapters.toFloat() / maxCh).coerceIn(0f, 1f),
                        trailing = day.chapters.toString(),
                    )
                }
            }
        }
    }
}

/** token/數量緊湊顯示（數字一律英文 locale，對齊既有統計慣例）。 */
private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(Locale.ENGLISH, n / 1_000_000.0)
    n >= 10_000 -> "%.0fk".format(Locale.ENGLISH, n / 1_000.0)
    n >= 1_000 -> "%.1fk".format(Locale.ENGLISH, n / 1_000.0)
    else -> n.toString()
}
