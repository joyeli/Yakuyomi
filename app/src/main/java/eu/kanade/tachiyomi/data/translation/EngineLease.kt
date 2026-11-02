package eu.kanade.tachiyomi.data.translation

import kotlinx.coroutines.delay
import java.io.File

/**
 * 夜讀向常駐翻譯引擎借偵測器的租約（S1，[TranslationEngineService] 持有一份）。純 Kotlin、不碰 Android，JVM 單元測試直接打。
 *
 * 借用以**一次偵測**為單位（借 → 偵測 → 還），不跨頁、不跨章：關引擎的路徑最多等一次前向（含等 NCNN 全域鎖）就關得了，
 * 合併輪的夜讀不必在章與章之間抓著翻譯引擎，翻譯也不會因為夜讀還借著而用舊設定翻（重建只多等那一次前向）。
 *
 *  - [publish]：引擎建好時登記（帶建好當下偵測模型檔的 [ModelStamp]）；關掉時登記 null。
 *  - [acquire]：有引擎、沒在關、[acquire] 的 accept 接受 → 借出（計數 +1，之後一定要 [release]）；否則 null（呼叫端用自己的
 *    偵測器）。
 *  - 關引擎的路徑：先 [beginClose]（之後一律不借），等借出數歸零（[awaitDrained]）才真的關；等不到、或不能等（onTrimMemory
 *    那條非 suspend）→ [cancelClose] 恢復借出、這次不關（同在飛頁不歸零時的做法）。
 */
class EngineLease<E : Any> {
    private val lock = Any()
    private var current: E? = null
    private var leases = 0
    private var closing = false

    /** 登記目前的引擎（null＝沒有）；同時結束「關閉中」。 */
    fun publish(e: E?) {
        synchronized(lock) {
            current = e
            closing = false
        }
    }

    /** 借一次：見類別說明。[accept] 在鎖內呼叫，要便宜（比路徑、比檔案戳記）。 */
    fun acquire(accept: (E) -> Boolean): E? = synchronized(lock) {
        val e = current
        if (e == null || closing || !accept(e)) {
            null
        } else {
            leases++
            e
        }
    }

    /** 不借、只問現在借不借得到（開組時決定要不要先建自己的偵測器、回合邊界決定要不要收掉自己那顆）。 */
    fun available(accept: (E) -> Boolean): Boolean = synchronized(lock) {
        val e = current
        e != null && !closing && accept(e)
    }

    /** 還一次（每次 [acquire] 成功恰好一次）。 */
    fun release() {
        synchronized(lock) {
            check(leases > 0) { "EngineLease：還的次數比借的多" }
            leases--
        }
    }

    /** 開始關：之後一律不借；回傳目前借出數（0＝可以直接關）。 */
    fun beginClose(): Int = synchronized(lock) {
        closing = true
        leases
    }

    /** 這次不關了（借出數等不到歸零）：恢復借出。 */
    fun cancelClose() {
        synchronized(lock) { closing = false }
    }

    /** 目前借出數。 */
    val outstanding: Int
        get() = synchronized(lock) { leases }

    /** 等借出數歸零，最多 [timeoutMs]（每 [pollMs] 看一次）。回 true＝歸零了。 */
    suspend fun awaitDrained(timeoutMs: Long, pollMs: Long = 20L): Boolean {
        var waited = 0L
        while (outstanding > 0) {
            if (waited >= timeoutMs) return false
            delay(pollMs)
            waited += pollMs
        }
        return true
    }
}

/**
 * 一顆 NCNN 模型（`.param`＋同名 `.bin`）的檔案戳記：兩個檔的長度與修改時間。共用與重用的判準之一——同一個路徑但檔案在
 * 載入之後被換過（例如夜讀組重新下載換了偵測器、翻譯引擎還留著舊的 Net）就不算同一顆。缺任一檔＝null。
 */
data class ModelStamp(val paramLen: Long, val paramTime: Long, val binLen: Long, val binTime: Long) {
    companion object {
        fun of(paramPath: String?): ModelStamp? {
            if (paramPath == null) return null
            val p = File(paramPath)
            val b = File(paramPath.removeSuffix(".param") + ".bin")
            if (!p.isFile || !b.isFile) return null
            return ModelStamp(p.length(), p.lastModified(), b.length(), b.lastModified())
        }
    }
}
