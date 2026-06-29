package eu.kanade.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.materialkolor.ktx.themeColorOrNull
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.model.Manga

/**
 * Yakuyomi：從漫畫封面萃取一個「種子色」（Material You 量化＋評分管線，materialKolor）。
 *
 * - 在 [Dispatchers.IO] 上跑（量化偏重）、封面縮到 96px 降成本；Coil 命中快取時很快。
 * - `allowHardware(false)`：要讀像素 → 必須是 software bitmap（hardware config 會丟例外）。
 * - 取不到色（無封面 / 解碼失敗 / 近灰階被 filter 濾掉）回 null → 呼叫端退回使用者原主題。
 * - key 含 [Manga.coverLastModified]：換自訂封面會重新萃取。
 * - [enabled] 為 false（功能關閉）→ 直接回 null、不做任何取色工作。
 */
@Composable
fun rememberCoverSeedColor(manga: Manga, enabled: Boolean): Color? {
    val context = LocalContext.current
    val seed by produceState<Color?>(
        null,
        manga.id,
        manga.thumbnailUrl,
        manga.coverLastModified,
        enabled,
    ) {
        if (!enabled) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(manga)
                    .allowHardware(false)
                    .size(96)
                    .build()
                context.imageLoader.execute(request).image
                    ?.asDrawable(context.resources)
                    ?.getBitmapOrNull()
                    ?.asImageBitmap()
                    ?.themeColorOrNull()
            }.getOrNull()
        }
    }
    return seed
}
