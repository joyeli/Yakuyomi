package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.authenticate
import kotlinx.collections.immutable.persistentMapOf
import mihon.domain.extension.interactor.GetExtensionStoreCountAsFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsBrowseScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.browse

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
        val getExtensionStoreCountAsFlow = remember { Injekt.get<GetExtensionStoreCountAsFlow>() }

        val reposCount by getExtensionStoreCountAsFlow().collectAsState(0)

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_sources),
                preferenceItems = listOf(
                    // Yakuyomi：「隱藏已在書庫」設定移除——已由探索頁的收藏篩選（filter）取代。
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.showRecentlyUsedSource,
                        title = stringResource(MR.strings.pref_show_recently_used_source),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.showLocalSource,
                        title = stringResource(MR.strings.pref_show_local_source),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.browseDefaultToLatest,
                        title = stringResource(MR.strings.pref_browse_default_to_latest),
                        subtitle = stringResource(MR.strings.pref_browse_default_to_latest_summary),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = sourcePreferences.browseLoadInterval,
                        entries = persistentMapOf(
                            0 to stringResource(MR.strings.off),
                            1 to "1 s",
                            2 to "2 s",
                            3 to "3 s",
                            5 to "5 s",
                        ),
                        title = stringResource(MR.strings.pref_browse_load_interval),
                        subtitle = stringResource(MR.strings.pref_browse_load_interval_summary),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.extensionStores),
                        subtitle = pluralStringResource(MR.plurals.num_repos, reposCount.toInt(), reposCount),
                        onClick = {
                            navigator.push(ExtensionStoresScreen())
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_browse_anchor_load),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = sourcePreferences.browseAnchorChunkPages,
                        entries = persistentMapOf(
                            3 to "3",
                            5 to "5",
                            8 to "8",
                            10 to "10",
                        ),
                        title = stringResource(MR.strings.pref_browse_anchor_chunk_pages),
                        subtitle = stringResource(MR.strings.pref_browse_anchor_chunk_pages_summary),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = sourcePreferences.browseAnchorIntervalMinutes,
                        entries = persistentMapOf(
                            1 to stringResource(MR.strings.label_minutes, 1),
                            2 to stringResource(MR.strings.label_minutes, 2),
                            3 to stringResource(MR.strings.label_minutes, 3),
                            5 to stringResource(MR.strings.label_minutes, 5),
                            10 to stringResource(MR.strings.label_minutes, 10),
                            15 to stringResource(MR.strings.label_minutes, 15),
                        ),
                        title = stringResource(MR.strings.pref_browse_anchor_interval),
                        subtitle = stringResource(MR.strings.pref_browse_anchor_interval_summary),
                    ),
                    Preference.PreferenceItem.InfoPreference(
                        stringResource(MR.strings.pref_browse_anchor_load_info),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_nsfw_content),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.showNsfwSource,
                        title = stringResource(MR.strings.pref_show_nsfw_source),
                        subtitle = stringResource(MR.strings.requires_app_restart),
                        onValueChanged = {
                            (context as FragmentActivity).authenticate(
                                title = context.stringResource(MR.strings.pref_category_nsfw_content),
                            )
                        },
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.parental_controls_info)),
                ),
            ),
        )
    }
}
