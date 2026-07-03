package eu.kanade.tachiyomi.data.translation

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.asFlow
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.translation.model.TranslationItem
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * 翻譯佇列的前景服務（對照 [eu.kanade.tachiyomi.data.download.DownloadJob]）。
 *
 * 實際翻譯在 [TranslationManager] 的 scope 跑；本 worker 只負責「保活 + 前景通知」——讓 app
 * 退到背景時系統不回收行程、翻譯能跑完。觀察 [TranslationManager.queueState]/[TranslationManager.isPaused]：
 * 還有 QUEUE/TRANSLATING 且未暫停 → 維持前景並刷新進度通知；佇列清空或暫停 → 結束、前景服務停。
 */
class TranslationJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val translationManager: TranslationManager = Injekt.get()
    private val translationPreferences: TranslationPreferences = Injekt.get()

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

        // 被系統中途停掉（isStopped）但仍有活 → retry：讓 WorkManager 重排、重建前景服務，避免「worker 退場但
        // 翻譯仍在 TranslationManager.scope 背景跑、失去保活而被凍」。正常結束（佇列空 / 暫停 / 總開關關 →
        // hasActiveWork=false）→ success（不重排）。
        return if (isStopped &&
            hasActiveWork(translationManager.queueState.value, translationManager.isPaused.value)
        ) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private fun hasActiveWork(items: List<TranslationItem>, paused: Boolean): Boolean {
        if (paused) return false
        // 硬總開關：master 關時佇列不會 drain → 不算「有活」，避免前景服務 +「翻譯中」通知無限常駐。
        if (!translationPreferences.translationMasterEnabled.get()) return false
        return items.any {
            it.status == TranslationItem.Status.QUEUE || it.status == TranslationItem.Status.TRANSLATING
        }
    }

    private suspend fun buildNotification(items: List<TranslationItem>): Notification {
        val active = items.firstOrNull { it.status == TranslationItem.Status.TRANSLATING }
        val cover = active?.let { getMangaIcon(it.manga) }
        return applicationContext.notificationBuilder(Notifications.CHANNEL_TRANSLATOR_PROGRESS) {
            setContentTitle(applicationContext.stringResource(MR.strings.translation_status_translating))
            if (active != null) {
                setContentText(active.manga.title)
                if (active.total > 0) setProgress(active.total, active.done, false)
            }
            if (cover != null) {
                // 收合時右側小縮圖；展開時整張完整封面大圖（BigPictureStyle）。bigLargeIcon(null)＝展開時收掉
                // 右上重複的小縮圖。封面是直幅、未裁切 → 大圖區置中顯示完整封面（兩側留白，不裁切）。
                setLargeIcon(cover)
                setStyle(NotificationCompat.BigPictureStyle().bigPicture(cover).bigLargeIcon(null as Bitmap?))
            }
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }.build()
    }

    /**
     * 載入該本封面（Coil；快取命中很快）：不加 CircleCrop → 保留完整封面比例；尺寸放大到 [COVER_SIZE]
     * 讓 BigPictureStyle 展開時夠清晰。失敗回 null、不擋通知。
     */
    private suspend fun getMangaIcon(manga: Manga): Bitmap? = runCatching {
        val request = ImageRequest.Builder(applicationContext)
            .data(manga)
            .size(COVER_SIZE)
            // 通知 bitmap 必須是 software bitmap（hardware bitmap 無法 parcel 進通知 → 整則被系統拒收）。
            // 沒了 CircleCropTransformation（會強制 software），這裡得自己關 hardware。
            .allowHardware(false)
            .build()
        applicationContext.imageLoader.execute(request).image
            ?.asDrawable(applicationContext.resources)
            ?.getBitmapOrNull()
    }.getOrNull()

    companion object {
        private const val TAG = "Translator"

        // 通知封面載入尺寸（長邊 px）。largeIcon + bigPicture 共用同一張、各 parcel 一份，
        // 兩份合計須壓在 Binder transaction ~1MB 內，否則系統拒收整則通知 → 256（直幅 ~256×366×4≈375KB×2≈750KB）。
        private const val COVER_SIZE = 256

        /** 啟動前景服務。已在跑就沿用（KEEP，不打斷正在跑的 Job；新加的章由 live queueState 帶進去）。 */
        fun start(context: Context) {
            // 不掛網路 constraint（對照 DownloadJob）：constraint 在網路抖動時會讓 WorkManager 停掉 worker →
            // 前景服務被拆 → 跑在 TranslationManager.scope 的翻譯失去保活、背景被凍。網路只在 LLM 那步要、本就有重試。
            val request = OneTimeWorkRequestBuilder<TranslationJob>()
                .addTag(TAG)
                // 縮短「被 OEM 硬殺後」的重排間隔（預設 30s 指數 → 10s 線性）：process 被殺時 worker 沒 return，
                // WorkManager 依此 backoff 重排 → 更快重啟前景服務、從 manifest 續傳，vivo 這類頻繁殺 process 的走停更連續。
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
            // 一併起週期心跳：前景服務被 OEM 硬殺後由它週期性拉回來（見 TranslationHeartbeatJob）。
            TranslationHeartbeatJob.start(context)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
            TranslationHeartbeatJob.stop(context)
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(TAG)
                .asFlow()
                .map { list -> list.any { it.state == WorkInfo.State.RUNNING } }
        }
    }
}
