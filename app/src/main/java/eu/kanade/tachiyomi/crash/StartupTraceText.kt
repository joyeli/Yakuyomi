package eu.kanade.tachiyomi.crash

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationStartInfo
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import java.util.Locale

// [StartupTrace] 寫進診斷紀錄的文字：純函式，單元測試直接測（StartupTraceTextTest）。只有數字與狀態名，沒有標題、網址、金鑰。

/**
 * 這次開畫面是哪一種啟動。[firstSeen]＝診斷紀錄開著以來，這個行程第一次建 MainActivity；
 * [hasSavedState]＝系統交回了上次存的畫面狀態（行程被殺後還原、或旋轉等重建）；
 * [processStartTraced]＝這個行程啟動時診斷紀錄就開著。紀錄是中途才從設定頁打開的話，第一個畫面不一定是新行程，
 * 標成 `first-since-logging-enabled`，不冒充 fresh-process。
 */
internal fun classifyActivityStart(firstSeen: Boolean, hasSavedState: Boolean, processStartTraced: Boolean): String =
    when {
        firstSeen && !processStartTraced -> "first-since-logging-enabled"
        firstSeen && !hasSavedState -> "fresh-process"
        firstSeen -> "restored-after-process-death"
        !hasSavedState -> "new-activity-in-running-process"
        else -> "activity-recreated"
    }

/** 行程重要度（[RunningAppProcessInfo.importance]），例如 `100(foreground)`。 */
internal fun importanceName(importance: Int): String {
    val name = when (importance) {
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground-service"
        RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "top-sleeping"
        RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "cant-save-state"
        RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
        RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
        else -> "other"
    }
    return "$importance($name)"
}

/** Android 15 起系統記的這次行程啟動類型（[ApplicationStartInfo.getStartType]），例如 `1(cold)`。 */
internal fun startTypeName(type: Int): String {
    val name = when (type) {
        ApplicationStartInfo.START_TYPE_COLD -> "cold"
        ApplicationStartInfo.START_TYPE_WARM -> "warm"
        ApplicationStartInfo.START_TYPE_HOT -> "hot"
        else -> "unset"
    }
    return "$type($name)"
}

/** Android 15 起系統記的這次行程為什麼被叫起來（[ApplicationStartInfo.getReason]），例如 `6(launcher)`。 */
internal fun startReasonName(reason: Int): String {
    val name = when (reason) {
        ApplicationStartInfo.START_REASON_ALARM -> "alarm"
        ApplicationStartInfo.START_REASON_BACKUP -> "backup"
        ApplicationStartInfo.START_REASON_BOOT_COMPLETE -> "boot-complete"
        ApplicationStartInfo.START_REASON_BROADCAST -> "broadcast"
        ApplicationStartInfo.START_REASON_CONTENT_PROVIDER -> "content-provider"
        ApplicationStartInfo.START_REASON_JOB -> "job"
        ApplicationStartInfo.START_REASON_LAUNCHER -> "launcher"
        ApplicationStartInfo.START_REASON_LAUNCHER_RECENTS -> "launcher-recents"
        ApplicationStartInfo.START_REASON_OTHER -> "other"
        ApplicationStartInfo.START_REASON_PUSH -> "push"
        ApplicationStartInfo.START_REASON_SERVICE -> "service"
        ApplicationStartInfo.START_REASON_START_ACTIVITY -> "start-activity"
        else -> "unknown"
    }
    return "$reason($name)"
}

/** 檔案大小：沒有這個檔回 `missing`，其餘 B／KB／MB。 */
internal fun formatSize(bytes: Long?): String = when {
    bytes == null -> "missing"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/**
 * 翻譯佇列當下的量：翻譯／重繪那一池與夜讀那一池各自排隊、在跑、失敗幾項。
 * [items] 每項＝（是不是夜讀池, 狀態）。
 */
internal fun summarizeQueue(items: List<Pair<Boolean, TranslationItem.Status>>): String {
    fun pool(night: Boolean): String {
        val mine = items.filter { it.first == night }
        val queued = mine.count { it.second == TranslationItem.Status.QUEUE }
        val running = mine.count { it.second == TranslationItem.Status.TRANSLATING }
        val error = mine.count { it.second == TranslationItem.Status.ERROR }
        return "queued=$queued running=$running error=$error"
    }
    return "translate(${pool(false)}) night(${pool(true)})"
}
