package eu.kanade.tachiyomi.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * 整體增強導覽：全螢幕多頁滑動導覽，一頁一大類介紹 Yakuyomi 相對原版 mihon 的所有增強。
 * 給「為探索新功能而來、未必用翻譯」的使用者——從 About「Yakuyomi 增強功能」列，或 onboarding
 * 亮點頁的「查看全部增強」鈕進入。純資訊、收尾直接 [navigator.pop]（不設任何 flag）。
 * 文案提煉自 README「What makes Yakuyomi different」/ README_zh「Yakuyomi 的特點」段。
 */
class EnhancementsGuideScreen : Screen() {

    private data class Highlight(val title: StringResource, val desc: StringResource)

    private data class GuidePage(
        val icon: ImageVector,
        val title: StringResource,
        val highlights: List<Highlight>,
    )

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()

        val pages = remember {
            listOf(
                GuidePage(
                    icon = Icons.Outlined.Translate,
                    title = MR.strings.enhancements_guide_translation_title,
                    highlights = listOf(
                        Highlight(
                            MR.strings.enhancements_guide_tr_inpaint_title,
                            MR.strings.enhancements_guide_tr_inpaint_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_tr_ondevice_title,
                            MR.strings.enhancements_guide_tr_ondevice_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_tr_workflows_title,
                            MR.strings.enhancements_guide_tr_workflows_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_tr_byok_title,
                            MR.strings.enhancements_guide_tr_byok_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_tr_safe_title,
                            MR.strings.enhancements_guide_tr_safe_desc,
                        ),
                    ),
                ),
                GuidePage(
                    icon = Icons.Outlined.AutoStories,
                    title = MR.strings.enhancements_guide_reader_title,
                    highlights = listOf(
                        Highlight(
                            MR.strings.enhancements_guide_rd_webtoon_title,
                            MR.strings.enhancements_guide_rd_webtoon_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_rd_chapters_title,
                            MR.strings.enhancements_guide_rd_chapters_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_rd_eink_title,
                            MR.strings.enhancements_guide_rd_eink_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_rd_notif_title,
                            MR.strings.enhancements_guide_rd_notif_desc,
                        ),
                    ),
                ),
                GuidePage(
                    icon = Icons.Outlined.CollectionsBookmark,
                    title = MR.strings.enhancements_guide_library_title,
                    highlights = listOf(
                        Highlight(
                            MR.strings.enhancements_guide_lb_reorder_title,
                            MR.strings.enhancements_guide_lb_reorder_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_lb_collapse_title,
                            MR.strings.enhancements_guide_lb_collapse_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_lb_covertheme_title,
                            MR.strings.enhancements_guide_lb_covertheme_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_lb_badge_title,
                            MR.strings.enhancements_guide_lb_badge_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_lb_stats_title,
                            MR.strings.enhancements_guide_lb_stats_desc,
                        ),
                    ),
                ),
                GuidePage(
                    icon = Icons.Outlined.TravelExplore,
                    title = MR.strings.enhancements_guide_browse_title,
                    highlights = listOf(
                        Highlight(
                            MR.strings.enhancements_guide_br_filter_title,
                            MR.strings.enhancements_guide_br_filter_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_br_anchor_title,
                            MR.strings.enhancements_guide_br_anchor_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_br_snapshot_title,
                            MR.strings.enhancements_guide_br_snapshot_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_br_bansafe_title,
                            MR.strings.enhancements_guide_br_bansafe_desc,
                        ),
                    ),
                ),
                GuidePage(
                    icon = Icons.Outlined.Search,
                    title = MR.strings.enhancements_guide_search_title,
                    highlights = listOf(
                        Highlight(
                            MR.strings.enhancements_guide_se_floating_title,
                            MR.strings.enhancements_guide_se_floating_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_se_saved_title,
                            MR.strings.enhancements_guide_se_saved_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_se_compactnav_title,
                            MR.strings.enhancements_guide_se_compactnav_desc,
                        ),
                    ),
                ),
                GuidePage(
                    icon = Icons.Outlined.Devices,
                    title = MR.strings.enhancements_guide_largescreen_title,
                    highlights = listOf(
                        Highlight(
                            MR.strings.enhancements_guide_ls_grid_title,
                            MR.strings.enhancements_guide_ls_grid_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_ls_doublepage_title,
                            MR.strings.enhancements_guide_ls_doublepage_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_ls_tablet_title,
                            MR.strings.enhancements_guide_ls_tablet_desc,
                        ),
                        Highlight(
                            MR.strings.enhancements_guide_ls_desc_title,
                            MR.strings.enhancements_guide_ls_desc_desc,
                        ),
                    ),
                ),
            )
        }
        val pagerState = rememberPagerState(pageCount = { pages.size })
        val isLast = pagerState.currentPage == pages.lastIndex

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.enhancements_guide_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(MR.strings.action_close),
                            )
                        }
                    },
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) { page ->
                    val guidePage = pages[page]
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Icon(
                            imageVector = guidePage.icon,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(guidePage.title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            guidePage.highlights.forEach { highlight ->
                                HighlightRow(title = highlight.title, desc = highlight.desc)
                            }
                        }
                    }
                }

                // 頁面指示點
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    repeat(pages.size) { i ->
                        val selected = i == pagerState.currentPage
                        Spacer(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (selected) 10.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    },
                                ),
                        )
                    }
                }

                Button(
                    onClick = {
                        if (isLast) {
                            navigator.pop()
                        } else {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (isLast) {
                                MR.strings.enhancements_guide_action_done
                            } else {
                                MR.strings.onboarding_action_next
                            },
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun HighlightRow(title: StringResource, desc: StringResource) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Spacer(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
