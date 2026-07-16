package eu.kanade.presentation.more.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ImportContacts
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * 首次啟動引導：Yakuyomi 亮點頁。四條「跟原版 mihon 不一樣」的代表功能，一行一條、可掃視。
 * 翻譯有自己的專頁（[TranslationStep]），這裡只放非翻譯類的招牌功能。
 * [isComplete]=true → 純資訊、不擋「下一步」。
 */
internal class HighlightsStep(
    private val onOpenGuide: () -> Unit,
) : OnboardingStep {

    override val isComplete: Boolean = true

    @Composable
    override fun Content() {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
        ) {
            Text(
                text = stringResource(MR.strings.onboarding_highlights_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            HighlightRow(
                icon = Icons.Outlined.Search,
                title = MR.strings.onboarding_highlights_search_title,
                desc = MR.strings.onboarding_highlights_search_desc,
            )
            HighlightRow(
                icon = Icons.Outlined.History,
                title = MR.strings.onboarding_highlights_snapshot_title,
                desc = MR.strings.onboarding_highlights_snapshot_desc,
            )
            HighlightRow(
                icon = Icons.Outlined.ImportContacts,
                title = MR.strings.onboarding_highlights_doublepage_title,
                desc = MR.strings.onboarding_highlights_doublepage_desc,
            )
            HighlightRow(
                icon = Icons.Outlined.QueryStats,
                title = MR.strings.onboarding_highlights_stats_title,
                desc = MR.strings.onboarding_highlights_stats_desc,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenGuide,
            ) {
                Text(stringResource(MR.strings.enhancements_guide_open))
            }
        }
    }

    @Composable
    private fun HighlightRow(icon: ImageVector, title: StringResource, desc: StringResource) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun HighlightsStepPreview() {
    TachiyomiPreviewTheme {
        HighlightsStep(onOpenGuide = {}).Content()
    }
}
