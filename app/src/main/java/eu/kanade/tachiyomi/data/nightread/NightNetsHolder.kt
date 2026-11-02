package eu.kanade.tachiyomi.data.nightread

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 合併輪（翻譯＋夜讀一條線）的夜讀多緒模型組（LOCKED）跨輪、跨章常駐（S2）。純 Kotlin、不碰 Android，JVM 單元測試直接打。
 *
 * 以前每一輪（一章）夜讀段開頭開一組（載 yolo、cseg、偵測器＋暖機，約 4.2 s）、結尾關掉；連讀幾章就每章付一次。現在一輪結束
 * 把組**還**回來留著，下一輪同一把 [K]（模型檔路徑＋檔案戳記）就直接拿去用（輸出相同：同一組 Net）。
 *
 * **釋放**（native 約 0.9–1.3 GB，一定要有出口）：
 *  - 夜讀總開關關、模型檔有變動、`App.onTrimMemory`、合併輪改走 FREE 組（1 緒那組另外開、不留）、分開模式的夜讀項要開自己那組
 *    （兩組同時開著放不下）→ [release]；
 *  - 還回來之後閒置 [idleMs]（預設 60 s）沒有下一輪來拿 → 自己關；
 *  - 下一輪來拿時 [K] 不同（模型換了）→ 先關舊的再開新的。
 * 借出中（一輪正在用）收到 [release] 不能當場關（頁還在用那些 Net）：記下原因，還回來時就關。
 *
 * 同時只借給一輪（合併輪只在翻譯那條消費者上跑，一次一章）；萬一同時有第二個來拿，給它一組另外開、不留的（[Lease.kept]＝false），
 * 還回來時直接關。開組（[acquire] 的 open）在鎖外做，關組也在鎖外做。
 *
 * [log] 的行（合併輪記在 `combined`）：`held open`（開新的一組）、`held reuse age=…`（拿留著的）、`held keep`（還回來、
 * 開始閒置計時）、`held release reason=…`（關掉留著的或借出中的那組；idle、night-off、trim、free、separate、models-changed、key-changed）、`held release deferred reason=…`（借出中、還回來時關）。
 */
class NightNetsHolder<K : Any, N : AutoCloseable>(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val log: (String) -> Unit,
    private val idleMs: Long = IDLE_RELEASE_MS,
) {
    /** 一次借用：[nets] 給一輪用到結束，然後 [giveBack]（恰好一次）。 */
    class Lease<N> internal constructor(
        val nets: N,
        /** 拿的是留著的那組（沒有重開）。 */
        val reused: Boolean,
        /** 還回來時留著（false＝另外開的那組，還回來就關）。 */
        internal val kept: Boolean,
        internal val key: Any,
    ) {
        internal var returned = false
    }

    private class Entry<K, N>(val key: K, val nets: N, var since: Long)

    private val lock = Any()

    /** 留著、沒人在用的那組。 */
    private var idle: Entry<K, N>? = null

    /** 借出中的那組（留著的那份；另外開的不記）。 */
    private var lent: Entry<K, N>? = null

    /** 借出中收到的釋放原因（還回來時關）。 */
    private var pendingRelease: String? = null

    private var idleJob: Job? = null

    /** 目前有留著或借出中的組（給合併輪判「多緒那組已經開著、記憶體已算在可用記憶體裡」用）。 */
    val holdsNets: Boolean
        get() = synchronized(lock) { idle != null || lent != null }

    /**
     * 拿一組給這一輪用：留著的那組 [K] 相同 → 重用；不同 → 關掉它（`release reason=key-changed`）再 [open] 一組新的。
     * [open] 拋例外照樣往外拋（什麼都沒留）。已經借出中（不該發生）→ 另外開一組不留的。
     */
    fun acquire(key: K, open: () -> N): Lease<N> {
        var stale: Entry<K, N>? = null
        val reuse: Entry<K, N>?
        val extra: Boolean
        synchronized(lock) {
            extra = lent != null
            if (!extra) {
                idleJob?.cancel()
                idleJob = null
                val held = idle
                idle = null
                reuse = if (held != null && held.key == key) held else null
                if (held != null && reuse == null) stale = held
                if (reuse != null) lent = reuse
            } else {
                reuse = null
            }
        }
        stale?.let { close(it.nets, "key-changed") }
        if (reuse != null) {
            log("held reuse age=${(now() - reuse.since) / 1000}s")
            return Lease(reuse.nets, reused = true, kept = true, key = key)
        }
        val nets = open()
        if (extra) {
            log("held open extra (another round is using the held set; this one is not kept)")
            return Lease(nets, reused = false, kept = false, key = key)
        }
        synchronized(lock) {
            lent = Entry(key, nets, now())
            pendingRelease = null
        }
        log("held open")
        return Lease(nets, reused = false, kept = true, key = key)
    }

    /** 這一輪用完還回來（每個 [Lease] 恰好一次，多還的忽略）：留著並開始閒置計時，或有待釋放原因／不留的就關。 */
    fun giveBack(lease: Lease<N>) {
        var closeReason: String? = null
        synchronized(lock) {
            if (lease.returned) return
            lease.returned = true
            if (!lease.kept) {
                closeReason = "extra"
                return@synchronized
            }
            val e = lent
            lent = null
            if (e == null || e.nets !== lease.nets) {
                // 借出中被換掉（不該發生）：保險起見關
                closeReason = "unknown-lease"
                return@synchronized
            }
            val pending = pendingRelease
            pendingRelease = null
            if (pending != null) {
                closeReason = pending
                return@synchronized
            }
            e.since = now()
            idle = e
            idleJob = scope.launch {
                delay(idleMs)
                expireIdle(e)
            }
        }
        val reason = closeReason
        if (reason != null) {
            close(lease.nets, reason)
        } else {
            log("held keep idleRelease=${idleMs / 1000}s")
        }
    }

    /** 閒置計時到：那組還是留著、沒人拿走 → 關。 */
    private fun expireIdle(e: Entry<K, N>) {
        val hit = synchronized(lock) {
            if (idle === e) {
                idle = null
                idleJob = null
                true
            } else {
                false
            }
        }
        if (hit) close(e.nets, "idle")
    }

    /**
     * 主動釋放（[reason] 記進紀錄）：留著的那組當場關；借出中的那組記下、還回來時關。什麼都沒有＝不記。
     */
    fun release(reason: String) {
        var toClose: Entry<K, N>? = null
        var deferred = false
        synchronized(lock) {
            idleJob?.cancel()
            idleJob = null
            toClose = idle
            idle = null
            if (lent != null) {
                pendingRelease = reason
                deferred = true
            }
        }
        toClose?.let { close(it.nets, reason) }
        if (deferred) log("held release deferred reason=$reason")
    }

    private fun close(nets: N, reason: String) {
        runCatching { nets.close() }
        log("held release reason=$reason")
    }

    companion object {
        /** 合併輪結束後留著的模型組閒置多久就關（S2：合併工作清空後閒置 60 s）。 */
        const val IDLE_RELEASE_MS = 60_000L
    }
}
