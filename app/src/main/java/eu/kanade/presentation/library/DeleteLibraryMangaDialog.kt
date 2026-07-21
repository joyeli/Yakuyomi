package eu.kanade.presentation.library

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.icerock.moko.resources.StringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun DeleteLibraryMangaDialog(
    containsLocalManga: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (Boolean, Boolean) -> Unit,
) {
    var list by remember {
        mutableStateOf(
            buildList<CheckboxState.State<StringResource>> {
                add(CheckboxState.State.None(MR.strings.manga_from_library))
                if (containsLocalManga) {
                    // Yakuyomi：local 漫畫可選「連同本機檔案一起刪除」（第二項語意＝刪該來源檔案）
                    add(CheckboxState.State.None(MR.strings.delete_local_files))
                } else {
                    add(CheckboxState.State.None(MR.strings.downloaded_chapters))
                }
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = list.any { it.isChecked },
                onClick = {
                    onDismissRequest()
                    onConfirm(
                        list[0].isChecked,
                        list.getOrElse(1) { CheckboxState.State.None(0) }.isChecked,
                    )
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_remove))
        },
        text = {
            Column {
                // 勾「連同本機檔案刪除」（local 時 index 1）→ 連動勾「書櫃上的作品」（index 0）並鎖住：
                // 刪本機檔＝這本沒了、必然移出書櫃，故 deleteFromLibrary 恆真、removeMangas 不需後端補丁。
                val localFilesChecked = containsLocalManga && list.getOrNull(1)?.isChecked == true
                list.forEachIndexed { index, state ->
                    LabeledCheckbox(
                        label = stringResource(state.value),
                        checked = state.isChecked,
                        enabled = !(index == 0 && localFilesChecked),
                        onCheckedChange = { checked ->
                            val mutableList = list.toMutableList()
                            mutableList[index] = if (checked) {
                                CheckboxState.State.Checked(state.value)
                            } else {
                                CheckboxState.State.None(state.value)
                            }
                            // 勾「連同本機檔案刪除」→ 連動勾「書櫃上的作品」（讓那個勾也跳起來）。
                            if (containsLocalManga && index == 1 && checked) {
                                mutableList[0] = CheckboxState.State.Checked(mutableList[0].value)
                            }
                            list = mutableList.toList()
                        },
                    )
                }
            }
        },
    )
}
