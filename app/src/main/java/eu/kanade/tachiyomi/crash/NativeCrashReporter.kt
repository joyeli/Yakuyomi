package eu.kanade.tachiyomi.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import eu.kanade.tachiyomi.BuildConfig
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 開機時檢查「上次行程死亡」是否為**原生 crash**（SIGSEGV/abort 等）——這類 crash 繞過 Kotlin 的
 * [GlobalExceptionHandler]、直接閃退、**不會有崩潰畫面**，沒 adb 就抓不到堆疊。
 *
 * 本類靠 [ApplicationExitInfo]（API 30+，系統會保留最近幾次行程死亡的原因＋原生 tombstone），
 * 開機時把上次的原生 crash 用**同一個崩潰畫面**（含分享鈕）叫出來 → 使用者不用 adb 就能把 trace 回報。
 * 用 SharedPreferences 記「已回報的時間戳」去重（同一次 crash 只彈一次）。
 *
 * **tombstone 存原始位元組**：系統給的 trace 是 protobuf 二進位，當文字讀（`readText()`）會把位址那些變長整數換成
 * 替代字元、還原不回來。原生庫 strip 之後 NCNN 內部的函式名不會再寫進 tombstone，只剩「位址＋Build ID」可以定位，
 * 位址壞掉就什麼都查不到。所以：
 *  - 原始位元組整份存成 `filesDir/diagnostics/native-crash-<時間>.pb`（app 私有；只留最新 [KEEP_TOMBSTONES] 份），
 *    由「設定 › 進階 › 分享診斷紀錄」連同診斷紀錄一起送出（[TraceLog.shareLog]）。
 *  - 崩潰畫面上的文字改由 [TombstoneText] 從位元組解出來（crash 那條執行緒每一格的相對位址、檔名、Build ID），
 *    分享崩潰紀錄時這幾行就夠在電腦上用未 strip 的庫符號化。
 */
object NativeCrashReporter {

    private const val PREF = "native_crash_reporter"
    private const val KEY_LAST_TS = "last_reported_ts"
    private const val MAX_TRACE_CHARS = 20_000 // intent extra 上限保險（避免 TransactionTooLarge）

    private const val DIR = "diagnostics" // 與 [TraceLog] 同一個私有資料夾
    private const val TOMBSTONE_PREFIX = "native-crash-"
    private const val TOMBSTONE_EXT = ".pb"
    private const val KEEP_TOMBSTONES = 3

    /**
     * 開機（fresh launch）呼叫一次（best-effort）。偵測到**新的**原生 crash → 叫崩潰畫面顯示 tombstone、回 true
     * （呼叫端應據此 finish 本 activity、把畫面讓給崩潰畫面）；沒有則回 false（正常啟動）。
     */
    fun checkAndReport(context: Context, crashActivity: Class<*>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            val am = context.getSystemService<ActivityManager>() ?: return false
            val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            // 最近一次「原生 crash / 被信號殺」的死亡（其餘正常結束/使用者關/低記憶體殺不算）。
            val crash = reasons.firstOrNull {
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    it.reason == ApplicationExitInfo.REASON_SIGNALED
            } ?: return false

            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (crash.timestamp <= prefs.getLong(KEY_LAST_TS, 0L)) return false // 這次已回報過、不重複彈

            // ★ 原始位元組：tombstone 是 protobuf 二進位，不能當文字讀（見類別說明）。
            val raw = runCatching { crash.traceInputStream?.use { it.readBytes() } }.getOrNull()
            val saved = if (raw != null && raw.isNotEmpty()) saveTombstone(context, crash.timestamp, raw) else null
            val message = buildString {
                appendLine("原生 crash（自動還原自系統 ApplicationExitInfo，非本次執行）")
                appendLine("reason=${crash.reason} desc=${crash.description} status=${crash.status}")
                appendLine("time=${stamp(crash.timestamp)} pss=${crash.pss / 1024}MB rss=${crash.rss / 1024}MB")
                // 目前安裝的版本：crash 之後才更新 app 的話，要用 crash 當時那版的符號檔（先比 BuildId）
                appendLine("app=${BuildConfig.VERSION_NAME} sha=${BuildConfig.COMMIT_SHA}（目前安裝的版本）")
                if (raw == null || raw.isEmpty()) {
                    appendLine()
                    append("(系統未附 tombstone trace；至少 reason/desc 可判斷 SIGSEGV/abort/OOM)")
                } else {
                    appendLine(
                        if (saved != null) {
                            "tombstone 原始檔 ${raw.size} bytes 已存成 ${saved.name}（設定 › 進階 › 分享診斷紀錄 會一併附上）"
                        } else {
                            "tombstone ${raw.size} bytes（原始檔存檔失敗，以下文字是唯一一份）"
                        },
                    )
                    appendLine()
                    // 可讀字串那條後備可能帶到 tombstone 裡的 logcat 尾巴 → 照診斷紀錄的規則遮一次像金鑰的字串
                    append(
                        TombstoneText.summarize(raw).lineSequence()
                            .joinToString("\n") { TraceLog.redactSecrets(it) }
                            .take(MAX_TRACE_CHARS),
                    )
                }
            }

            prefs.edit().putLong(KEY_LAST_TS, crash.timestamp).apply()
            GlobalExceptionHandler.showCrashScreen(context, crashActivity, Throwable(message))
            true
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "NativeCrashReporter 讀取上次原生 crash 失敗" }
            false
        }
    }

    /**
     * 存在私有資料夾裡的 tombstone 原始檔，**新的在前**（檔名帶時間、照檔名排）。給 [TraceLog.shareLog] 附檔用。
     */
    fun savedTombstones(context: Context): List<File> =
        File(context.filesDir, DIR).listFiles { f ->
            f.isFile && f.name.startsWith(TOMBSTONE_PREFIX) && f.name.endsWith(TOMBSTONE_EXT)
        }.orEmpty().sortedByDescending { it.name }

    /** 把 [raw] 原樣寫成 `native-crash-<時間>.pb`，並把超過 [KEEP_TOMBSTONES] 份的舊檔刪掉。失敗回 null。 */
    private fun saveTombstone(context: Context, timestamp: Long, raw: ByteArray): File? = runCatching {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "$TOMBSTONE_PREFIX${stamp(timestamp)}$TOMBSTONE_EXT")
        file.writeBytes(raw)
        savedTombstones(context).drop(KEEP_TOMBSTONES).forEach { runCatching { it.delete() } }
        file
    }.onFailure { logcat(LogPriority.WARN, it) { "tombstone 原始檔存檔失敗" } }.getOrNull()

    private fun stamp(timestamp: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
}
