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

    // Yakuyomi：來源清單是否顯示「最近使用」欄（預設關）。關＝該來源只照 pin/語言正常分組，不另置頂一份。
    val showRecentlyUsedSource: Preference<Boolean> = preferenceStore.getBoolean("show_recently_used_source", false)

    // Yakuyomi：來源清單是否顯示本地來源（預設關）。
    val showLocalSource: Preference<Boolean> = preferenceStore.getBoolean("show_local_source", false)

    // Yakuyomi：點來源進去預設顯示「最新」而非「熱門」（預設開）。開＝探索頁隱藏「最新」chip、進來直接最新。
    val browseDefaultToLatest: Preference<Boolean> = preferenceStore.getBoolean("browse_default_to_latest", true)

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

    // Yakuyomi：每 source 一份「已擷取的書本 url 集合」（單調累積、擷取過就永久記住）。
    // 用 url 當 key（每來源穩定）→ 免疫 DB initialized 被來源清單重載打回 false（SManga.initialized 恆 false）+
    // 免疫 ClearDatabase 換 mangaId。探索「已擷取」篩選（browseFilterFetched）讀此集合作持久判準。
    fun browseFetchedUrls(sourceId: Long): Preference<Set<String>> =
        preferenceStore.getStringSet("browse_fetched_$sourceId", emptySet())

    // Yakuyomi：自動載入到錨點的「續傳頁碼」（每 source）。>0＝上次抓到第幾頁、還沒到錨點，可續；0＝無/已完成（到錨點或到底）。
    fun browseAnchorResumePage(sourceId: Long): Preference<Int> =
        preferenceStore.getInt("browse_anchor_resume_page_$sourceId", 0)

    // Yakuyomi：目前正在背景載入到錨點的來源（單一全域槽）。>0＝該 sourceId 正在跑；-1＝無。跨行程持久 → 殺行程/重開機仍知道要續。
    val browseAnchorCrawlActive: Preference<Long> = preferenceStore.getLong("browse_anchor_crawl_active", -1L)

    // Yakuyomi：背景載入連續抓不到任何頁（多半＝被 ban）的次數。達上限就停下、通知可續。任一批抓到就歸零。
    val browseAnchorFailStreak: Preference<Int> = preferenceStore.getInt("browse_anchor_fail_streak", 0)

    // Yakuyomi：背景載入每批頁數（越小越不易 ban）。預設 5（實測某來源約 24 頁 burst 就 ban，取遠低值）。
    val browseAnchorChunkPages: Preference<Int> = preferenceStore.getInt("browse_anchor_chunk_pages", 5)

    // Yakuyomi：背景載入批次間隔（分鐘）。批與批之間歇這麼久，讓來源速率視窗重置。預設 1（實測某來源 3000+ 本跑通）。
    // 加速靠縮短間隔（每批維持小頁數＝每次 burst 最小、最安全），不靠加大每批頁數。
    val browseAnchorIntervalMinutes: Preference<Int> = preferenceStore.getInt("browse_anchor_interval_minutes", 1)

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
