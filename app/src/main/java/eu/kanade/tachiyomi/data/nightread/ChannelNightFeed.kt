package eu.kanade.tachiyomi.data.nightread

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 用程式送頁的 [NightFeed]（合併處理的夜讀段：翻譯落地一頁就送一頁，見
 * [eu.kanade.tachiyomi.data.translation.CombinedLine]）。執行緒安全：[offer]／[close] 可從任何執行緒呼叫，[poll]／
 * [awaitMore] 由 [NightPump] 的 producer 呼叫。
 *
 *  - [offer] 不等待；[close] 之後再送一律忽略、回 false。
 *  - [awaitMore]：有頁就回；關了而且手上沒頁、或 shouldStop 成立 → null。等待時每 [POLL_MS] 看一次 shouldStop
 *    （[offer]／[close] 會立刻叫醒）。
 */
class ChannelNightFeed<P : Any>(private val orderOf: (P) -> Int) : NightFeed<P> {
    private val lock = Any()
    private val pending = ArrayList<P>()
    private var closed = false

    /** 叫醒等待中的 [awaitMore]（合併多次，只需知道「有變化」）。 */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** 送一頁（不等待）；[close] 之後呼叫＝忽略、回 false。 */
    fun offer(page: P): Boolean {
        synchronized(lock) {
            if (closed) return false
            pending += page
        }
        wake.trySend(Unit)
        return true
    }

    /** 不會再有新頁：[awaitMore] 把手上的交完之後回 null。可重複呼叫。 */
    fun close() {
        synchronized(lock) { closed = true }
        wake.trySend(Unit)
    }

    override fun order(page: P): Int = orderOf(page)

    /** 不等待，取走目前所有頁（沒有＝空）。 */
    override fun poll(): List<P> = synchronized(lock) { drainLocked() }

    override suspend fun awaitMore(shouldStop: () -> Boolean): List<P>? {
        while (true) {
            if (shouldStop()) return null
            val (got, isClosed) = synchronized(lock) { drainLocked() to closed }
            if (got.isNotEmpty()) return got
            if (isClosed) return null
            withTimeoutOrNull(POLL_MS) { wake.receive() }
        }
    }

    /** 在 [lock] 下：取走手上的頁。 */
    private fun drainLocked(): List<P> {
        if (pending.isEmpty()) return emptyList()
        return pending.toList().also { pending.clear() }
    }

    companion object {
        /** 等新頁時看 shouldStop 的間隔。 */
        const val POLL_MS = 250L
    }
}
