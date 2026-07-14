package eu.kanade.tachiyomi.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * 開機時檢查「上次行程死亡」是否為**原生 crash**（SIGSEGV/abort 等）——這類 crash 繞過 Kotlin 的
 * [GlobalExceptionHandler]、直接閃退、**不會有崩潰畫面**，沒 adb 就抓不到堆疊。
 *
 * 本類靠 [ApplicationExitInfo]（API 30+，系統會保留最近幾次行程死亡的原因＋原生 tombstone），
 * 開機時把上次的原生 crash 用**同一個崩潰畫面**（含分享鈕）叫出來 → 使用者不用 adb 就能把 trace 回報。
 * 用 SharedPreferences 記「已回報的時間戳」去重（同一次 crash 只彈一次）。
 */
object NativeCrashReporter {

    private const val PREF = "native_crash_reporter"
    private const val KEY_LAST_TS = "last_reported_ts"
    private const val MAX_TRACE_CHARS = 20_000 // intent extra 上限保險（避免 TransactionTooLarge）

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

            val trace = runCatching {
                crash.traceInputStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            val message = buildString {
                appendLine("原生 crash（自動還原自系統 ApplicationExitInfo，非本次執行）")
                appendLine("reason=${crash.reason} desc=${crash.description}")
                appendLine("time=${crash.timestamp}")
                if (trace.isNotBlank()) {
                    appendLine()
                    append(trace.take(MAX_TRACE_CHARS))
                } else {
                    appendLine()
                    append("(系統未附 tombstone trace；至少 reason/desc 可判斷 SIGSEGV/abort/OOM)")
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
}
