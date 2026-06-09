package eu.kanade.tachiyomi.ui.translation

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
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 翻譯快速上手導覽（4 頁可滑）：首次開啟翻譯設定時自動跳一次（見 [eu.kanade.presentation.more.settings.screen.SettingsTranslationScreen]），
 * 之後可從設定頂端「快速上手」列重開。內容＝這功能在做什麼（含隱私/費用揭露）、設定三件事、兩種工作流、手動翻與管理。
 *
 * 隱私合併：看完按「了解」＝設 [TranslationPreferences.privacyAcknowledged]，之後翻 enable/live 開關不再跳同意對話框
 * （第 1 頁的揭露文字＝原本一次性同意對話框的同一份內容）。
 */
class TranslationQuickstartScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember { Injekt.get<TranslationPreferences>() }
        val scope = rememberCoroutineScope()

        val pages = remember {
            listOf(
                MR.strings.quickstart_what_title to MR.strings.quickstart_what_body,
                MR.strings.quickstart_setup_title to MR.strings.quickstart_setup_body,
                MR.strings.quickstart_workflows_title to MR.strings.quickstart_workflows_body,
                MR.strings.quickstart_manual_title to MR.strings.quickstart_manual_body,
            )
        }
        val pagerState = rememberPagerState(pageCount = { pages.size })
        val isLast = pagerState.currentPage == pages.lastIndex

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.pref_translation_quickstart)) },
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
                    val (titleRes, bodyRes) = pages[page]
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(titleRes),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(bodyRes),
                            style = MaterialTheme.typography.bodyLarge,
                        )
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
                            // 合併：看完快速上手＝已同意隱私揭露 → 之後翻 enable/live 開關不再跳同意對話框。
                            prefs.privacyAcknowledged.set(true)
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
                            if (isLast) MR.strings.quickstart_action_done else MR.strings.onboarding_action_next,
                        ),
                    )
                }
            }
        }
    }
}
