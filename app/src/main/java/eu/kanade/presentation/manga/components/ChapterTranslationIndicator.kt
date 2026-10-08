package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/**
 * 章節列的夜讀版狀態（[eu.kanade.tachiyomi.ui.manga.MangaViewModel] 從佇列 NIGHT 項 + 「已有夜讀版」算好，
 * 放在 ChapterList.Item.nightStatus；夜讀總開關關或未下載＝HIDDEN）。
 */
enum class ChapterNightStatus {
    HIDDEN, // 夜讀總開關關 / 未下載：不顯示、不佔位（夜讀版以下載的頁圖為對象）
    NONE, // 已下載、還沒有夜讀版（顯示月亮外框鈕）
    QUEUED, // 排隊中（灰）
    RENDERING, // 產生中（環形進度）
    DONE, // 已有夜讀版（實心月亮主色）
    UPDATABLE, // 已有夜讀版、但有頁是舊規則產生的（實心月亮＋右下「更新」徽章；點＝重新產生舊版頁）
    ERROR, // 失敗（紅）
}

/**
 * 章節列的夜讀版指示器（對照 [ChapterTranslationIndicator]，圖示換月亮）。顯示狀態，點擊＝（重）產生該章夜讀版。
 * 產生中顯示環形進度；排隊中不觸發回呼（已在佇列、再排只會被去重）；NONE 即一般夜讀鈕。
 * UPDATABLE（app 更新改了夜讀規則、這章有舊版頁）：實心月亮右下疊「更新」徽章（第三色），長按出說明；點一下照樣排入，
 * 產生端只重做舊版頁。
 */
@Composable
fun ChapterNightIndicator(
    enabled: Boolean,
    stateProvider: () -> ChapterNightStatus,
    progressProvider: () -> Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = stateProvider()
    if (state == ChapterNightStatus.HIDDEN) return // 總開關關 / 未下載 → 不畫指示器
    when (state) {
        ChapterNightStatus.RENDERING -> {
            val description = stringResource(MR.strings.nightread_chapter_status_rendering)
            Box(
                modifier = modifier
                    .size(40.dp)
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                val progress = progressProvider() // 0..1（頁 done/total）；null / 0＝剛開始
                if (progress != null && progress > 0f) {
                    val animated by animateFloatAsState(
                        targetValue = progress.coerceIn(0f, 1f),
                        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                        label = "nightProgress",
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
        ChapterNightStatus.UPDATABLE -> {
            val description = stringResource(MR.strings.nightread_chapter_updatable)
            TooltipBox(
                positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(description) } },
                state = rememberTooltipState(),
                focusable = false,
            ) {
                IconButton(
                    onClick = onClick,
                    enabled = enabled,
                    modifier = modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.DarkMode,
                            contentDescription = description,
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        // 「可更新」徽章：同翻譯指示器的打勾徽章位置，換成向上箭頭、第三色，跟 DONE 一眼分得出
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(14.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                                .padding(1.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ArrowCircleUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        else -> {
            IconButton(
                // 排隊中不觸發（不用 disable：灰上再灰會看不見）
                onClick = { if (state != ChapterNightStatus.QUEUED) onClick() },
                enabled = enabled,
                modifier = modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = when (state) {
                        ChapterNightStatus.ERROR -> Icons.Outlined.ErrorOutline
                        ChapterNightStatus.DONE -> Icons.Filled.DarkMode
                        else -> Icons.Outlined.DarkMode
                    },
                    // 每個狀態各自的說明（TalkBack 才分得出「已有」「排隊中」「失敗」，不是一律「產生夜讀版」）
                    contentDescription = stringResource(
                        when (state) {
                            ChapterNightStatus.DONE -> MR.strings.nightread_chapter_status_done
                            ChapterNightStatus.QUEUED -> MR.strings.nightread_chapter_status_queued
                            ChapterNightStatus.ERROR -> MR.strings.nightread_chapter_status_error
                            else -> MR.strings.action_nightread_chapter
                        },
                    ),
                    modifier = Modifier.size(26.dp),
                    tint = when (state) {
                        ChapterNightStatus.DONE -> MaterialTheme.colorScheme.primary
                        ChapterNightStatus.ERROR -> MaterialTheme.colorScheme.error
                        ChapterNightStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
