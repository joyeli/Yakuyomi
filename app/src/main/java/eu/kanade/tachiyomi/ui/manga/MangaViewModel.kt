package eu.kanade.tachiyomi.ui.manga

import android.app.Application
import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.util.fastAny
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterNightStatus
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.nightread.NightPages
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.translation.TranslationCache
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.capture.readMangaMeta
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.floor

class MangaViewModel(
    private val context: Context,
    private val mangaId: Long,
    private val isFromSource: Boolean,
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    trackPreferences: TrackPreferences = Injekt.get(),
    readerPreferences: ReaderPreferences = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val translationManager: TranslationManager = Injekt.get(),
    private val translationCache: TranslationCache = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val getMangaAndChapters: GetMangaWithChapters = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getAvailableScanlators: GetAvailableScanlators = Injekt.get(),
    private val getExcludedScanlators: GetExcludedScanlators = Injekt.get(),
    private val setExcludedScanlators: SetExcludedScanlators = Injekt.get(),
    private val setMangaChapterFlags: SetMangaChapterFlags = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val filterChaptersForDownload: FilterChaptersForDownload = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateViewModel<MangaViewModel.State>(State.Loading) {

    companion object {
        val MANGA_ID_KEY = CreationExtras.Key<Long>()

        val IS_FROM_SOURCE_KEY = CreationExtras.Key<Boolean>()

        val Factory = viewModelFactory {
            initializer {
                MangaViewModel(
                    context = Injekt.get<Application>(),
                    mangaId = get(MANGA_ID_KEY)!!,
                    isFromSource = get(IS_FROM_SOURCE_KEY)!!,
                )
            }
        }
    }

    private val successState: State.Success?
        get() = state.value as? State.Success

    val manga: Manga?
        get() = successState?.manga

    val source: Source?
        get() = successState?.source

    private val isFavorited: Boolean
        get() = manga?.favorite ?: false

    private val allChapters: List<ChapterList.Item>?
        get() = successState?.chapters

    private val filteredChapters: List<ChapterList.Item>?
        get() = successState?.processedChapters

    val chapterSwipeStartAction = libraryPreferences.swipeToEndAction.get()
    val chapterSwipeEndAction = libraryPreferences.swipeToStartAction.get()
    var autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead.get()

    private val skipFiltered by readerPreferences.skipFiltered.asState(viewModelScope)

    val isUpdateIntervalEnabled =
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateMangaRestrictions.get()

    private val selectedPositions: Array<Int> = arrayOf(-1, -1) // first and last selected index in list
    private val selectedChapterIds: HashSet<Long> = HashSet()

    // Yakuyomi：探索錨點（詳情頁入口）。⚠️ 必須宣告在 init{} 之前——init 啟動的 IO coroutine 會用到；
    // Kotlin 按宣告順序初始化，宣告在 init 之後曾造成 coroutine 搶先取用 → NPE 閃退（不定期、race）。
    private val sourcePreferences: eu.kanade.domain.source.service.SourcePreferences = Injekt.get()
    private val browseAnchorLoadManager: eu.kanade.tachiyomi.data.browse.BrowseAnchorLoadManager = Injekt.get()

    /**
     * Helper function to update the UI state only if it's currently in success state
     */
    private inline fun updateSuccessState(func: (State.Success) -> State.Success) {
        mutableState.update {
            when (it) {
                State.Loading -> it
                is State.Success -> func(it)
            }
        }
    }

    init {
        viewModelScope.launchIO {
            combine(
                getMangaAndChapters.subscribe(mangaId, applyScanlatorFilter = true).distinctUntilChanged(),
                downloadCache.changes,
                downloadManager.queueState,
                // Yakuyomi：夜讀版磁碟狀態變了（做完一章／翻譯重繪作廢舊版）→ 重掃 hasNightPages，指示器才不會卡在 DONE
                translationManager.nightVersion,
            ) { mangaAndChapters, _, _, _ -> mangaAndChapters }
                .collectLatest { (manga, chapters) ->
                    val items = chapters.toChapterListItems(manga)
                    updateSuccessState {
                        it.copy(
                            manga = manga,
                            // 佇列狀態在寫入當下才讀（見 currentTranslationObservation）
                            chapters = items.withTranslationState(currentTranslationObservation()),
                        )
                    }
                }
        }

        viewModelScope.launchIO {
            getExcludedScanlators.subscribe(mangaId)
                .distinctUntilChanged()
                .collectLatest { excludedScanlators ->
                    updateSuccessState {
                        it.copy(excludedScanlators = excludedScanlators)
                    }
                }
        }

        viewModelScope.launchIO {
            getAvailableScanlators.subscribe(mangaId)
                .distinctUntilChanged()
                .collectLatest { availableScanlators ->
                    updateSuccessState {
                        it.copy(availableScanlators = availableScanlators)
                    }
                }
        }

        observeDownloads()
        observeTranslations()

        // Yakuyomi：觀察本源錨點 pref → 更新 State.isAnchor，讓詳情頁「設為錨點」動作鈕在設/取消時即時反映。
        viewModelScope.launchIO {
            val manga = getMangaAndChapters.awaitManga(mangaId)
            sourcePreferences.browseAnchor(manga.source).changes()
                .distinctUntilChanged()
                .collectLatest { anchorUrl ->
                    updateSuccessState { it.copy(isAnchor = anchorUrl.isNotEmpty() && anchorUrl == manga.url) }
                }
        }

        viewModelScope.launchIO {
            val manga = getMangaAndChapters.awaitManga(mangaId)
            val chapters = getMangaAndChapters.awaitChapters(mangaId, applyScanlatorFilter = true)
                .toChapterListItems(manga)

            if (!manga.favorite) {
                setMangaDefaultChapterFlags.await(manga)
            }

            // Yakuyomi：開啟漫畫時自動刷新章節（設定開啟）→ 每次進詳情頁都向來源抓最新（非只首次/空清單）。
            val autoRefreshOnOpen = libraryPreferences.autoRefreshMangaOnOpen.get()
            val needRefreshInfo = !manga.initialized || autoRefreshOnOpen
            val needRefreshChapter = chapters.isEmpty() || autoRefreshOnOpen

            // 兩個 DB 查詢先做完：下面寫入時最後才讀佇列狀態（具名參數由左到右求值，查詢放在後面的話佇列狀態會先讀、
            // 查詢期間的佇列變化在 Loading 時是 no-op，就被這份較舊的值蓋掉）
            val availableScanlators = getAvailableScanlators.await(mangaId)
            val excludedScanlators = getExcludedScanlators.await(mangaId)

            // Show what we have earlier
            mutableState.update {
                State.Success(
                    manga = manga,
                    source = Injekt.get<SourceManager>().getOrStub(manga.source),
                    isFromSource = isFromSource,
                    // 佇列狀態在寫入當下才讀：Loading 期間佇列觀察者的更新是 no-op，這裡不能用掃描前的快照
                    chapters = chapters.withTranslationState(currentTranslationObservation()),
                    availableScanlators = availableScanlators,
                    excludedScanlators = excludedScanlators,
                    isRefreshingData = needRefreshInfo || needRefreshChapter,
                    dialog = null,
                    hideMissingChapters = libraryPreferences.hideMissingChapters.get(),
                    pullToRefresh = libraryPreferences.swipeToRefresh.get(),
                    isAnchor = sourcePreferences.browseAnchor(manga.source).get()
                        .let { it.isNotEmpty() && it == manga.url },
                )
            }

            // Start observe tracking since it only needs mangaId
            observeTrackers()

            // Fetch info-chapters when needed
            if ((needRefreshInfo || needRefreshChapter) && viewModelScope.isActive) {
                fetchAllFromSource(
                    manualFetch = false,
                    fetchDetails = needRefreshInfo,
                    fetchChapters = needRefreshChapter,
                )
            }

            // Initial loading finished
            updateSuccessState { it.copy(isRefreshingData = false) }
        }
    }

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        viewModelScope.launch {
            updateSuccessState { it.copy(isRefreshingData = true) }
            fetchAllFromSource(
                manualFetch = manualFetch,
                fetchDetails = true,
                fetchChapters = true,
            )
            updateSuccessState { it.copy(isRefreshingData = false) }
        }
    }

    private suspend fun fetchAllFromSource(
        manualFetch: Boolean,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) {
        val state = successState ?: return
        try {
            withUIContext {
                val update = updateMangaFromRemote(
                    source = state.source,
                    manga = state.manga,
                    fetchDetails = fetchDetails,
                    fetchChapters = fetchChapters,
                    manualFetch = manualFetch,
                )
                    .getOrThrow()

                // Yakuyomi：成功抓到詳情（updateMangaFromRemote 已把 manga.initialized 設 true）→ 把本書 url 記進所屬
                // source 的「已擷取」持久集合，讓探索「已擷取」篩選對「只點進詳情頁看一眼」的書也永久成立（免疫 re-browse
                // 把 DB initialized 打回 false，與 BrowseFetchManager 批次擷取同一機制）。只在真的抓詳情時記（fetchDetails）、
                // 排除 local（本地漫畫無探索篩選意義）；集合單調累積、getAndSet 併集冪等（重複點同一本不出問題）。
                if (fetchDetails && !state.manga.isLocal()) {
                    sourcePreferences.browseFetchedUrls(state.manga.source)
                        .getAndSet { it + state.manga.url }
                }

                if (manualFetch) {
                    downloadNewChapters(update.newChapters)
                }
            }
        } catch (_: CancellationException) {
            // ignore
        } catch (e: Exception) {
            val message = if (e is NoChaptersException) {
                context.stringResource(MR.strings.no_chapters_error)
            } else {
                logcat(LogPriority.ERROR, e)
                with(context) { e.formattedMessage }
            }

            viewModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
        }
    }

    // Manga info - start

    fun toggleFavorite() {
        toggleFavorite(
            onRemoved = {
                viewModelScope.launch {
                    if (!hasDownloads()) return@launch
                    val result = snackbarHostState.showSnackbar(
                        message = context.stringResource(MR.strings.delete_downloads_for_manga),
                        actionLabel = context.stringResource(MR.strings.action_delete),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        deleteDownloads()
                    }
                }
            },
        )
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     */
    fun toggleFavorite(
        onRemoved: () -> Unit,
        checkDuplicate: Boolean = true,
    ) {
        val state = successState ?: return
        viewModelScope.launchIO {
            val manga = state.manga

            if (isFavorited) {
                // Remove from library
                if (updateManga.awaitUpdateFavorite(manga.id, false)) {
                    // Remove covers and update last modified in db
                    if (manga.removeCovers() != manga) {
                        updateManga.awaitUpdateCoverLastModified(manga.id)
                    }
                    withUIContext { onRemoved() }
                }
            } else {
                // Add to library
                // First, check if duplicate exists if callback is provided
                if (checkDuplicate) {
                    val duplicates = getDuplicateLibraryManga(manga)

                    if (duplicates.isNotEmpty()) {
                        updateSuccessState { it.copy(dialog = Dialog.DuplicateManga(manga, duplicates)) }
                        return@launchIO
                    }
                }

                // Now check if user previously set categories, when available
                val categories = getCategories()
                val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
                val defaultCategory = categories.find { it.id == defaultCategoryId }
                when {
                    // Default category set
                    defaultCategory != null -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launchIO
                        moveMangaToCategory(defaultCategory)
                    }

                    // Automatic 'Default' or no categories
                    defaultCategoryId == 0L || categories.isEmpty() -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launchIO
                        moveMangaToCategory(null)
                    }

                    // Choose a category
                    else -> showChangeCategoryDialog()
                }

                // Finally match with enhanced tracking when available
                addTracks.bindEnhancedTrackers(manga, state.source)
            }
        }
    }

    fun showChangeCategoryDialog() {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            val categories = getCategories()
            // Yakuyomi：新書目（尚無分類）→ 帶入上次選過的分類（只留仍存在的）→ 使用者確認即可、不必每次重選。
            val selection = getMangaCategoryIds(manga).ifEmpty { lastUsedCategoryIds(categories) }
            updateSuccessState { successState ->
                successState.copy(
                    dialog = Dialog.ChangeCategory(
                        manga = manga,
                        initialSelection = categories.mapAsCheckboxState { it.id in selection },
                    ),
                )
            }
        }
    }

    fun showSetFetchIntervalDialog() {
        val manga = successState?.manga ?: return
        updateSuccessState {
            it.copy(dialog = Dialog.SetFetchInterval(manga))
        }
    }

    fun showSetAnchorDialog() {
        successState ?: return
        updateSuccessState { it.copy(dialog = Dialog.SetAnchorConfirm) }
    }

    /** 把這本設成它所屬 source 的探索錨點。錨點更新後修剪快照（砍掉錨點之後的更舊項；解析大 json 挪 IO 免卡 UI）。 */
    fun setBrowseAnchor() {
        val state = successState ?: return
        sourcePreferences.browseAnchor(state.manga.source).set(state.manga.url)
        viewModelScope.launchIO { browseAnchorLoadManager.trimSnapshotToAnchor(state.manga.source) }
    }

    fun setFetchInterval(manga: Manga, interval: Int) {
        viewModelScope.launchIO {
            if (
                updateManga.awaitUpdateFetchInterval(
                    // Custom intervals are negative
                    manga.copy(fetchInterval = -interval),
                )
            ) {
                val updatedManga = mangaRepository.getMangaById(manga.id)
                updateSuccessState { it.copy(manga = updatedManga) }
            }
        }
    }

    /**
     * Returns true if the manga has any downloads.
     */
    private fun hasDownloads(): Boolean {
        val manga = successState?.manga ?: return false
        return downloadManager.getDownloadCount(manga) > 0
    }

    /**
     * Deletes all the downloads for the manga.
     */
    private fun deleteDownloads() {
        val state = successState ?: return
        downloadManager.deleteManga(state.manga, state.source)
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        rememberLastUsedCategories(categories)
        moveMangaToCategory(categories)
        if (manga.favorite) return

        viewModelScope.launchIO {
            updateManga.awaitUpdateFavorite(manga.id, true)
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

    /**
     * Move the given manga to categories.
     *
     * @param categories the selected categories.
     */
    private fun moveMangaToCategories(categories: List<Category>) {
        val categoryIds = categories.map { it.id }
        moveMangaToCategory(categoryIds)
    }

    private fun moveMangaToCategory(categoryIds: List<Long>) {
        viewModelScope.launchIO {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    /**
     * Move the given manga to the category.
     *
     * @param category the selected category, or null for default category.
     */
    private fun moveMangaToCategory(category: Category?) {
        moveMangaToCategories(listOfNotNull(category))
    }

    // Manga info - end

    // Chapters list - start

    private fun observeDownloads() {
        viewModelScope.launchIO {
            downloadManager.statusFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }

        viewModelScope.launchIO {
            downloadManager.progressFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }
    }

    private fun updateDownloadState(download: Download) {
        updateSuccessState { successState ->
            val modifiedIndex = successState.chapters.indexOfFirst { it.id == download.chapter.id }
            if (modifiedIndex < 0) return@updateSuccessState successState

            val newChapters = successState.chapters.toMutableList().apply {
                val item = removeAt(modifiedIndex)
                    .copy(downloadState = download.status, downloadProgress = download.progress)
                add(modifiedIndex, item)
            }
            successState.copy(chapters = newChapters)
        }
    }

    private fun observeTranslations() {
        viewModelScope.launchIO {
            combine(
                translationManager.queueState,
                translationManager.translatedIds,
                translationManager.nightDoneIds,
                translationPreferences.nightReadEnabled.changes(),
            ) { queue, translated, nightDone, nightEnabled ->
                TranslationObservation(queue, translated, nightDone, nightEnabled)
            }
                .collect { withUIContext { updateTranslationState(it) } }
        }
    }

    private fun updateTranslationState(obs: TranslationObservation) {
        updateSuccessState { successState ->
            successState.copy(chapters = successState.chapters.withTranslationState(obs))
        }
    }

    /**
     * 佇列與本 session 集合的**當下**值。章節列掃磁碟（[toChapterListItems]）要好幾秒（SAF），掃完寫入時一律在
     * `updateSuccessState` 的 lambda 裡呼叫這個、重套一次佇列狀態：用掃描開頭的快照的話，掃描期間佇列觀察者套上去的新狀態
     * 會被舊快照蓋回去（例如夜讀做完一章 → nightVersion 觸發重掃、重掃開頭佇列還沒移除那項 → 掃完把指示器寫回「產生中」，
     * 之後佇列沒有新變化就一直轉圈）。
     */
    private fun currentTranslationObservation() = TranslationObservation(
        queue = translationManager.queueState.value,
        translatedIds = translationManager.translatedIds.value,
        nightDoneIds = translationManager.nightDoneIds.value,
        nightEnabled = translationPreferences.nightReadEnabled.get(),
    )

    /**
     * 掃磁碟建章節列：下載狀態與磁碟上的「已翻」「已有夜讀版」「可更新」「能不能夜讀」。佇列與本 session 的狀態不在這裡算——
     * 呼叫端寫入時以 [withTranslationState] 套上當下的值（見 [currentTranslationObservation]）。
     */
    private suspend fun List<Chapter>.toChapterListItems(manga: Manga): List<ChapterList.Item> {
        val isLocal = manga.isLocal()
        val scanContext = currentCoroutineContext()
        return map { chapter ->
            // 每章檢查一次取消：掃描是阻塞的 SAF 呼叫，佇列跑著時 nightVersion 每做完一章就觸發一次重掃，collectLatest
            // 要能在章與章之間停掉舊的那次，不然舊掃描會一直跑完、佔著磁碟
            scanContext.ensureActive()
            val activeDownload = if (isLocal) {
                null
            } else {
                downloadManager.getQueuedDownloadOrNull(chapter.id)
            }
            val downloaded = if (isLocal) {
                true
            } else {
                downloadManager.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    manga.title,
                    manga.source,
                )
            }
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }

            // 磁碟上的「已翻」與夜讀資訊，只對已下載章、同一次找章節夾一起算（SAF 上找章節夾要列目錄）：
            //  - 已翻：manifest 有標記（跨重啟；observer combine 了 downloadCache.changes，下載增刪會重跑這裡刷新）。
            //  - 已有夜讀版：是鬆散圖夾，而且 .yakuyomi/ 有完成標記（std／三檔 l1）或舊版單檔；同一次列目錄順便判「可更新」
            //    （有舊規則產生的頁，見 NightPages 的規則版本）與「能不能夜讀」（壓縮檔章、本機 epub、找不到章節夾 → 夜讀
            //    做不了，不畫夜讀入口）。
            // 夜讀總開關關著也照掃：這裡只在 downloadCache 變動時重跑，若關著跳過，開啟那一刻會先閃一輪 NONE。
            val disk = if (downloaded) translationManager.chapterDiskInfo(manga, chapter) else null
            val nightInfo = disk?.night
            ChapterList.Item(
                chapter = chapter,
                downloadState = downloadState,
                downloadProgress = activeDownload?.progress ?: 0,
                translatedOnDisk = disk?.translated == true,
                selected = chapter.id in selectedChapterIds,
                nightOnDisk = nightInfo != null && nightInfo.summary.state != NightPages.ChapterState.NONE,
                nightOutdated = nightInfo?.summary?.outdated == true,
                nightUnsupported = nightInfo != null && !nightInfo.loose,
            )
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    fun chapterSwipe(chapterItem: ChapterList.Item, swipeAction: LibraryPreferences.ChapterSwipeAction) {
        viewModelScope.launch {
            executeChapterSwipeAction(chapterItem, swipeAction)
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    private fun executeChapterSwipeAction(
        chapterItem: ChapterList.Item,
        swipeAction: LibraryPreferences.ChapterSwipeAction,
    ) {
        val chapter = chapterItem.chapter
        when (swipeAction) {
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                markChaptersRead(listOf(chapter), !chapter.read)
            }
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                bookmarkChapters(listOf(chapter), !chapter.bookmark)
            }
            LibraryPreferences.ChapterSwipeAction.Download -> {
                val downloadAction: ChapterDownloadAction = when (chapterItem.downloadState) {
                    Download.State.ERROR,
                    Download.State.NOT_DOWNLOADED,
                    -> ChapterDownloadAction.START_NOW
                    Download.State.QUEUE,
                    Download.State.DOWNLOADING,
                    -> ChapterDownloadAction.CANCEL
                    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                }
                runChapterDownloadActions(
                    items = listOf(chapterItem),
                    action = downloadAction,
                )
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): Chapter? {
        val successState = successState ?: return null
        return successState.chapters.getNextUnread(successState.manga)
    }

    private fun getUnreadChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> !chapter.read && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun getUnreadChaptersSorted(): List<Chapter> {
        val manga = successState?.manga ?: return emptyList()
        val chaptersSorted = getUnreadChapters().sortedWith(getChapterSort(manga))
        return if (manga.sortDescending()) chaptersSorted.reversed() else chaptersSorted
    }

    private fun getBookmarkedChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> chapter.bookmark && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun startDownload(
        chapters: List<Chapter>,
        startNow: Boolean,
    ) {
        val successState = successState ?: return

        viewModelScope.launchNonCancellable {
            if (startNow) {
                val chapterId = chapters.singleOrNull()?.id ?: return@launchNonCancellable
                downloadManager.startDownloadNow(chapterId)
            } else {
                downloadChapters(chapters)
            }

            if (!isFavorited && !successState.hasPromptedToAddBefore) {
                updateSuccessState { state ->
                    state.copy(hasPromptedToAddBefore = true)
                }
                val result = snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.snack_add_to_library),
                    actionLabel = context.stringResource(MR.strings.action_add),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && !isFavorited) {
                    toggleFavorite()
                }
            }
        }
    }

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) {
        when (action) {
            ChapterDownloadAction.START -> {
                startDownload(items.map { it.chapter }, false)
                if (items.any { it.downloadState == Download.State.ERROR }) {
                    downloadManager.startDownloads()
                }
            }
            ChapterDownloadAction.START_NOW -> {
                val chapter = items.singleOrNull()?.chapter ?: return
                startDownload(listOf(chapter), true)
            }
            ChapterDownloadAction.CANCEL -> {
                val chapterId = items.singleOrNull()?.id ?: return
                cancelDownload(chapterId)
            }
            ChapterDownloadAction.DELETE -> {
                deleteChapters(items.map { it.chapter })
            }
        }
    }

    fun runChapterTranslateAction(items: List<ChapterList.Item>) {
        val manga = successState?.manga ?: return
        // 只翻已下載的章（翻譯對象是下載好的頁圖；未下載的略過，與單列指示器一致）
        var downloaded = items.filter { it.isDownloaded }.map { it.chapter }
        if (downloaded.isEmpty()) return
        // local EPUB：結構化（OPF/xhtml），重壓會破壞 → v1 不支援；過濾並提示，避免排入後翻失敗變紅。
        // （local 資料夾 / cbz / cbr / 7z 皆可就地翻，唯 epub 例外。）
        if (manga.isLocal()) {
            val (epub, rest) = downloaded.partition { it.url.substringAfterLast('.', "").equals("epub", true) }
            if (epub.isNotEmpty()) {
                viewModelScope.launch {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.translation_epub_unsupported))
                }
            }
            downloaded = rest
        }
        if (downloaded.isEmpty()) return
        val toTranslate = downloaded
        viewModelScope.launchIO {
            // 模型不可用（缺 / 舊版 v1，見 TranslationEngineConfig.modelsResolvable）→ 明確引導去設定更新，
            // 別靜默排入後整章變紅（修 0.16.0「舊模型 → 按翻譯沒反應」）。單獨查模型、不看翻譯開關（手動翻常關著它）。
            if (!TranslationEngineConfig.modelsResolvable(context)) {
                snackbarHostState.showSnackbar(
                    message = context.stringResource(
                        if (TranslationEngineConfig.modelsOutdated(context)) {
                            MR.strings.translation_models_outdated_action
                        } else {
                            MR.strings.translation_models_missing_action
                        },
                    ),
                )
                return@launchIO
            }
            translationManager.translate(manga, toTranslate)
        }
    }

    /**
     * Yakuyomi 繼續擷取（詳情頁 overflow → [eu.kanade.tachiyomi.ui.capture.CaptureScreen]）：組出要帶入的
     * （書名, 來源網址）。只對 local 漫畫有意義（呼叫端已 gate）。
     *
     * - **書名**：`buildValidFilename(title)` 若恰好等於夾名 [Manga.url]（LocalSource 用夾名當 url）→ 用 `title`
     *   （顯示自然）；否則用夾名 `url`。★ 關鍵：CaptureViewModel.saveCapture 以 `safeBook =
     *   buildValidFilename(book)` 定位存檔夾——回傳夾名時 `buildValidFilename(夾名) == 夾名`（夾名本就由
     *   buildValidFilename 產生、冪等），保證續截的頁**存回原本那個夾**、不會新建一本。
     * - **來源網址**：讀書名夾根的 `.yakuyomi_manga`（擷取來的漫畫才寫過；舊檔名 `.yakuyomi_manga.json` 會被
     *   LocalSource 當 legacy json 刪掉 → 已改無副檔名，readMangaMeta 讀到舊檔會自動 migrate）。一般 local 漫畫沒有 → null →
     *   CaptureScreen initialUrl 空 → 照 S0 自動展開瀏覽（等於手動繼續擷取這本，不 crash）。
     */
    suspend fun buildContinueCaptureArgs(): Pair<String, String?> {
        val manga = successState?.manga ?: return "" to null
        val folderName = manga.url
        val book = if (DiskUtil.buildValidFilename(manga.title) == folderName) manga.title else folderName
        val url = withIOContext {
            runCatching {
                storageManager.getLocalSourceDirectory()
                    ?.findFile(folderName)?.takeIf { it.isDirectory }
                    ?.let { readMangaMeta(it) }
            }.getOrNull()
        }
        return book to url
    }

    /**
     * 重繪選取章（換 [method] 去字法重做去字+排版，復用素材、不重跑 OCR/翻譯）。對象＝已下載章（同翻譯）。
     * 沒有重繪素材的章（翻譯時沒開「保留重繪素材」、只存了夜讀素材、壓縮檔章）不排，提示略過幾話——排了只會重繪 0 頁、
     * 整章標錯誤。
     */
    fun runChapterReRenderAction(items: List<ChapterList.Item>, method: String) {
        val manga = successState?.manga ?: return
        val downloaded = items.filter { it.isDownloaded }.map { it.chapter }
        if (downloaded.isEmpty()) return
        viewModelScope.launchIO {
            val (eligible, skipped) = downloaded.partition { translationManager.hasReRenderMaterials(manga, it) }
            if (eligible.isNotEmpty()) translationManager.reRender(manga, eligible, method)
            if (skipped.isNotEmpty()) {
                snackbarHostState.showSnackbar(
                    context.pluralStringResource(MR.plurals.rerender_skipped_no_materials, skipped.size, skipped.size),
                )
            }
        }
    }

    /**
     * 產生選取章的夜讀版（[TranslationManager.nightRender]：人物分割 + 白底變暗另存夜讀檔，不翻譯）。
     * 對象＝已下載章（同翻譯；不需已翻——夜讀是對頁圖本身的重繪）。舊章補做的入口；新翻完的章由佇列自動接。
     * 夜讀模型（yolo/cseg）一顆都沒有、或翻譯模型（偵測器 dbnet 與夜讀共用）缺/舊版 → 提示後不排入，
     * 免得排入後 renderNightChapter 拋錯、整章變紅才知道缺模型。
     */
    fun runChapterNightRenderAction(items: List<ChapterList.Item>) {
        val manga = successState?.manga ?: return
        val downloaded = items.filter { it.isDownloaded }.map { it.chapter }
        if (downloaded.isEmpty()) return
        viewModelScope.launchIO {
            // 夜讀只要偵測器（dbnet）＋人物分割，不要求 OCR／去字模型齊（非翻譯本、BYOM 只放這兩顆也能用）
            val modelsOk = TranslationEngineConfig.detectorResolvable(context) &&
                TranslationEngineConfig.charSegResolvable(context)
            if (!modelsOk) {
                snackbarHostState.showSnackbar(context.stringResource(MR.strings.nightread_missing_models))
                return@launchIO
            }
            // 只鬆散圖夾（本機來源的 cbz/rar/7z/epub 章 downloaded 恆為 true，排了只會整章紅）→ 先分流、提示不支援
            val (loose, unsupported) = downloaded.partition { translationManager.isLooseChapter(manga, it) }
            if (unsupported.isNotEmpty()) {
                snackbarHostState.showSnackbar(context.stringResource(MR.strings.nightread_archive_unsupported))
            }
            if (loose.isEmpty()) return@launchIO
            translationManager.nightRender(manga, loose)
        }
    }

    /**
     * 重繪「整本」：用**目前的去字設定**（不彈方法選擇器）把本作**已下載且已翻**的章全部重做去字+排版，
     * 復用素材、不重跑 OCR/翻譯/網路（見 [TranslationManager.reRender]）。從漫畫頁的溢位選單(⋮)觸發。
     *
     * 對象限定＝既下載又已翻的章（[ChapterList.Item.isDownloaded] && [ChapterList.Item.isTranslated]）：
     * 沒下載＝沒素材可重繪、沒翻過＝重繪無意義，皆排除（與單列指示器/底部選單一致）。
     * 沒有可重繪的章 → 提示後返回；否則讀目前去字法字串排入佇列、回報排入幾章。
     * 已翻章裡沒有重繪素材的（翻譯時沒開「保留重繪素材」、只存了夜讀素材、壓縮檔章）不排，另外提示略過幾話。
     */
    fun runMangaReRenderAction() {
        val state = successState ?: return
        val chapters = state.chapters
            .filter { it.isDownloaded && it.isTranslated }
            .map { it.chapter }
        if (chapters.isEmpty()) {
            viewModelScope.launch {
                snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.rerender_none))
            }
            return
        }
        // 目前的去字法原始字串（boxfill / auto_whole / auto_tile），與設定頁選擇器寫入的同一格
        val method = translationPreferences.inpaintMethod.get()
        viewModelScope.launchIO {
            val (eligible, skipped) = chapters.partition { translationManager.hasReRenderMaterials(state.manga, it) }
            if (eligible.isNotEmpty()) {
                translationManager.reRender(state.manga, eligible, method)
                snackbarHostState.showSnackbar(
                    message = context.pluralStringResource(
                        MR.plurals.pref_translation_render_update_queued,
                        eligible.size,
                        eligible.size,
                    ),
                )
            }
            if (skipped.isNotEmpty()) {
                snackbarHostState.showSnackbar(
                    context.pluralStringResource(MR.plurals.rerender_skipped_no_materials, skipped.size, skipped.size),
                )
            }
        }
    }

    fun runDownloadAction(action: DownloadAction) {
        val chaptersToDownload = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> getUnreadChaptersSorted().take(1)
            DownloadAction.NEXT_5_CHAPTERS -> getUnreadChaptersSorted().take(5)
            DownloadAction.NEXT_10_CHAPTERS -> getUnreadChaptersSorted().take(10)
            DownloadAction.NEXT_25_CHAPTERS -> getUnreadChaptersSorted().take(25)
            DownloadAction.UNREAD_CHAPTERS -> getUnreadChapters()
            DownloadAction.BOOKMARKED_CHAPTERS -> getBookmarkedChapters()
        }
        if (chaptersToDownload.isNotEmpty()) {
            startDownload(chaptersToDownload, false)
        }
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
    }

    fun markPreviousChapterRead(pointer: Chapter) {
        val manga = successState?.manga ?: return
        val chapters = filteredChapters.orEmpty().map { it.chapter }
        val prevChapters = if (manga.sortDescending()) chapters.asReversed() else chapters
        val pointerPos = prevChapters.indexOf(pointer)
        if (pointerPos != -1) markChaptersRead(prevChapters.take(pointerPos), true)
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        toggleAllSelection(false)
        if (chapters.isEmpty()) return
        viewModelScope.launchIO {
            setReadStatus.await(
                read = read,
                chapters = chapters.toTypedArray(),
            )

            if (!read || successState?.hasLoggedInTrackers == false || autoTrackState == AutoTrackState.NEVER) {
                return@launchIO
            }

            refreshTrackers()

            val tracks = getTracks.await(mangaId)
            val maxChapterNumber = chapters.maxOf { it.chapterNumber }
            val shouldPromptTrackingUpdate = tracks.any { track -> maxChapterNumber > track.lastChapterRead }

            if (!shouldPromptTrackingUpdate) return@launchIO
            if (autoTrackState == AutoTrackState.ALWAYS) {
                trackChapter.await(context, mangaId, maxChapterNumber)
                withUIContext {
                    context.toast(context.stringResource(MR.strings.trackers_updated_summary, maxChapterNumber.toInt()))
                }
                return@launchIO
            }

            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.confirm_tracker_update, maxChapterNumber.toInt()),
                actionLabel = context.stringResource(MR.strings.action_ok),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )

            if (result == SnackbarResult.ActionPerformed) {
                trackChapter.await(context, mangaId, maxChapterNumber)
            }
        }
    }

    private suspend fun refreshTrackers(
        refreshTracks: RefreshTracks = Injekt.get(),
    ) {
        refreshTracks.await(mangaId)
            .filter { it.first != null }
            .forEach { (track, e) ->
                logcat(LogPriority.ERROR, e) {
                    "Failed to refresh track data mangaId=$mangaId for service ${track!!.id}"
                }
                withUIContext {
                    context.toast(
                        context.stringResource(
                            MR.strings.track_error,
                            track!!.name,
                            e.message ?: "",
                        ),
                    )
                }
            }
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    private fun downloadChapters(chapters: List<Chapter>) {
        val manga = successState?.manga ?: return
        downloadManager.downloadChapters(manga, chapters)
        toggleAllSelection(false)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param chapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        viewModelScope.launchIO {
            chapters
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(id = it.id, bookmark = bookmarked) }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<Chapter>) {
        viewModelScope.launchNonCancellable {
            try {
                successState?.let { state ->
                    // Yakuyomi：本機來源的「已下載」章其實是硬設的（見 toChapterListItems），實體檔在
                    // <local>/<manga.url>/ 而非 downloads 目錄 → downloadManager.deleteChapters 找不到會 no-op。
                    // 改走本機刪檔分支（鏡射 LibraryViewModel.removeMangas 的 local 寫法）。
                    if (state.source is LocalSource) {
                        deleteLocalChapters(state.manga, chapters)
                    } else {
                        downloadManager.deleteChapters(
                            chapters,
                            state.manga,
                            state.source,
                        )
                    }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Yakuyomi：刪除本機（LocalSource）章節的實體檔案，並清掉隨之產生的孤兒章節 DB row。
     *
     * 本機章 chapter.url = "<mangaDir>/<chapterFileName>"（見 LocalSource.getChapterList），
     * manga.url = 該漫畫資料夾名。先定位 manga 夾（takeIf isDirectory 防誤刪 local 根、全鏈 null-safe），
     * 再逐章刪對應的章夾/檔（substringAfterLast('/') 取檔名）。
     *
     * 刪檔後 DB row 不會自動消失（local 章 downloaded 是硬設 true、不看檔）→ 重新掃描來源，讓
     * SyncChaptersWithSource 依 LocalSource.getChapterList 的最新掃描結果移除已刪章的 DB row；
     * getMangaAndChapters.subscribe 觀察 DB 變動 → 章節列表即時刷新。
     */
    private suspend fun deleteLocalChapters(manga: Manga, chapters: List<Chapter>) {
        if (manga.url.isBlank()) return // 防空 url 讓 findFile 落到 local 根、誤刪整夾
        val mangaDir = storageManager.getLocalSourceDirectory()
            ?.findFile(manga.url)
            ?.takeIf { it.isDirectory }
            ?: return
        chapters.forEach { chapter ->
            mangaDir.findFile(chapter.url.substringAfterLast('/'))?.delete()
        }
        translationCache.invalidate(manga.id)
        // 重新掃描來源 → 移除已刪章的孤兒 DB row + 即時刷新列表（不抓詳情、不自動下載新章）
        fetchAllFromSource(manualFetch = false, fetchDetails = false, fetchChapters = true)
    }

    private fun downloadNewChapters(chapters: List<Chapter>) {
        viewModelScope.launchNonCancellable {
            val manga = successState?.manga ?: return@launchNonCancellable
            val chaptersToDownload = filterChaptersForDownload.await(manga, chapters)

            if (chaptersToDownload.isNotEmpty()) {
                downloadChapters(chaptersToDownload)
            }
        }
    }

    /**
     * Sets the read filter and requests an UI update.
     * @param state whether to display only unread chapters or all chapters.
     */
    fun setUnreadFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
        }
        viewModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetUnreadFilter(manga, flag)
        }
    }

    /**
     * Sets the download filter and requests an UI update.
     * @param state whether to display only downloaded chapters or all chapters.
     */
    fun setDownloadedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
        }

        viewModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetDownloadedFilter(manga, flag)
        }
    }

    /**
     * Sets the bookmark filter and requests an UI update.
     * @param state whether to display only bookmarked chapters or all chapters.
     */
    fun setBookmarkedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }

        viewModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetBookmarkFilter(manga, flag)
        }
    }

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Long) {
        val manga = successState?.manga ?: return

        viewModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetDisplayMode(manga, mode)
        }
    }

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Long) {
        val manga = successState?.manga ?: return

        viewModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, sort)
        }
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val manga = successState?.manga ?: return
        viewModelScope.launchNonCancellable {
            libraryPreferences.setChapterSettingsDefault(manga)
            if (applyToExisting) {
                setMangaDefaultChapterFlags.awaitAll()
            }
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.chapter_settings_updated))
        }
    }

    fun resetToDefaultSettings() {
        val manga = successState?.manga ?: return
        viewModelScope.launchNonCancellable {
            setMangaDefaultChapterFlags.await(manga)
        }
    }

    fun toggleSelection(
        item: ChapterList.Item,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        updateSuccessState { successState ->
            val newChapters = successState.processedChapters.toMutableList().apply {
                val selectedIndex = successState.processedChapters.indexOfFirst { it.id == item.chapter.id }
                if (selectedIndex < 0) return@apply

                val selectedItem = get(selectedIndex)
                if ((selectedItem.selected && selected) || (!selectedItem.selected && !selected)) return@apply

                val firstSelection = none { it.selected }
                set(selectedIndex, selectedItem.copy(selected = selected))
                selectedChapterIds.addOrRemove(item.id, selected)

                if (selected && fromLongPress) {
                    if (firstSelection) {
                        selectedPositions[0] = selectedIndex
                        selectedPositions[1] = selectedIndex
                    } else {
                        // Try to select the items in-between when possible
                        val range: IntRange
                        if (selectedIndex < selectedPositions[0]) {
                            range = selectedIndex + 1..<selectedPositions[0]
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            range = (selectedPositions[1] + 1)..<selectedIndex
                            selectedPositions[1] = selectedIndex
                        } else {
                            // Just select itself
                            range = IntRange.EMPTY
                        }

                        range.forEach {
                            val inbetweenItem = get(it)
                            if (!inbetweenItem.selected) {
                                selectedChapterIds.add(inbetweenItem.id)
                                set(it, inbetweenItem.copy(selected = true))
                            }
                        }
                    }
                } else if (!fromLongPress) {
                    if (!selected) {
                        if (selectedIndex == selectedPositions[0]) {
                            selectedPositions[0] = indexOfFirst { it.selected }
                        } else if (selectedIndex == selectedPositions[1]) {
                            selectedPositions[1] = indexOfLast { it.selected }
                        }
                    } else {
                        if (selectedIndex < selectedPositions[0]) {
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            selectedPositions[1] = selectedIndex
                        }
                    }
                }
            }
            successState.copy(chapters = newChapters)
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        updateSuccessState { successState ->
            val newChapters = successState.chapters.map {
                selectedChapterIds.addOrRemove(it.id, selected)
                it.copy(selected = selected)
            }
            selectedPositions[0] = -1
            selectedPositions[1] = -1
            successState.copy(chapters = newChapters)
        }
    }

    fun invertSelection() {
        updateSuccessState { successState ->
            val newChapters = successState.chapters.map {
                selectedChapterIds.addOrRemove(it.id, !it.selected)
                it.copy(selected = !it.selected)
            }
            selectedPositions[0] = -1
            selectedPositions[1] = -1
            successState.copy(chapters = newChapters)
        }
    }

    // Chapters list - end

    // Track sheet - start

    private fun observeTrackers() {
        val manga = successState?.manga ?: return

        viewModelScope.launchIO {
            combine(
                getTracks.subscribe(manga.id).catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInTrackersFlow(),
            ) { mangaTracks, loggedInTrackers ->
                // Show only if the service supports this manga's source
                val supportedTrackers = loggedInTrackers.filter { (it as? EnhancedTracker)?.accept(source!!) ?: true }
                val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
                val supportedTrackerTracks = mangaTracks.filter { it.trackerId in supportedTrackerIds }
                supportedTrackerTracks.size to supportedTrackers.isNotEmpty()
            }
                .distinctUntilChanged()
                .collectLatest { (trackingCount, hasLoggedInTrackers) ->
                    updateSuccessState {
                        it.copy(
                            trackingCount = trackingCount,
                            hasLoggedInTrackers = hasLoggedInTrackers,
                        )
                    }
                }
        }
    }

    // Track sheet - end

    sealed interface Dialog {
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteChapters(val chapters: List<Chapter>) : Dialog
        data class DuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class SetFetchInterval(val manga: Manga) : Dialog
        data object SetAnchorConfirm : Dialog
        data object SettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object FullCover : Dialog
    }

    fun dismissDialog() {
        updateSuccessState { it.copy(dialog = null) }
    }

    fun showDeleteChapterDialog(chapters: List<Chapter>) {
        updateSuccessState { it.copy(dialog = Dialog.DeleteChapters(chapters)) }
    }

    fun showSettingsDialog() {
        updateSuccessState { it.copy(dialog = Dialog.SettingsSheet) }
    }

    fun showTrackDialog() {
        updateSuccessState { it.copy(dialog = Dialog.TrackSheet) }
    }

    fun showCoverDialog() {
        updateSuccessState { it.copy(dialog = Dialog.FullCover) }
    }

    fun showMigrateDialog(duplicate: Manga) {
        val manga = successState?.manga ?: return
        updateSuccessState { it.copy(dialog = Dialog.Migrate(target = manga, current = duplicate)) }
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        viewModelScope.launchIO {
            setExcludedScanlators.await(mangaId, excludedScanlators)
        }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val manga: Manga,
            val source: Source,
            val isFromSource: Boolean,
            val chapters: List<ChapterList.Item>,
            val availableScanlators: Set<String>,
            val excludedScanlators: Set<String>,
            val trackingCount: Int = 0,
            val hasLoggedInTrackers: Boolean = false,
            val isRefreshingData: Boolean = false,
            val dialog: Dialog? = null,
            val hasPromptedToAddBefore: Boolean = false,
            val hideMissingChapters: Boolean = false,
            // Yakuyomi：下拉更新開關（預設關，與書庫共用 LibraryPreferences.swipeToRefresh）。
            val pullToRefresh: Boolean = false,
            // Yakuyomi：本書是否為其來源的探索錨點（詳情頁「設為錨點」動作鈕據此反映實心/主色狀態）。
            val isAnchor: Boolean = false,
        ) : State {
            val processedChapters by lazy {
                chapters.applyFilters(manga).toList()
            }

            val isAnySelected by lazy {
                chapters.fastAny { it.selected }
            }

            val chapterListItems by lazy {
                if (hideMissingChapters) {
                    return@lazy processedChapters
                }

                processedChapters.insertSeparators { before, after ->
                    val (lowerChapter, higherChapter) = if (manga.sortDescending()) {
                        after to before
                    } else {
                        before to after
                    }
                    if (higherChapter == null) return@insertSeparators null

                    if (lowerChapter == null) {
                        floor(higherChapter.chapter.chapterNumber)
                            .toInt()
                            .minus(1)
                            .coerceAtLeast(0)
                    } else {
                        calculateChapterGap(higherChapter.chapter, lowerChapter.chapter)
                    }
                        .takeIf { it > 0 }
                        ?.let { missingCount ->
                            ChapterList.MissingCount(
                                id = "${lowerChapter?.id}-${higherChapter.id}",
                                count = missingCount,
                            )
                        }
                }
            }

            val scanlatorFilterActive: Boolean
                get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val filterActive: Boolean
                get() = scanlatorFilterActive || manga.chaptersFiltered()

            /**
             * Applies the view filters to the list of chapters obtained from the database.
             * @return an observable of the list of chapters filtered and sorted.
             */
            private fun List<ChapterList.Item>.applyFilters(manga: Manga): Sequence<ChapterList.Item> {
                val isLocalManga = manga.isLocal()
                val unreadFilter = manga.unreadFilter
                val downloadedFilter = manga.downloadedFilter
                val bookmarkedFilter = manga.bookmarkedFilter
                return asSequence()
                    .filter { (chapter) -> applyFilter(unreadFilter) { !chapter.read } }
                    .filter { (chapter) -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
                    .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalManga } }
                    .sortedWith { (chapter1), (chapter2) -> getChapterSort(manga).invoke(chapter1, chapter2) }
            }
        }
    }
}

