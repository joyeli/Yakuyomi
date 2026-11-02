package eu.kanade.tachiyomi.data.translation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 合併輪一頁譯圖的交接（軌道 B2 的 S3／S4）：翻譯那邊把譯圖編碼成頁檔位元組之後、落地之前，就把這份交給夜讀段，夜讀不必
 * 等頁檔寫好、也不必再讀檔解碼、讀素材。不碰 Android（遮罩型別 [M] 由呼叫端決定，app 裡是 Bitmap），JVM 測試見
 * NightHandoffTest。
 *
 *  - [bytes] 的前 [length] 個位元組：要寫進頁檔的內容（[PageTranslator] 的 encodePage；落地寫進頁檔的就是這些位元組）。夜讀用
 *    `decodeByteArray` 解碼，跟落地後讀檔解碼是同一份位元組、同一組 Options（只在 inSampleSize＝1 的頁交接）。
 *  - [quads]：這頁每行原文四邊形（素材 json 各文字區依序攤平、濾掉不是四點的行；跟夜讀從素材檔讀到的逐點相同）。
 *  - 遮罩：二值化的去字遮罩（跟素材的 `<base>.mask.png` 同像素）。用 [acquireMask]／[releaseMask] 借用，[close] 之後最後一個
 *    借用還回來時才 [recycle]（借用中被關也不會在用的時候被回收）。
 *  - 落地：翻譯那邊落地完回報 [land]（頁檔寫完時的修改時間與大小，以及素材有沒有照這份寫成）或 [landFailed]（寫回失敗還原、
 *    別的路徑先落地、例外）；只認第一次回報。夜讀換檔前 [awaitLanding]：落地沒成功、或素材沒照這份寫成，夜讀結果都不能用
 *    （算過期，之後從檔案重做）；成功時用落地記下的 (mtime, size) 做過期檢查。
 *
 * 所有方法都可以多緒呼叫。
 */
internal class NightHandoff<M : Any>(
    bytes: ByteArray,
    val length: Int,
    val width: Int,
    val height: Int,
    val quads: List<List<List<Float>>>,
    mask: M?,
    private val recycle: (M) -> Unit,
) : AutoCloseable {

    /** 落地結果。[materialsExact]＝這頁的素材（文字框與去字遮罩）照這份交接寫成了，夜讀從檔案讀會拿到一樣的東西。 */
    data class Landing(val mtime: Long, val size: Long, val materialsExact: Boolean)

    /** [awaitLanding] 的結果。 */
    sealed interface Wait {
        data class Landed(val landing: Landing) : Wait

        /** 落地失敗（頁檔不是這份位元組）。 */
        data object Failed : Wait

        /** 等的時候 stop 成立（暫停、取消）。 */
        data object Stopped : Wait

        /** 等太久（不該發生：落地只是幾次寫檔）。 */
        data object TimedOut : Wait
    }

    private val lock = Any()
    private val landed = CountDownLatch(1)

    @Volatile
    private var outcome: Wait? = null

    // 以下在 [lock] 下
    private var bytesRef: ByteArray? = bytes
    private var maskRef: M? = mask
    private var maskUsers = 0
    private var closed = false

    /** 交接的頁檔位元組（有效的是前 [length] 個）；[close] 之後 null。 */
    val bytes: ByteArray? get() = synchronized(lock) { bytesRef }

    /** 有沒有遮罩（不論還在不在）。 */
    val hasMask: Boolean = mask != null

    /** 回報落地成功（只認第一次回報）。 */
    fun land(
        mtime: Long,
        size: Long,
        materialsExact: Boolean,
    ) = settle(Wait.Landed(Landing(mtime, size, materialsExact)))

    /** 回報落地失敗（只認第一次回報）。 */
    fun landFailed() = settle(Wait.Failed)

    private fun settle(w: Wait) {
        synchronized(lock) {
            if (outcome != null) return
            outcome = w
        }
        landed.countDown()
    }

    /** 落地結果（不等）：還沒回報＝null，否則 [Wait.Landed] 或 [Wait.Failed]。 */
    fun landingNow(): Wait? = outcome

    /**
     * 等落地結果：每 [pollMs] 看一次 [stop]（成立＝[Wait.Stopped]），最多等 [timeoutMs]（＝[Wait.TimedOut]）。等的時候被中斷照樣
     * 等完（中斷旗標回傳前補回去），跟夜讀頁其他的等待一樣不因中斷提早離開。
     */
    fun awaitLanding(stop: () -> Boolean, pollMs: Long = POLL_MS, timeoutMs: Long = TIMEOUT_MS): Wait {
        outcome?.let { return it }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var interrupted = false
        try {
            while (true) {
                outcome?.let { return it }
                if (stop()) return Wait.Stopped
                val left = deadline - System.nanoTime()
                if (left <= 0) return Wait.TimedOut
                try {
                    landed.await(minOf(TimeUnit.MILLISECONDS.toNanos(pollMs), left), TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** 借遮罩（沒有遮罩、或已經 [close] → null，不必 [releaseMask]）。拿到的要 [releaseMask] 還。 */
    fun acquireMask(): M? = synchronized(lock) {
        val m = maskRef
        if (m == null || closed) return null
        maskUsers++
        m
    }

    /** 還遮罩（[acquireMask] 拿到非 null 才還）。已經 [close] 而且這是最後一個借用 → 回收。 */
    fun releaseMask() {
        val toRecycle = synchronized(lock) {
            check(maskUsers > 0) { "遮罩沒借就還" }
            maskUsers--
            if (closed && maskUsers == 0) takeMaskLocked() else null
        }
        toRecycle?.let(recycle)
    }

    /**
     * 不再用這份交接（夜讀結果確定、過期、夜讀段關掉，或沒送出去）：放掉位元組、回收遮罩（還有人借著就等最後一個還回來時）。
     * 也把還沒回報的落地當成失敗（等的人不會一直等）。可重複呼叫。
     */
    override fun close() {
        val toRecycle = synchronized(lock) {
            if (closed) return
            closed = true
            bytesRef = null
            if (maskUsers == 0) takeMaskLocked() else null
        }
        settle(Wait.Failed)
        toRecycle?.let(recycle)
    }

    /** 已經 [close]。 */
    val isClosed: Boolean get() = synchronized(lock) { closed }

    private fun takeMaskLocked(): M? {
        val m = maskRef
        maskRef = null
        return m
    }

    companion object {
        /** [awaitLanding] 看停止條件的間隔。 */
        const val POLL_MS = 200L

        /** [awaitLanding] 最多等多久（落地是幾次寫檔，正常一秒內；這只是保險）。 */
        const val TIMEOUT_MS = 5L * 60 * 1000
    }
}
