package eu.kanade.presentation.browse.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    source: Source?,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSearch: (String) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    // Yakuyomi：錨點工具列按鈕（單一直接動作，依狀態切換：標記錨點 / 自動載入 / 停止）。三者擇一非空。
    onMarkFirstAsAnchor: (() -> Unit)? = null,
    onAutoLoadToAnchor: (() -> Unit)? = null,
    onStopAutoLoad: (() -> Unit)? = null,
    // Yakuyomi：清除快照（overflow，僅有快照時非空）。
    onClearSnapshot: (() -> Unit)? = null,
) {
    // Avoid capturing unstable source in actions lambda
    val title = source?.name
    val isLocalSource = source is LocalSource
    val isConfigurableSource = source is ConfigurableSource

    var selectingDisplayMode by remember { mutableStateOf(false) }

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = { AppBarTitle(title) },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = navigateUp,
        actions = {
            AppBarActions(
                actions = persistentListOf<AppBar.AppBarAction>().builder()
                    .apply {
                        // Yakuyomi：錨點按鈕（直接放外面，依狀態切換圖示/動作）。
                        when {
                            onStopAutoLoad != null -> add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_stop_auto_load),
                                    icon = Icons.Filled.Stop,
                                    onClick = onStopAutoLoad,
                                ),
                            )
                            onAutoLoadToAnchor != null -> add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_auto_load_to_anchor),
                                    icon = Icons.Outlined.PlayArrow,
                                    onClick = onAutoLoadToAnchor,
                                ),
                            )
                            onMarkFirstAsAnchor != null -> add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_set_anchor),
                                    icon = Icons.Outlined.Flag,
                                    onClick = onMarkFirstAsAnchor,
                                ),
                            )
                        }
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_display_mode),
                                icon = if (displayMode == LibraryDisplayMode.List) {
                                    Icons.AutoMirrored.Filled.ViewList
                                } else {
                                    Icons.Filled.ViewModule
                                },
                                onClick = { selectingDisplayMode = true },
                            ),
                        )
                        if (isLocalSource) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.label_help),
                                    onClick = onHelpClick,
                                ),
                            )
                        } else {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_open_in_web_view),
                                    onClick = onWebViewClick,
                                ),
                            )
                        }
                        if (isConfigurableSource) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_settings),
                                    onClick = onSettingsClick,
                                ),
                            )
                        }
                        if (onClearSnapshot != null) {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_clear_snapshot),
                                    onClick = onClearSnapshot,
                                ),
                            )
                        }
                    }
                    .build(),
            )

            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
            ) {
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_comfortable_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.CompactGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_list)) },
                    isChecked = displayMode == LibraryDisplayMode.List,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.List)
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
