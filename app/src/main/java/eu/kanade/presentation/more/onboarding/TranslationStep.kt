package eu.kanade.presentation.more.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * 首次啟動引導：翻譯預告頁。一句「能翻漫畫、圖不出裝置、只送 OCR 文字到你的 LLM（自備金鑰、可能花錢）」
 * + 一顆「開啟翻譯設定」鈕（[onOpenTranslation]：結束 onboarding 後跳到翻譯設定，那裡會自動帶出快速上手）。
 * [isComplete]=true → 永遠不擋「開始使用」（翻譯是選用功能，可跳過、之後在設定裡仍找得到）。
 */
internal class TranslationStep(
    private val onOpenTranslation: () -> Unit,
) : OnboardingStep {

    override val isComplete: Boolean = true

    @Composable
    override fun Content() {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Text(
                text = stringResource(MR.strings.onboarding_translation_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(MR.strings.onboarding_translation_description))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenTranslation,
            ) {
                Text(stringResource(MR.strings.onboarding_translation_action))
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun TranslationStepPreview() {
    TachiyomiPreviewTheme {
        TranslationStep(
            onOpenTranslation = {},
        ).Content()
    }
}
