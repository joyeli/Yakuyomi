package eu.kanade.tachiyomi.data.browse

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.flow.takeWhile
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Yakuyomi：探索批次擷取的前景服務（對照 [eu.kanade.tachiyomi.data.translation.TranslationJob] 的精簡版）。
 *
 * 實際擷取在 [BrowseFetchManager] 的 scope 跑；本 worker 只負責「保活 + 前景通知」——app 退背景時系統不回收
 * 行程、擷取能跑完。觀察 [BrowseFetchManager.state]：仍 running → 維持前景並刷新進度；結束/中止 → 收掉。
 * **不持久化**：不讀回任何佇列（行程被殺就沒了、重送即可）。
 */
class BrowseFetchJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val manager: BrowseFetchManager = Injekt.get()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_BROWSE_FETCH_PROGRESS,
            buildNotification(manager.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        if (!manager.state.value.running) return Result.success()
        setForegroundSafely()

        manager.state
            .takeWhile { !isStopped && it.running }
            .collect { setForegroundSafely() }

        return Result.success()
    }

    private fun buildNotification(state: BrowseFetchManager.State): Notification {
        val sourceName = manager.sourceName(state.sourceId)
        return applicationContext.notificationBuilder(Notifications.CHANNEL_BROWSE_FETCH) {
            setContentTitle(applicationContext.stringResource(MR.strings.browse_fetch_running_title))
            setContentText(
                if (state.total > 0) {
                    applicationContext.stringResource(
                        MR.strings.browse_fetch_running_text,
                        sourceName,
                        state.done,
                        state.total,
                    )
                } else {
                    sourceName
                },
            )
            if (state.total > 0) setProgress(state.total, state.done, false)
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
            setOnlyAlertOnce(true)
            addAction(
                R.drawable.ic_close_24dp,
                applicationContext.stringResource(MR.strings.action_cancel),
                NotificationReceiver.cancelBrowseFetchPendingBroadcast(applicationContext),
            )
        }.build()
    }

    companion object {
        private const val TAG = "BrowseFetch"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<BrowseFetchJob>()
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
        }
    }
}
