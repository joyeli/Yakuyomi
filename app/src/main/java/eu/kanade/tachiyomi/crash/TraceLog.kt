package eu.kanade.tachiyomi.crash

import android.app.ActivityManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.core.content.getSystemService
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import li.joye.yakuyomi.engine.EngineTrace
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
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
    private const val PREV_FILE = "yakuyomi-trace.prev.log" // 上一次啟動的紀錄（[init] 時輪替）
    private const val SHARE_FILE = "yakuyomi-trace-share.log" // 分享用：上一次＋本次合併
    private const val TOMBSTONE_SHARE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 分享時只附 7 天內存下來的 tombstone
    private const val MAX_LINES = 8000 // ring buffer 上限（防單次 session 無限長；覆寫成本 = 目前行數）

    @Volatile
    private var appContext: Context? = null

    /** 是否啟用；[log] 在**取鎖前**以此短路 → 關閉時零開銷（未 [init] 也維持 false）。 */
    @Volatile
    private var enabled = false

    /** 診斷紀錄開著沒（給要先算東西才能記的呼叫端先問一聲：關著就什麼都不做，例如 [StartupTrace]）。 */
    val isEnabled: Boolean get() = enabled

    /**
     * 最近一次 [init] 開始的 uptimeMillis 與花了幾毫秒（輪替上次紀錄＋寫表頭，都在呼叫端的執行緒上）。
     * [StartupTrace] 用來把診斷紀錄自己的成本從啟動時間分出來。
     */
    @Volatile
    internal var lastInitStartedAt = 0L
        private set

    @Volatile
    internal var lastInitMs = 0L
        private set

    private val lines = ArrayDeque<String>()
    private var sink: File? = null
    private val startTime = System.currentTimeMillis()

    // 系統記憶體查詢是 Binder 呼叫 → 節流快取（每行都戳但至多每 250ms 真查一次）。
    private var lastSysMs = 0L
    private var cachedSys = "sys=?"

    /** 開啟診斷：綁 context、接上引擎 trace hook、寫 session 表頭。設定頁開開關 / app 啟動（pref 為真）時呼叫。 */
    fun init(context: Context) {
        val start = SystemClock.uptimeMillis()
        appContext = context.applicationContext
        rotatePrevious(context.applicationContext)
        enabled = true
        EngineTrace.sink = { msg -> log("engine", msg) }
        log("app", "==== launch v${BuildConfig.VERSION_NAME} sha=${BuildConfig.COMMIT_SHA} ====")
        lastInitStartedAt = start
        lastInitMs = SystemClock.uptimeMillis() - start
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

    /**
     * 本次 session 還沒寫任何一行時，把磁碟上的舊紀錄（上一次啟動，可能是閃退前）搬到 [PREV_FILE]，
     * 免得本次第一行把它整檔覆寫掉——不然「跑完 → 重開 app → 分享」永遠只拿到新 session。搬的同時遮蔽舊版
     * 寫進去的明文金鑰（0.16.8–0.22.0 的 `ensureEngine.rebuild sig=` 含 API key）。
     */
    @Synchronized
    private fun rotatePrevious(ctx: Context) {
        if (lines.isNotEmpty()) return
        runCatching {
            val dir = File(ctx.filesDir, DIR)
            val cur = File(dir, FILE)
            if (!cur.exists() || cur.length() == 0L) return
            File(dir, PREV_FILE).writeText(cur.readLines().joinToString("\n") { redactSecrets(it) })
            cur.delete()
        }
    }

    /**
     * 診斷紀錄會被使用者分享出去：遮蔽像金鑰的字串（OpenAI／DeepSeek 類 `sk-…`、Google `AIza…`），
     * 以及舊版 `ensureEngine.rebuild sig=…` 整段（裡面混著 API key）。只是多一道保險，源頭本來就不該寫金鑰。
     */
    internal fun redactSecrets(s: String): String {
        var out = LEGACY_SIG.replace(s) { m ->
            val sig = m.groupValues[2]
            if ("<key-redacted>" in sig || "<no-key>" in sig) {
                m.value
            } else {
                m.groupValues[1] + "<redacted>" + m.groupValues[3]
            }
        }
        out = KEY_LIKE.replace(out, "<key-redacted>")
        return out
    }

    private val LEGACY_SIG = Regex("""(ensureEngine\.rebuild sig=)(.*?)( hadEngine=)""")
    private val KEY_LIKE = Regex("""\b(sk-[A-Za-z0-9_\-]{16,}|AIza[0-9A-Za-z_\-]{30,})""")

    private fun buildLine(ctx: Context, tag: String, msg: String): String {
        val t = System.currentTimeMillis() - startTime
        val thread = Thread.currentThread().name
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        val jUsed = (rt.totalMemory() - rt.freeMemory()) / mb
        val jMax = rt.maxMemory() / mb
        val nAlloc = Debug.getNativeHeapAllocatedSize() / mb
        return "+%7dms [%s] jH=%d/%dM nH=%dM %s | %s: %s"
            .format(t, thread, jUsed, jMax, nAlloc, sysMem(ctx), tag, redactSecrets(msg))
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
     * 把診斷 log 檔透過 FileProvider（authorities `${applicationId}.provider`）叫出系統分享。
     * 不依賴 [init]——可直接讀私有目錄的既有檔（crash 重開 app 後回傳上次的紀錄）。
     *
     * 上次原生 crash 的 tombstone 原始檔（[NativeCrashReporter] 存的 `.pb`，最新一份）有的話一併附上；只附
     * [TOMBSTONE_SHARE_MAX_AGE_MS] 以內存下來的：tombstone 是系統原檔、沒辦法像文字紀錄那樣遮蔽，幾週前的一次 crash
     * 不該跟著之後每一次（為別的問題）分享出去。
     * 只有紀錄 → 單檔 [Intent.ACTION_SEND]（text/plain，跟以前一樣）；有 tombstone → [Intent.ACTION_SEND_MULTIPLE]。
     * 診斷紀錄沒開過、但有 tombstone 時只送 tombstone。兩種都沒有或分享失敗回 false。
     */
    fun shareLog(context: Context): Boolean {
        val dir = File(context.filesDir, DIR)
        val cur = File(dir, FILE)
        val prev = File(dir, PREV_FILE)
        val tombstone = NativeCrashReporter.savedTombstones(context).firstOrNull()
            ?.takeIf { System.currentTimeMillis() - it.lastModified() <= TOMBSTONE_SHARE_MAX_AGE_MS }
        if (!cur.exists() && !prev.exists() && tombstone == null) return false
        return runCatching {
            val uris = ArrayList<Uri>()
            if (cur.exists() || prev.exists()) {
                // 上一次啟動（較早）在前、本次在後；兩份都再過一次遮蔽（舊檔可能是更新前寫的）
                val f = File(dir, SHARE_FILE)
                val parts = buildList {
                    if (prev.exists()) add("==== previous launch ====\n" + prev.readText())
                    if (cur.exists()) add("==== current launch ====\n" + cur.readText())
                }
                f.writeText(
                    parts.joinToString("\n\n") { part -> part.lines().joinToString("\n") { redactSecrets(it) } },
                )
                uris += f.getUriCompat(context)
            }
            if (tombstone != null) uris += tombstone.getUriCompat(context)
            val intent = if (uris.size == 1) {
                uris[0].toShareIntent(context, if (tombstone == null) "text/plain" else "application/octet-stream")
            } else {
                val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    clipData = ClipData.newRawUri(null, uris[0]).apply {
                        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                    }
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                Intent.createChooser(send, context.stringResource(MR.strings.action_share)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
