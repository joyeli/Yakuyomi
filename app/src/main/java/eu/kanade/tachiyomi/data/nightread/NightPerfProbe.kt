package eu.kanade.tachiyomi.data.nightread

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.getSystemService
import li.joye.yakuyomi.engine.NCNN_POOL_THREAD_NAME
import java.io.File

/**
 * 夜讀逐頁效能探針（寫進診斷紀錄）：分辨「CPU 被限速」與「記憶體回收拖慢」。
 *
 * 2026-09-28 真機回報夜讀變慢：36 頁每頁 70–110 s，但每次開始或恢復後的頭一兩頁只要 13–15 s。
 * 光看牆鐘時間分不出原因，所以每頁另記：
 * - `cpu`：整個行程（含 NCNN 推論執行緒）這一頁用掉的 CPU 時間，和牆鐘時間的比值；被限到小核或被搶走時比值會掉。
 * - `thr`：夜讀那條執行緒自己的 CPU 時間（Kotlin 重繪幾乎都在這條上）。
 * - `gc`／`blk`：ART 垃圾回收次數與耗時（blk＝會卡住執行緒的那種）；記憶體回收拖慢時這兩個會暴增。
 * - `imp`：app 當下的重要性（100 前景、125 前景服務、200 可見、300 服務、400 快取），`screen`：螢幕是否亮著。
 * - `nice`、`cpus`（允許跑的核心）、`on`（最後跑的核心）、`cg`（cgroup）：優先權與 CPU 群組。
 * - `therm`／`head`：溫控狀態（0＝無、1＝輕微 … 4＝嚴重）與溫控餘裕（1.0＝即將降頻）。
 * - `omp`：NCNN 的 SimpleOMP 常駐 worker（執行緒名＝[NCNN_POOL_THREAD_NAME]）依 nice 彙總，例如 `7@n0`；它們由第一個
 *   推論者建立、繼承其 nice，應該永遠是 n0。找不到＝`0`。
 *
 * 多頁並行時 `cpu`／`gc`／`blk` 是全行程數字、會互相重疊（搭配呼叫端 perf 行的 `n=在飛/上限` 看）；`nice` 是頁結束時讀的
 * （ceiling 已還原）。
 *
 * 用法：頁開始時 [start]，做完時 [finish]。只讀系統狀態，不影響輸出；診斷紀錄關閉時呼叫端不必建立。
 */
class NightPerfProbe private constructor(
    private val wallMs: Long,
    private val procCpuMs: Long,
    private val threadCpuMs: Long,
    private val gcCount: Long,
    private val gcTimeMs: Long,
    private val blockingGcCount: Long,
    private val blockingGcTimeMs: Long,
) {

    /** 從 [start] 到現在的各項差值，加上當下的行程／執行緒狀態，一行字串。 */
    fun finish(context: Context): String {
        val now = start()
        val wall = now.wallMs - wallMs
        val cpu = now.procCpuMs - procCpuMs
        val ratio = if (wall > 0) cpu.toDouble() / wall else 0.0
        return "wall=${wall}ms cpu=${cpu}ms(%.2fx) thr=${now.threadCpuMs - threadCpuMs}ms ".format(ratio) +
            "gc=${now.gcCount - gcCount}/${now.gcTimeMs - gcTimeMs}ms " +
            "blk=${now.blockingGcCount - blockingGcCount}/${now.blockingGcTimeMs - blockingGcTimeMs}ms " +
            state(context)
    }

    companion object {
        fun start(): NightPerfProbe = NightPerfProbe(
            wallMs = SystemClock.elapsedRealtime(),
            procCpuMs = Process.getElapsedCpuTime(),
            threadCpuMs = SystemClock.currentThreadTimeMillis(),
            gcCount = runtimeStat("art.gc.gc-count"),
            gcTimeMs = runtimeStat("art.gc.gc-time"),
            blockingGcCount = runtimeStat("art.gc.blocking-gc-count"),
            blockingGcTimeMs = runtimeStat("art.gc.blocking-gc-time"),
        )

        private fun runtimeStat(key: String): Long = runCatching { Debug.getRuntimeStat(key)?.toLongOrNull() }
            .getOrNull() ?: -1L

        /** 呼叫端執行緒與行程的當下狀態（見類別說明的欄位）。每一項讀不到就填 `?`，不拋。 */
        fun state(context: Context): String {
            val tid = Process.myTid()
            val imp = runCatching {
                ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
            }.getOrNull()
            val pm = context.getSystemService<PowerManager>()
            val screen = runCatching { pm?.isInteractive }.getOrNull()
            val nice = runCatching { Process.getThreadPriority(tid) }.getOrNull()
            val status = runCatching { File("/proc/self/task/$tid/status").readLines() }.getOrNull()
            val cpus = status?.firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim()
            val lastCpu = runCatching {
                // stat 第 39 欄＝最後跑的核心；comm 可能含空白，從最後一個 ')' 之後切（第 3 欄起）。
                File("/proc/self/task/$tid/stat").readText().substringAfterLast(')').trim().split(' ')[36]
            }.getOrNull()
            val cgroup = runCatching {
                File("/proc/self/task/$tid/cgroup").readLines()
                    .filter { it.contains("cpuset") || it.contains("schedtune") || it.startsWith("0::") }
                    .joinToString(",") { it.substringAfter(':') }
                    .take(120)
            }.getOrNull()
            val therm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { pm?.currentThermalStatus }.getOrNull()
            } else {
                null
            }
            val head = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { pm?.getThermalHeadroom(0) }.getOrNull()?.takeIf { !it.isNaN() }
            } else {
                null
            }
            return "imp=${imp ?: "?"} screen=${screen ?: "?"} nice=${nice ?: "?"} cpus=${cpus ?: "?"} " +
                "on=${lastCpu ?: "?"} cg=${cgroup ?: "?"} therm=${therm ?: "?"} " +
                "head=${head?.let { "%.2f".format(it) } ?: "?"} omp=${ompWorkers() ?: "?"}"
        }

        /**
         * SimpleOMP worker 的 nice 分佈（`7@n0`、`5@n0,2@n9`…）：掃 `/proc/self/task/<tid>/comm` 找 [NCNN_POOL_THREAD_NAME]，
         * 讀 stat 第 19 欄（nice）。建池那條專用執行緒已結束，所以同名的都是 worker。讀不到 → null。
         */
        private fun ompWorkers(): String? = runCatching {
            val nices = File("/proc/self/task").listFiles().orEmpty().mapNotNull { task ->
                runCatching {
                    if (File(task, "comm").readText().trim() != NCNN_POOL_THREAD_NAME) return@runCatching null
                    // stat 第 19 欄＝nice；comm 可能含空白，從最後一個 ')' 之後切（第 3 欄起）。
                    File(task, "stat").readText().substringAfterLast(')').trim().split(' ')[16].toInt()
                }.getOrNull()
            }
            if (nices.isEmpty()) {
                "0"
            } else {
                nices.groupingBy { it }.eachCount().toSortedMap().entries.joinToString(",") { "${it.value}@n${it.key}" }
            }
        }.getOrNull()
    }
}
