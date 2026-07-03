package eu.kanade.tachiyomi.data.translation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * Yakuyomi：翻譯佇列的背景「心跳」（週期 worker，非前景、很輕）。
 *
 * 為什麼要它：翻譯的實際保活是 [TranslationJob] 前景服務，但 vivo / Funtouch 這類 OEM 會在螢幕關一段時間後
 * **硬殺前景服務**（連 [TranslationJob] 的 `Result.retry()` 都來不及走）。此時只能等使用者重開 app（`ensureRestored`）
 * 才續。這個週期心跳補上那個洞：只要系統在充電/亮屏/Doze 維護窗口放行 WorkManager，它就醒來、發現還有沒翻完的章，
 * 把前景服務重新拉回來——**不必手動開 app 就會自動恢復**。翻完（[TranslationManager.hasPendingWork] 為 false）就自停。
 *
 * 對抗 OEM 凍結的補強，不保證（OEM 可能連週期 worker 都凍到解凍才跑），但把「停了要手動開 app」改善成「系統一放行就自動接上」。
 */
class TranslationHeartbeatJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val manager: TranslationManager = Injekt.get()

    override suspend fun doWork(): Result {
        // 行程若被殺重啟，先把持久佇列讀回來（idempotent；有排隊章時自行 ensureDrain）。
        manager.ensureRestored()
        if (manager.hasPendingWork()) {
            // 還有要翻的 → 把前景服務拉回來（KEEP：已在跑就沿用）。實際 drain 由 ensureRestored/既有機制帶動。
            TranslationJob.start(applicationContext)
        } else {
            // 沒事做 → 停掉自己（別再每 15 分空醒）。
            stop(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "TranslatorHeartbeat"

        /** 開始週期心跳（已在跑就沿用）。隨前景服務一起啟動。 */
        fun start(context: Context) {
            val request = PeriodicWorkRequestBuilder<TranslationHeartbeatJob>(15, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
        }
    }
}
