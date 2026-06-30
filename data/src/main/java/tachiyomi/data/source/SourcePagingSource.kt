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
import kotlin.random.Random

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

        // Yakuyomi：節流連續翻頁 + 隨機抖動。所有翻下一頁（正常捲動／全域篩選濾稀疏→Paging 為填滿畫面狂翻／
        // 自動載入到錨點）都走這條 load()，對來源連續猛打 request＝ban 風險。除了基礎最小間隔（[PREF_LOAD_INTERVAL]，
        // 預設 1 秒）外，再疊一段隨機抖動（[JITTER_MIN_MS]+rand[JITTER_SPAN_MS]）讓間隔不規律——固定心跳本身也是
        // ban 訊號。正常捲動（兩頁載入間隔本就遠大於此值）幾乎不受影響，只擋住「濾稀疏／自動載入到錨點」的爆衝。
        if (page > 1) {
            val baseMs = preferenceStore.getInt(PREF_LOAD_INTERVAL, 1).get().coerceAtLeast(0) * 1000L
            val targetMs = baseMs + JITTER_MIN_MS + Random.nextLong(JITTER_SPAN_MS)
            val wait = targetMs - (SystemClock.elapsedRealtime() - lastLoadAt)
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

        // 翻頁抖動：基礎間隔之上再疊 [JITTER_MIN_MS, JITTER_MIN_MS+JITTER_SPAN_MS) 毫秒的隨機延遲（0.5–2.0s），
        // 即使基礎間隔設 0 也保有抖動，避免固定心跳被偵測。預設 base(1s)+0.5 = 下限 1.5s、抖動到 3.0s（「平衡」安全檔）。
        // 寬抖動（接近 base ±100%）比窄抖動更不像機器人；安全節奏約 0.25–0.5 req/s。
        private const val JITTER_MIN_MS = 500L
        private const val JITTER_SPAN_MS = 1500L
    }
}

class NoResultsException : Exception()
