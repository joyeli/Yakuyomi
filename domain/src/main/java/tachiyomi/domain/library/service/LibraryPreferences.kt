package tachiyomi.domain.library.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getLongArray
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.Manga

class LibraryPreferences(
    private val preferenceStore: PreferenceStore,
) {

    val displayMode: Preference<LibraryDisplayMode> = preferenceStore.getObjectFromString(
        "pref_display_mode_library",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    val sortingMode: Preference<LibrarySort> = preferenceStore.getObjectFromString(
        "library_sorting_mode",
        LibrarySort.default,
        LibrarySort.Serializer::serialize,
        LibrarySort.Serializer::deserialize,
    )

    val randomSortSeed: Preference<Int> = preferenceStore.getInt("library_random_sort_seed", 0)

    // Yakuyomi：網格封面最小寬度（dp）。欄數＝GridCells.Adaptive(此值) 依實際螢幕寬度自動算，
    // 折疊機折/展、手機/平板全自動對應；調此值＝同時改封面大小與每行數量。取代舊的直/橫固定欄數。
    val gridCoverMinWidth: Preference<Int> = preferenceStore.getInt("pref_grid_cover_min_width_dp", 128)

    val lastUpdatedTimestamp: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("library_update_last_timestamp"),
        0L,
    )
    val autoUpdateInterval: Preference<Int> = preferenceStore.getInt("pref_library_update_interval_key", 0)

    val autoUpdateDeviceRestrictions: Preference<Set<String>> = preferenceStore.getStringSet(
        "library_update_restriction",
        setOf(
            DEVICE_ONLY_ON_WIFI,
        ),
    )
    val autoUpdateMangaRestrictions: Preference<Set<String>> = preferenceStore.getStringSet(
        "library_update_manga_restriction",
        setOf(
            MANGA_HAS_UNREAD,
            MANGA_NON_COMPLETED,
            MANGA_NON_READ,
            MANGA_OUTSIDE_RELEASE_PERIOD,
        ),
    )

    val autoUpdateMetadata: Preference<Boolean> = preferenceStore.getBoolean("auto_update_metadata", false)

    // Yakuyomi：開啟漫畫詳情頁時自動向來源刷新章節清單（預設關＝mihon 原行為，只首次/空清單才抓）。
    val autoRefreshMangaOnOpen: Preference<Boolean> = preferenceStore.getBoolean("auto_refresh_manga_on_open", false)

    val showContinueReadingButton: Preference<Boolean> = preferenceStore.getBoolean(
        "display_continue_reading_button",
        false,
    )

    // Yakuyomi：書庫浮動搜尋列（借鏡 J2K/Yokai 單手 UX）。開啟＝搜尋入口從頂部 app bar 移到底部浮動 pill。
    val floatingSearchBar: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_floating_search_bar",
        false,
    )

    // Yakuyomi：書庫下拉更新（swipe-to-refresh）開關。預設關——已有預設開的自動載入書目資訊，
    // 避免書庫下拉誤觸發整庫大量更新。開＝恢復 mihon 原生下拉更新。
    val swipeToRefresh: Preference<Boolean> = preferenceStore.getBoolean(
        "library_swipe_to_refresh",
        false,
    )

    // Yakuyomi：書庫已儲存搜尋（每筆編碼 "namequery"，名稱/查詢字串不得含此分隔字元）。
    val savedSearches: Preference<Set<String>> = preferenceStore.getStringSet(
        "library_saved_searches",
        emptySet(),
    )

    // Yakuyomi：書庫手動排序（拖放）——每分類一份有序 manga id 清單（comma-joined）；零 DB 改動。
    fun manualOrderForCategory(categoryId: Long): Preference<List<Long>> =
        preferenceStore.getLongArray("library_manual_order_category_$categoryId", emptyList())

    val markDuplicateReadChapterAsRead: Preference<Set<String>> = preferenceStore.getStringSet(
        "mark_duplicate_read_chapter_read",
        emptySet(),
    )

    // region Filter

    val filterDownloaded: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_library_downloaded_v2",
        TriState.DISABLED,
    )

    val filterUnread: Preference<TriState> = preferenceStore.getEnum("pref_filter_library_unread_v2", TriState.DISABLED)

    val filterStarted: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_library_started_v2",
        TriState.DISABLED,
    )

    val filterBookmarked: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_library_bookmarked_v2",
        TriState.DISABLED,
    )

    val filterCompleted: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_library_completed_v2",
        TriState.DISABLED,
    )

    // Yakuyomi：依「已翻譯」狀態篩選（雙向 TriState，對齊 filterDownloaded）。
    val filterTranslated: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_library_translated",
        TriState.DISABLED,
    )

    val filterIntervalCustom: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_library_interval_custom",
        TriState.DISABLED,
    )

    fun filterTracking(id: Int): Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_library_tracked_${id}_v2",
        TriState.DISABLED,
    )

    // endregion

    // region Badges

    val downloadBadge: Preference<Boolean> = preferenceStore.getBoolean("display_download_badge", false)

    val translationBadge: Preference<Boolean> = preferenceStore.getBoolean("display_translation_badge", true)

    val unreadBadge: Preference<Boolean> = preferenceStore.getBoolean("display_unread_badge", true)

    val localBadge: Preference<Boolean> = preferenceStore.getBoolean("display_local_badge", true)

    val languageBadge: Preference<Boolean> = preferenceStore.getBoolean("display_language_badge", false)

    val newShowUpdatesCount: Preference<Boolean> = preferenceStore.getBoolean("library_show_updates_count", true)
    val newUpdatesCount: Preference<Int> = preferenceStore.getInt(
        Preference.appStateKey("library_unseen_updates_count"),
        0,
    )

    // endregion

    // region Category

    val defaultCategory: Preference<Int> = preferenceStore.getInt(DEFAULT_CATEGORY_PREF_KEY, -1)

    val lastUsedCategory: Preference<Int> = preferenceStore.getInt(Preference.appStateKey("last_used_category"), 0)

    // Yakuyomi：加入書庫「選擇分類」對話框上次選過的分類組（id 字串）。新書目自動帶入勾選 → 確認即可、不必每次重選。
    val lastUsedCategories: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("last_used_categories"),
        emptySet(),
    )

    // Yakuyomi：新書目加入書庫時自動帶入上次選過的分類（[lastUsedCategories]）。預設開；關＝沿用原本「全不勾」。
    val rememberLastCategorySelection: Preference<Boolean> = preferenceStore.getBoolean(
        "remember_last_category_selection",
        true,
    )

    val categoryTabs: Preference<Boolean> = preferenceStore.getBoolean("display_category_tabs", true)

    val categoryNumberOfItems: Preference<Boolean> = preferenceStore.getBoolean("display_number_of_items", false)

    // Yakuyomi：每分類獨立的排序/顯示設定，預設開（單清單模式的每分類排序標頭靠此）。
    val categorizedDisplaySettings: Preference<Boolean> = preferenceStore.getBoolean("categorized_display", true)

    // Yakuyomi：把所有分類顯示為單一可摺疊清單（取代頁籤/分頁），預設關＝維持原頁籤行為。
    val singleListCollapsibleCategories: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_library_single_list_collapsible_categories",
        false,
    )

    // Yakuyomi：單一清單模式下已摺疊的分類 id（存成字串集合，零 DB 改動）。
    val collapsedCategoryIds: Preference<Set<String>> = preferenceStore.getStringSet(
        "library_collapsed_category_ids",
        emptySet(),
    )

    val updateCategories: Preference<Set<String>> = preferenceStore.getStringSet(
        LIBRARY_UPDATE_CATEGORIES_PREF_KEY,
        emptySet(),
    )

    val updateCategoriesExclude: Preference<Set<String>> = preferenceStore.getStringSet(
        LIBRARY_UPDATE_CATEGORIES_EXCLUDE_PREF_KEY,
        emptySet(),
    )

    // endregion

    // region Chapter

    val filterChapterByRead: Preference<Long> = preferenceStore.getLong(
        "default_chapter_filter_by_read",
        Manga.SHOW_ALL,
    )

    val filterChapterByDownloaded: Preference<Long> = preferenceStore.getLong(
        "default_chapter_filter_by_downloaded",
        Manga.SHOW_ALL,
    )

    val filterChapterByBookmarked: Preference<Long> = preferenceStore.getLong(
        "default_chapter_filter_by_bookmarked",
        Manga.SHOW_ALL,
    )

    // and upload date
    val sortChapterBySourceOrNumber: Preference<Long> = preferenceStore.getLong(
        "default_chapter_sort_by_source_or_number",
        Manga.CHAPTER_SORTING_SOURCE,
    )

    val displayChapterByNameOrNumber: Preference<Long> = preferenceStore.getLong(
        "default_chapter_display_by_name_or_number",
        Manga.CHAPTER_DISPLAY_NAME,
    )

    val sortChapterByAscendingOrDescending: Preference<Long> = preferenceStore.getLong(
        "default_chapter_sort_by_ascending_or_descending",
        Manga.CHAPTER_SORT_DESC,
    )

    fun setChapterSettingsDefault(manga: Manga) {
        filterChapterByRead.set(manga.unreadFilterRaw)
        filterChapterByDownloaded.set(manga.downloadedFilterRaw)
        filterChapterByBookmarked.set(manga.bookmarkedFilterRaw)
        sortChapterBySourceOrNumber.set(manga.sorting)
        displayChapterByNameOrNumber.set(manga.displayMode)
        sortChapterByAscendingOrDescending.set(
            if (manga.sortDescending()) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC,
        )
    }

    val autoClearChapterCache: Preference<Boolean> = preferenceStore.getBoolean("auto_clear_chapter_cache", false)

    val hideMissingChapters: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_hide_missing_chapter_indicators",
        false,
    )
    // endregion

    // region Swipe Actions

    val swipeToStartAction: Preference<ChapterSwipeAction> = preferenceStore.getEnum(
        "pref_chapter_swipe_end_action",
        ChapterSwipeAction.ToggleBookmark,
    )

    val swipeToEndAction: Preference<ChapterSwipeAction> = preferenceStore.getEnum(
        "pref_chapter_swipe_start_action",
        ChapterSwipeAction.ToggleRead,
    )

    val updateMangaTitles: Preference<Boolean> = preferenceStore.getBoolean("pref_update_library_manga_titles", false)

    val disallowNonAsciiFilenames: Preference<Boolean> = preferenceStore.getBoolean(
        "disallow_non_ascii_filenames",
        false,
    )

    // endregion

    enum class ChapterSwipeAction {
        ToggleRead,
        ToggleBookmark,
        Download,
        Disabled,
    }

    companion object {
        const val DEVICE_ONLY_ON_WIFI = "wifi"
        const val DEVICE_NETWORK_NOT_METERED = "network_not_metered"
        const val DEVICE_CHARGING = "ac"

        const val MANGA_NON_COMPLETED = "manga_ongoing"
        const val MANGA_HAS_UNREAD = "manga_fully_read"
        const val MANGA_NON_READ = "manga_started"
        const val MANGA_OUTSIDE_RELEASE_PERIOD = "manga_outside_release_period"

        const val MARK_DUPLICATE_CHAPTER_READ_NEW = "new"
        const val MARK_DUPLICATE_CHAPTER_READ_EXISTING = "existing"

        const val DEFAULT_CATEGORY_PREF_KEY = "default_category"
        private const val LIBRARY_UPDATE_CATEGORIES_PREF_KEY = "library_update_categories"
        private const val LIBRARY_UPDATE_CATEGORIES_EXCLUDE_PREF_KEY = "library_update_categories_exclude"
        val categoryPreferenceKeys = setOf(
            DEFAULT_CATEGORY_PREF_KEY,
            LIBRARY_UPDATE_CATEGORIES_PREF_KEY,
            LIBRARY_UPDATE_CATEGORIES_EXCLUDE_PREF_KEY,
        )
    }
}
