package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import kotlin.random.Random
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

class BrowseSourceScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    sourceManager: SourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getRemoteManga: GetRemoteManga = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
) : StateScreenModel<BrowseSourceScreenModel.State>(State(Listing.valueOf(listingQuery))) {

    var displayMode by sourcePreferences.sourceDisplayMode.asState(screenModelScope)

    val source = sourceManager.getOrStub(sourceId)

    init {
        if (source is CatalogueSource) {
            mutableState.update {
                var query: String? = null
                var listing = it.listing

                if (listing is Listing.Search) {
                    query = listing.query
                    listing = Listing.Search(query, source.getFilterList())
                }

                it.copy(
                    listing = listing,
                    filters = source.getFilterList(),
                    toolbarQuery = query,
                )
            }
        }

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedSource.set(source.id)
        }
    }

    /**
     * Flow of Pager flow tied to [State.listing]
     */
    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()

    // Yakuyomi：探索全域篩選（收藏/開卷/擷取三態，跨所有來源、與 source 自帶 extension filter 獨立）。
    val browseFilterFavorite = sourcePreferences.browseFilterFavorite
    val browseFilterRead = sourcePreferences.browseFilterRead
    val browseFilterFetched = sourcePreferences.browseFilterFetched

    // Yakuyomi：探索「錨點」（每 source 一本，值＝mangaUrl）。標「上次處理到這」，瀏覽清單以旗標徽章標出。
    val browseAnchor = sourcePreferences.browseAnchor(sourceId)

    // 錨點是否已在「已載入」範圍出現（在過濾前的串流偵測 → 篩選把它濾掉也算數）。自動載入到錨點用此停止。
    private val _anchorReached = MutableStateFlow(false)
    val anchorReached: StateFlow<Boolean> = _anchorReached.asStateFlow()

    // Yakuyomi：探索批次擷取詳情＋章節。對「filter 後留在清單的全部書目」逐一抓詳情 + 同步章節
    // （未擷取→首次擷取、已擷取→更新新章），每筆節流 ~0.8–1.3s + jitter 防被來源限流；失敗收進 [BatchFetch.failedIds]、
    // 結束時 showResults 給 UI 開結果清單逐一檢查（點進詳情頁手動處理、返回保留）。
    @Immutable
    data class BatchFetch(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val failedIds: List<Long> = emptyList(),
        val showResults: Boolean = false,
    )

    private val _batchFetch = MutableStateFlow(BatchFetch())
    val batchFetch: StateFlow<BatchFetch> = _batchFetch.asStateFlow()
    private var batchFetchJob: Job? = null

    /** 開始批次擷取。[mangaList]＝呼叫端傳入的「filter 後留在清單的全部書目」（含已擷取＝更新章節）。 */
    fun startBatchFetch(mangaList: List<Manga>) {
        if (_batchFetch.value.running || mangaList.isEmpty()) return
        batchFetchJob = screenModelScope.launchIO {
            val failed = mutableListOf<Long>()
            _batchFetch.value = BatchFetch(running = true, done = 0, total = mangaList.size)
            try {
                mangaList.forEachIndexed { i, manga ->
                    if (!isActive) return@forEachIndexed
                    val ok = runCatching { fetchOneFromSource(manga) }.isSuccess
                    if (!ok) failed.add(manga.id)
                    _batchFetch.update { it.copy(done = i + 1, failedIds = failed.toList()) }
                    // 節流防 ban：每筆間隔 0.4–0.75s + 隨機抖動（避免規律被偵測）。最後一筆不等。
                    if (i < mangaList.lastIndex && isActive) delay(400L + Random.nextLong(350L))
                }
            } finally {
                _batchFetch.update { it.copy(running = false, showResults = it.failedIds.isNotEmpty()) }
            }
        }
    }

    fun cancelBatchFetch() {
        batchFetchJob?.cancel()
        _batchFetch.update { it.copy(running = false) }
    }

    fun dismissBatchResults() {
        _batchFetch.update { it.copy(showResults = false) }
    }

    /** 抓一本：詳情（[UpdateManga] 設 initialized=true）+ 同步章節（[SyncChaptersWithSource] diff 加新章）。重用 mihon 既有路徑。 */
    private suspend fun fetchOneFromSource(manga: Manga) {
        val networkManga = source.getMangaDetails(manga.toSManga())
        updateManga.awaitUpdateFromSource(manga, networkManga, manualFetch = false)
        val chapters = source.getChapterList(manga.toSManga())
        syncChaptersWithSource.await(chapters, manga, source, manualFetch = false)
    }

    val mangaPagerFlowFlow = state.map { it.listing }
        .distinctUntilChanged()
        .map { listing ->
            _anchorReached.value = false // 換 listing → 重置錨點偵測
            val cached = if (listing is Listing.Snapshot) {
                // 快照：離線從 DB 讀存好的 urls 做成靜態 PagingData，零連線（避開 listing 分頁 request＝ban 大頭）。
                flow { emit(PagingData.from(buildSnapshotItems())) }
                    .cachedIn(ioCoroutineScope)
            } else {
                // Pager 只跟著 listing（熱門/最新/搜尋）建立並 cachedIn → 只有切換 listing 才重抓來源。
                Pager(PagingConfig(pageSize = 25)) {
                    getRemoteManga(sourceId, listing.query ?: "", listing.filters)
                }.flow.map { pagingData ->
                    pagingData.map { manga ->
                        // 過濾前偵測錨點：來源回傳到這本（不管之後顯不顯示）→ 標記已抵達錨點。
                        if (browseAnchor.get().let { it.isNotEmpty() && it == manga.url }) {
                            _anchorReached.value = true
                        }
                        getManga.subscribe(manga.url, manga.source)
                            .map { it ?: manga }
                            .stateIn(ioCoroutineScope)
                    }
                }
                    .cachedIn(ioCoroutineScope)
            }
            // 全域篩選套在 cachedIn 之後 → 改篩選只對「已載入的清單」就地重新過濾，不重抓來源、不回第 1 頁。
            combine(
                cached,
                browseFilterFavorite.changes(),
                browseFilterRead.changes(),
                browseFilterFetched.changes(),
            ) { pagingData, favFilter, readFilter, fetchedFilter ->
                pagingData.filter { stateFlow ->
                    val m = stateFlow.value
                    if (hideInLibraryItems && m.favorite) return@filter false
                    // 收藏（三態）
                    if (favFilter == TriState.ENABLED_IS && !m.favorite) return@filter false
                    if (favFilter == TriState.ENABLED_NOT && m.favorite) return@filter false
                    // 開卷＝該本任一章「已讀 or 有閱讀進度（lastPageRead>0）」：有讀過就算，不限整話讀完。只在啟用時查 DB。
                    if (readFilter != TriState.DISABLED) {
                        val started = m.id > 0L &&
                            getChaptersByMangaId.await(m.id).any { it.read || it.lastPageRead > 0 }
                        if (readFilter == TriState.ENABLED_IS && !started) return@filter false
                        if (readFilter == TriState.ENABLED_NOT && started) return@filter false
                    }
                    // 擷取＝詳情曾被載入過（initialized：曾點進去/載過書目說明）；未擷取＝全新沒點過。
                    if (fetchedFilter != TriState.DISABLED) {
                        val fetched = m.initialized
                        if (fetchedFilter == TriState.ENABLED_IS && !fetched) return@filter false
                        if (fetchedFilter == TriState.ENABLED_NOT && fetched) return@filter false
                    }
                    true
                }
            }
        }
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, emptyFlow())

    /** 全域篩選三態循環（忽略→只顯示→只不顯示→忽略），對齊書庫的 toggleFilter。 */
    fun toggleGlobalFavoriteFilter() = browseFilterFavorite.set(browseFilterFavorite.get().next())
    fun toggleGlobalReadFilter() = browseFilterRead.set(browseFilterRead.get().next())
    fun toggleGlobalFetchedFilter() = browseFilterFetched.set(browseFilterFetched.get().next())

    /** 清除全域篩選（收藏/開卷/擷取皆設回忽略）。 */
    fun clearGlobalFilters() {
        browseFilterFavorite.set(TriState.DISABLED)
        browseFilterRead.set(TriState.DISABLED)
        browseFilterFetched.set(TriState.DISABLED)
    }

    /** 設此 source 的錨點為這本（取代舊的）。 */
    fun setAnchor(manga: Manga) = browseAnchor.set(manga.url)

    /** 清除此 source 的錨點。 */
    fun clearAnchor() = browseAnchor.set("")

    /** 切換：已是錨點→清除，否則設為錨點。 */
    fun toggleAnchor(manga: Manga) {
        if (browseAnchor.get() == manga.url) clearAnchor() else setAnchor(manga)
    }

    // ── 快照（離線清單，每 source 一份）──────────────────────────
    val browseSnapshot = sourcePreferences.browseSnapshot(sourceId)
    private val snapshotJson = Json { ignoreUnknownKeys = true }

    fun readSnapshot(): BrowseSnapshot? = browseSnapshot.get()
        .takeIf { it.isNotEmpty() }
        ?.let { runCatching { snapshotJson.decodeFromString<BrowseSnapshot>(it) }.getOrNull() }

    fun hasSnapshot(): Boolean = browseSnapshot.get().isNotEmpty()

    /** 把目前已載入清單的 urls 存成快照（覆蓋同 source 舊的）。 */
    fun saveSnapshot(urls: List<String>) {
        browseSnapshot.set(snapshotJson.encodeToString(BrowseSnapshot(System.currentTimeMillis(), urls)))
    }

    fun clearSnapshot() = browseSnapshot.set("")

    /** 從快照 urls 讀 DB（跳過已被「清除資料庫」清掉的），做成可顯示清單。 */
    private suspend fun buildSnapshotItems(): List<StateFlow<Manga>> {
        val snap = readSnapshot() ?: return emptyList()
        return snap.urls.mapNotNull { url ->
            val current = getManga.subscribe(url, sourceId).first() ?: return@mapNotNull null
            getManga.subscribe(url, sourceId).map { it ?: current }.stateIn(ioCoroutineScope)
        }
    }

    // Yakuyomi：欄數依封面最小寬度自適應（跨手機/平板/折疊機自動），與書庫共用同一個設定。
    fun getColumnsPreference(): GridCells {
        return GridCells.Adaptive(libraryPreferences.gridCoverMinWidth.get().dp)
    }

    fun resetFilters() {
        if (source !is CatalogueSource) return

        mutableState.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        mutableState.update { it.copy(listing = listing, toolbarQuery = null) }
    }

    fun setFilters(filters: FilterList) {
        if (source !is CatalogueSource) return

        mutableState.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        if (source !is CatalogueSource) return

        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = source.getFilterList())

        mutableState.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = query ?: input.query,
            )
        }
    }

    fun searchGenre(genreName: String) {
        if (source !is CatalogueSource) return

        val defaultFilters = source.getFilterList()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is SourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is SourceModelFilter<*> && filter.name.equals(genreName, true)) {
                        when (filter) {
                            is SourceModelFilter.TriState -> filter.state = 1
                            is SourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is SourceModelFilter.Select<*>) {
                val index = sourceFilter.values.filterIsInstance<String>()
                    .indexOfFirst { it.equals(genreName, true) }

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            }
        }

        mutableState.update {
            val listing = if (genreExists) {
                Listing.Search(query = null, filters = defaultFilters)
            } else {
                Listing.Search(query = genreName, filters = defaultFilters)
            }
            it.copy(
                filters = defaultFilters,
                listing = listing,
                toolbarQuery = listing.query,
            )
        }
    }

    /**
     * Adds or removes a manga from the library.
     *
     * @param manga the manga to update.
     */
    fun changeMangaFavorite(manga: Manga) {
        screenModelScope.launch {
            var new = manga.copy(
                favorite = !manga.favorite,
                dateAdded = when (manga.favorite) {
                    true -> 0
                    false -> Instant.now().toEpochMilli()
                },
            )

            if (!new.favorite) {
                new = new.removeCovers(coverCache)
            } else {
                setMangaDefaultChapterFlags.await(manga)
                addTracks.bindEnhancedTrackers(manga, source)
            }

            updateManga.await(new.toMangaUpdate())
        }
    }

    fun addFavorite(manga: Manga) {
        screenModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    moveMangaToCategories(manga, defaultCategory)

                    changeMangaFavorite(manga)
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    moveMangaToCategories(manga)

                    changeMangaFavorite(manga)
                }

                // Choose a category
                else -> {
                    val preselectedIds = getCategories.await(manga.id).map { it.id }
                    setDialog(
                        Dialog.ChangeMangaCategory(
                            manga,
                            categories.mapAsCheckboxState { it.id in preselectedIds }.toImmutableList(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    suspend fun getDuplicateLibraryManga(manga: Manga): List<MangaWithChapterCount> {
        return getDuplicateLibraryManga.invoke(manga)
    }

    private fun moveMangaToCategories(manga: Manga, vararg categories: Category) {
        moveMangaToCategories(manga, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveMangaToCategories(manga: Manga, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setMangaCategories.await(
                mangaId = manga.id,
                categoryIds = categoryIds.toList(),
            )
        }
    }

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteManga.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteManga.QUERY_LATEST, filters = FilterList())

        // Yakuyomi：離線快照清單（不連線，從 DB 讀存好的 urls）。帶 sentinel query 以便從來源列表直接導航進來。
        data object Snapshot : Listing(query = SNAPSHOT_QUERY, filters = FilterList())
        data class Search(
            override val query: String?,
            override val filters: FilterList,
        ) : Listing(query = query, filters = filters)

        companion object {
            // Yakuyomi：快照清單的 sentinel query（不會與真實搜尋字串相撞）。
            const val SNAPSHOT_QUERY = "__yakuyomi_snapshot__"

            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteManga.QUERY_POPULAR -> Popular
                    GetRemoteManga.QUERY_LATEST -> Latest
                    SNAPSHOT_QUERY -> Snapshot
                    else -> Search(query = query, filters = FilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog

        // Yakuyomi：長按漫畫 → 動作選單（加入/移除書庫 + 設/清錨點）。
        data class MangaActions(val manga: Manga) : Dialog
        data class RemoveManga(val manga: Manga) : Dialog
        data class AddDuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class ChangeMangaCategory(
            val manga: Manga,
            val initialSelection: ImmutableList<CheckboxState.State<Category>>,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }
}

/** Yakuyomi：探索快照（離線清單）序列化模型。每 source 一份，存進 SourcePreferences.browseSnapshot。 */
@Serializable
data class BrowseSnapshot(val timestamp: Long, val urls: List<String>)
