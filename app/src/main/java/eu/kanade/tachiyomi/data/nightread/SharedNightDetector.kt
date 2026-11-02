package eu.kanade.tachiyomi.data.nightread

/**
 * 合併輪夜讀（多緒組）的偵測來源（S1）：每次先向常駐翻譯引擎**借**它那顆偵測器跑（[borrow]，借不到回 null），借不到才用
 * 自己的偵測器——開組時就有（[own] 先給了），或第一次借不到時才建（[openOwn]，不暖機：多緒組的前向在全域鎖內串行，碰不到
 * 「多頁同時打進還沒初始化的 Net」）。兩邊輸出相同（同一個模型檔、同一種建法、同一份 `DetectorConfig()`），所以混用沒有差別。
 * 純 Kotlin（[P] 頁、[R] 偵測結果、[O] 自己的偵測器），JVM 單元測試直接打。
 *
 * 自己那顆一旦建了就留到 [close]（或回合邊界 [dropOwnIfBorrowable]），不會每頁開關。可多緒同時呼叫 [detect]（多頁並行），
 * 建自己那顆只會建一次。
 */
class SharedNightDetector<P, R, O : AutoCloseable>(
    private val borrow: (P) -> R?,
    private val openOwn: () -> O,
    private val runOwn: (O, P) -> R,
    own: O? = null,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()

    /** 自己的偵測器（在 [lock] 下改）。 */
    private var own: O? = own

    /** 這組的偵測器現在是不是自己的（開組時借得到＝false；借不到建了自己的、或開組時就建了＝true）。 */
    val hasOwn: Boolean get() = synchronized(lock) { own != null }

    /** 一次偵測：先借、借不到用自己的（必要時建）。 */
    fun detect(page: P): R = detectReport(page).first

    /** 正在用自己那顆的呼叫數（在 [lock] 下改）：[dropOwnIfBorrowable] 不關正在用的。 */
    private var ownUsers = 0

    /** 同 [detect]，另外回這次是不是借來的（debug 比對要知道）。 */
    fun detectReport(page: P): Pair<R, Boolean> {
        borrow(page)?.let { return it to true }
        val o = synchronized(lock) {
            val cur = own ?: openOwn().also {
                own = it
                log("night detector own (engine detector not lendable)")
            }
            ownUsers++
            cur
        }
        try {
            return runOwn(o, page) to false
        } finally {
            synchronized(lock) { ownUsers-- }
        }
    }

    /**
     * 回合邊界（沒有頁在飛）：自己那顆在、沒人在用、而引擎現在借得到（[borrowable]）→ 關掉自己那顆、之後都用借的（省約
     * 0.4 GB 原生）。回 true＝關了。
     */
    fun dropOwnIfBorrowable(borrowable: () -> Boolean): Boolean {
        val o = synchronized(lock) {
            val cur = own ?: return false
            if (ownUsers > 0 || !borrowable()) return false
            own = null
            cur
        }
        runCatching { o.close() }
        log("night detector own dropped (engine detector lendable again)")
        return true
    }

    override fun close() {
        val o = synchronized(lock) {
            own.also { own = null }
        }
        o?.let { runCatching { it.close() } }
    }
}