@Immutable
sealed class ChapterList {
    @Immutable
    data class MissingCount(
        val id: String,
        val count: Int,
    ) : ChapterList()

    @Immutable
    data class Item(
        val chapter: Chapter,
        val downloadState: Download.State,
        val downloadProgress: Int,
        val translationStatus: TranslationItem.Status? = null,
        val translationProgress: Int = 0,
        /** 已翻＝[translatedOnDisk] OR 本 session 翻成的（[withTranslationState] 算）。 */
        val isTranslated: Boolean = false,
        val selected: Boolean = false,
        /** 已有夜讀版＝[nightOnDisk] OR 本 session 做完的（[withTranslationState] 算）。 */
        val hasNightPages: Boolean = false,
        /** 夜讀版裡有舊規則產生的頁（app 更新改了夜讀規則；見 NightPages 的規則版本）：指示器畫「可更新」。只從磁碟掃。 */
        val nightOutdated: Boolean = false,
        /** 章節列夜讀指示器狀態（model 算好；夜讀總開關關 / 未下載 / 做不了夜讀＝HIDDEN，UI 不畫）。 */
        val nightStatus: ChapterNightStatus = ChapterNightStatus.HIDDEN,
        /** 夜讀產生中的進度 0..1；沒在跑 / 未知＝null（畫不定轉圈）。 */
        val nightProgress: Float? = null,
        /**
         * 磁碟上的「已翻」（manifest／壓縮檔 marker；只在掃磁碟時算）。和本 session 集合分開存：session 集合清掉時
         * （刪下載、重新下載，見 TranslationManager.forgetChapterOutputs）合併值才退得回去。
         */
        val translatedOnDisk: Boolean = false,
        /** 磁碟上的「已有夜讀版」（`.yakuyomi/` 有完成標記或舊版單檔；只在掃磁碟時算）。分開存的理由同 [translatedOnDisk]。 */
        val nightOnDisk: Boolean = false,
        /** 已下載但做不了夜讀（壓縮檔章、本機 epub、找不到章節夾）：夜讀入口不畫。只在掃磁碟時算。 */
        val nightUnsupported: Boolean = false,
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED
    }
}

