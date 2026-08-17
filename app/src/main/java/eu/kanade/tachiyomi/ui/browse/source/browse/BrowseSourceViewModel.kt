package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import eu.kanade.core.preference.asState
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.browse.BrowseAnchorLoadManager
import eu.kanade.tachiyomi.data.browse.BrowseFetchManager
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.core.viewmodel.StateViewModel
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
import kotlin.random.Random
import kotlin.time.Clock
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

class BrowseSourceViewModel(
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
    private val addTracks: AddTracks = Injekt.get(),
    private val browseFetchManager: BrowseFetchManager = Injekt.get(),
    private val browseAnchorLoadManager: BrowseAnchorLoadManager = Injekt.get(),
    getIncognitoState: GetIncognitoState = Injekt.get(),
) : StateViewModel<BrowseSourceViewModel.State>(
    Listing.valueOf(listingQuery).let { initial ->
        State(
            listing = initial,
            // 記住初始的列表清單（熱門/最新）；非列表清單起手則退回熱門。
            lastListListing = if (initial is Listing.Latest) Listing.Latest else Listing.Popular,
        )
    },
) {

    companion object {
        val SOURCE_ID_KEY = CreationExtras.Key<Long>()
        val LISTING_QUERY_KEY = CreationExtras.Key<String?>()

        val Factory = viewModelFactory {
            initializer {
                BrowseSourceViewModel(
                    sourceId = get(SOURCE_ID_KEY)!!,
                    listingQuery = get(LISTING_QUERY_KEY),
                )
            }
        }
    }

    var displayMode by sourcePreferences.sourceDisplayMode.asState(viewModelScope)

    val source = sourceManager.getOrStub(sourceId)

    init {
        mutableState.update {
            var query: String? = null
            var listing = it.listing

            if (listing is Listing.Search) {
                query = listing.query
                listing = Listing.Search(query, source.getFilterList())
            }

            // Yakuyomi：預設清單＝設定（browseDefaultToLatest 開 + 來源支援最新 → 最新，否則熱門）。
            val defaultListListing = if (sourcePreferences.browseDefaultToLatest.get() && source.supportsLatest) {
                Listing.Latest
            } else {
                Listing.Popular
            }
            // 點來源主體（Popular）進來 → 套預設清單（開關開 + 支援最新則變最新）。
            if (listing is Listing.Popular) {
                listing = defaultListListing
            }

            it.copy(
                listing = listing,
                filters = source.getFilterList(),
                toolbarQuery = query,
                // 「回到列表」鈕的目標：起手就是列表清單（熱門/最新）就記它；快照/搜尋起手則記**預設清單**
                // （非硬編熱門）→ 修「從快照進來時，左邊清單鈕永遠顯示熱門、無視預設設定為最新」。
                lastListListing = if (listing is Listing.Popular || listing is Listing.Latest) {
                    listing
                } else {
                    defaultListListing
                },
            )
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

    // Yakuyomi：每 source「已擷取的書本 url 集合」（持久判準，見 SourcePreferences.browseFetchedUrls）。
    // 擷取寫進來的 initialized=true 會被來源清單重載打回 false → 改以此集合作「已擷取」的可靠來源。
    private val browseFetchedUrls = sourcePreferences.browseFetchedUrls(sourceId)

    // Yakuyomi：全局浮動搜尋開關（與書庫同一顆）。開＝探索頁改用頂部窄 bar＋右下浮動球。
    val floatingSearchBar = libraryPreferences.floatingSearchBar

    // Yakuyomi：探索「錨點」（每 source 一本，值＝mangaUrl）。標「上次處理到這」，瀏覽清單以旗標徽章標出。
    val browseAnchor = sourcePreferences.browseAnchor(sourceId)

    // Yakuyomi：自動載入到錨點的續傳頁碼（>0＝尚未到錨點、可續 → UI 按鈕顯示「繼續載入」）。
    val browseAnchorResumePage = sourcePreferences.browseAnchorResumePage(sourceId)

    // 錨點是否已在「已載入」範圍出現（在過濾前的串流偵測 → 篩選把它濾掉也算數）。自動載入到錨點用此停止。
    private val _anchorReached = MutableStateFlow(false)
    val anchorReached: StateFlow<Boolean> = _anchorReached.asStateFlow()

    // Yakuyomi：錨點本符當前全域篩選會被濾掉、但因錨點被強制留在清單 → true（UI 對該本加區別視覺：暗化 + 灰旗）。
    private val _anchorFilteredOut = MutableStateFlow(false)
    val anchorFilteredOut: StateFlow<Boolean> = _anchorFilteredOut.asStateFlow()

    // Yakuyomi：快照清單刷新觸發。在快照清單上長壓重設錨點→修剪後 bump，令靜態快照清單重建、就地反映修剪結果。
    private val snapshotRefresh = MutableStateFlow(0)

    // Yakuyomi：探索批次擷取詳情＋章節，委派給常駐的 [BrowseFetchManager]（單一全域槽、前景服務保活）：
    // 送出後可離開畫面、前景繼續操作；中止/重送由 manager 管。節流/逐本抓的細節在 manager。
    val browseFetchState: StateFlow<BrowseFetchManager.State> = browseFetchManager.state
    val browseFetchResult: StateFlow<BrowseFetchManager.Result?> = browseFetchManager.result

    /** 送一份清單到背景擷取。回 false＝已有任務在跑（忙線，UI 提示）。 */
    fun startBatchFetch(mangaList: List<Manga>): Boolean = browseFetchManager.start(sourceId, mangaList)

    fun cancelBatchFetch() = browseFetchManager.cancel()

    fun consumeFetchResult() = browseFetchManager.consumeResult()

    // Yakuyomi：自動載入到錨點改成背景任務（週期冷卻防 ban、可停、完成存快照），委派給 [BrowseAnchorLoadManager]。
    val anchorLoadState: StateFlow<BrowseAnchorLoadManager.State> = browseAnchorLoadManager.state
    val anchorLoadResult: StateFlow<BrowseAnchorLoadManager.Result?> = browseAnchorLoadManager.result

    /** 開始背景載入到本源錨點。回 false＝忙線 / 無錨點 / 來源不可用。 */
    fun startAnchorLoad(): Boolean = browseAnchorLoadManager.start(sourceId, browseAnchor.get())

    fun cancelAnchorLoad() = browseAnchorLoadManager.cancel()

    fun consumeAnchorLoadResult() = browseAnchorLoadManager.consumeResult()

    val mangaPagerFlowFlow = state.map { it.listing }
        .distinctUntilChanged()
        .map { listing ->
            _anchorReached.value = false // 換 listing → 重置錨點偵測
            _anchorFilteredOut.value = false // 換 listing → 重置「錨點被濾掉」標記
            val cached = if (listing is Listing.Snapshot) {
                // 快照：離線從 DB 讀存好的 urls 做成靜態 PagingData，零連線（避開 listing 分頁 request＝ban 大頭）。
                // 對 snapshotRefresh 反應（初始建一次 + 在快照清單重設錨點修剪後 bump→重建）；爬取每批不 bump、不會每分鐘重掃。
                snapshotRefresh
                    .map { PagingData.from(buildSnapshotItems()) }
                    .cachedIn(viewModelScope)
            } else {
                // Pager 只跟著 listing（熱門/最新/搜尋）建立並 cachedIn → 只有切換 listing 才重抓來源。
                // 防 ban：預設 prefetchDistance=pageSize=25、initialLoadSize=3×=75 會讓「一搜/一捲動」提前連抓好幾頁
                // 湊緩衝（漫画柜這類敏感來源＝短時間對搜尋 endpoint 連爆 → 封 IP）。改成幾乎不提前預抓（prefetchDistance=3）、
                // 初始不 3×（initialLoadSize=pageSize）→ 送出的分頁請求數 ≈ 真人翻頁數。代價：捲到接近底部才載下一頁、略頓。
                Pager(
                    PagingConfig(
                        pageSize = 25,
                        initialLoadSize = 25,
                        prefetchDistance = 3,
                    ),
                ) {
                    getRemoteManga(sourceId, listing.query ?: "", listing.filters)
                }.flow.map { pagingData ->
                    pagingData.map { manga ->
                        // 過濾前偵測錨點：來源回傳到這本（不管之後顯不顯示）→ 標記已抵達錨點。
                        if (browseAnchor.get().let { it.isNotEmpty() && it == manga.url }) {
                            _anchorReached.value = true
                        }
                        getManga.subscribe(manga.url, manga.source)
                            .map { it ?: manga }
                            .stateIn(viewModelScope)
                    }
                }
                    .cachedIn(viewModelScope)
            }
            // 全域篩選套在 cachedIn 之後 → 改篩選只對「已載入的清單」就地重新過濾，不重抓來源、不回第 1 頁。
            combine(
                cached,
                browseFilterFavorite.changes(),
                browseFilterRead.changes(),
                browseFilterFetched.changes(),
                browseFetchedUrls.changes(),
            ) { pagingData, favFilter, readFilter, fetchedFilter, fetchedUrls ->
                pagingData.filter { stateFlow ->
                    val m = stateFlow.value
                    val passes = run passes@{
                        if (hideInLibraryItems && m.favorite) return@passes false
                        // 收藏（三態）
                        if (favFilter == TriState.ENABLED_IS && !m.favorite) return@passes false
                        if (favFilter == TriState.ENABLED_NOT && m.favorite) return@passes false
                        // 開卷＝該本任一章「已讀 or 有閱讀進度（lastPageRead>0）」：有讀過就算，不限整話讀完。
                        // 只在「開卷/擷取」任一啟用時查 DB（擷取判定要用到 started，見下）。
                        val started = (readFilter != TriState.DISABLED || fetchedFilter != TriState.DISABLED) &&
                            m.id > 0L &&
                            getChaptersByMangaId.await(m.id).any { it.read || it.lastPageRead > 0 }
                        if (readFilter != TriState.DISABLED) {
                            if (readFilter == TriState.ENABLED_IS && !started) return@passes false
                            if (readFilter == TriState.ENABLED_NOT && started) return@passes false
                        }
                        // 擷取＝這本詳情「曾經」被載入過。判準以 **DB 持久 initialized** 為準（用 id 直查 DB、與上面 started 同法），
                        // **不**用分頁項的 m.initialized——後者是「當前來源清單解析出的最新資訊」，對「剛擷取完但分頁快照未同步/
                        // 來源清單只回基本資訊」的情形會是舊的 false → 造成「明明擷取過卻濾不掉」。開卷過必定已擷取 → 併入 started。
                        if (fetchedFilter != TriState.DISABLED) {
                            // fetchedUrls＝本 source 的持久「已擷取」集合（免疫 initialized 被來源清單重載打回 false）。
                            val fetched = started ||
                                fetchedUrls.contains(m.url) ||
                                m.initialized ||
                                (m.id > 0L && getManga.await(m.id)?.initialized == true)
                            if (fetchedFilter == TriState.ENABLED_IS && !fetched) return@passes false
                            if (fetchedFilter == TriState.ENABLED_NOT && fetched) return@passes false
                        }
                        true
                    }
                    // Yakuyomi：錨點永遠保留在清單；若不符當前篩選 → 標記讓 UI 加區別視覺（暗化 + 灰旗）。
                    val isAnchorManga = browseAnchor.get().let { it.isNotEmpty() && it == m.url }
                    if (isAnchorManga) {
                        _anchorFilteredOut.value = !passes
                        true
                    } else {
                        passes
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyFlow())

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

    // Yakuyomi：離開探索（此來源頁真正被 pop、ViewModel 被清）時自動清掉全域篩選——狀態不留到下次回來。
    // 進 manga 詳情再返回不觸發（ViewModelStoreOwner 留在返回堆疊、不 clear），故瀏覽當下的篩選不會被清。
    override fun onCleared() {
        super.onCleared()
        clearGlobalFilters()
    }

    /**
     * 設此 source 的錨點為這本（取代舊的）。錨點更新後修剪快照（砍掉錨點之後的更舊項，錨點成為最後一筆）。
     * 修剪（解析大 json）挪到 IO 免卡 UI；修剪後 bump snapshotRefresh → 若正停在快照清單（長壓重設錨點）就地刷新。
     */
    fun setAnchor(manga: Manga) {
        browseAnchor.set(manga.url) // 同步：讓錨點旗標即時反映
        viewModelScope.launchIO {
            browseAnchorLoadManager.trimSnapshotToAnchor(sourceId)
            snapshotRefresh.update { it + 1 }
        }
    }

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

    /** 把目前已載入清單的 urls 存成快照（覆蓋同 source 舊的）。快照產生後修剪（有錨點就砍掉其後的更舊項）。 */
    fun saveSnapshot(urls: List<String>) {
        // 同步寫入：呼叫端隨後切到快照分頁依賴它已寫好。
        browseSnapshot.set(snapshotJson.encodeToString(BrowseSnapshot(System.currentTimeMillis(), urls)))
        viewModelScope.launchIO {
            browseAnchorLoadManager.trimSnapshotToAnchor(sourceId)
            snapshotRefresh.update { it + 1 }
        }
    }

    fun clearSnapshot() {
        browseSnapshot.set("")
        // 清快取＝整個重來 → 續傳頁碼一起歸零，下次「開始」從第 1 頁（否則會從舊斷點續、按鈕也還顯示「繼續」）。
        browseAnchorResumePage.set(0)
    }

    /**
     * 從快照 urls 讀 DB（跳過已被「清除資料庫」清掉的），做成可顯示清單。
     *
     * ★ 明確切到 IO：v0.20.4 起上游把 `ioCoroutineScope` 移除、pager 流改掛 `viewModelScope`
     * （Dispatchers.Main.immediate）——這裡會解析大 json（[readSnapshot]）並逐 url 查 DB，
     * 留在主執行緒等於大快照直接 ANR。
     */
    private suspend fun buildSnapshotItems(): List<StateFlow<Manga>> = withContext(Dispatchers.IO) {
        val snap = readSnapshot() ?: return@withContext emptyList()
        snap.urls.mapNotNull { url ->
            val current = getManga.subscribe(url, sourceId).first() ?: return@mapNotNull null
            getManga.subscribe(url, sourceId).map { it ?: current }.stateIn(viewModelScope)
        }
    }

    // Yakuyomi：欄數依封面最小寬度自適應（跨手機/平板/折疊機自動），與書庫共用同一個設定。
    fun getColumnsPreference(): GridCells {
        return GridCells.Adaptive(libraryPreferences.gridCoverMinWidth.get().dp)
    }

    fun resetFilters() {
        mutableState.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        mutableState.update {
            it.copy(
                listing = listing,
                toolbarQuery = null,
                // 切到熱門/最新時更新「最後所在的列表清單」；快照/搜尋不覆蓋，留給頂部清單鈕顯示並返回。
                lastListListing = if (listing is Listing.Popular || listing is Listing.Latest) {
                    listing
                } else {
                    it.lastListListing
                },
            )
        }
    }

    fun setFilters(filters: FilterList) {
        mutableState.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
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
        viewModelScope.launch {
            var new = manga.copy(
                favorite = !manga.favorite,
                dateAdded = when (manga.favorite) {
                    true -> 0
                    false -> Clock.System.now().toEpochMilliseconds()
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
        viewModelScope.launch {
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
                    // Yakuyomi：新書目（尚無分類）→ 帶入上次選過的分類（只留仍存在的），確認即可、不必每次重選。
                    val preselectedIds = getCategories.await(manga.id).map { it.id }
                        .ifEmpty { lastUsedCategoryIds(categories) }
                    setDialog(
                        Dialog.ChangeMangaCategory(
                            manga,
                            categories.mapAsCheckboxState { it.id in preselectedIds },
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
        rememberLastUsedCategories(categoryIds)
        viewModelScope.launchIO {
            setMangaCategories.await(
                mangaId = manga.id,
                categoryIds = categoryIds.toList(),
            )
        }
    }

    /** Yakuyomi：上次在「選擇分類」對話框選過的分類（過濾掉已刪除者）；設定關閉時回空＝不預先勾選。 */
    private fun lastUsedCategoryIds(categories: List<Category>): List<Long> {
        if (!libraryPreferences.rememberLastCategorySelection.get()) return emptyList()
        return libraryPreferences.lastUsedCategories.get()
            .mapNotNull { it.toLongOrNull() }
            .filter { id -> categories.any { it.id == id } }
    }

    /** Yakuyomi：記住這次手動選的分類組（空＝不更新，保留上次）。 */
    private fun rememberLastUsedCategories(categoryIds: List<Long>) {
        if (categoryIds.isNotEmpty()) {
            libraryPreferences.lastUsedCategories.set(categoryIds.map { it.toString() }.toSet())
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
            val initialSelection: List<CheckboxState.State<Category>>,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
        // Yakuyomi：最後所在的「列表清單」（熱門/最新）。快照/搜尋時頂部清單鈕顯示並返回它；
        // 存在 ViewModel → 跨導航（進漫畫再返回）保留，不像 Composable remember 會被重置。
        val lastListListing: Listing = Listing.Popular,
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }
}

/** Yakuyomi：探索快照（離線清單）序列化模型。每 source 一份，存進 SourcePreferences.browseSnapshot。 */
@Serializable
data class BrowseSnapshot(val timestamp: Long, val urls: List<String>)
