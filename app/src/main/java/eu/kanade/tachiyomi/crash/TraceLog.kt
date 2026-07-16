package eu.kanade.tachiyomi.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import androidx.core.content.getSystemService
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import li.joye.yakuyomi.engine.EngineTrace
import java.io.File

/**
 * 進階診斷用 trace 記錄器（**預設關**，由「翻譯設定 → 診斷紀錄」開關控制；見 [TranslationPreferences.diagnosticLog]）。
 *
 * 為什麼需要：**原生 crash（SIGSEGV/abort）與被 lowmemorykiller SIGKILL 的 OOM 都會秒殺行程、丟失 logcat**，
 * 且不彈崩潰畫面 → 連 mihon 內建的 crash log 也抓不到。開啟後本類把翻譯引擎每個階段**每寫一行就整檔落盤**
 * （覆寫、非 append、且 flush）到 app 私有目錄 `filesDir/diagnostics/yakuyomi-trace.log`，行程被殺後那檔仍在磁碟
 * → 進設定頁「分享診斷紀錄」把最新一行前的內容傳回。**最後一行＝死前最後跑到的階段**。
 *
 * 每行都戳記憶體（java heap / native heap / 系統 availMem+lowMemory）→ 一眼看出是不是記憶體爬升到 OOM。
 * 引擎端階段（含 ncnn 原生 enter/call/exit）經 [EngineTrace.sink] 併進同一檔（見 [init]）。
 *
 * **零開銷保證**：關閉時 [App][eu.kanade.tachiyomi.App] 不呼叫 [init] → [EngineTrace.sink] 維持 null（引擎完全不 log）、
 * [enabled] 為 false → [log] 在取鎖前先短路 return。設定頁可執行時 [init]/[stop] 切換，不必重啟 app。
 * 落地在 app 私有目錄 → native crash / SIGKILL 後檔案仍在磁碟（crash 存活性不變），且 file:// 直寫比 SAF/OneDrive 快很多。
 */
object TraceLog {
    private const val DIR = "diagnostics"
    private const val FILE = "yakuyomi-trace.log"
    private const val MAX_LINES = 8000 // ring buffer 上限（防單次 session 無限長；覆寫成本 = 目前行數）

    @Volatile
    private var appContext: Context? = null

    /** 是否啟用；[log] 在**取鎖前**以此短路 → 關閉時零開銷（未 [init] 也維持 false）。 */
    @Volatile
    private var enabled = false

    private val lines = ArrayDeque<String>()
    private var sink: File? = null
    private val startTime = System.currentTimeMillis()

    // 系統記憶體查詢是 Binder 呼叫 → 節流快取（每行都戳但至多每 250ms 真查一次）。
    private var lastSysMs = 0L
    private var cachedSys = "sys=?"

    /** 開啟診斷：綁 context、接上引擎 trace hook、寫 session 表頭。設定頁開開關 / app 啟動（pref 為真）時呼叫。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        enabled = true
        EngineTrace.sink = { msg -> log("engine", msg) }
        log("app", "==== launch v${BuildConfig.VERSION_NAME} sha=${BuildConfig.COMMIT_SHA} ====")
    }

    /** 關閉診斷：斷開引擎 hook、標記停止、清 buffer。之後 [log] 零開銷。設定頁關開關時呼叫（不必重啟）。 */
    @Synchronized
    fun stop() {
        enabled = false
        EngineTrace.sink = null
        lines.clear()
    }

    /**
     * 記一個階段（[tag]＝來源如 app/engine/queue/page，[msg]＝階段）。關閉時（[enabled]=false）**取鎖前**即 return＝零開銷。
     * 啟用時整段在 [synchronized] 下＝單寫者、順序一致。
     */
    fun log(tag: String, msg: String) {
        if (!enabled) return
        val ctx = appContext ?: return
        synchronized(this) {
            if (!enabled) return
            lines.addLast(buildLine(ctx, tag, msg))
            while (lines.size > MAX_LINES) lines.removeFirst()
            writeAll(ctx)
        }
    }

    private fun buildLine(ctx: Context, tag: String, msg: String): String {
        val t = System.currentTimeMillis() - startTime
        val thread = Thread.currentThread().name
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        val jUsed = (rt.totalMemory() - rt.freeMemory()) / mb
        val jMax = rt.maxMemory() / mb
        val nAlloc = Debug.getNativeHeapAllocatedSize() / mb
        return "+%7dms [%s] jH=%d/%dM nH=%dM %s | %s: %s"
            .format(t, thread, jUsed, jMax, nAlloc, sysMem(ctx), tag, msg)
    }

    private fun sysMem(ctx: Context): String {
        val now = System.currentTimeMillis()
        if (now - lastSysMs < 250 && cachedSys != "sys=?") return cachedSys
        lastSysMs = now
        cachedSys = runCatching {
            val am = ctx.getSystemService<ActivityManager>() ?: return@runCatching "sys=?"
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            "sysAvail=${mi.availMem / (1024 * 1024)}M low=${mi.lowMemory}"
        }.getOrDefault("sys=?")
        return cachedSys
    }

    // 整檔覆寫（非 append）＋ flush：ring buffer 有上限，每行落盤保證「行程被殺前的最後階段」在磁碟上。
    // 走 app 私有目錄 file://，直接 outputStream()（截斷寫）——比舊版 SAF content:// + OneDrive 同步快很多。
    private fun writeAll(ctx: Context) {
        val f = resolveSink(ctx) ?: return
        runCatching {
            val bytes = lines.joinToString("\n").toByteArray()
            f.outputStream().use {
                it.write(bytes)
                it.flush()
            }
        }
    }

    private fun resolveSink(ctx: Context): File? {
        sink?.let { return it }
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        return File(dir, FILE).also { sink = it }
    }

    /**
     * 把診斷 log 檔透過 FileProvider（authorities `${applicationId}.provider`）叫出系統分享（[Intent.ACTION_SEND]、text/plain）。
     * 不依賴 [init]——可直接讀私有目錄的既有檔（crash 重開 app 後回傳上次的紀錄）。檔案不存在或分享失敗回 false。
     */
    fun shareLog(context: Context): Boolean {
        val f = File(File(context.filesDir, DIR), FILE)
        if (!f.exists()) return false
        return runCatching {
            val uri = f.getUriCompat(context)
            context.startActivity(uri.toShareIntent(context, "text/plain"))
            true
        }.getOrDefault(false)
    }
}
