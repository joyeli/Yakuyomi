package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** 章節列的翻譯狀態（由 model 的 translationStatus + isTranslated 推得）。 */
enum class ChapterTranslationState {
    HIDDEN, // 未下載：不顯示（翻譯以下載的頁圖為對象）
    NONE, // 已下載未翻、可翻（顯示翻譯鈕）
    QUEUED, // 排隊中（灰）
    TRANSLATING, // 翻譯中（轉圈）
    TRANSLATED, // 已翻（主色＋打勾徽章）
    ERROR, // 失敗（紅）
}

/**
 * 章節列的翻譯狀態指示器（對照 [ChapterDownloadIndicator]）。顯示狀態，點擊＝（重）翻譯該章。
 * 翻譯中顯示轉圈、其餘以圖示/色調區分；NONE 即一般翻譯鈕。
 */
@Composable
fun ChapterTranslationIndicator(
    enabled: Boolean,
    stateProvider: () -> ChapterTranslationState,
    progressProvider: () -> Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = stateProvider()
    if (state == ChapterTranslationState.HIDDEN) return // 未下載 → 不畫指示器
    when (state) {
        ChapterTranslationState.TRANSLATING -> {
            Box(
                modifier = modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                val progress = progressProvider() // 0-100（頁 done/total）
                if (progress > 0) {
                    val animated by animateFloatAsState(
                        targetValue = progress / 100f,
                        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                        label = "translationProgress",
                    )
                    CircularProgressIndicator(
                        progress = { animated },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    CircularProgressIndicator( // 剛開始(0)→不定轉圈
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
        else -> {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (state == ChapterTranslationState.ERROR) {
                            Icons.Outlined.ErrorOutline
                        } else {
                            Icons.Outlined.Translate
                        },
                        contentDescription = stringResource(MR.strings.action_translate),
                        modifier = Modifier.size(26.dp),
                        tint = when (state) {
                            ChapterTranslationState.TRANSLATED -> MaterialTheme.colorScheme.primary
                            ChapterTranslationState.ERROR -> MaterialTheme.colorScheme.error
                            ChapterTranslationState.QUEUED ->
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    // 已翻：右下疊一個打勾徽章。未翻(NONE)與已翻(TRANSLATED)本是同一顆翻譯圖示、只差色調，
                    // 易被誤判為「已完成」（連開發者都中過）。加打勾後兩者一眼分得出。
                    if (state == ChapterTranslationState.TRANSLATED) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(14.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                                .padding(1.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
