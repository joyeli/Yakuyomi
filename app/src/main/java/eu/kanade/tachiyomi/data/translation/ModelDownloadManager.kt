package eu.kanade.tachiyomi.data.translation

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import li.joye.yakuyomi.engine.ModelDownloader
import li.joye.yakuyomi.engine.ModelProgress
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * 模型自動下載的 reader 端管理者（BYOM 手動放檔的「自動版」）。
 *
 * 把引擎 [ModelDownloader]（撈 manifest → 串流下載 → sha256 驗）跑在 app-scope coroutine 裡，落點＝
 * [TranslationEngineConfig.downloadedDir]（`filesDir/models`，引擎直接從此載入、免 SAF）。進度同時發
 * [StateFlow]（設定頁內嵌顯示）＋ 進度通知（背景可見）。**冪等**：已存在且 sha256 正確的檔跳過，
 * 中斷後重按即續（壞檔/半成品重抓）。
 *
 * 注意：非前景服務——使用者主動觸發的一次性下載夠用；app 若在背景被系統清掉，下次重按即可（sha256 跳過已好的）。
 */
class ModelDownloadManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    sealed interface State {
        data object Idle : State
        data class Running(val label: String, val percent: Int) : State
        data object Done : State
        data class Error(val message: String) : State
    }

    /** 觸發下載（已在下載中則忽略）。 */
    fun download() {
        if (_state.value is State.Running) return
        update(State.Running(context.stringResource(MR.strings.model_dl_preparing), 0))
        scope.launch {
            try {
                val models = ModelDownloader.fetchManifest()
                val dir = TranslationEngineConfig.downloadedDir(context)
                val total = models.sumOf { it.size }.coerceAtLeast(1)
                var completed = 0L
                ModelDownloader.ensure(models, dir) { p ->
                    when (p) {
                        is ModelProgress.Downloading -> {
                            val pct = ((completed + p.bytes) * 100 / total).toInt().coerceIn(0, 100)
                            update(State.Running(context.stringResource(MR.strings.model_dl_downloading, p.name), pct))
                        }
                        is ModelProgress.Verifying ->
                            update(
                                State.Running(
                                    context.stringResource(MR.strings.model_dl_verifying, p.name),
                                    (
                                        completed *
                                            100 /
                                            total
                                        ).toInt().coerceIn(0, 100),
                                ),
                            )
                        is ModelProgress.Done -> {
                            // 依「檔名」累計（NCNN 一個 role 有 .param+.bin 兩檔，用 role 找會重覆算 .param、漏掉 .bin）。
                            completed += models.firstOrNull { it.name == p.name }?.size ?: 0
                            update(
                                State.Running(
                                    context.stringResource(MR.strings.model_dl_done_one, p.name),
                                    (
                                        completed *
                                            100 /
                                            total
                                        ).toInt().coerceIn(0, 100),
                                ),
                            )
                        }
                        is ModelProgress.Failed -> Unit // 例外會由下面 catch 統一處理
                    }
                }
                // 清掉不在 manifest 內的殘留檔：v1→v2 換了檔名（lama-manga.onnx / comictextdetector.pt.onnx /
                // ocr_48px_ctc.onnx 等），不清會殘留 ~460MB，且讓「舊版模型」偵測誤判。dir 只放自動下載、刪除安全。
                // keep 空（理論上的退化 manifest）就不 prune——絕不因空清單掃掉已下載好的模型。
                val keep = models.map { it.name }.toSet()
                if (keep.isNotEmpty()) {
                    dir.listFiles()?.forEach { f -> if (f.isFile && f.name !in keep) f.delete() }
                }
                update(State.Done)
            } catch (t: Throwable) {
                update(State.Error(t.message ?: context.stringResource(MR.strings.model_dl_failed)))
            }
        }
    }

    private fun update(s: State) {
        _state.value = s
        when (s) {
            is State.Running ->
                notify(
                    context.stringResource(MR.strings.model_dl_notif_title, s.percent),
                    s.label,
                    ongoing = true,
                    percent = s.percent,
                )
            State.Done ->
                notify(
                    context.stringResource(MR.strings.model_dl_notif_done),
                    context.stringResource(MR.strings.model_dl_notif_done_text),
                    ongoing = false,
                )
            is State.Error ->
                notify(context.stringResource(MR.strings.model_dl_notif_failed), s.message, ongoing = false)
            State.Idle -> Unit
        }
    }

    /** 進度通知（best-effort：無 POST_NOTIFICATIONS 權限則靜默略過）。 */
    private fun notify(title: String, text: String, ongoing: Boolean, percent: Int = 0) {
        try {
            val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_TRANSLATOR_PROGRESS)
                .setSmallIcon(R.drawable.ic_mihon)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
            if (ongoing) builder.setProgress(100, percent, false)
            NotificationManagerCompat.from(context).notify(Notifications.ID_MODEL_DOWNLOAD, builder.build())
        } catch (_: Throwable) {
            // 通知是加分、不擋下載
        }
    }
}
