package eu.kanade.tachiyomi.data.translation

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 翻譯佇列的前景服務（對照 [eu.kanade.tachiyomi.data.download.DownloadJob]）。
 *
 * 實際翻譯在 [TranslationManager] 的 scope 跑；本 worker 只負責「保活 + 前景通知」——讓 app
 * 退到背景時系統不回收行程、翻譯能跑完。觀察 [TranslationManager.queueState]/[TranslationManager.isPaused]：
 * 還有 QUEUE/TRANSLATING 且未暫停 → 維持前景並刷新進度通知；佇列清空或暫停 → 結束、前景服務停。
 */
class TranslationJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val translationManager: TranslationManager = Injekt.get()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_TRANSLATION_PROGRESS,
            buildNotification(translationManager.queueState.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        // 行程被殺 / 重開機後 WorkManager 會重跑本 worker：先把持久佇列讀回來（[TranslationManager.ensureRestored]
        // idempotent、有排隊章時自行 ensureDrain），再判斷有沒有活要做——否則新行程裡佇列是空的、會誤判沒事做。
        translationManager.ensureRestored()
        if (!hasActiveWork(translationManager.queueState.value, translationManager.isPaused.value)) {
            return Result.success()
        }
        setForegroundSafely()

        // 保活直到佇列無活躍工作或暫停；每次佇列變動就用 setForeground 刷新通知（讀 live queueState）。
        combine(
            translationManager.queueState,
            translationManager.isPaused,
        ) { items, paused -> items to paused }
            .takeWhile { (items, paused) -> !isStopped && hasActiveWork(items, paused) }
            .collect { setForegroundSafely() }

        return Result.success()
    }

    private fun hasActiveWork(items: List<TranslationItem>, paused: Boolean): Boolean {
        if (paused) return false
        return items.any {
            it.status == TranslationItem.Status.QUEUE || it.status == TranslationItem.Status.TRANSLATING
        }
    }

    private fun buildNotification(items: List<TranslationItem>): Notification {
        val active = items.firstOrNull { it.status == TranslationItem.Status.TRANSLATING }
        return applicationContext.notificationBuilder(Notifications.CHANNEL_TRANSLATOR_PROGRESS) {
            setContentTitle(applicationContext.stringResource(MR.strings.translation_status_translating))
            if (active != null) {
                setContentText(active.manga.title)
                if (active.total > 0) setProgress(active.total, active.done, false)
            }
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }.build()
    }

    companion object {
        private const val TAG = "Translator"

        /** 啟動前景服務。已在跑就沿用（KEEP，不打斷正在跑的 Job；新加的章由 live queueState 帶進去）。 */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<TranslationJob>()
                .addTag(TAG)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(TAG)
                .asFlow()
                .map { list -> list.any { it.state == WorkInfo.State.RUNNING } }
        }
    }
}
