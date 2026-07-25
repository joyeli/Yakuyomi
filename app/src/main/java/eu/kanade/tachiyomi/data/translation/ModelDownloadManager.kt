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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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

    /**
     * 常駐引擎服務：模型檔換新後要主動叫它釋放（見 [download] 末尾的說明）。
     * 取法對齊本層既有風格（[PageTranslator]/[TranslationManager] 也是欄位 `Injekt.get()`）。
     */
    private val engineService: TranslationEngineService = Injekt.get()

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
                // 本次是否真的有新檔落地（決定要不要叫 warm 引擎釋放，見本函式末尾）。
                // 判準用 [ModelProgress.Verifying]：它只在「真的下載完一顆」之後才發（引擎 ModelDownloader.ensure
                // 對已存在且 size+sha256 相符的檔是直接發 Done 跳過、不發 Verifying），且不受檔案大小影響
                // （Downloading 每 ~1MB 才回報一次，小檔如 .param 可能一次都沒報）。
                var modelsChanged = false
                ModelDownloader.ensure(models, dir) { p ->
                    when (p) {
                        is ModelProgress.Downloading -> {
                            val pct = ((completed + p.bytes) * 100 / total).toInt().coerceIn(0, 100)
                            update(State.Running(context.stringResource(MR.strings.model_dl_downloading, p.name), pct))
                        }
                        is ModelProgress.Verifying -> {
                            modelsChanged = true // 走到驗證＝這顆是剛下載下來的新檔（非跳過）
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
                        }
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
                    dir.listFiles()?.forEach { f ->
                        // 刪掉殘留＝模型資料夾內容變了（下次解析可能挑到不同檔）→ 也算 modelsChanged。
                        if (f.isFile && f.name !in keep && f.delete()) modelsChanged = true
                    }
                }
                update(State.Done)
                // ★ 模型換新後主動釋放 warm 引擎（下次翻譯會 lazy 重建、載到新權重）。
                // 原因：[TranslationEngineService.ensureEngine] 只比對 [configSignature]（一堆 pref 值），
                // **模型檔路徑/內容不在簽章裡**（要 stat SAF/檔案系統，每頁翻譯都做太貴）→ 引擎 warm 時按「下載/更新
                // 翻譯模型」，新權重落地後引擎不會自己重建，會沿用舊模型 session，直到「即時翻關 / onTrimMemory /
                // 佇列清空」三個既有釋放時機之一才換。這裡在下載**全部成功**後補一次釋放，把那個空窗補上。
                // 只在真有新檔落地（或清掉殘留）時做——沒變就別關，省一次 ~100MB 重載。
                // 下載失敗/取消（走上面 catch）不釋放：模型沒換，關了純浪費，而且使用者可能正靠 warm 引擎在翻。
                // shutdownAsync 是 fire-and-forget（背景 IO）且內部持 mutex + 等 inFlight 歸零才關，
                // 翻譯進行中呼叫安全（在飛頁翻完才關；等不到就放棄關、引擎續 warm）。
                if (modelsChanged) engineService.shutdownAsync()
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
