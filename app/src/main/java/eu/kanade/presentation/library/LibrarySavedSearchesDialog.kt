package eu.kanade.presentation.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Yakuyomi：書庫已儲存搜尋對話框。上半＝已存清單（點載入、垃圾桶刪除）；
 * 下半＝目前有搜尋字時，輸入名稱儲存（同名覆蓋）。搜尋字串可含進階語法（genre:/author:、逗號 AND、- 反向）。
 */
@Composable
fun LibrarySavedSearchesDialog(
    onDismissRequest: () -> Unit,
    savedSearches: List<Pair<String, String>>,
    currentQuery: String?,
    onLoad: (String) -> Unit,
    onSave: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.action_saved_searches)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (savedSearches.isEmpty()) {
                    Text(
                        text = stringResource(MR.strings.saved_searches_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    savedSearches.forEach { (name, query) ->
                        ListItem(
                            modifier = Modifier.clickable {
                                onLoad(query)
                                onDismissRequest()
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(query, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                IconButton(onClick = { onDelete(name) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = stringResource(MR.strings.action_delete),
                                    )
                                }
                            },
                        )
                    }
                }

                if (!currentQuery.isNullOrBlank()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(MR.strings.saved_search_name)) },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (newName.isNotBlank()) {
                                        onSave(newName)
                                        newName = ""
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Save,
                                    contentDescription = stringResource(MR.strings.action_save),
                                )
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_close))
            }
        },
    )
}