/**
 * 章節列夜讀指示器的狀態（純函式，JVM 測試見 ChapterNightStatusTest）：夜讀總開關關 / 未下載 / 這章做不了夜讀
 * （[unsupported]：壓縮檔章等）→ HIDDEN（不畫）；佇列有該章 NIGHT 項 → 依其狀態（產生中、排隊中、失敗都蓋過「可更新」：
 * 已在處理的章不必再提示）；否則看有沒有夜讀版（本 session 完成或 `.yakuyomi/` 裡有完成標記 std／三檔 l1 或舊版單檔，
 * 見 [TranslationManager.nightSummary]）→ 有舊規則產生的頁（[outdated]）＝UPDATABLE、否則 DONE；沒有 → NONE。
 */
internal fun nightStatusOf(
    nightEnabled: Boolean,
    downloaded: Boolean,
    queued: TranslationItem?,
    hasNightPages: Boolean,
    outdated: Boolean,
    unsupported: Boolean = false,
): ChapterNightStatus = when {
    !nightEnabled || !downloaded || unsupported -> ChapterNightStatus.HIDDEN
    queued?.status == TranslationItem.Status.TRANSLATING -> ChapterNightStatus.RENDERING
    queued?.status == TranslationItem.Status.QUEUE -> ChapterNightStatus.QUEUED
    queued?.status == TranslationItem.Status.ERROR -> ChapterNightStatus.ERROR
    hasNightPages && outdated -> ChapterNightStatus.UPDATABLE
    hasNightPages -> ChapterNightStatus.DONE
    else -> ChapterNightStatus.NONE
}

