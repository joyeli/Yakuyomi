package eu.kanade.tachiyomi.data.browse

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.delay
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Yakuyomi：探索「自動載入到錨點」的**常駐前景服務**（對照 [BrowseFetchJob]）。跑整個迴圈：一批（[BrowseAnchorLoadManager.runChunk]）
 * → 沒到錨點就 `delay(間隔)` → 下一批，到錨點/到底/被停才結束。
 *
 * 用前景服務（而非 WorkManager 延遲鏈自我重排）的原因：vivo/小米/OPPO 等會殺背景的 OEM 會把延遲工作凍結、鏈一斷就不再續；
 * 前景服務活得夠久、跨行程被殺後也由 WorkManager 重啟（從續傳頁接著跑）。進度通知即前景通知（同一 id，附「停止」鈕）。
 */
class BrowseAnchorLoadJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val manager: BrowseAnchorLoadManager = Injekt.get()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_ANCHOR_LOAD_PROGRESS,
            manager.progressNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        setForegroundSafely()
        // 整個迴圈都在這一個前景服務內：跑一批 → 到錨點/到底就收工，否則歇 interval 再跑。被停（isStopped）就退出。
        while (!isStopped) {
            val outcome = manager.runChunk()
            if (outcome == BrowseAnchorLoadManager.ChunkOutcome.STOP) break
            delay(manager.nextDelayMs())
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "BrowseAnchorLoad"

        /** 開始（取代任何既有的）。 */
        fun startNow(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE)

        /** app 啟動還原：旗標還在但服務可能被 OEM 殺了 → 補跑（已在跑的就保留）。 */
        fun ensureScheduled(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
        }

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<BrowseAnchorLoadJob>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, policy, request)
        }
    }
}
