package eu.kanade.tachiyomi.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import androidx.core.content.getSystemService
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import li.joye.yakuyomi.engine.EngineTrace
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 診斷用 trace 記錄器（**暫時**，抓那隻「送翻譯後閃退、無崩潰畫面」的原生/OOM crash）。
 *
 * 為什麼不用 logcat：**原生 crash（SIGSEGV/abort）與 OOM 被 lowmemorykiller SIGKILL 都會秒殺行程、丟失
 * logcat**，且不彈崩潰畫面 → 沒 adb 就抓不到。本類把每個階段**每寫一行就整檔落盤**（覆寫，非 append——SAF
 * DocumentsProvider 對 append 不可靠）到使用者的下載夾根目錄（＝OneDrive 同步夾），行程被殺後那檔仍在磁碟、
 * 同步上雲 → 使用者直接把最新一行前的內容傳回。**最後一行＝死前最後跑到的階段**。
 *
 * 每行都戳記憶體（java heap / native heap / 系統 availMem+lowMemory）→ 一眼看出是不是記憶體爬升到 OOM。
 * 引擎端階段（含 ncnn 原生 enter/call/exit）經 [EngineTrace.sink] 併進同一檔（見 [init]）。
 *
 * 用完即拆：診斷完把 [init] 呼叫、散落的 [log] 與這檔一起移除。
 */
object TraceLog {
    private const val FILE = "yakuyomi-trace.log"
    private const val MAX_LINES = 8000 // ring buffer 上限（防單次 session 無限長；覆寫成本 = 目前行數）

    @Volatile
    private var appContext: Context? = null
    private val lines = ArrayDeque<String>()
    private var sink: UniFile? = null
    private var sinkTried = false
    private val startTime = System.currentTimeMillis()

    // 系統記憶體查詢是 Binder 呼叫 → 節流快取（每行都戳但至多每 250ms 真查一次）。
    private var lastSysMs = 0L
    private var cachedSys = "sys=?"

    /** app 啟動時呼叫一次：綁 context、接上引擎 trace hook、寫 session 表頭。best-effort。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        EngineTrace.sink = { msg -> log("engine", msg) }
        log("app", "==== launch v${BuildConfig.VERSION_NAME} sha=${BuildConfig.COMMIT_SHA} ====")
    }

    /** 記一個階段（[tag]＝來源如 app/engine/svc/page，[msg]＝階段）。@Synchronized＝單寫者、順序一致。 */
    @Synchronized
    fun log(tag: String, msg: String) {
        val ctx = appContext ?: return
        lines.addLast(buildLine(ctx, tag, msg))
        while (lines.size > MAX_LINES) lines.removeFirst()
        writeAll(ctx)
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

    // 整檔覆寫（非 append）：SAF DocumentsProvider 對 "wa" 不可靠 → 用 "wt" 每次寫全部（ring buffer 有上限）。
    // file:// 走 openOutputStream(false)；content:// 走 ContentResolver "wt"（截斷寫）。與 PageTranslator.overwriteBytes 同招。
    private fun writeAll(ctx: Context) {
        val f = resolveSink(ctx) ?: return
        runCatching {
            val bytes = lines.joinToString("\n").toByteArray()
            val out = if (f.uri.scheme == "file") {
                f.openOutputStream(false)
            } else {
                ctx.contentResolver.openOutputStream(f.uri, "wt")
            }
            out?.use {
                it.write(bytes)
                it.flush()
            }
        }
    }

    private fun resolveSink(ctx: Context): UniFile? {
        sink?.let { return it }
        if (sinkTried && sink == null) {
            // 儲存還沒設好時每次重試（首頁翻譯前使用者多半已設下載夾）。
        }
        sinkTried = true
        val root = runCatching { Injekt.get<StorageManager>().getDownloadsDirectory() }.getOrNull() ?: return null
        sink = runCatching { root.findFile(FILE) ?: root.createFile(FILE) }.getOrNull()
        return sink
    }
}
