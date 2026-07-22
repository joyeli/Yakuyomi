package eu.kanade.presentation.capture

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.ui.capture.CapturePage
import eu.kanade.tachiyomi.ui.capture.CaptureReviewState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Yakuyomi 擷取漫畫確認頁內容：3 欄縮圖網格、角落順序標號 + 勾選、底部動作列。
 *
 * 三個動作的語意分明：
 * - **繼續擷取**（[onContinueCapture]）＝這話還沒截完 → 不儲存、不重編號、不跳詳情，只退回擷取工具續截
 *   （頁碼由存檔時掃章夾 max+1 天然接續；回去後仍要自己按「開始」才續截）。
 * - **儲存**（[onSave]）＝這話完成 → 重新編號成連續頁碼 + 跳漫畫詳情。
 * - **放棄**（[onDiscardSession]，TopAppBar）＝丟掉這次 session 截的頁。
 *
 * 底部排版：平時只有「繼續擷取 / 儲存」兩顆主要動作；**有勾選時才多出一列「刪除選取 (N)」**（error 色），
 * 免得三顆鈕擠一排、也讓破壞性動作與主要動作分開。
 */
@Composable
fun CaptureReviewScreenContent(
    state: CaptureReviewState,
    onNavigateUp: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onReCapture: (CapturePage) -> Unit,
    onInsert: (CapturePage, Boolean) -> Unit,
    onDeleteSelected: () -> Unit,
    onSave: () -> Unit,
    // 回擷取工具續截這話（不儲存 / 不重編號 / 不跳詳情）。
    onContinueCapture: () -> Unit = {},
    // 本次連續截圖存下的頁數（0＝非連續 session 進入或無新頁）；>0 才顯示「放棄這次截圖」。
    sessionPageCount: Int = 0,
    onDiscardSession: () -> Unit = {},
) {
    // 「放棄這次截圖」確認對話框（防誤觸，此動作刪頁不可復原）。
    var showDiscardDialog by remember { mutableStateOf(false) }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(MR.strings.capture_review_discard)) },
            text = { Text(stringResource(MR.strings.capture_review_discard_confirm, sessionPageCount)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onDiscardSession()
                    },
                ) {
                    Text(stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            AppBar(
                title = stringResource(MR.strings.capture_review_title),
                navigateUp = onNavigateUp,
                // 放棄整批入口放 TopAppBar action（不擠底部「刪除選取／儲存」）；只在有本次 session 新頁時出現。
                actions = {
                    if (sessionPageCount > 0) {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.capture_review_discard),
                                    icon = Icons.Outlined.DeleteSweep,
                                    onClick = { showDiscardDialog = true },
                                    enabled = !state.saving,
                                ),
                            ),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 刪除列：只在有勾選時出現（平時底部只剩「繼續擷取 / 儲存」兩顆主要動作）。
                    if (state.selected.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onDeleteSelected,
                            enabled = !state.saving,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(imageVector = Icons.Outlined.Delete, contentDescription = null)
                            Text(
                                text = stringResource(MR.strings.capture_review_delete_selected, state.selected.size),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 繼續擷取：不儲存、不重編號、不跳詳情，單純退回擷取工具接著截這話。
                        OutlinedButton(
                            onClick = onContinueCapture,
                            enabled = !state.saving,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(imageVector = Icons.Outlined.PhotoCamera, contentDescription = null)
                            Text(
                                text = stringResource(MR.strings.capture_review_continue),
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
                        onInsert = { before -> onInsert(page, before) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewGridItem(
    page: CapturePage,
    number: Int,
    selected: Boolean,
    reloadKey: Int,
    onToggle: () -> Unit,
    onReCapture: () -> Unit,
    onInsert: (before: Boolean) -> Unit,
) {
    // 長按縮圖開「在此頁前/後插入」選單（角落已有 3 個 icon，插入走長按不再加角落 icon）。
    var menuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(MangaPageRatio)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                onClick = onToggle,
                onLongClick = { menuExpanded = true },
            ),
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

        // 右上角勾選框（管批次刪除）：與右下重截 icon 完全對稱（同款黑底方塊 + 28dp），三角落大小一致。
        IconButton(
            onClick = onToggle,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .clip(MaterialTheme.shapes.small)
                .background(Color.Black.copy(alpha = 0.55f))
                .size(28.dp),
        ) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }

        // 右下角單頁重截鈕（獨立於刪除勾選）：開該頁記錄的網址重截、覆蓋這一頁。
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

        // 插入選單（長按縮圖開）：在此頁前 / 在此頁後插入一張新截圖。
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.capture_insert_before)) },
                onClick = {
                    menuExpanded = false
                    onInsert(true)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.capture_insert_after)) },
                onClick = {
                    menuExpanded = false
                    onInsert(false)
                },
            )
        }
    }
}

// 漫畫頁多為直向，縮圖用近書本比例（寬:高 ≈ 0.7）好一覽快掃。
private const val MangaPageRatio = 0.7f