/** [MangaViewModel] 觀察翻譯佇列時一次 combine 的來源：佇列快照、本 session 翻成 / 夜讀完成集合、夜讀總開關。 */
internal data class TranslationObservation(
    val queue: List<TranslationItem>,
    val translatedIds: Set<Long>,
    val nightDoneIds: Set<Long>,
    val nightEnabled: Boolean,
)

/**
 * 把佇列與本 session 的狀態套到章節列（純函式，JVM 測試見 ChapterListTranslationStateTest）。只讀 [obs] 與每項的磁碟欄位
 * （[ChapterList.Item.translatedOnDisk]／[ChapterList.Item.nightOnDisk]／[ChapterList.Item.nightOutdated]／
 * [ChapterList.Item.nightUnsupported]），不讀這一項先前套上去的值，所以同一個 [obs] 套幾次結果都一樣，誰最後寫誰就對：
 * 掃磁碟那條在寫入當下拿最新的 [obs] 重套，不會被掃描開頭的舊快照蓋回去；session 集合清掉時合併值也跟著退回。
 * 翻譯指示器只反映翻譯/重繪項、夜讀指示器只反映 NIGHT 項：同章可同時有兩種項，各自顯示、互不遮蓋。
 */
internal fun List<ChapterList.Item>.withTranslationState(obs: TranslationObservation): List<ChapterList.Item> {
    val (nightQueue, translationQueue) = obs.queue.partition { it.kind == TranslationItem.Kind.NIGHT }
    val byId = translationQueue.associateBy { it.chapter.id }
    val nightById = nightQueue.associateBy { it.chapter.id }
    return map { item ->
        val t = byId[item.id]
        val n = nightById[item.id]
        val hasNight = item.nightOnDisk || item.id in obs.nightDoneIds
        item.copy(
            translationStatus = t?.status,
            translationProgress = if (t != null && t.total > 0) t.done * 100 / t.total else 0,
            isTranslated = item.translatedOnDisk || item.id in obs.translatedIds,
            hasNightPages = hasNight,
            nightStatus = nightStatusOf(
                obs.nightEnabled,
                item.isDownloaded,
                n,
                hasNight,
                item.nightOutdated,
                item.nightUnsupported,
            ),
            // 夜讀項進度 0..1（頁 done/total）；沒在佇列 / total 未知 → null（指示器畫不定轉圈）
            nightProgress = n?.takeIf { it.total > 0 }?.let { it.done.toFloat() / it.total },
        )
    }
}
