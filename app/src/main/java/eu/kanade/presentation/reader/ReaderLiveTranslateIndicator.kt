package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Reader 內的「當前章正在翻譯」小指示器（非侵入式角落藥丸）。
 *
 * - [loading]＝true → 顯示「引擎載入中…」（優先；引擎在背景載 ~100MB ONNX，讓使用者知道延遲是掛載模型而非卡死）。
 * - [translating]＝true → 顯示「翻譯中 X/Y」（[done]/[total]）；false → 顯示「翻譯排隊中」。
 * - 半透明圓角底、`labelSmall` 小字、緊湊內距；放在角落不擋頁面、也不攔觸控
 *   （[Surface] 不帶 onClick、無 clickable modifier ⇒ 點擊穿透到底下的 viewer，照常切換選單）。
 * - 與切換式 chrome（app bars / 頁碼）獨立：這是常駐狀態，選單收起時也照顯示。
 *
 * 由呼叫端（[eu.kanade.tachiyomi.ui.reader.ReaderActivity]）在進度為 null 時整個不組合（不顯示）。
 */
@Composable
fun ReaderLiveTranslateIndicator(
    translating: Boolean,
    done: Int,
    total: Int,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val text = when {
        // 引擎正在載入（~100MB ONNX）→ 優先顯示，讓使用者知道延遲是在掛載模型（非卡死）。
        loading -> stringResource(MR.strings.reader_engine_loading)
        // 翻譯中且已知頁數 → 帶進度；total 還沒回報（剛開始）時退化成不帶數字、避免顯示「0/0」。
        translating && total > 0 -> stringResource(MR.strings.reader_translating_progress, done, total)
        translating -> stringResource(MR.strings.reader_translating)
        else -> stringResource(MR.strings.reader_translating_queued)
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        // 半透明底：在亮/暗頁面上都看得到，又不至於蓋住內容；不可點（無 onClick）⇒ 觸控穿透。
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@PreviewLightDark
@Composable
private fun ReaderLiveTranslateIndicatorPreview() {
    TachiyomiPreviewTheme {
        Surface {
            ReaderLiveTranslateIndicator(translating = true, done = 13, total = 22)
        }
    }
}
