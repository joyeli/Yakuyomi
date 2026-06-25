package eu.kanade.domain.source.service

import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.tachiyomi.util.system.LocaleHelper
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getLongArray
import tachiyomi.domain.library.model.LibraryDisplayMode

class SourcePreferences(
    private val preferenceStore: PreferenceStore,
) {

    val sourceDisplayMode: Preference<LibraryDisplayMode> = preferenceStore.getObjectFromString(
        "pref_display_mode_catalogue",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    val enabledLanguages: Preference<Set<String>> = preferenceStore.getStringSet(
        "source_languages",
        LocaleHelper.getDefaultEnabledLanguages(),
    )

    val disabledSources: Preference<Set<String>> = preferenceStore.getStringSet("hidden_catalogues", emptySet())

    val incognitoExtensions: Preference<Set<String>> = preferenceStore.getStringSet("incognito_extensions", emptySet())

    val pinnedSources: Preference<Set<String>> = preferenceStore.getStringSet("pinned_catalogues", emptySet())

    val lastUsedSource: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("last_catalogue_source"),
        -1,
    )

    val showNsfwSource: Preference<Boolean> = preferenceStore.getBoolean("show_nsfw_source", true)

    val migrationSortingMode: Preference<SetMigrateSorting.Mode> = preferenceStore.getEnum(
        "pref_migration_sorting",
        SetMigrateSorting.Mode.ALPHABETICAL,
    )

    val migrationSortingDirection: Preference<SetMigrateSorting.Direction> = preferenceStore.getEnum(
        "pref_migration_direction",
        SetMigrateSorting.Direction.ASCENDING,
    )

    val hideInLibraryItems: Preference<Boolean> = preferenceStore.getBoolean("browse_hide_in_library_items", false)

    // Yakuyomi：探索全域篩選（跨所有來源、與各 source 自帶 extension filter 獨立）。客戶端後置篩選。
    val browseFilterFavorite: Preference<TriState> = preferenceStore.getEnum(
        "browse_filter_favorite",
        TriState.DISABLED,
    )
    val browseFilterRead: Preference<TriState> = preferenceStore.getEnum("browse_filter_read", TriState.DISABLED)

    // 擷取＝該本詳情曾被載入過（[tachiyomi.domain.manga.model.Manga.initialized]，曾點進去/載過書目說明）；未擷取＝全新沒點過。
    val browseFilterFetched: Preference<TriState> = preferenceStore.getEnum("browse_filter_fetched", TriState.DISABLED)

    // 探索翻頁最小間隔（秒）。擋住客戶端篩選稀疏時的連翻爆衝、防 ban。與 data 層 SourcePagingSource.PREF_LOAD_INTERVAL 同 key。
    val browseLoadInterval: Preference<Int> = preferenceStore.getInt("browse_load_interval", 1)

    // 探索「錨點」：每 source 一個（值＝該本 mangaUrl，空＝未設）。標記「上次處理到這」，瀏覽清單上以旗標徽章標出。
    fun browseAnchor(sourceId: Long): Preference<String> = preferenceStore.getString("browse_anchor_$sourceId", "")

    // 探索「快照」：每 source 一份離線清單（JSON：時間戳 + 書本 url 順序）。空＝無快照。詳見 BrowseSnapshotStore 用法。
    fun browseSnapshot(sourceId: Long): Preference<String> = preferenceStore.getString("browse_snapshot_$sourceId", "")

    val extensionRepos: Preference<Set<String>> = preferenceStore.getStringSet("extension_repos", emptySet())

    val extensionUpdatesCount: Preference<Int> = preferenceStore.getInt("ext_updates_count", 0)

    val trustedExtensions: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("trusted_extensions"),
        emptySet(),
    )

    val globalSearchFilterState: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("has_filters_toggle_state"),
        false,
    )

    val migrationSources: Preference<List<Long>> = preferenceStore.getLongArray("migration_sources", emptyList())

    val migrationFlags: Preference<Set<MigrationFlag>> = preferenceStore.getObjectFromInt(
        key = "migration_flags",
        defaultValue = MigrationFlag.entries.toSet(),
        serializer = { MigrationFlag.toBit(it) },
        deserializer = { value: Int -> MigrationFlag.fromBit(value) },
    )

    val migrationDeepSearchMode: Preference<Boolean> = preferenceStore.getBoolean("migration_deep_search", false)

    val migrationPrioritizeByChapters: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_prioritize_by_chapters",
        false,
    )

    val migrationHideUnmatched: Preference<Boolean> = preferenceStore.getBoolean("migration_hide_unmatched", false)

    val migrationHideWithoutUpdates: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_hide_without_updates",
        false,
    )
}
