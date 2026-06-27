package tachiyomi.data.source

import android.os.SystemClock
import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.delay
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.repository.SourcePagingSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceSearchPagingSource(
    source: Source,
    private val query: String,
    private val filters: FilterList,
) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getSearchManga(currentPage, query, filters)
    }
}

class SourcePopularPagingSource(source: Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getPopularManga(currentPage)
    }
}

class SourceLatestPagingSource(source: Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class BaseSourcePagingSource(
    protected val source: Source,
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
) : SourcePagingSource() {

    private val seenManga = hashSetOf<String>()
    private var lastLoadAt = 0L

    abstract suspend fun requestNextPage(currentPage: Int): MangasPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Manga> {
        val page = params.key ?: 1

        // Yakuyomi：節流連續翻頁。客戶端全域篩選把整頁濾到剩沒幾本時，Paging 會為了填滿畫面狂翻下一頁、
        // 對來源連續猛打 request（有被 ban 風險）。確保兩次載入之間至少間隔設定秒數（[PREF_LOAD_INTERVAL]，預設 1 秒）——
        // 正常捲動（兩頁載入本就間隔遠大於此值）幾乎不受影響，只擋住「濾到很稀疏→連翻爆衝」這種情況。
        if (page > 1) {
            val intervalMs = preferenceStore.getInt(PREF_LOAD_INTERVAL, 1).get().coerceAtLeast(0) * 1000L
            val wait = intervalMs - (SystemClock.elapsedRealtime() - lastLoadAt)
            if (wait > 0) delay(wait)
        }
        lastLoadAt = SystemClock.elapsedRealtime()

        return try {
            val mangasPage = withIOContext {
                requestNextPage(page.toInt())
                    .takeIf { it.mangas.isNotEmpty() }
                    ?: throw NoResultsException()
            }

            val manga = mangasPage.mangas
                .map { it.toDomainManga(source.id) }
                .filter { seenManga.add(it.url) }
                .let { networkToLocalManga(it) }

            LoadResult.Page(
                data = manga,
                prevKey = null,
                nextKey = if (mangasPage.hasNextPage) page + 1 else null,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, Manga>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }

    companion object {
        // 翻頁最小間隔（秒）偏好 key。與 app 端 SourcePreferences.browseLoadInterval 同 key（此處在 data 層、直接讀 store 免跨層）。
        const val PREF_LOAD_INTERVAL = "browse_load_interval"
    }
}

class NoResultsException : Exception()
