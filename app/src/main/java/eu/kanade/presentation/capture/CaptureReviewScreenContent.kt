package eu.kanade.presentation.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.capture.CapturePage
import eu.kanade.tachiyomi.ui.capture.CaptureReviewState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Yakuyomi 擷取漫畫確認頁內容：3 欄縮圖網格、角落順序標號 + 勾選、底部「刪除選取 / 儲存」。
 */
@Composable
fun CaptureReviewScreenContent(
    state: CaptureReviewState,
    onNavigateUp: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onReCapture: (CapturePage) -> Unit,
    onDeleteSelected: () -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(
        topBar = {
            AppBar(
                title = stringResource(MR.strings.capture_review_title),
                navigateUp = onNavigateUp,
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDeleteSelected,
                        enabled = state.selected.isNotEmpty() && !state.saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(imageVector = Icons.Outlined.Delete, contentDescription = null)
                        Text(
                            text = stringResource(MR.strings.capture_review_delete_selected, state.selected.size),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    Button(
                        onClick = onSave,
                        enabled = !state.saving && state.pages.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(imageVector = Icons.Outlined.Save, contentDescription = null)
                            Text(
                                text = stringResource(MR.strings.action_save),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        },
    ) { contentPadding ->
        when {
            state.loading -> LoadingScreen(modifier = Modifier.padding(contentPadding))
            state.pages.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(MR.strings.capture_review_empty),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentPadding = PaddingValues(MaterialTheme.padding.small),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                itemsIndexed(state.pages, key = { _, page -> page.uri }) { index, page ->
                    ReviewGridItem(
                        page = page,
                        number = index + 1,
                        selected = page.uri in state.selected,
                        reloadKey = state.reloadKey,
                        onToggle = { onToggleSelect(page.uri) },
                        onReCapture = { onReCapture(page) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewGridItem(
    page: CapturePage,
    number: Int,
    selected: Boolean,
    reloadKey: Int,
    onToggle: () -> Unit,
    onReCapture: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(MangaPageRatio)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle),
    ) {
        // 重截同檔名覆蓋 → coil 預設用 uri 當快取鍵會顯示舊圖；把 reloadKey 併進快取鍵每次重掃即失效。
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(page.file.uri)
                .memoryCacheKey("${page.uri}#$reloadKey")
                .diskCacheKey("${page.uri}#$reloadKey")
                .build(),
            contentDescription = page.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // 勾選（要刪的）時疊主色遮罩，一眼看出被選了。
        if (selected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            )
        }

        // 左上角順序標號 001/002…（半透明底、白字）。
        Text(
            text = "%03d".format(number),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .clip(MaterialTheme.shapes.small)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        // 右上角勾選框（管批次刪除）。
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.align(Alignment.TopEnd),
        )

        // 右下角單頁重截鈕（獨立於刪除 Checkbox）：開該頁記錄的網址重截、覆蓋這一頁。
        IconButton(
            onClick = onReCapture,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(2.dp)
                .clip(MaterialTheme.shapes.small)
                .background(Color.Black.copy(alpha = 0.55f))
                .size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = stringResource(MR.strings.capture_recapture_action, number),
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// 漫畫頁多為直向，縮圖用近書本比例（寬:高 ≈ 0.7）好一覽快掃。
private const val MangaPageRatio = 0.7f
