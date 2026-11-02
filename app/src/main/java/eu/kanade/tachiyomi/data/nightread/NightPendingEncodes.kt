package eu.kanade.tachiyomi.data.nightread

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/**
 * 一頁一輪夜讀的編碼工作（軌道 B2 的 S5）：重繪那條執行緒每合成好一檔就把「壓成無損 WebP、寫暫存檔」交給 [executor]，
 * 自己接著合成下一檔；換檔（`NightRound.commit`）與丟掉暫存（`NightRound.discard`）之前 [awaitAll] 等全部做完。
 * 不碰 Android，JVM 測試見 NightPendingEncodesTest。只給一條重繪執行緒用（[submit] 與 [awaitAll] 在同一條上呼叫）；工作本身
 * 在 [executor] 的執行緒上跑。
 *
 *  - [submit] 的 [work] 拋的例外（含 Error）收起來，[awaitAll] 回傳第一個（照送出的順序），不往外拋——呼叫端決定（換檔前＝
 *    拋出去、這頁記錯；丟暫存＝忽略）。
 *  - [executor] 拒收（關閉中、執行緒開不出來）：[onRejected] 在呼叫執行緒上跑（例如回收交出去的 Bitmap），也記成失敗。
 *  - [awaitAll] 等的時候被中斷照樣等完（中斷旗標回傳前補回去）：暫存檔的帳要等工作真的結束才算得準。
 */
class NightPendingEncodes(private val executor: Executor) {

    private class Task(val body: () -> Unit) : Runnable {
        val done = CountDownLatch(1)

        @Volatile
        var error: Throwable? = null

        @Volatile
        var nanos = 0L

        override fun run() {
            val t = System.nanoTime()
            try {
                body()
            } catch (e: Throwable) {
                error = e
            } finally {
                nanos = System.nanoTime() - t
                done.countDown()
            }
        }
    }

    private val tasks = ArrayList<Task>(2)
    private val waitNanos = AtomicLong(0)

    /** 送出一件編碼工作（不等）。拒收時 [onRejected] 在這條執行緒上跑，並記成這件的失敗。 */
    fun submit(onRejected: () -> Unit, work: () -> Unit) {
        val task = Task(work)
        synchronized(tasks) { tasks += task }
        try {
            executor.execute(task)
        } catch (t: Throwable) {
            try {
                onRejected()
            } finally {
                task.error = t
                task.done.countDown()
            }
        }
    }

    /** 等到目前送出的工作都結束；回傳第一個失敗（沒有＝null）。可重複呼叫。 */
    fun awaitAll(): Throwable? {
        val snapshot = synchronized(tasks) { tasks.toList() }
        val t0 = System.nanoTime()
        var interrupted = false
        for (task in snapshot) {
            while (true) {
                try {
                    task.done.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        waitNanos.addAndGet(System.nanoTime() - t0)
        if (interrupted) Thread.currentThread().interrupt()
        return snapshot.firstNotNullOfOrNull { it.error }
    }

    /** 已結束的工作本身花的時間合計（ms；在編碼執行緒上）。 */
    val workMs: Long
        get() = synchronized(tasks) { tasks.sumOf { if (it.done.count == 0L) it.nanos else 0L } } / 1_000_000

    /** [awaitAll] 在呼叫執行緒上等了多久合計（ms）。 */
    val waitMs: Long get() = waitNanos.get() / 1_000_000

    /** 送出過幾件。 */
    val submitted: Int get() = synchronized(tasks) { tasks.size }
}
